package com.devgangavkar.knowledgecopilot;

import com.devgangavkar.knowledgecopilot.support.DemoAiConfiguration;
import com.devgangavkar.knowledgecopilot.support.FakeAiConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the backend for local UI development with no external dependencies:
 * a throwaway pgvector container plus offline demo AI.
 *
 *   ./mvnw spring-boot:test-run
 *
 * Admin login: admin@example.com / admin12345 (only in this mode).
 */
public class LocalDevApplication {

    public static void main(String[] args) {
        System.setProperty("spring.ai.model.chat", "none");
        System.setProperty("spring.ai.model.embedding.text", "none");
        System.setProperty("spring.ai.google.genai.api-key", "offline");
        System.setProperty("spring.ai.google.genai.embedding.api-key", "offline");
        System.setProperty("application.admin.password", "admin12345");
        // The offline bag-of-words embeddings score lower than Gemini's, so relax the cutoff.
        System.setProperty("application.rag.min-relevance-score", "0.05");

        SpringApplication.from(KnowledgeCopilotApplication::main)
                .with(Containers.class, FakeAiConfiguration.class, DemoAiConfiguration.class)
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
        }
    }
}
