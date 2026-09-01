package com.ragknowledge.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题过长，请精简后再试")
        String query) {
}
