package com.devgangavkar.knowledgecopilot.support;

import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

/**
 * Deterministic stand-ins for Gemini so tests run offline. Embeddings are a hashed
 * bag-of-words, so texts that share words are close in cosine space.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiConfiguration {

    public static final String FAKE_ANSWER = "This is a grounded fake answer.";
    // A question containing this makes the fake stream fail midway, like a Gemini error.
    public static final String FAIL_STREAM_MARKER = "simulate-stream-failure";
    public static final List<String> FAKE_STREAM_TOKENS = List.of("This ", "is ", "a ", "streamed ", "answer.");

    @Bean
    EmbeddingModel fakeEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<String> texts = request.getInstructions();
                java.util.ArrayList<Embedding> embeddings = new java.util.ArrayList<>();
                for (int index = 0; index < texts.size(); index++) {
                    embeddings.add(new Embedding(embed(texts.get(index)), index));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public float[] embed(Document document) {
                return embed(document.getText());
            }

            @Override
            public float[] embed(String text) {
                return bagOfWords(text);
            }

            @Override
            public int dimensions() {
                return DocumentChunk.EMBEDDING_DIMENSIONS;
            }
        };
    }

    @Bean
    ChatModel fakeChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response(FAKE_ANSWER);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (prompt.getContents().contains(FAIL_STREAM_MARKER)) {
                    return Flux.concat(Flux.just(response("Partial ")), Flux.error(new IllegalStateException("simulated Gemini failure")));
                }
                return Flux.fromIterable(FAKE_STREAM_TOKENS).map(this::response);
            }

            private ChatResponse response(String text) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }
        };
    }

    public static float[] bagOfWords(String text) {
        float[] vector = new float[DocumentChunk.EMBEDDING_DIMENSIONS];
        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() >= 3) {
                vector[Math.floorMod(token.hashCode(), vector.length)] += 1.0f;
            }
        }

        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1.0f;
            return vector;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
        return vector;
    }
}
