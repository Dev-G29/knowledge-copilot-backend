package com.devgangavkar.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devgangavkar.knowledgecopilot.support.FakeAiConfiguration;
import com.devgangavkar.knowledgecopilot.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class KnowledgeCopilotApiTest extends IntegrationTest {

    private static final String POLICY_TEXT = """
            Vacation policy. Every full-time employee receives twenty five paid vacation days per calendar year.
            Unused vacation days can be carried over into the first quarter of the following year.

            Remote work policy. Employees may work remotely up to three days per week with manager approval.
            """;

    @Test
    void registerLoginAndProfile() throws Exception {
        String token = newUserToken();

        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.roles[0]").value("USER"));

        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRejectsDuplicateEmailRegardlessOfCase() throws Exception {
        Map<String, String> first = Map.of("username", "case-user-1", "email", "Case.User@example.com", "password", "password-123");
        Map<String, String> second = Map.of("username", "case-user-2", "email", "case.user@EXAMPLE.com", "password", "password-123");

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(first)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    void documentsChatStreamingAndConversationManagement() throws Exception {
        String admin = adminToken();
        String user = newUserToken();
        String otherUser = newUserToken();

        // --- Documents -------------------------------------------------------------
        MockMultipartFile file = new MockMultipartFile(
                "file", "vacation-policy.txt", "text/plain", POLICY_TEXT.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/documents/upload").file(file).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());

        String uploaded = mockMvc.perform(multipart("/documents/upload").file(file).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("vacation-policy.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andReturn().getResponse().getContentAsString();
        long documentId = readJson(uploaded).get("id").asLong();

        mockMvc.perform(get("/documents").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].chunkCount", documentId).value(1))
                .andExpect(jsonPath("$[?(@.id == %d)].sizeBytes", documentId).exists());

        // --- Synchronous chat (pgvector retrieval) --------------------------------
        String answer = mockMvc.perform(post("/chat/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "How many vacation days does an employee receive per year?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(FakeAiConfiguration.FAKE_ANSWER))
                .andExpect(jsonPath("$.sources[0].document").value("vacation-policy.txt"))
                .andReturn().getResponse().getContentAsString();
        JsonNode answerJson = readJson(answer);
        long conversationId = answerJson.get("conversationId").asLong();
        assertThat(answerJson.get("confidence").asDouble()).isGreaterThan(0.25);

        // --- Streaming chat in the same conversation ------------------------------
        Map<String, Object> streamRequest = new HashMap<>();
        streamRequest.put("query", "Can unused vacation days be carried over?");
        streamRequest.put("conversationId", conversationId);
        MvcResult streaming = mockMvc.perform(post("/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(streamRequest)))
                .andExpect(request().asyncStarted())
                .andReturn();
        streaming.getAsyncResult(10_000);
        String events = streaming.getResponse().getContentAsString();

        assertThat(streaming.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(events).contains("event:meta", "\"conversationId\":" + conversationId, "vacation-policy.txt");
        assertThat(events).contains("event:token", "\"text\":\"streamed \"");
        assertThat(events).contains("event:done").doesNotContain("event:error");

        // --- History keeps answers and citations ----------------------------------
        mockMvc.perform(get("/chat/conversations").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("How many vacation days does an employee receive per year?"))
                .andExpect(jsonPath("$[0].updatedAt").exists());

        mockMvc.perform(get("/chat/conversations/{id}", conversationId).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].answer").value(String.join("", FakeAiConfiguration.FAKE_STREAM_TOKENS)))
                .andExpect(jsonPath("$[1].sources[0].document").value("vacation-policy.txt"))
                .andExpect(jsonPath("$[1].confidence").isNumber());

        // --- Ownership, rename, delete ---------------------------------------------
        mockMvc.perform(get("/chat/conversations/{id}", conversationId).header(HttpHeaders.AUTHORIZATION, bearer(otherUser)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/chat/conversations/{id}", conversationId).header(HttpHeaders.AUTHORIZATION, bearer(otherUser)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/chat/conversations/{id}", conversationId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Vacation questions"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Vacation questions"));

        mockMvc.perform(delete("/chat/conversations/{id}", conversationId).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/chat/conversations").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.length()").value(0));

        // --- Document deletion removes it from retrieval ---------------------------
        mockMvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/chat/query")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "How many vacation days does an employee receive per year?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("No relevant information found in uploaded documents."))
                .andExpect(jsonPath("$.sources.length()").value(0));
    }

    @Test
    void conversationSurvivesAFailedStreamSoFollowUpsWork() throws Exception {
        String admin = adminToken();
        String user = newUserToken();
        MockMultipartFile file = new MockMultipartFile(
                "file", "leave-policy.txt", "text/plain", POLICY_TEXT.getBytes(StandardCharsets.UTF_8));
        String uploaded = mockMvc.perform(multipart("/documents/upload").file(file).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long documentId = readJson(uploaded).get("id").asLong();

        String failed = streamEvents(user, Map.of(
                "query", "How many paid vacation days per year? " + FakeAiConfiguration.FAIL_STREAM_MARKER));
        assertThat(failed).contains("event:meta", "event:error").doesNotContain("event:done");
        String idMarker = "\"conversationId\":";
        int start = failed.indexOf(idMarker) + idMarker.length();
        long conversationId = Long.parseLong(failed.substring(start, failed.indexOf(',', start)));

        Map<String, Object> followUp = new HashMap<>();
        followUp.put("query", "How many paid vacation days per year?");
        followUp.put("conversationId", conversationId);
        assertThat(streamEvents(user, followUp)).contains("event:done").doesNotContain("event:error");

        // Tests share one database; don't leave documents behind for retrieval assertions elsewhere.
        mockMvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
    }

    private String streamEvents(String token, Map<String, ?> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);
        return result.getResponse().getContentAsString();
    }

    @Test
    void streamingRejectsUnknownConversationWithJsonError() throws Exception {
        String user = newUserToken();

        mockMvc.perform(post("/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header(HttpHeaders.ACCEPT, "text/event-stream, application/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "hello there", "conversationId", 999_999))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Conversation not found: 999999"));
    }

    @Test
    void actuatorHealthIsPublicButMetricsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void corsAllowsConfiguredOriginOnly() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/chat/query")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/chat/query")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
