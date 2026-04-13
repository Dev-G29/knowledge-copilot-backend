package com.devgangavkar.knowledgecopilot.ai;

import java.util.List;

public interface EmbeddingService {

    List<List<Double>> embedAll(List<String> texts);

    List<Double> embed(String text);
}
