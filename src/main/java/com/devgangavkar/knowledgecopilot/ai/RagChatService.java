package com.devgangavkar.knowledgecopilot.ai;

import com.devgangavkar.knowledgecopilot.config.ApplicationProperties;
import com.devgangavkar.knowledgecopilot.dto.ChatQueryResponse;
import com.devgangavkar.knowledgecopilot.dto.ChatSourceResponse;
import com.devgangavkar.knowledgecopilot.dto.ChatStreamEvents;
import com.devgangavkar.knowledgecopilot.entity.ChatMessage;
import com.devgangavkar.knowledgecopilot.entity.Conversation;
import com.devgangavkar.knowledgecopilot.entity.User;
import com.devgangavkar.knowledgecopilot.exception.AiServiceException;
import com.devgangavkar.knowledgecopilot.exception.ResourceNotFoundException;
import com.devgangavkar.knowledgecopilot.exception.UnauthorizedException;
import com.devgangavkar.knowledgecopilot.repository.ChatMessageRepository;
import com.devgangavkar.knowledgecopilot.repository.ConversationRepository;
import com.devgangavkar.knowledgecopilot.security.CurrentUserService;
import com.devgangavkar.knowledgecopilot.service.ChatService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService implements ChatService {

    private static final int RETRIEVAL_CANDIDATE_LIMIT = 20;
    private static final int FINAL_CHUNK_LIMIT = 6;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 2;
    private static final int SNIPPET_LENGTH = 180;
    private static final int TITLE_LENGTH = 80;
    private static final String NO_RELEVANT_CONTEXT_MESSAGE = "No relevant information found in uploaded documents.";
    private static final String STREAM_FAILURE_MESSAGE = "The assistant could not finish this answer. Please try again.";

    private final VectorSearchService vectorSearchService;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CurrentUserService currentUserService;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationProperties applicationProperties;

    // Everything needed to generate and then store one answer.
    private record PreparedQuery(
            Long conversationId,
            boolean newConversation,
            String query,
            String prompt,
            List<ChatSourceResponse> sources,
            double confidence,
            int chunkCount
    ) {
        boolean hasContext() {
            return prompt != null;
        }
    }

    private record ConversationContext(Long conversationId, boolean newConversation, List<ChatMessage> recentMessages) {
    }

    @Override
    public ChatQueryResponse query(String query, Long conversationId) {
        String normalizedQuery = normalizeQuery(query);
        long startedAt = System.nanoTime();
        PreparedQuery prepared = null;

        try {
            prepared = prepare(normalizedQuery, conversationId);
            String answer = prepared.hasContext() ? generateAnswer(prepared.prompt()) : NO_RELEVANT_CONTEXT_MESSAGE;
            persist(prepared, answer);
            logCompletion(prepared, startedAt);

            return new ChatQueryResponse(answer, prepared.sources(), prepared.conversationId(), prepared.confidence());
        } catch (RuntimeException exception) {
            discardIfEmpty(prepared);
            log.error(
                    "Failed to process RAG chat query. conversationId={}, query=\"{}\", responseTimeMs={}",
                    conversationId,
                    abbreviateForLogs(normalizedQuery),
                    elapsedMillis(startedAt),
                    exception
            );
            throw exception;
        }
    }

    @Override
    public Flux<ServerSentEvent<Object>> stream(String query, Long conversationId) {
        String normalizedQuery = normalizeQuery(query);
        long startedAt = System.nanoTime();

        // Runs on the request thread, so the security context is available and any failure
        // here (bad conversation id, embedding error) is returned as a normal JSON error response.
        PreparedQuery prepared;
        try {
            prepared = prepare(normalizedQuery, conversationId);
        } catch (RuntimeException exception) {
            log.error("Failed to prepare streaming chat query. conversationId={}, query=\"{}\"",
                    conversationId, abbreviateForLogs(normalizedQuery), exception);
            throw exception;
        }

        StringBuffer answer = new StringBuffer();
        AtomicBoolean finished = new AtomicBoolean(false);

        Flux<String> tokens = prepared.hasContext()
                ? streamAnswer(prepared.prompt())
                : Flux.just(NO_RELEVANT_CONTEXT_MESSAGE);

        Mono<ServerSentEvent<Object>> meta = Mono.just(event("meta",
                new ChatStreamEvents.Meta(prepared.conversationId(), prepared.sources(), prepared.confidence())));

        Flux<ServerSentEvent<Object>> tokenEvents = tokens
                .doOnNext(answer::append)
                .map(text -> event("token", new ChatStreamEvents.Token(text)));

        Mono<ServerSentEvent<Object>> done = Mono.fromCallable(() -> {
                    String fullAnswer = answer.toString().trim();
                    if (fullAnswer.isEmpty()) {
                        throw new AiServiceException("Chat model returned a blank answer");
                    }
                    if (!finished.compareAndSet(false, true)) {
                        return event("done", new ChatStreamEvents.Done(prepared.conversationId(), null));
                    }
                    Long messageId = persist(prepared, fullAnswer);
                    logCompletion(prepared, startedAt);
                    return event("done", new ChatStreamEvents.Done(prepared.conversationId(), messageId));
                })
                .subscribeOn(Schedulers.boundedElastic());

        return Flux.concat(meta, tokenEvents, done)
                .onErrorResume(exception -> {
                    log.error("Streaming chat query failed. conversationId={}, query=\"{}\", responseTimeMs={}",
                            prepared.conversationId(), abbreviateForLogs(normalizedQuery), elapsedMillis(startedAt), exception);
                    if (finished.compareAndSet(false, true)) {
                        discardIfEmpty(prepared);
                    }
                    return Mono.just(event("error", new ChatStreamEvents.Error(STREAM_FAILURE_MESSAGE)));
                })
                .doOnCancel(() -> {
                    // The client stopped the answer or went away: keep whatever was generated so far.
                    if (!finished.compareAndSet(false, true)) {
                        return;
                    }
                    String partialAnswer = answer.toString().trim();
                    if (partialAnswer.isEmpty()) {
                        discardIfEmpty(prepared);
                    } else {
                        persist(prepared, partialAnswer);
                    }
                });
    }

    private PreparedQuery prepare(String normalizedQuery, Long conversationId) {
        ConversationContext context = transactionTemplate.execute(status -> {
            User currentUser = currentUserService.requireCurrentUser();
            boolean newConversation = conversationId == null;
            Conversation conversation = newConversation
                    ? createConversation(currentUser, normalizedQuery)
                    : requireOwnedConversation(currentUser, conversationId);
            return new ConversationContext(conversation.getId(), newConversation, getRecentMessages(conversation.getId()));
        });

        try {
            List<SimilaritySearchResult> retrievedMatches = vectorSearchService.searchSimilarChunks(
                    normalizedQuery,
                    RETRIEVAL_CANDIDATE_LIMIT
            );
            List<SimilaritySearchResult> filteredMatches = filterRelevantMatches(retrievedMatches);
            List<SimilaritySearchResult> matches = selectMatches(filteredMatches);

            logRetrievalSelection(normalizedQuery, retrievedMatches, filteredMatches, matches);

            if (matches.isEmpty()) {
                return new PreparedQuery(context.conversationId(), context.newConversation(), normalizedQuery,
                        null, List.of(), 0.0d, 0);
            }

            return new PreparedQuery(
                    context.conversationId(),
                    context.newConversation(),
                    normalizedQuery,
                    buildPrompt(matches, context.recentMessages(), normalizedQuery),
                    extractSources(matches, normalizedQuery),
                    calculateConfidence(matches),
                    matches.size()
            );
        } catch (RuntimeException exception) {
            discardIfEmpty(new PreparedQuery(context.conversationId(), context.newConversation(), normalizedQuery,
                    null, List.of(), 0.0d, 0));
            throw exception;
        }
    }

    private Long persist(PreparedQuery prepared, String answer) {
        return transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            Conversation conversation = conversationRepository.findById(prepared.conversationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + prepared.conversationId()));
            conversation.setUpdatedAt(now);

            ChatMessage message = chatMessageRepository.save(ChatMessage.builder()
                    .conversation(conversation)
                    .query(prepared.query())
                    .response(answer)
                    .timestamp(now)
                    .sources(prepared.sources())
                    .confidence(prepared.hasContext() ? prepared.confidence() : null)
                    .build());
            return message.getId();
        });
    }

    // A conversation created for a question that never got an answer should not linger.
    private void discardIfEmpty(PreparedQuery prepared) {
        if (prepared == null || !prepared.newConversation()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                chatMessageRepository.deleteByConversationId(prepared.conversationId());
                conversationRepository.deleteConversationById(prepared.conversationId());
            });
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not remove empty conversation {}", prepared.conversationId(), cleanupFailure);
        }
    }

    private Conversation createConversation(User currentUser, String query) {
        Instant now = Instant.now();
        return conversationRepository.save(Conversation.builder()
                .userId(currentUser.getId())
                .title(abbreviate(query, TITLE_LENGTH))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Conversation requireOwnedConversation(User currentUser, Long conversationId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found: " + conversationId));

        if (!conversation.getUserId().equals(currentUser.getId())) {
            throw new UnauthorizedException("You do not have access to this conversation");
        }

        return conversation;
    }

    private List<ChatMessage> getRecentMessages(Long conversationId) {
        List<ChatMessage> messages = new ArrayList<>(
                chatMessageRepository.findTop3ByConversation_IdOrderByTimestampDesc(conversationId)
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
            documentContext.append("Document: ")
                    .append(resolveDocumentName(match))
                    .append(System.lineSeparator())
                    .append("Content: ")
                    .append(match.content().trim())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        return """
                Answer the question based only on the following context.
                Use the recent conversation only to preserve continuity, not to invent facts.
                If the answer cannot be found in the context, say that you do not know.
                Format the answer in Markdown when it helps readability (lists, short headings, tables).

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

    private List<SimilaritySearchResult> filterRelevantMatches(List<SimilaritySearchResult> retrievedMatches) {
        return retrievedMatches.stream()
                .filter(this::hasUsableChunk)
                .filter(result -> result.score() >= applicationProperties.rag().minRelevanceScore())
                .sorted(Comparator.comparingDouble(SimilaritySearchResult::score).reversed())
                .toList();
    }

    private List<SimilaritySearchResult> selectMatches(List<SimilaritySearchResult> filteredMatches) {
        if (filteredMatches.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, List<SimilaritySearchResult>> matchesByDocument = new LinkedHashMap<>();
        for (SimilaritySearchResult match : filteredMatches) {
            matchesByDocument.computeIfAbsent(resolveDocumentName(match), ignored -> new ArrayList<>()).add(match);
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
        return result != null
                && result.content() != null
                && !result.content().isBlank();
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

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query is required");
        }
        return query.trim();
    }

    // Query text is useful in logs, but trimming it keeps production logs readable.
    private String abbreviateForLogs(String query) {
        return abbreviate(query, 200);
    }

    private static String abbreviate(String text, int maxLength) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maxLength) {
            return singleLine;
        }
        return singleLine.substring(0, maxLength - 3) + "...";
    }

    private void logCompletion(PreparedQuery prepared, long startedAt) {
        log.info(
                "Processed RAG chat query. conversationId={}, query=\"{}\", chunks={}, confidence={}, responseTimeMs={}",
                prepared.conversationId(),
                abbreviateForLogs(prepared.query()),
                prepared.chunkCount(),
                prepared.confidence(),
                elapsedMillis(startedAt)
        );
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
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

    private Flux<String> streamAnswer(String promptText) {
        return Flux.defer(() -> requireChatModel().stream(new Prompt(promptText)))
                .map(this::extractText)
                .filter(text -> !text.isEmpty())
                .onErrorMap(exception -> !(exception instanceof AiServiceException),
                        exception -> new AiServiceException("Failed to generate response from AI model", exception));
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private List<ChatSourceResponse> extractSources(List<SimilaritySearchResult> matches, String query) {
        LinkedHashMap<String, ChatSourceResponse> sourcesByDocument = new LinkedHashMap<>();
        for (SimilaritySearchResult match : matches) {
            String documentName = match.documentName();
            if (documentName != null && !documentName.isBlank()) {
                sourcesByDocument.putIfAbsent(documentName, new ChatSourceResponse(
                        documentName,
                        buildSnippet(match.content(), query)
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
            distribution.merge(resolveDocumentName(match), 1, Integer::sum);
        }
        return distribution;
    }

    private String resolveDocumentName(SimilaritySearchResult match) {
        if (match.documentName() == null || match.documentName().isBlank()) {
            return "Unknown document";
        }
        return match.documentName();
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
