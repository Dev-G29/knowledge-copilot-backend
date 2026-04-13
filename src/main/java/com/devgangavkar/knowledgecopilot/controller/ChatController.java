package com.devgangavkar.knowledgecopilot.controller;

import com.devgangavkar.knowledgecopilot.dto.ChatQueryRequest;
import com.devgangavkar.knowledgecopilot.dto.ChatQueryResponse;
import com.devgangavkar.knowledgecopilot.dto.ConversationDto;
import com.devgangavkar.knowledgecopilot.dto.ConversationMessageDto;
import com.devgangavkar.knowledgecopilot.service.ChatService;
import com.devgangavkar.knowledgecopilot.service.ConversationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    @PostMapping("/query")
    public ResponseEntity<ChatQueryResponse> query(@Valid @RequestBody ChatQueryRequest request) {
        return ResponseEntity.ok(chatService.query(request.query(), request.conversationId()));
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationDto>> listConversations() {
        return ResponseEntity.ok(conversationService.listRecentConversations());
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<List<ConversationMessageDto>> getConversationMessages(@PathVariable Long id) {
        return ResponseEntity.ok(conversationService.getConversationMessages(id));
    }
}
