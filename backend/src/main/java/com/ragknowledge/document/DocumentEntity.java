package com.ragknowledge.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
// 列表按创建时间倒序返回，索引避免文档量上来后每轮轮询都做全表排序
@Table(name = "kb_document", indexes = @Index(name = "idx_kb_document_created_at", columnList = "createdAt"))
public class DocumentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 500)
    private String fileName;

    @Column(nullable = false, length = 20)
    private String fileType;

    @Column(nullable = false)
    private Long fileSize;

    /** 已完成入库的切片数 */
    private Integer chunkCount;

    /**
     * 本次入库的切片总数，与 chunkCount 组合即为入库进度。
     * 0 表示还在解析/切分阶段、总数未知；入库完成或失败后统一归零。
     */
    @Column(name = "chunk_total", columnDefinition = "integer default 0")
    private int chunkTotal = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @Column(length = 2000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public int getChunkTotal() {
        return chunkTotal;
    }

    public void setChunkTotal(int chunkTotal) {
        this.chunkTotal = chunkTotal;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 进入处理中：进度归零、错误信息清空。
     * 上传登记与失败重试共用同一状态入口，保证"新一轮开始"的字段语义只在这一处定义。
     */
    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
        this.chunkCount = 0;
        this.chunkTotal = 0;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 入库完成终态：chunkCount 固化为最终切片数（前端展示总量），进度条使命结束、chunkTotal 归零。
     */
    public void markCompleted(int chunkCount) {
        this.status = DocumentStatus.COMPLETED;
        this.chunkCount = chunkCount;
        this.chunkTotal = 0;
        this.errorMessage = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 失败终态：进度归零、错误信息落库。
     * 入库失败与启动清扫共用同一状态入口，保证"失败长什么样"只在这一处定义。
     */
    public void markFailed(String message) {
        this.status = DocumentStatus.FAILED;
        this.chunkCount = 0;
        this.chunkTotal = 0;
        this.errorMessage = message;
        this.updatedAt = LocalDateTime.now();
    }
}
