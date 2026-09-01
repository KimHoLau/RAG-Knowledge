package com.ragknowledge.document.dto;

import com.ragknowledge.document.DocumentEntity;
import com.ragknowledge.document.DocumentStatus;

import java.time.LocalDateTime;

public record DocumentVO(
        String id,
        String fileName,
        String fileType,
        long fileSize,
        Integer chunkCount,
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
                e.getStatus(),
                e.getErrorMessage(),
                e.getCreatedAt());
    }
}
