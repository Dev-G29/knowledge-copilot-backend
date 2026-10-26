package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    // Cosine similarity search in Postgres. The halfvec cast must match the expression of the
    // HNSW index created in V2__pgvector_embeddings.sql, otherwise the index is not used.
    // (cast(x as t) and x::t are the same expression to Postgres; cast() avoids clashing with :params.)
    @Query(value = """
            select c.id as "chunkId",
                   d.id as "documentId",
                   d.name as "documentName",
                   c.content as "content",
                   1 - (cast(c.embedding as halfvec(3072)) <=> cast(:queryVector as halfvec(3072))) as "score"
            from document_chunks c
            join documents d on d.id = c.document_id
            where c.embedding is not null
            order by cast(c.embedding as halfvec(3072)) <=> cast(:queryVector as halfvec(3072))
            limit :limit
            """, nativeQuery = true)
    List<ChunkMatch> findNearest(@Param("queryVector") String queryVector, @Param("limit") int limit);

    @Modifying
    @Query("delete from DocumentChunk chunk where chunk.document.id = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
