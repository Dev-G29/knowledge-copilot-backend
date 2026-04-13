package com.devgangavkar.knowledgecopilot.config;

import com.devgangavkar.knowledgecopilot.entity.Role;
import com.devgangavkar.knowledgecopilot.entity.User;
import com.devgangavkar.knowledgecopilot.repository.UserRepository;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "admin123";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.existsByRolesContaining(Role.ADMIN)) {
            log.info("Admin user already exists, skipping admin seeding.");
            return;
        }

        if (userRepository.existsByUsername(ADMIN_USERNAME) || userRepository.existsByEmail(ADMIN_EMAIL)) {
            log.warn("Admin role is missing but an admin username or email is already present. Skipping auto-creation to avoid duplicate credentials.");
            return;
        }

        User admin = User.builder()
                .username(ADMIN_USERNAME)
                .email(ADMIN_EMAIL)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .roles(new HashSet<>(Set.of(Role.ADMIN)))
                .build();

        userRepository.save(admin);
        log.info("Default admin user created with username='{}' and email='{}'.", ADMIN_USERNAME, ADMIN_EMAIL);
    }
}
