package com.devgangavkar.knowledgecopilot.service;

import com.devgangavkar.knowledgecopilot.dto.AuthRequest;
import com.devgangavkar.knowledgecopilot.dto.AuthResponse;
import com.devgangavkar.knowledgecopilot.dto.RegisterRequest;

public interface AuthService {

    // Creates a new application user, assigns default roles, and returns a JWT response
    // so the client can start authenticated immediately after registration.
    AuthResponse register(RegisterRequest request);

    // Verifies username/password credentials and returns a fresh JWT on success.
    AuthResponse login(AuthRequest request);
}
