package com.devgangavkar.knowledgecopilot.ai;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpringAiEmbeddingService implements EmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        EmbeddingModel embeddingModel = requireEmbeddingModel();
        List<float[]> embeddings = embeddingModel.embed(texts);

        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("Embedding model returned an unexpected number of vectors");
        }

        List<List<Double>> result = new ArrayList<>(embeddings.size());
        for (float[] embedding : embeddings) {
            result.add(toDoubleList(embedding));
        }
        return result;
    }

    @Override
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed is required");
        }

        return toDoubleList(requireEmbeddingModel().embed(text));
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

    private List<Double> toDoubleList(float[] embedding) {
        List<Double> values = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            values.add((double) value);
        }
        return values;
    }
}
