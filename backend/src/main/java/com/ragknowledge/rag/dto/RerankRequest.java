package com.ragknowledge.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 重排序请求体（Jina/Cohere 风格 /rerank 协议，llama.cpp/vLLM/SiliconFlow 均兼容）。
 * top_n/return_documents 是协议要求的 snake_case 字段，用 @JsonProperty 显式指定，
 * 不依赖 Jackson 对 record 组件名的驼峰推导。
 */
public record RerankRequest(
        String model,
        String query,
        List<String> documents,
        @JsonProperty("top_n") int topN,
        @JsonProperty("return_documents") boolean returnDocuments) {
}
