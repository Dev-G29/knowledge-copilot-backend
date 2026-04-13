package com.devgangavkar.knowledgecopilot.dto;

import java.time.LocalDateTime;

public record ConversationDto(
        Long id,
        String title,
        LocalDateTime createdAt
) {
}
