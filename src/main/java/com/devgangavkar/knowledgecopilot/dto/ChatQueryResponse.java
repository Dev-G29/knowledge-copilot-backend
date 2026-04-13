package com.devgangavkar.knowledgecopilot.dto;

import java.util.List;

public record ChatQueryResponse(
        String answer,
        List<ChatSourceResponse> sources,
        Long conversationId,
        double confidence
) {
}
