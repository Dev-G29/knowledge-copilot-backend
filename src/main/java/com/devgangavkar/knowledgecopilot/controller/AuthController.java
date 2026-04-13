package com.devgangavkar.knowledgecopilot.controller;

import com.devgangavkar.knowledgecopilot.dto.AuthRequest;
import com.devgangavkar.knowledgecopilot.dto.AuthResponse;
import com.devgangavkar.knowledgecopilot.dto.RegisterRequest;
import com.devgangavkar.knowledgecopilot.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    // As with the document controller, the controller only handles HTTP input/output
    // and delegates the business rules to the service layer.
    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
