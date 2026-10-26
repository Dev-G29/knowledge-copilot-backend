package com.devgangavkar.knowledgecopilot.dto;

import java.util.List;

// Payloads of the server-sent events emitted by POST /chat/stream:
//   event: meta  -> Meta (sent first)
//   event: token -> Token (zero or more, in order)
//   event: done  -> Done, or event: error -> Error
public final class ChatStreamEvents {

    private ChatStreamEvents() {
    }

    public record Meta(Long conversationId, List<ChatSourceResponse> sources, double confidence) {
    }

    public record Token(String text) {
    }

    public record Done(Long conversationId, Long messageId) {
    }

    public record Error(String message) {
    }
}
