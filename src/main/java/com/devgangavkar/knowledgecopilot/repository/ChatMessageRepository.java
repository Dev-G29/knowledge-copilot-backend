package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop3ByConversation_IdOrderByTimestampDesc(Long conversationId);

    Optional<ChatMessage> findFirstByConversation_IdOrderByTimestampAsc(Long conversationId);

    List<ChatMessage> findByConversation_IdOrderByTimestampAsc(Long conversationId);

    @Modifying
    @Query("delete from ChatMessage message where message.conversation.id = :conversationId")
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
