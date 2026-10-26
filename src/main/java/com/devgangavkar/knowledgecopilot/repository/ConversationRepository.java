package com.devgangavkar.knowledgecopilot.repository;

import com.devgangavkar.knowledgecopilot.entity.Conversation;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    // Conversations without any saved message (e.g. a stream that failed) are hidden.
    @Query("""
            select c from Conversation c
            where c.userId = :userId and c.messages is not empty
            order by c.updatedAt desc
            """)
    List<Conversation> findRecentWithMessages(@Param("userId") Long userId, Pageable pageable);

    @Modifying
    @Query("delete from Conversation conversation where conversation.id = :id")
    int deleteConversationById(@Param("id") Long id);
}
