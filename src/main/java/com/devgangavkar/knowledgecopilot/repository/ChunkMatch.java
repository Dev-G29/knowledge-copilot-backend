package com.devgangavkar.knowledgecopilot.repository;

// Projection returned by the pgvector similarity query.
public interface ChunkMatch {

    Long getChunkId();

    Long getDocumentId();

    String getDocumentName();

    String getContent();

    Double getScore();
}
