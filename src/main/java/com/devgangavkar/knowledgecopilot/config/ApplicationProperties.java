package com.devgangavkar.knowledgecopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application")
public record ApplicationProperties(
        Security security
) {

    public record Security(
            Jwt jwt
    ) {
    }

    public record Jwt(
            String secret,
            long accessTokenExpirationMinutes
    ) {
    }
}
