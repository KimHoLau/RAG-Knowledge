package com.ragknowledge.document;

import com.ragknowledge.common.BizException;
import com.ragknowledge.document.dto.DocumentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository repository;
    private final DocumentIngestService ingestService;
    private final JdbcTemplate jdbcTemplate;
    private final Path storageDir;

    public DocumentService(DocumentRepository repository,
                           DocumentIngestService ingestService,
                           JdbcTemplate jdbcTemplate,
                           @Value("${rag.storage-dir}") String storageDir) throws IOException {
        this.repository = repository;
        this.ingestService = ingestService;
        this.jdbcTemplate = jdbcTemplate;
        this.storageDir = Paths.get(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(this.storageDir);
    }

    public List<DocumentVO> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(DocumentVO::from)
                .toList();
    }

    public DocumentVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BizException("文件名不合法");
        }
        String fileName = Paths.get(originalName).getFileName().toString();
        String fileType = extension(fileName);

        String docId = UUID.randomUUID().toString();
        Path target = storagePath(docId, fileName);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("文件保存失败：" + e.getMessage());
        }

        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setFileName(fileName);
        entity.setFileType(fileType);
        entity.setFileSize(file.getSize());
        entity.setChunkCount(0);
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);

        // 异步执行解析、切分与向量化，前端通过列表接口轮询状态
        ingestService.ingest(docId, target, fileName);
        log.info("文件已上传：{}（{}），docId={}", fileName, fileType, docId);
        return DocumentVO.from(entity);
    }

    @Transactional
    public void delete(String docId) {
        DocumentEntity entity = repository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        // 删除该文档写入向量库的全部切片（doc_id 写在切片 metadata 中）
        jdbcTemplate.update("DELETE FROM public.vector_store WHERE metadata->>'doc_id' = ?", docId);
        repository.delete(entity);
        try {
            Files.deleteIfExists(storagePath(docId, entity.getFileName()));
        } catch (IOException e) {
            log.warn("删除源文件失败：{}", e.getMessage());
        }
        log.info("文档已删除：{}（{}）", entity.getFileName(), docId);
    }

    /** 源文件落盘路径：{docId}_{原始文件名}，upload 落盘与 delete 清理必须共用同一命名规则 */
    private Path storagePath(String docId, String fileName) {
        return storageDir.resolve(docId + "_" + fileName);
    }

    private String extension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase(Locale.ROOT) : "unknown";
    }
}
