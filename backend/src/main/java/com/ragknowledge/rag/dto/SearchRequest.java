package com.ragknowledge.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchRequest(
        @NotBlank(message = "检索内容不能为空")
        @Size(max = 2000, message = "检索内容过长")
        String query,
        @Size(min = 1, max = 20, message = "topK 取值范围为 1-20")
        Integer topK) {
}
