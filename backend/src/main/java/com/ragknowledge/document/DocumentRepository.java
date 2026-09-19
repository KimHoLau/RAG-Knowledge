package com.ragknowledge.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    List<DocumentEntity> findAllByOrderByCreatedAtDesc();

    /** 启动清扫用：捞出服务中断时仍停留在处理中的文档 */
    List<DocumentEntity> findByStatus(DocumentStatus status);
}
