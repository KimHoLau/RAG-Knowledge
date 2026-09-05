package com.ragknowledge.rag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * 重排序响应体（Jina/Cohere 风格 /rerank 协议）：
 * {"results":[{"index":0,"relevance_score":0.98},...]}，index 为请求 documents 数组的下标。
 * 各实现可能附带 document 等额外字段，一律忽略（文档内容调用方本地就有，按 index 回取）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResponse(List<RerankResult> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RerankResult(
            int index,
            // 主字段 relevance_score（Jina/llama.cpp/vLLM/SiliconFlow）；个别实现（TEI、Cohere v1）叫 score
            @JsonProperty("relevance_score")
            @JsonAlias("score")
            double relevanceScore) {
    }
}
