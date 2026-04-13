package com.devgangavkar.knowledgecopilot.service.impl;

import com.devgangavkar.knowledgecopilot.ai.EmbeddingService;
import com.devgangavkar.knowledgecopilot.dto.DocumentResponse;
import com.devgangavkar.knowledgecopilot.dto.DocumentUploadResponse;
import com.devgangavkar.knowledgecopilot.entity.Document;
import com.devgangavkar.knowledgecopilot.entity.DocumentChunk;
import com.devgangavkar.knowledgecopilot.repository.DocumentRepository;
import com.devgangavkar.knowledgecopilot.service.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    // We aim for readable chunks near this size.
    private static final int TARGET_CHUNK_SIZE = 800;

    // We try not to create tiny chunks unless the source content is genuinely small.
    private static final int MIN_CHUNK_SIZE = 500;

    // This upper bound protects downstream model context and storage consistency.
    private static final int MAX_CHUNK_SIZE = 1000;

    // A small overlap helps the next chunk retain continuity from the previous one.
    private static final int OVERLAP_SIZE = 120;

    // Blank lines are treated as paragraph boundaries after normalization.
    private static final Pattern PARAGRAPH_SPLIT_PATTERN = Pattern.compile("\\n\\s*\\n");

    // Sentence splitting is intentionally simple here: good enough for common business text
    // without pulling in a full NLP library.
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[.!?])\\s+");

    private final DocumentRepository documentRepository;
    private final EmbeddingService embeddingService;

    @Override
    @Transactional
    public DocumentUploadResponse upload(MultipartFile file) {
        // Step 1: reject invalid uploads early before doing any expensive parsing.
        validateFile(file);

        // Step 2: convert the uploaded file into plain text.
        String content = extractText(file);

        // Step 3: turn the plain text into semantically friendlier chunks.
        List<String> chunks = splitIntoChunks(content);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file does not contain readable text");
        }

        // Step 4: create the parent document record.
        Document document = Document.builder()
                .name(file.getOriginalFilename())
                .uploadedBy(resolveCurrentUsername())
                .createdAt(Instant.now())
                .build();

        List<List<Double>> embeddings = embeddingService.embedAll(chunks);

        // Step 5: attach each generated chunk to the document so JPA can persist the full graph.
        List<DocumentChunk> documentChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            documentChunks.add(DocumentChunk.builder()
                        .document(document)
                        .content(chunks.get(index))
                        .embedding(embeddings.get(index))
                        .build());
        }

        document.setChunks(documentChunks);

        // Step 6: save once and let cascading persist both the document and its chunks.
        Document saved = documentRepository.save(document);
        return new DocumentUploadResponse(
                saved.getId(),
                saved.getName(),
                saved.getUploadedBy(),
                saved.getCreatedAt(),
                saved.getChunks().size()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponse> getDocuments() {
        // This is just a metadata listing endpoint, so we map entities into lightweight DTOs.
        return documentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(document -> new DocumentResponse(
                        document.getId(),
                        document.getName(),
                        document.getUploadedBy(),
                        document.getCreatedAt(),
                        document.getChunks().size()
                ))
                .toList();
    }

    private void validateFile(MultipartFile file) {
        // Multipart requests can arrive with an empty part or no file name at all.
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("File name is required");
        }

        String lowerName = filename.toLowerCase();
        if (!(lowerName.endsWith(".pdf") || lowerName.endsWith(".txt") || lowerName.endsWith(".docx"))) {
            throw new IllegalArgumentException("Only PDF, TXT, and DOCX files are supported");
        }
    }

    private String extractText(MultipartFile file) {
        String fileName = file.getOriginalFilename().toLowerCase();
        try {
            // TXT files are already plain text, so decoding bytes is enough.
            if (fileName.endsWith(".txt")) {
                return normalizeText(new String(file.getBytes(), StandardCharsets.UTF_8));
            }

            // PDFs require text extraction from the rendered document structure.
            if (fileName.endsWith(".pdf")) {
                return extractFromPdf(file.getInputStream());
            }

            // DOCX files are XML-based and are read paragraph by paragraph.
            if (fileName.endsWith(".docx")) {
                return extractFromDocx(file.getInputStream());
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read uploaded file: " + ex.getMessage());
        }

        throw new IllegalArgumentException("Unsupported file type");
    }

    private String extractFromPdf(InputStream inputStream) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(inputStream.readAllBytes())) {
            // PDFTextStripper gives us the readable text content from the PDF pages.
            return normalizeText(new PDFTextStripper().getText(pdf));
        }
    }

    private String extractFromDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder builder = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    // We preserve paragraph gaps because they are useful for smarter chunking later.
                    builder.append(text.trim()).append(System.lineSeparator()).append(System.lineSeparator());
                }
            }
            return normalizeText(builder.toString());
        }
    }

    private List<String> splitIntoChunks(String text) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return List.of();
        }

        // We first break the content into paragraph-like blocks.
        List<String> paragraphs = splitIntoParagraphs(normalized);
        List<String> baseChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            // Small or medium paragraphs are merged together to form a chunk near the target size.
            if (paragraph.length() <= MAX_CHUNK_SIZE) {
                currentChunk = appendSegment(baseChunks, currentChunk, paragraph);
                continue;
            }

            // Large paragraphs are further decomposed into sentence-aware subchunks.
            if (!currentChunk.isEmpty()) {
                baseChunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
            }
            baseChunks.addAll(splitLargeParagraph(paragraph));
        }

        if (!currentChunk.isEmpty()) {
            baseChunks.add(currentChunk.toString().trim());
        }

        // Finally, we add overlap between neighboring chunks to preserve continuity.
        return addOverlap(baseChunks);
    }

    private List<String> splitIntoParagraphs(String text) {
        String[] rawParagraphs = PARAGRAPH_SPLIT_PATTERN.split(text);
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : rawParagraphs) {
            String cleaned = paragraph.trim();
            if (!cleaned.isBlank()) {
                paragraphs.add(cleaned);
            }
        }
        return paragraphs;
    }

    private StringBuilder appendSegment(List<String> chunks, StringBuilder currentChunk, String segment) {
        if (currentChunk.isEmpty()) {
            currentChunk.append(segment);
            return currentChunk;
        }

        String candidate = currentChunk + "\n\n" + segment;

        // If the segment fits, keep building the current chunk.
        if (candidate.length() <= MAX_CHUNK_SIZE) {
            currentChunk.append("\n\n").append(segment);
            return currentChunk;
        }

        // If the current chunk is already reasonably sized, close it and start a new one.
        if (currentChunk.length() >= MIN_CHUNK_SIZE) {
            chunks.add(currentChunk.toString().trim());
            currentChunk.setLength(0);
            currentChunk.append(segment);
            return currentChunk;
        }

        // If the current chunk is still too small, we tolerate a slightly larger chunk by trying
        // to split the incoming segment into smaller sentence-based pieces.
        List<String> splitSegment = splitLargeParagraph(segment);
        for (String piece : splitSegment) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(piece);
            } else if (currentChunk.length() + 2 + piece.length() <= MAX_CHUNK_SIZE) {
                currentChunk.append("\n\n").append(piece);
            } else {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
                currentChunk.append(piece);
            }
        }
        return currentChunk;
    }

    private List<String> splitLargeParagraph(String paragraph) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = SENTENCE_SPLIT_PATTERN.split(paragraph.trim());
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            String cleanedSentence = sentence.trim();
            if (cleanedSentence.isBlank()) {
                continue;
            }

            // A single oversized sentence needs one final fallback split.
            if (cleanedSentence.length() > MAX_CHUNK_SIZE) {
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                chunks.addAll(splitOversizedSentence(cleanedSentence));
                continue;
            }

            if (currentChunk.isEmpty()) {
                currentChunk.append(cleanedSentence);
                continue;
            }

            if (currentChunk.length() + 1 + cleanedSentence.length() <= TARGET_CHUNK_SIZE) {
                currentChunk.append(' ').append(cleanedSentence);
                continue;
            }

            if (currentChunk.length() >= MIN_CHUNK_SIZE) {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
                currentChunk.append(cleanedSentence);
                continue;
            }

            // If we are below the minimum size, allow a bit more growth before splitting.
            if (currentChunk.length() + 1 + cleanedSentence.length() <= MAX_CHUNK_SIZE) {
                currentChunk.append(' ').append(cleanedSentence);
            } else {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
                currentChunk.append(cleanedSentence);
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitOversizedSentence(String sentence) {
        List<String> chunks = new ArrayList<>();
        String[] words = sentence.split("\\s+");
        StringBuilder currentChunk = new StringBuilder();

        for (String word : words) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(word);
                continue;
            }

            if (currentChunk.length() + 1 + word.length() <= MAX_CHUNK_SIZE) {
                currentChunk.append(' ').append(word);
            } else {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
                currentChunk.append(word);
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> addOverlap(List<String> chunks) {
        List<String> overlappedChunks = new ArrayList<>();

        for (int index = 0; index < chunks.size(); index++) {
            String currentChunk = chunks.get(index).trim();
            if (currentChunk.isBlank()) {
                continue;
            }

            if (index == 0) {
                overlappedChunks.add(currentChunk);
                continue;
            }

            String overlap = takeTrailingContext(chunks.get(index - 1), OVERLAP_SIZE);
            String combined = (overlap + " " + currentChunk).trim();

            // If overlap makes the chunk too large, keep the current chunk as-is instead of
            // exceeding the configured maximum.
            overlappedChunks.add(combined.length() <= MAX_CHUNK_SIZE ? combined : currentChunk);
        }

        return overlappedChunks;
    }

    private String takeTrailingContext(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }

        int start = trimmed.length() - maxLength;

        // Move to the next whitespace so the overlap begins on a word boundary when possible.
        while (start < trimmed.length() && !Character.isWhitespace(trimmed.charAt(start))) {
            start++;
        }

        return start >= trimmed.length() ? trimmed.substring(trimmed.length() - maxLength).trim() : trimmed.substring(start).trim();
    }

    private String normalizeText(String text) {
        // Normalization gives the chunker consistent input regardless of original file format.
        return text == null ? "" : text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" +", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String resolveCurrentUsername() {
        // We store who uploaded the file so later audit/history features have the information.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) {
            return "system";
        }
        return authentication.getName();
    }
}
