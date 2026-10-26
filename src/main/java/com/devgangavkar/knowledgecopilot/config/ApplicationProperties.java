package com.devgangavkar.knowledgecopilot.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application")
public record ApplicationProperties(
        Security security,
        Cors cors,
        Admin admin,
        RateLimit rateLimit,
        Rag rag
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

    public record Cors(
            List<String> allowedOrigins
    ) {
    }

    public record Admin(
            String username,
            String email,
            String password,
            boolean syncPassword
    ) {
    }

    public record RateLimit(
            int chatRequestsPerMinute,
            int authRequestsPerMinute
    ) {
    }

    public record Rag(
            // Chunks with a cosine similarity below this are ignored when building the prompt.
            double minRelevanceScore
    ) {
    }
}
