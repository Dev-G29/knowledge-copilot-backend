package com.devgangavkar.knowledgecopilot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devgangavkar.knowledgecopilot.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "application.rate-limit.chat-requests-per-minute=2")
class RateLimitTest extends IntegrationTest {

    @Test
    void chatRequestsAreLimitedPerUser() throws Exception {
        String user = newUserToken();
        String otherUser = newUserToken();
        String body = json(Map.of("query", "anything at all"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/chat/query").header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/chat/query").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // Limits are per user, so someone else is unaffected.
        mockMvc.perform(post("/chat/query").header(HttpHeaders.AUTHORIZATION, bearer(otherUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
