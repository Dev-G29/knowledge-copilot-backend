package com.devgangavkar.knowledgecopilot.ai;

import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;
import com.devgangavkar.knowledgecopilot.repository.DocumentChunkRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private static final int DEFAULT_TOP_K = 5;

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;

    @Transactional(readOnly = true)
    public List<SimilaritySearchResult> searchSimilarChunks(String query) {
        return searchSimilarChunks(query, DEFAULT_TOP_K);
    }

    @Transactional(readOnly = true)
    public List<SimilaritySearchResult> searchSimilarChunks(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query is required");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }

        List<Double> queryEmbedding = embeddingService.embed(query);

        return documentChunkRepository.findAllWithEmbedding().stream()
                .map(chunk -> new SimilaritySearchResult(chunk, cosineSimilarity(queryEmbedding, chunk.getEmbedding())))
                .sorted(Comparator.comparingDouble(SimilaritySearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return -1.0d;
        }

        double dotProduct = 0.0d;
        double leftMagnitude = 0.0d;
        double rightMagnitude = 0.0d;

        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dotProduct += leftValue * rightValue;
            leftMagnitude += leftValue * leftValue;
            rightMagnitude += rightValue * rightValue;
        }

        if (leftMagnitude == 0.0d || rightMagnitude == 0.0d) {
            return -1.0d;
        }

        return dotProduct / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
