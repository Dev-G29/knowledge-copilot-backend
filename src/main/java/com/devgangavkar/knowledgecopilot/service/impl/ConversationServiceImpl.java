package com.devgangavkar.knowledgecopilot.service.impl;

import com.devgangavkar.knowledgecopilot.dto.ConversationDto;
import com.devgangavkar.knowledgecopilot.dto.ConversationMessageDto;
import com.devgangavkar.knowledgecopilot.entity.ChatMessage;
import com.devgangavkar.knowledgecopilot.entity.Conversation;
import com.devgangavkar.knowledgecopilot.entity.User;
import com.devgangavkar.knowledgecopilot.exception.ResourceNotFoundException;
import com.devgangavkar.knowledgecopilot.exception.UnauthorizedException;
import com.devgangavkar.knowledgecopilot.repository.ChatMessageRepository;
import com.devgangavkar.knowledgecopilot.repository.ConversationRepository;
import com.devgangavkar.knowledgecopilot.security.CurrentUserService;
import com.devgangavkar.knowledgecopilot.service.ConversationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final int RECENT_CONVERSATION_LIMIT = 30;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public List<ConversationDto> listRecentConversations() {
        User currentUser = currentUserService.requireCurrentUser();

        return conversationRepository.findRecentWithMessages(currentUser.getId(), PageRequest.of(0, RECENT_CONVERSATION_LIMIT))
                .stream()
                .map(this::toConversationDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationMessageDto> getConversationMessages(Long conversationId) {
        requireOwnedConversation(conversationId);

        return chatMessageRepository.findByConversation_IdOrderByTimestampAsc(conversationId)
                .stream()
                .map(this::toMessageDto)
                .toList();
    }

    @Override
    @Transactional
    public ConversationDto renameConversation(Long conversationId, String title) {
        Conversation conversation = requireOwnedConversation(conversationId);
        conversation.setTitle(title.trim());
        return toConversationDto(conversationRepository.save(conversation));
    }

    @Override
    @Transactional
    public void deleteConversation(Long conversationId) {
        requireOwnedConversation(conversationId);
        chatMessageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteConversationById(conversationId);
    }

    private Conversation requireOwnedConversation(Long conversationId) {
        User currentUser = currentUserService.requireCurrentUser();
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));

        if (!conversation.getUserId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You do not have access to this conversation");
        }
        return conversation;
    }

    private ConversationDto toConversationDto(Conversation conversation) {
        String title = conversation.getTitle();
        if (title == null || title.isBlank()) {
            // Conversations created before titles existed fall back to their first question.
            title = chatMessageRepository.findFirstByConversation_IdOrderByTimestampAsc(conversation.getId())
                    .map(ChatMessage::getQuery)
                    .filter(query -> query != null && !query.isBlank())
                    .map(String::trim)
                    .orElse("Conversation " + conversation.getId());
        }

        return new ConversationDto(
                conversation.getId(),
                title,
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private ConversationMessageDto toMessageDto(ChatMessage chatMessage) {
        return new ConversationMessageDto(
                chatMessage.getId(),
                chatMessage.getQuery(),
                chatMessage.getResponse(),
                chatMessage.getTimestamp(),
                chatMessage.getSources() == null ? List.of() : chatMessage.getSources(),
                chatMessage.getConfidence()
        );
    }
}
