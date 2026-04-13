package com.devgangavkar.knowledgecopilot.ai;

import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;

public record SimilaritySearchResult(
        DocumentChunk chunk,
        double score
) {
}
