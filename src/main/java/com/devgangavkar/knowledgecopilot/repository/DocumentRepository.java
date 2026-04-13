package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.Document;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findAllByOrderByCreatedAtDesc();
}
