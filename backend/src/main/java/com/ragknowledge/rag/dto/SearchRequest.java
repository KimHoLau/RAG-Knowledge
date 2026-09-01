package com.ragknowledge.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SearchRequest(
        @NotBlank(message = "检索内容不能为空")
        @Size(max = 2000, message = "检索内容过长")
        String query,
        @Min(value = 1, message = "topK 取值范围为 1-20")
        @Max(value = 20, message = "topK 取值范围为 1-20")
        Integer topK) {
}
