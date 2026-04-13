package com.devgangavkar.knowledgecopilot.ai;

import com.devgangavkar.knowledgecopilot.dto.ChatSourceResponse;
import com.devgangavkar.knowledgecopilot.dto.ChatQueryResponse;
import com.devgangavkar.knowledgecopilot.entity.ChatMessage;
import com.devgangavkar.knowledgecopilot.entity.Conversation;
import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;
import com.devgangavkar.knowledgecopilot.entity.User;
import com.devgangavkar.knowledgecopilot.exception.AiServiceException;
import com.devgangavkar.knowledgecopilot.exception.ResourceNotFoundException;
import com.devgangavkar.knowledgecopilot.exception.UnauthorizedException;
import com.devgangavkar.knowledgecopilot.repository.ChatMessageRepository;
import com.devgangavkar.knowledgecopilot.repository.ConversationRepository;
import com.devgangavkar.knowledgecopilot.repository.UserRepository;
import com.devgangavkar.knowledgecopilot.service.ChatService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService implements ChatService {

    private static final int RETRIEVAL_CANDIDATE_LIMIT = 20;
    private static final int FINAL_CHUNK_LIMIT = 6;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 2;
    private static final int SNIPPET_LENGTH = 180;
    private static final double MIN_RELEVANCE_SCORE = 0.25d;
    private static final String NO_RELEVANT_CONTEXT_MESSAGE = "No relevant information found in uploaded documents.";

    private final VectorSearchService vectorSearchService;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ChatQueryResponse query(String query, Long conversationId) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query is required");
        }

        String normalizedQuery = query.trim();
        long startedAt = System.nanoTime();

        try {
            Conversation conversation = resolveConversation(conversationId);
            List<SimilaritySearchResult> retrievedMatches = vectorSearchService.searchSimilarChunks(
                    normalizedQuery,
                    RETRIEVAL_CANDIDATE_LIMIT
            );
            List<SimilaritySearchResult> filteredMatches = filterRelevantMatches(retrievedMatches);
            List<SimilaritySearchResult> matches = selectMatches(filteredMatches);

            logRetrievalSelection(normalizedQuery, retrievedMatches, filteredMatches, matches);

            String answer;
            List<ChatSourceResponse> sources;
            double confidence;

            if (matches.isEmpty()) {
                answer = NO_RELEVANT_CONTEXT_MESSAGE;
                sources = List.of();
                confidence = 0.0d;
            } else {
                List<ChatMessage> recentMessages = getRecentMessages(conversation);
                String prompt = buildPrompt(matches, recentMessages, normalizedQuery);
                answer = generateAnswer(prompt);
                sources = extractSources(matches, normalizedQuery);
                confidence = calculateConfidence(matches);
            }

            saveChatMessage(conversation, normalizedQuery, answer);

            long responseTimeMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info(
                    "Processed RAG chat query. conversationId={}, query=\"{}\", chunks={}, confidence={}, responseTimeMs={}",
                    conversation.getId(),
                    abbreviateForLogs(normalizedQuery),
                    matches.size(),
                    confidence,
                    responseTimeMs
            );

            return new ChatQueryResponse(answer, sources, conversation.getId(), confidence);
        } catch (Exception exception) {
            long responseTimeMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.error(
                    "Failed to process RAG chat query. conversationId={}, query=\"{}\", responseTimeMs={}",
                    conversationId,
                    abbreviateForLogs(normalizedQuery),
                    responseTimeMs,
                    exception
            );
            throw exception;
        }
    }

    private Conversation resolveConversation(Long conversationId) {
        User currentUser = resolveCurrentUser();

        if (conversationId == null) {
            return conversationRepository.save(Conversation.builder()
                    .userId(currentUser.getId())
                    .createdAt(Instant.now())
                    .build());
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));

        if (!conversation.getUserId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You do not have access to this conversation");
        }

        return conversation;
    }

    private List<ChatMessage> getRecentMessages(Conversation conversation) {
        List<ChatMessage> messages = new ArrayList<>(
                chatMessageRepository.findTop3ByConversation_IdOrderByTimestampDesc(conversation.getId())
        );
        Collections.reverse(messages);
        return messages;
    }

    private String buildPrompt(List<SimilaritySearchResult> matches, List<ChatMessage> recentMessages, String query) {
        StringBuilder conversationContext = new StringBuilder();
        for (ChatMessage message : recentMessages) {
            conversationContext.append("User: ")
                    .append(message.getQuery().trim())
                    .append(System.lineSeparator())
                    .append("Assistant: ")
                    .append(message.getResponse().trim())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        StringBuilder documentContext = new StringBuilder();
        for (SimilaritySearchResult match : matches) {
            DocumentChunk chunk = match.chunk();
            documentContext.append("Document: ")
                    .append(chunk.getDocument().getName())
                    .append(System.lineSeparator())
                    .append("Content: ")
                    .append(chunk.getContent().trim())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        return """
                Answer the question based only on the following context.
                Use the recent conversation only to preserve continuity, not to invent facts.
                If the answer cannot be found in the context, say that you do not know.

                <conversation>
                %s
                </conversation>

                <context>
                %s
                </context>

                Question: %s
                """.formatted(
                conversationContext.isEmpty() ? "No previous conversation." : conversationContext.toString().trim(),
                documentContext.toString().trim(),
                query.trim()
        );
    }

    private void saveChatMessage(Conversation conversation, String query, String answer) {
        chatMessageRepository.save(ChatMessage.builder()
                .conversation(conversation)
                .query(query.trim())
                .response(answer)
                .timestamp(Instant.now())
                .build());
    }

    private List<SimilaritySearchResult> filterRelevantMatches(List<SimilaritySearchResult> retrievedMatches) {
        return retrievedMatches.stream()
                .filter(this::hasUsableChunk)
                .filter(result -> result.score() >= MIN_RELEVANCE_SCORE)
                .sorted(Comparator.comparingDouble(SimilaritySearchResult::score).reversed())
                .toList();
    }

    private List<SimilaritySearchResult> selectMatches(List<SimilaritySearchResult> filteredMatches) {
        if (filteredMatches.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, List<SimilaritySearchResult>> matchesByDocument = new LinkedHashMap<>();
        for (SimilaritySearchResult match : filteredMatches) {
            matchesByDocument.computeIfAbsent(resolveDocumentName(match.chunk()), ignored -> new ArrayList<>()).add(match);
        }

        List<SimilaritySearchResult> selectedMatches = new ArrayList<>(Math.min(FINAL_CHUNK_LIMIT, filteredMatches.size()));

        // First pass takes the best chunk from each document so the prompt is not dominated by one file.
        for (List<SimilaritySearchResult> documentMatches : matchesByDocument.values()) {
            if (selectedMatches.size() >= FINAL_CHUNK_LIMIT) {
                break;
            }
            selectedMatches.add(documentMatches.get(0));
        }

        for (int index = 1; index < MAX_CHUNKS_PER_DOCUMENT && selectedMatches.size() < FINAL_CHUNK_LIMIT; index++) {
            for (List<SimilaritySearchResult> documentMatches : matchesByDocument.values()) {
                if (selectedMatches.size() >= FINAL_CHUNK_LIMIT) {
                    break;
                }
                if (documentMatches.size() > index) {
                    selectedMatches.add(documentMatches.get(index));
                }
            }
        }

        return selectedMatches.stream()
                .sorted(Comparator.comparingDouble(SimilaritySearchResult::score).reversed())
                .toList();
    }

    private boolean hasUsableChunk(SimilaritySearchResult result) {
        return result.chunk() != null
                && result.chunk().getContent() != null
                && !result.chunk().getContent().isBlank();
    }

    // Keep the confidence payload predictable for the client while still reflecting retrieval quality.
    private double calculateConfidence(List<SimilaritySearchResult> matches) {
        double averageScore = matches.stream()
                .mapToDouble(SimilaritySearchResult::score)
                .average()
                .orElse(0.0d);

        return BigDecimal.valueOf(averageScore)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // Query text is useful in logs, but trimming it keeps production logs readable.
    private String abbreviateForLogs(String query) {
        if (query.length() <= 200) {
            return query;
        }
        return query.substring(0, 197) + "...";
    }

    private String generateAnswer(String promptText) {
        try {
            ChatModel chatModel = requireChatModel();
            ChatResponse response = chatModel.call(new Prompt(promptText));

            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new AiServiceException("Chat model returned an empty response");
            }

            String answer = response.getResult().getOutput().getText();
            if (answer == null || answer.isBlank()) {
                throw new AiServiceException("Chat model returned a blank answer");
            }

            return answer.trim();
        } catch (AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiServiceException("Failed to generate response from AI model", exception);
        }
    }

    private List<ChatSourceResponse> extractSources(List<SimilaritySearchResult> matches, String query) {
        LinkedHashMap<String, ChatSourceResponse> sourcesByDocument = new LinkedHashMap<>();
        for (SimilaritySearchResult match : matches) {
            DocumentChunk chunk = match.chunk();
            if (chunk.getDocument() != null && chunk.getDocument().getName() != null && !chunk.getDocument().getName().isBlank()) {
                String documentName = chunk.getDocument().getName();
                sourcesByDocument.putIfAbsent(documentName, new ChatSourceResponse(
                        documentName,
                        buildSnippet(chunk.getContent(), query)
                ));
            }
        }
        return List.copyOf(sourcesByDocument.values());
    }

    private String buildSnippet(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= SNIPPET_LENGTH) {
            return normalized;
        }

        int snippetStart = findSnippetStart(normalized, query);
        int snippetEnd = Math.min(normalized.length(), snippetStart + SNIPPET_LENGTH);

        if (snippetEnd < normalized.length()) {
            int adjustedEnd = normalized.lastIndexOf(' ', snippetEnd);
            if (adjustedEnd > snippetStart + (SNIPPET_LENGTH / 2)) {
                snippetEnd = adjustedEnd;
            }
        }

        String snippet = normalized.substring(snippetStart, snippetEnd).trim();
        if (snippetStart > 0) {
            snippet = "..." + snippet;
        }
        if (snippetEnd < normalized.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private int findSnippetStart(String content, String query) {
        List<String> keywords = extractKeywords(query);
        String lowerCaseContent = content.toLowerCase(Locale.ROOT);

        int keywordIndex = -1;
        int keywordLength = 0;
        for (String keyword : keywords) {
            int matchIndex = lowerCaseContent.indexOf(keyword);
            if (matchIndex >= 0 && keyword.length() > keywordLength) {
                keywordIndex = matchIndex;
                keywordLength = keyword.length();
            }
        }

        int preferredCenter = keywordIndex >= 0
                ? keywordIndex + (keywordLength / 2)
                : content.length() / 2;
        int snippetStart = Math.max(0, preferredCenter - (SNIPPET_LENGTH / 2));

        if (snippetStart > 0) {
            int adjustedStart = content.indexOf(' ', snippetStart);
            if (adjustedStart >= 0 && adjustedStart < content.length() - 1) {
                snippetStart = adjustedStart + 1;
            }
        }

        int maxStart = Math.max(0, content.length() - SNIPPET_LENGTH);
        return Math.min(snippetStart, maxStart);
    }

    private List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (token.length() >= 3) {
                keywords.add(token);
            }
        }
        return List.copyOf(keywords);
    }

    private void logRetrievalSelection(
            String query,
            List<SimilaritySearchResult> retrievedMatches,
            List<SimilaritySearchResult> filteredMatches,
            List<SimilaritySearchResult> selectedMatches
    ) {
        log.info(
                "RAG retrieval summary. query=\"{}\", totalRetrieved={}, afterFiltering={}, finalSelected={}, documentDistribution={}",
                abbreviateForLogs(query),
                retrievedMatches.size(),
                filteredMatches.size(),
                selectedMatches.size(),
                buildDocumentDistribution(selectedMatches)
        );
    }

    private Map<String, Integer> buildDocumentDistribution(List<SimilaritySearchResult> matches) {
        LinkedHashMap<String, Integer> distribution = new LinkedHashMap<>();
        for (SimilaritySearchResult match : matches) {
            distribution.merge(resolveDocumentName(match.chunk()), 1, Integer::sum);
        }
        return distribution;
    }

    private String resolveDocumentName(DocumentChunk chunk) {
        if (chunk.getDocument() == null || chunk.getDocument().getName() == null || chunk.getDocument().getName().isBlank()) {
            return "Unknown document";
        }
        return chunk.getDocument().getName();
    }

    private User resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) {
            throw new UnauthorizedException("Authentication is required to access this resource");
        }

        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
    }

    private ChatModel requireChatModel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new AiServiceException(
                    "No Spring AI ChatModel bean is configured. Configure Gemini via spring.ai.google.genai.api-key to enable chat."
            );
        }
        return chatModel;
    }
}
