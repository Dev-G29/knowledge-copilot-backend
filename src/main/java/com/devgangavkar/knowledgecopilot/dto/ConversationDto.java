package com.devgangavkar.knowledgecopilot.dto;

import java.time.Instant;

public record ConversationDto(
        Long id,
        String title,
        Instant createdAt,
        Instant updatedAt
) {
}
