package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    @Query("select chunk from DocumentChunk chunk where chunk.embedding is not null")
    List<DocumentChunk> findAllWithEmbedding();
}
