package com.devgangavkar.knowledgecopilot.dto;

import java.time.Instant;

public record DocumentResponse(
        Long id,
        String name,
        String uploadedBy,
        Instant createdAt,
        int chunkCount
) {
}
