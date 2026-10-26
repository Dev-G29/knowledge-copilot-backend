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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationProperties applicationProperties;

    @Override
    public void run(String... args) {
        ApplicationProperties.Admin admin = applicationProperties.admin();
        if (admin == null || admin.password() == null || admin.password().isBlank()) {
            log.info("ADMIN_PASSWORD is not set, skipping admin seeding.");
            return;
        }

        String username = admin.username().trim();
        String email = admin.email().trim().toLowerCase();

        // Lets an operator rotate the admin password (e.g. after migrating an old database)
        // by setting ADMIN_SYNC_PASSWORD=true for one deploy.
        if (admin.syncPassword()) {
            userRepository.findByUsername(username).ifPresent(existing -> {
                existing.setPassword(passwordEncoder.encode(admin.password()));
                existing.getRoles().add(Role.ADMIN);
                userRepository.save(existing);
                log.info("Admin password synchronised for username='{}'.", username);
            });
        }

        if (userRepository.existsByRolesContaining(Role.ADMIN)) {
            log.info("Admin user already exists, skipping admin seeding.");
            return;
        }

        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            log.warn("Admin role is missing but an admin username or email is already present. Skipping auto-creation to avoid duplicate credentials.");
            return;
        }

        userRepository.save(User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(admin.password()))
                .roles(new HashSet<>(Set.of(Role.ADMIN)))
                .build());
        log.info("Admin user created with username='{}' and email='{}'.", username, email);
    }
}
