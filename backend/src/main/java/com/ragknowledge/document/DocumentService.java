package com.ragknowledge.document;

import com.ragknowledge.common.BizException;
import com.ragknowledge.document.dto.DocumentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 允许入库的扩展名；超出范围的直接拒绝，避免把无关文件塞进 Tika 做无谓解析 */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("pdf", "doc", "docx", "ppt", "pptx", "txt", "md", "markdown", "html", "htm");

    /** 文件名中的路径分隔符、Windows 非法字符与控制字符 */
    private static final Pattern ILLEGAL_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private static final int MAX_FILE_NAME_LENGTH = 200;

    private final DocumentRepository repository;
    private final DocumentIngestService ingestService;
    private final DocumentVectorRepository vectorRepository;
    private final Path storageDir;

    public DocumentService(DocumentRepository repository,
                           DocumentIngestService ingestService,
                           DocumentVectorRepository vectorRepository,
                           @Value("${rag.storage-dir}") String storageDir) {
        this.repository = repository;
        this.ingestService = ingestService;
        this.vectorRepository = vectorRepository;
        this.storageDir = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            // 构造器不向外抛受检异常，否则 Spring 只会给出一层 BeanCreationException，排障不便
            throw new IllegalStateException("资料存储目录不可用：" + this.storageDir, e);
        }
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
        String fileName = sanitize(Paths.get(originalName).getFileName().toString());
        String fileType = extension(fileName);
        if (!ALLOWED_TYPES.contains(fileType)) {
            throw new BizException("不支持的文件类型：" + fileType);
        }

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
        entity.setChunkTotal(0);
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            repository.save(entity);
        } catch (RuntimeException e) {
            // 文件已落盘但登记失败：不清理就会成为没人认领的孤儿文件
            deleteFileQuietly(target);
            throw e;
        }

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
        vectorRepository.deleteByDocId(docId);
        repository.delete(entity);
        // 先删实体再删文件：即使源文件清理失败，知识库里也不会再检索到该文档
        deleteFileQuietly(storagePath(docId, entity.getFileName()));
        log.info("文档已删除：{}（{}）", entity.getFileName(), docId);
    }

    private void deleteFileQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除源文件失败：{} -> {}", file, e.getMessage());
        }
    }

    /** 清洗原始文件名：去掉非法字符并限制长度，保证落盘一定成功 */
    private String sanitize(String name) {
        String cleaned = ILLEGAL_NAME_CHARS.matcher(name).replaceAll("_").trim();
        if (cleaned.chars().allMatch(c -> c == '.')) {
            cleaned = "";
        }
        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot >= 0 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, MAX_FILE_NAME_LENGTH - ext.length()) + ext;
        }
        return cleaned.isEmpty() ? "unnamed" : cleaned;
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
