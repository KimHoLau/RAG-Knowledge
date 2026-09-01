package com.ragknowledge.document;

import com.ragknowledge.common.ApiResult;
import com.ragknowledge.document.dto.DocumentVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

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

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        documentService.delete(id);
        return ApiResult.ok();
    }
}
