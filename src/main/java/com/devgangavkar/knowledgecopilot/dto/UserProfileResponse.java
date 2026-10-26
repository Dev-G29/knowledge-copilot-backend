package com.devgangavkar.knowledgecopilot.dto;

import java.util.Set;

public record UserProfileResponse(
        String username,
        String email,
        Set<String> roles
) {
}
