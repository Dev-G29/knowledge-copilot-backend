package com.devgangavkar.knowledgecopilot.dto;

import java.time.LocalDateTime;

public record ConversationMessageDto(
        String query,
        String answer,
        LocalDateTime timestamp
) {
}
