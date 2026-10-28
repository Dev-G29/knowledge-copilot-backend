package com.devgangavkar.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devgangavkar.knowledgecopilot.support.FakeAiConfiguration;
import com.devgangavkar.knowledgecopilot.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties = "application.rag.first-token-timeout-seconds=1")
class StreamTimeoutTest extends IntegrationTest {

    @Test
    void aModelThatNeverAnswersEndsWithAClearErrorInsteadOfHanging() throws Exception {
        String admin = adminToken();
        String user = newUserToken();
        MockMultipartFile file = new MockMultipartFile("file", "timeout-policy.txt", "text/plain",
                "Every full-time employee receives twenty paid vacation days per calendar year.".getBytes(StandardCharsets.UTF_8));
        String uploaded = mockMvc.perform(multipart("/documents/upload").file(file).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long documentId = readJson(uploaded).get("id").asLong();

        long startedAt = System.nanoTime();
        MvcResult result = mockMvc.perform(post("/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("query", "How many paid vacation days per year? " + FakeAiConfiguration.HANGING_STREAM_MARKER))))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.getResponse().getContentAsString())
                .contains("event:meta", "event:error", "taking too long")
                .doesNotContain("event:done");
        assertThat(elapsedMillis).isLessThan(5_000);

        mockMvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
    }
}
