package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.dto.DocumentResponse;
import com.devgangavkar.knowledgecopilot.entity.Document;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    // Counts chunks in SQL so listing documents never loads chunk rows (and their embeddings).
    @Query("""
            select new com.devgangavkar.knowledgecopilot.dto.DocumentResponse(
                d.id, d.name, d.uploadedBy, d.createdAt, d.contentType, d.sizeBytes, count(c.id))
            from Document d
            left join d.chunks c
            group by d.id, d.name, d.uploadedBy, d.createdAt, d.contentType, d.sizeBytes
            order by d.createdAt desc
            """)
    List<DocumentResponse> findAllSummaries();

    @Modifying
    @Query("delete from Document document where document.id = :id")
    int deleteDocumentById(@Param("id") Long id);
}
