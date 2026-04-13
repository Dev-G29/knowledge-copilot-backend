package com.devgangavkar.knowledgecopilot.service;

import com.devgangavkar.knowledgecopilot.dto.ConversationDto;
import com.devgangavkar.knowledgecopilot.dto.ConversationMessageDto;
import java.util.List;

public interface ConversationService {

    List<ConversationDto> listRecentConversations();

    List<ConversationMessageDto> getConversationMessages(Long conversationId);
}
