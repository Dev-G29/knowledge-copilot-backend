package com.devgangavkar.knowledgecopilot.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatQueryRequest(
        @NotBlank(message = "Query is required")
        String query,
        Long conversationId
) {
}
