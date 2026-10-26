package com.devgangavkar.knowledgecopilot.service;

import com.devgangavkar.knowledgecopilot.dto.ChatQueryResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

public interface ChatService {

    ChatQueryResponse query(String query, Long conversationId);

    // Same pipeline as query(), but the answer is emitted token by token as server-sent events.
    Flux<ServerSentEvent<Object>> stream(String query, Long conversationId);
}
