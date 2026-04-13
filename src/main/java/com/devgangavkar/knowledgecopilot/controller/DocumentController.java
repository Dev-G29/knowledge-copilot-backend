package com.devgangavkar.knowledgecopilot.controller;

import com.devgangavkar.knowledgecopilot.dto.DocumentResponse;
import com.devgangavkar.knowledgecopilot.dto.DocumentUploadResponse;
import com.devgangavkar.knowledgecopilot.service.DocumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    // The controller is intentionally thin: it only handles HTTP concerns and delegates
    // the actual processing flow to the service layer.
    private final DocumentService documentService;

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.upload(file));
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getDocuments() {
        return ResponseEntity.ok(documentService.getDocuments());
    }
}
