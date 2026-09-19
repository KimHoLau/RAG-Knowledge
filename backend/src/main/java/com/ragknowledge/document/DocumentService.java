package com.ragknowledge.document;

import com.ragknowledge.common.BizException;
import com.ragknowledge.document.dto.DocumentFile;
import com.ragknowledge.document.dto.DocumentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
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

    /**
     * 取源文件用于下载核对。
     * 入库状态不影响下载——文件在上传时就已落盘，失败/处理中的文档同样需要下载排查。
     * 源文件被外部清理（手工删目录、换机器部署等）时给出明确提示，避免浏览器收到一个 500。
     */
    public DocumentFile load(String docId) {
        DocumentEntity entity = repository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        Path file = storagePath(docId, entity.getFileName());
        if (!Files.isRegularFile(file)) {
            throw new BizException("源文件已不存在，无法下载");
        }
        return new DocumentFile(entity.getFileName(), new FileSystemResource(file));
    }

    /**
     * 重试入库：对失败终态的文档复用已落盘源文件与既有异步入库路径，不必删除后重新上传。
     * 仅失败终态可触发（处理中/已完成重跑只会制造重复切片与混乱状态）；
     * 触发前校验源文件仍在盘——文件被外部清理时给明确提示，而不是等入库线程稍后才报错。
     */
    public DocumentVO retry(String docId) {
        DocumentEntity entity = repository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        if (entity.getStatus() != DocumentStatus.FAILED) {
            throw new BizException("仅入库失败的文档可重试（当前状态：" + statusText(entity.getStatus()) + "）");
        }
        Path file = storagePath(docId, entity.getFileName());
        if (!Files.isRegularFile(file)) {
            throw new BizException("源文件已不存在，无法重试入库，请删除后重新上传");
        }
        entity.setStatus(DocumentStatus.PROCESSING);
        entity.setChunkCount(0);
        entity.setChunkTotal(0);
        entity.setErrorMessage(null);
        entity.setUpdatedAt(LocalDateTime.now());
        DocumentVO vo = DocumentVO.from(repository.save(entity));
        // 与上传共用同一条异步入库路径；批次向量化半途失败仍由入库服务全有全无清理
        ingestService.ingest(docId, file, entity.getFileName());
        log.info("已触发重试入库：{}（{}）", entity.getFileName(), docId);
        return vo;
    }

    /**
     * 启动清扫：上次进程中断遗留的处理中文档不会再有结果（入库线程已随进程消失），
     * 应用就绪时统一标记为失败终态并按 doc_id 清掉半成品切片，
     * 避免列表里永远转圈的文档、也避免残留切片悄悄参与检索。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepInterruptedIngests() {
        List<DocumentEntity> stuck = repository.findByStatus(DocumentStatus.PROCESSING);
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("检测到 {} 个服务重启中断的处理中文档，清扫为失败终态", stuck.size());
        for (DocumentEntity entity : stuck) {
            try {
                vectorRepository.deleteByDocId(entity.getId());
                entity.setStatus(DocumentStatus.FAILED);
                entity.setChunkCount(0);
                entity.setChunkTotal(0);
                entity.setErrorMessage("服务重启中断，可重试入库");
                entity.setUpdatedAt(LocalDateTime.now());
                repository.save(entity);
                log.warn("已清扫中断文档：{}（{}）", entity.getFileName(), entity.getId());
            } catch (Exception e) {
                // 单个文档清扫失败只记录，不能让启动被一个坏数据卡死
                log.error("清扫中断文档失败（docId={}）：{}", entity.getId(), e.getMessage());
            }
        }
    }

    private String statusText(DocumentStatus status) {
        return switch (status) {
            case PROCESSING -> "处理中";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
        };
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
