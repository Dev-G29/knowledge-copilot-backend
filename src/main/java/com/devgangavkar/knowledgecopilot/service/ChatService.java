package com.devgangavkar.knowledgecopilot.service;

import com.devgangavkar.knowledgecopilot.dto.ChatQueryResponse;

public interface ChatService {

    ChatQueryResponse query(String query, Long conversationId);
}
