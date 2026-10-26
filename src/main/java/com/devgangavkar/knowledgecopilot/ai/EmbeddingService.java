package com.devgangavkar.knowledgecopilot.ai;

import java.util.List;

public interface EmbeddingService {

    // Embeds document chunks for storage (retrieval "document" side).
    List<float[]> embedAll(List<String> texts);

    // Embeds a user question for similarity search (retrieval "query" side).
    float[] embedQuery(String text);
}
