package com.devgangavkar.knowledgecopilot.dto;

import java.time.Instant;
import java.util.List;

public record ConversationMessageDto(
        Long id,
        String query,
        String answer,
        Instant timestamp,
        List<ChatSourceResponse> sources,
        Double confidence
) {
}
