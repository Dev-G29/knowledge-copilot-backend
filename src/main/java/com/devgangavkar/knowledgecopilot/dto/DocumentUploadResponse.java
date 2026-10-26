package com.devgangavkar.knowledgecopilot.dto;

import java.time.Instant;

public record DocumentUploadResponse(
        Long id,
        String name,
        String uploadedBy,
        Instant createdAt,
        String contentType,
        Long sizeBytes,
        int chunkCount
) {
}
