package com.devgangavkar.knowledgecopilot.ai;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpringAiEmbeddingService implements EmbeddingService {

    // Keeps each embedding API call to a size the Gemini batch endpoint accepts.
    private static final int BATCH_SIZE = 100;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        EmbeddingModel embeddingModel = requireEmbeddingModel();
        List<float[]> result = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + BATCH_SIZE));
            result.addAll(embed(embeddingModel, batch, GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT));
        }
        return result;
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed is required");
        }

        return embed(requireEmbeddingModel(), List.of(text), GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY).get(0);
    }

    private List<float[]> embed(EmbeddingModel embeddingModel, List<String> texts, GoogleGenAiTextEmbeddingOptions.TaskType taskType) {
        EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(texts, optionsFor(embeddingModel, taskType)));
        List<float[]> embeddings = response.getResults().stream()
                .map(embedding -> embedding.getOutput())
                .toList();

        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("Embedding model returned an unexpected number of vectors");
        }
        return embeddings;
    }

    // Gemini produces better retrieval when questions and documents are embedded with
    // their matching task types. Other providers just use their configured defaults.
    private EmbeddingOptions optionsFor(EmbeddingModel embeddingModel, GoogleGenAiTextEmbeddingOptions.TaskType taskType) {
        if (embeddingModel instanceof GoogleGenAiTextEmbeddingModel googleModel) {
            return GoogleGenAiTextEmbeddingOptions.builder()
                    .from(googleModel.defaultOptions)
                    .taskType(taskType)
                    .build();
        }
        return null;
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException(
                    "No Spring AI EmbeddingModel bean is configured. Configure Gemini via spring.ai.google.genai.embedding.api-key to enable embeddings."
            );
        }
        return embeddingModel;
    }
}
