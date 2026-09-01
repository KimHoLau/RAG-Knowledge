package com.ragknowledge.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 资料入库：Tika 解析（PDF/Word/PPT）-> TokenTextSplitter 切分 -> bge-m3 向量化 -> pgvector 存储。
 * 切片 metadata 中写入 doc_id / doc_name，用于溯源展示与按文档删除。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final DocumentRepository repository;
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final int batchSize;

    public DocumentIngestService(DocumentRepository repository,
                                 VectorStore vectorStore,
                                 TokenTextSplitter textSplitter,
                                 @Value("${rag.ingest.batch-size}") int batchSize) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.batchSize = Math.max(1, batchSize);
    }

    @Async
    public void ingest(String docId, Path file, String fileName) {
        try {
            String text = extractText(file);
            if (text.isBlank()) {
                throw new IllegalStateException("未能从文件中提取到文本内容（扫描件 PDF 需先做 OCR 处理）");
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("doc_id", docId);
            metadata.put("doc_name", fileName);

            List<Document> chunks = textSplitter.apply(List.of(new Document(text, metadata)));
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档切分后未产生有效切片");
            }
            for (int i = 0; i < chunks.size(); i += batchSize) {
                vectorStore.add(chunks.subList(i, Math.min(i + batchSize, chunks.size())));
            }
            updateStatus(docId, DocumentStatus.COMPLETED, chunks.size(), null);
            log.info("资料入库完成：{} -> {} 个切片（docId={}）", fileName, chunks.size(), docId);
        } catch (Exception e) {
            log.error("资料入库失败（docId={}，file={}）", docId, fileName, e);
            updateStatus(docId, DocumentStatus.FAILED, 0, abbreviate(e));
        }
    }

    private String extractText(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(bytes));
        return reader.read().stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    private void updateStatus(String docId, DocumentStatus status, int chunkCount, String error) {
        repository.findById(docId).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setChunkCount(chunkCount);
            entity.setErrorMessage(error);
            entity.setUpdatedAt(LocalDateTime.now());
            repository.save(entity);
        });
    }

    private String abbreviate(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        return msg.length() > 1900 ? msg.substring(0, 1900) : msg;
    }
}
