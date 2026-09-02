package com.ragknowledge.document.dto;

import com.ragknowledge.document.DocumentEntity;
import com.ragknowledge.document.DocumentStatus;

import java.time.LocalDateTime;

/**
 * 文档列表项。
 * PROCESSING 期间 chunkCount/chunkTotal 表示入库进度（已完成切片数 / 切片总数），
 * chunkTotal 为 0 说明还在解析切分阶段、总数未知，前端应展示不确定态进度。
 */
public record DocumentVO(
        String id,
        String fileName,
        String fileType,
        long fileSize,
        Integer chunkCount,
        int chunkTotal,
        DocumentStatus status,
        String errorMessage,
        LocalDateTime createdAt) {

    public static DocumentVO from(DocumentEntity e) {
        return new DocumentVO(
                e.getId(),
                e.getFileName(),
                e.getFileType(),
                e.getFileSize(),
                e.getChunkCount(),
                e.getChunkTotal(),
                e.getStatus(),
                e.getErrorMessage(),
                e.getCreatedAt());
    }
}
