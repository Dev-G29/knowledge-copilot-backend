package com.devgangavkar.knowledgecopilot.ai;

public record SimilaritySearchResult(
        Long chunkId,
        Long documentId,
        String documentName,
        String content,
        double score
) {
}
