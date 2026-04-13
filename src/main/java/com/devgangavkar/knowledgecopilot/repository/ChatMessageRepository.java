package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop3ByConversation_IdOrderByTimestampDesc(Long conversationId);

    Optional<ChatMessage> findFirstByConversation_IdOrderByTimestampAsc(Long conversationId);

    List<ChatMessage> findByConversation_IdOrderByTimestampAsc(Long conversationId);
}
