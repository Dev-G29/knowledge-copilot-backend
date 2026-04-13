package com.devgangavkar.knowledgecopilot.dto;

import java.util.Set;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInMinutes,
        String username,
        Set<String> roles
) {
}
