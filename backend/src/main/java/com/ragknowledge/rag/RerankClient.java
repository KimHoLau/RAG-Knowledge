package com.ragknowledge.rag;

import com.ragknowledge.rag.dto.RerankRequest;
import com.ragknowledge.rag.dto.RerankResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;

/**
 * 重排序客户端：调用 Jina/Cohere 风格的 POST {base-url}/rerank 端点（llama.cpp、vLLM、SiliconFlow 均兼容）。
 * 不走 OpenAI SDK——rerank 没有 OpenAI 协议，且 Ollama 至今没有原生 rerank 端点，
 * Qwen3-Reranker 需由 llama-server（--embedding --pooling rank --rerank）等服务承载。
 *
 * <p>定位是"增强"而非"依赖"：未启用、服务未起、超时、响应异常一律返回空列表，
 * 由 RagService 退回向量排序——重排序挂掉只影响引用排序质量，不影响问答可用性。
 */
@Service
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** 重排序失败后的静默期（毫秒）：期间直接退回向量排序，避免每个请求都同步撞一次连接超时 */
    private static final long FAILURE_BACKOFF_MILLIS = 60_000;

    private final RestClient restClient;
    private final String model;
    private final boolean enabled;
    /** 允许再次尝试重排序的最早时间戳（上次失败时间 + 静默期）；0 表示从未失败 */
    private volatile long retryEarliestMillis;

    public RerankClient(RestClient rerankRestClient,
                        @Value("${rag.rerank.model}") String model,
                        @Value("${rag.rerank.enabled}") boolean enabled) {
        this.restClient = rerankRestClient;
        this.model = model;
        this.enabled = enabled;
    }

    /**
     * 对候选切片按与查询的相关度重排序。
     *
     * @param documents 候选文本，顺序与调用方的候选列表一致（返回结果用下标回指）
     * @param limit     只保留相关度最高的前 limit 条
     * @return 按相关度降序的结果；未启用、候选不足、处于失败静默期或任何失败时返回空列表（调用方退回向量排序）
     */
    public List<RerankResponse.RerankResult> rerank(String query, List<String> documents, int limit) {
        // 单个候选无需排序；limit 过小同样没有精排意义
        if (!enabled || limit <= 0 || documents.size() <= 1) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (now < retryEarliestMillis) {
            return List.of();
        }
        try {
            RerankResponse response = restClient.post()
                    .uri("/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RerankRequest(model, query, documents, limit, false))
                    .retrieve()
                    .body(RerankResponse.class);
            List<RerankResponse.RerankResult> results = response == null || response.results() == null
                    ? List.of()
                    : response.results();
            // 兼容个别实现返回越界下标或乱序：过滤后按相关度降序截前 limit 条
            List<RerankResponse.RerankResult> ranked = results.stream()
                    .filter(r -> r.index() >= 0 && r.index() < documents.size())
                    .sorted(Comparator.comparingDouble(RerankResponse.RerankResult::relevanceScore).reversed())
                    .limit(limit)
                    .toList();
            log.debug("重排序完成：{} 个候选取前 {} 条", documents.size(), ranked.size());
            return ranked;
        } catch (Exception e) {
            retryEarliestMillis = now + FAILURE_BACKOFF_MILLIS;
            log.warn("重排序失败，{}s 内退回向量排序：{}", FAILURE_BACKOFF_MILLIS / 1000, errorMessage(e));
            return List.of();
        }
    }

    private String errorMessage(Throwable e) {
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : e.getClass().getSimpleName();
    }
}
