package com.devgangavkar.knowledgecopilot.service.impl;
import java.time.Instant;
import com.devgangavkar.knowledgecopilot.dto.ConversationDto;
import com.devgangavkar.knowledgecopilot.dto.ConversationMessageDto;
import com.devgangavkar.knowledgecopilot.entity.ChatMessage;
import com.devgangavkar.knowledgecopilot.entity.Conversation;
import com.devgangavkar.knowledgecopilot.entity.User;
import com.devgangavkar.knowledgecopilot.exception.ResourceNotFoundException;
import com.devgangavkar.knowledgecopilot.exception.UnauthorizedException;
import com.devgangavkar.knowledgecopilot.repository.ChatMessageRepository;
import com.devgangavkar.knowledgecopilot.repository.ConversationRepository;
import com.devgangavkar.knowledgecopilot.repository.UserRepository;
import com.devgangavkar.knowledgecopilot.service.ConversationService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ConversationDto> listRecentConversations() {
        User currentUser = resolveCurrentUser();

        return conversationRepository.findTop10ByUserIdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(this::toConversationDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationMessageDto> getConversationMessages(Long conversationId) {
        User currentUser = resolveCurrentUser();
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));

        if (!conversation.getUserId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You do not have access to this conversation");
        }

        return chatMessageRepository.findByConversation_IdOrderByTimestampAsc(conversationId)
                .stream()
                .map(this::toMessageDto)
                .toList();
    }

    private ConversationDto toConversationDto(Conversation conversation) {
        String title = chatMessageRepository.findFirstByConversation_IdOrderByTimestampAsc(conversation.getId())
                .map(ChatMessage::getQuery)
                .filter(query -> query != null && !query.isBlank())
                .map(String::trim)
                .orElse("Conversation " + conversation.getId());

        return new ConversationDto(
                conversation.getId(),
                title,
                toLocalDateTime(conversation.getCreatedAt())
        );
    }

    private ConversationMessageDto toMessageDto(ChatMessage chatMessage) {
        return new ConversationMessageDto(
                chatMessage.getQuery(),
                chatMessage.getResponse(),
                toLocalDateTime(chatMessage.getTimestamp())
        );
    }

    private User resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) {
            throw new UnauthorizedException("Authentication is required to access this resource");
        }

        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
