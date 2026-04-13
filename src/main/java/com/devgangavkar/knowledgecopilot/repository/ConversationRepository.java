package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.Conversation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);
}
