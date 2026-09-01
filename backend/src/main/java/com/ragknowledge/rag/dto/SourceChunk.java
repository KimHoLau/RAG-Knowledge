package com.ragknowledge.rag.dto;

/**
 * 检索命中的知识切片，用于问答引用来源与知识检索结果展示。
 * score 为相似度（1 - 余弦距离），取值 0~1。
 */
public record SourceChunk(int index, String docName, double score, String content) {
}
