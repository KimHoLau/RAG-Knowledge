package com.ragknowledge.rag;

import com.ragknowledge.common.ApiResult;
import com.ragknowledge.rag.dto.SearchRequest;
import com.ragknowledge.rag.dto.SourceChunk;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final RagService ragService;

    public SearchController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 知识检索：直接返回向量检索命中的知识切片（不经过大模型） */
    @PostMapping
    public ApiResult<List<SourceChunk>> search(@Valid @RequestBody SearchRequest request) {
        return ApiResult.ok(ragService.search(request.query(), request.topK()));
    }
}
