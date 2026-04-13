package com.devgangavkar.knowledgecopilot.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record AuthRequest(
        @JsonAlias("email")
        @NotBlank(message = "Username or email is required")
        String username,
        @NotBlank(message = "Password is required")
        String password
) {
}
