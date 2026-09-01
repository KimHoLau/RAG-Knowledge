package com.ragknowledge.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    List<DocumentEntity> findAllByOrderByCreatedAtDesc();
}
