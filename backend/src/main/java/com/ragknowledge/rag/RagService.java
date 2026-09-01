package com.ragknowledge.rag;

import com.ragknowledge.rag.dto.SourceChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 核心：向量检索 -> 组装上下文 -> GLM 流式生成。
 * SSE 事件协议（data 均为单行 JSON）：
 *   {"type":"sources","payload":[SourceChunk...]}  检索到的引用来源（最先下发）
 *   {"type":"message","payload":"增量文本"}          流式回答内容
 *   {"type":"done","payload":"ok"}                  正常结束
 *   {"type":"error","payload":"错误信息"}            任意阶段出错
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个严谨的企业知识库问答助手，必须严格遵守以下规则：
            1. 仅依据下方【参考资料】回答用户问题，严禁编造资料中不存在的内容。
            2. 引用资料时在对应语句末尾标注编号，形式为 [1]、[2]。
            3. 若参考资料不足以回答问题，请明确说明“知识库中暂无该问题的相关资料”，并简要给出调整提问方向的建议，不要自行发挥。
            4. 使用简体中文回答，条理清晰；内容较多时适当使用 Markdown 标题和列表。

            【参考资料】
            %s
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final int topK;
    private final double similarityThreshold;

    public RagService(VectorStore vectorStore,
                      ChatClient chatClient,
                      ObjectMapper objectMapper,
                      @Value("${rag.search.top-k}") int topK,
                      @Value("${rag.search.similarity-threshold}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    /** 知识检索：不做相似度阈值过滤，返回 Top-K 切片 */
    public List<SourceChunk> search(String query, Integer topK) {
        int k = (topK != null && topK > 0) ? Math.min(topK, 20) : this.topK;
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(k)
                .similarityThreshold(0.0)
                .build());
        return toChunks(docs);
    }

    public Flux<ServerSentEvent<String>> answerStream(String query) {
        return Flux.defer(() -> {
            List<SourceChunk> sources = retrieve(query);
            String systemPrompt = buildSystemPrompt(sources);
            Flux<ServerSentEvent<String>> head = Flux.just(event("sources", sources));
            Flux<ServerSentEvent<String>> body = chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .stream()
                    .content()
                    .map(delta -> event("message", delta));
            return head
                    .concatWith(body)
                    .concatWith(Flux.just(event("done", "ok")))
                    .onErrorResume(e -> {
                        log.error("流式问答失败", e);
                        return Flux.just(event("error", errorMessage(e)));
                    });
        });
    }

    private List<SourceChunk> retrieve(String query) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build());
        return toChunks(docs);
    }

    private List<SourceChunk> toChunks(List<Document> docs) {
        List<SourceChunk> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Object docName = doc.getMetadata().get("doc_name");
            Object distance = doc.getMetadata().get("distance");
            double score = 0;
            if (distance instanceof Number n) {
                score = 1.0 - n.doubleValue();
            }
            result.add(new SourceChunk(
                    i + 1,
                    docName != null ? docName.toString() : "未知文档",
                    Math.max(0, Math.min(1, score)),
                    doc.getText()));
        }
        return result;
    }

    private String buildSystemPrompt(List<SourceChunk> sources) {
        if (sources.isEmpty()) {
            return "你是知识库问答助手。知识库中目前没有与用户问题相关的资料，"
                    + "请明确告知“知识库中暂无该问题的相关资料”，并建议用户调整提问方式或先上传相关资料，"
                    + "不要使用自身知识作答。";
        }
        StringBuilder sb = new StringBuilder();
        for (SourceChunk s : sources) {
            sb.append("[").append(s.index()).append("] 来源：").append(s.docName()).append('\n')
                    .append(s.content()).append("\n\n");
        }
        return SYSTEM_PROMPT_TEMPLATE.formatted(sb.toString().trim());
    }

    private ServerSentEvent<String> event(String type, Object payload) {
        return ServerSentEvent.builder(toJson(new SseEvent(type, payload))).build();
    }

    private String errorMessage(Throwable e) {
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"payload\":\"JSON 序列化失败\"}";
        }
    }

    private record SseEvent(String type, Object payload) {
    }
}
