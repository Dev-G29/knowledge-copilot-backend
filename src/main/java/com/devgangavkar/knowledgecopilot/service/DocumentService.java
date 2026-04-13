package com.devgangavkar.knowledgecopilot.service;

import com.devgangavkar.knowledgecopilot.dto.DocumentResponse;
import com.devgangavkar.knowledgecopilot.dto.DocumentUploadResponse;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    // Accepts a raw uploaded file, extracts readable text, chunks it, and stores both
    // the parent document and its chunks in the database.
    DocumentUploadResponse upload(MultipartFile file);

    // Returns the stored document metadata so the UI or API client can list uploads.
    List<DocumentResponse> getDocuments();
}
