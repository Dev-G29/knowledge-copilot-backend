package com.devgangavkar.knowledgecopilot.service.impl;

import com.devgangavkar.knowledgecopilot.dto.AuthRequest;
import com.devgangavkar.knowledgecopilot.dto.AuthResponse;
import com.devgangavkar.knowledgecopilot.dto.RegisterRequest;
import com.devgangavkar.knowledgecopilot.entity.Role;
import com.devgangavkar.knowledgecopilot.entity.User;
import com.devgangavkar.knowledgecopilot.exception.UnauthorizedException;
import com.devgangavkar.knowledgecopilot.repository.UserRepository;
import com.devgangavkar.knowledgecopilot.security.JwtUtil;
import com.devgangavkar.knowledgecopilot.service.AuthService;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    // Repository access lets us check uniqueness and persist new users.
    private final UserRepository userRepository;

    // Passwords are never stored in plain text; BCrypt hashes them before persistence.
    private final PasswordEncoder passwordEncoder;

    // JwtUtil is responsible only for token creation and validation logic.
    private final JwtUtil jwtUtil;

    // Spring Security's AuthenticationManager performs the actual credential verification.
    private final AuthenticationManager authenticationManager;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Registration must fail fast if the username or email is already in use.
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username is already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email is already registered");
        }

        // New users start with the USER role by default.
        User user = User.builder()
                .username(request.username().trim())
                .email(request.email().trim().toLowerCase())
                .password(passwordEncoder.encode(request.password()))
                .roles(new java.util.HashSet<>(Set.of(Role.USER)))
                .build();

        // After saving, we issue a token immediately so registration and login feel seamless.
        User savedUser = userRepository.save(user);
        String token = jwtUtil.generateToken(savedUser);
        return buildResponse(savedUser, token);
    }

    @Override
    public AuthResponse login(AuthRequest request) {
        try {
            String loginIdentifier = request.username().trim();
            String username = resolveUsernameForLogin(loginIdentifier);

            // This delegates username/password verification to Spring Security.
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password())
            );

            // Once authentication succeeds, we load the domain user so we can include roles in the response.
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));

            String token = jwtUtil.generateToken(user);
            return buildResponse(user, token);
        } catch (BadCredentialsException ex) {
            throw new UnauthorizedException("Invalid username or password");
        }
    }

    private String resolveUsernameForLogin(String loginIdentifier) {
        if (loginIdentifier.contains("@")) {
            // Emails are stored normalized, so we do the same before lookup.
            return userRepository.findByEmail(loginIdentifier.toLowerCase())
                    .map(User::getUsername)
                    .orElse(loginIdentifier);
        }
        return loginIdentifier;
    }

    private AuthResponse buildResponse(User user, String token) {
        // The response contains the JWT plus user-facing metadata the client commonly needs.
        return new AuthResponse(
                token,
                "Bearer",
                jwtUtil.getAccessTokenExpirationMinutes(),
                user.getUsername(),
                user.getRoles().stream().map(Enum::name).collect(Collectors.toSet())
        );
    }
}
