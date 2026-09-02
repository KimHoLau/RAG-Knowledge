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
        List<SourceChunk> result = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            Object docName = doc.getMetadata().get("doc_name");
            result.add(new SourceChunk(
                    i + 1,
                    docName != null ? docName.toString() : "未知文档",
                    similarityOf(doc),
                    doc.getText()));
        }
        return result;
    }

    /**
     * 取相似度（0~1）。
     *
     * <p>兼容两种来源：Spring AI 2.0 起 Document 带 score 字段；部分 VectorStore 实现则把距离
     * 写进 metadata 的 distance（余弦距离，需 1-d 换算成相似度）。
     *
     * <p><b>当前现状</b>：Spring AI 2.0.1 的 PgVectorStore 两者都不写，因此这里的实际返回值恒为 0，
     * 前端展示的相关度均为 0。要拿到真实分值需自行用 JDBC 查询并把 distance 换算后回填。
     */
    private double similarityOf(Document doc) {
        Double score = doc.getScore();
        if (score != null) {
            return clamp(score);
        }
        if (doc.getMetadata().get("distance") instanceof Number distance) {
            return clamp(1.0 - distance.doubleValue());
        }
        return 0;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
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
