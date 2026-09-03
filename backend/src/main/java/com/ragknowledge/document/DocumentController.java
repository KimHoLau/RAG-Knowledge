package com.ragknowledge.document;

import com.ragknowledge.common.ApiResult;
import com.ragknowledge.document.dto.DocumentFile;
import com.ragknowledge.document.dto.DocumentVO;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** 非 ASCII 字符（中文文件名等）在 filename= 里不安全，降级时统一替换为下划线 */
    private static final Pattern NON_ASCII = Pattern.compile("[^\\x20-\\x7E]");

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ApiResult<DocumentVO> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok(documentService.upload(file));
    }

    @GetMapping
    public ApiResult<List<DocumentVO>> list() {
        return ApiResult.ok(documentService.list());
    }

    /**
     * 下载原始文件，供人工核对入库内容。
     * 下载名含中文/空格时不能直接写 filename=，否则浏览器拿到乱码；
     * 这里按 RFC 5987 同时给出 ASCII 降级名与 filename*=UTF-8'' 编码名，老浏览器也能用。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        DocumentFile file = documentService.load(id);
        String encoded = UriUtils.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String asciiFallback = NON_ASCII.matcher(file.fileName()).replaceAll("_");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded)
                .body(file.resource());
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        documentService.delete(id);
        return ApiResult.ok();
    }
}
