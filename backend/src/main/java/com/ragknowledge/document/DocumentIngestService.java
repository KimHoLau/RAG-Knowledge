package com.ragknowledge.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 资料入库：文本单元提取（Tika 整篇拍平，扫描件 PDF 文本层缺失时在提取服务内自动转 PdfOcrService 识别）
 * -> TokenTextSplitter 切分 -> qwen3-embedding:4b 向量化 -> pgvector 存储。
 * 切片 metadata 中写入 doc_id / doc_name（及定位信息 page/section，逐页与章节路径接入后生效），
 * 用于溯源展示、引用定位与按文档删除。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final DocumentRepository repository;
    private final DocumentVectorRepository vectorRepository;
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final TextExtractionService extractionService;
    private final int batchSize;

    public DocumentIngestService(DocumentRepository repository,
                                 DocumentVectorRepository vectorRepository,
                                 VectorStore vectorStore,
                                 TokenTextSplitter textSplitter,
                                 TextExtractionService extractionService,
                                 @Value("${rag.ingest.batch-size}") int batchSize) {
        this.repository = repository;
        this.vectorRepository = vectorRepository;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.extractionService = extractionService;
        this.batchSize = Math.max(1, batchSize);
    }

    @Async("ragIngestExecutor")
    public void ingest(String docId, Path file, String fileName) {
        long startAt = System.currentTimeMillis();
        try {
            // 提取服务产出统一文本单元；扫描件 PDF 的整本 OCR 兜底也在其中，
            // 页级进度借 chunkCount/chunkTotal 上报，前端同一进度条展示
            List<TextUnit> units = extractionService.extract(file, (done, total) -> reportProgress(docId, done, total));
            // 入库前先清掉该文档的历史切片：失败重传、重复入库都不会留下重复向量
            vectorRepository.deleteByDocId(docId);

            // 每个文本单元独立切分：定位信息（页码/章节）不会跨单元串片，
            // 整篇拍平路径只有一个单元，切分行为与旧实现完全一致
            List<Document> chunks = new ArrayList<>();
            for (TextUnit unit : units) {
                chunks.addAll(textSplitter.apply(List.of(new Document(unit.text(), unit.toMetadata(docId, fileName)))));
            }
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档切分后未产生有效切片");
            }
            int total = chunks.size();
            // 先落总数，前端进度条才有分母；此时已完成数为 0，展示为 0%
            reportProgress(docId, 0, total);

            // 分批向量化写入，每批完成回写一次进度，前端轮询即可看到进度条推进
            for (int i = 0; i < total; i += batchSize) {
                vectorStore.add(chunks.subList(i, Math.min(i + batchSize, total)));
                reportProgress(docId, Math.min(i + batchSize, total), total);
            }
            mutateDocument(docId, entity -> entity.markCompleted(total));
            log.info("资料入库完成：{} -> {} 个切片（docId={}），耗时 {} ms",
                    fileName, total, docId, System.currentTimeMillis() - startAt);
        } catch (Exception e) {
            log.error("资料入库失败（docId={}，file={}）", docId, fileName, e);
            // 分批写入可能在半途失败，清掉残留切片，避免脏数据参与检索
            deleteVectorsQuietly(docId);
            mutateDocument(docId, entity -> entity.markFailed(abbreviate(e)));
        }
    }

    /**
     * 回写入库进度并渲染前端进度条，不改状态。
     * OCR 阶段为已完成页数/总页数，切分向量化阶段为已完成切片数/切片总数，同一进度条分阶段复用。
     */
    private void reportProgress(String docId, int done, int total) {
        mutateDocument(docId, entity -> {
            entity.setChunkCount(done);
            entity.setChunkTotal(total);
        });
    }

    /**
     * 加载文档实体、应用变更并保存。
     * 走 findById + save 而不是 @Modifying 更新：入库线程没有事务边界，
     * 自定义 @Modifying 方法在无事务时会抛 TransactionRequiredException。
     */
    private void mutateDocument(String docId, Consumer<DocumentEntity> mutator) {
        repository.findById(docId).ifPresent(entity -> {
            mutator.accept(entity);
            entity.setUpdatedAt(LocalDateTime.now());
            repository.save(entity);
        });
    }

    private void deleteVectorsQuietly(String docId) {
        try {
            int deleted = vectorRepository.deleteByDocId(docId);
            if (deleted > 0) {
                log.info("已清理入库失败的残留切片：docId={}，{} 行", docId, deleted);
            }
        } catch (Exception e) {
            // 清理失败只记录，不能掩盖真正的入库失败原因
            log.warn("清理残留切片失败（docId={}）：{}", docId, e.getMessage());
        }
    }

    private String abbreviate(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        return msg.length() > 1900 ? msg.substring(0, 1900) : msg;
    }
}
