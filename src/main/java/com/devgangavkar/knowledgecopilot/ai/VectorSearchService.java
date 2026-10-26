package com.devgangavkar.knowledgecopilot.ai;

import com.devgangavkar.knowledgecopilot.repository.DocumentChunkRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private static final int DEFAULT_TOP_K = 5;

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;

    public List<SimilaritySearchResult> searchSimilarChunks(String query) {
        return searchSimilarChunks(query, DEFAULT_TOP_K);
    }

    // Deliberately not transactional: the embedding call goes to Gemini over the network and
    // should not hold a database connection. The repository query runs in its own read-only transaction.
    public List<SimilaritySearchResult> searchSimilarChunks(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query is required");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }

        String queryVector = toVectorLiteral(embeddingService.embedQuery(query));

        return documentChunkRepository.findNearest(queryVector, topK).stream()
                .map(match -> new SimilaritySearchResult(
                        match.getChunkId(),
                        match.getDocumentId(),
                        match.getDocumentName(),
                        match.getContent(),
                        match.getScore() == null ? -1.0d : match.getScore()
                ))
                .toList();
    }

    // pgvector's text input format: [0.1,0.2,...]
    static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }
}
