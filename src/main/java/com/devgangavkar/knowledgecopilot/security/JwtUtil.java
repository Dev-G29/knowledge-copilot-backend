package com.devgangavkar.knowledgecopilot.security;

import com.devgangavkar.knowledgecopilot.config.ApplicationProperties;
import com.devgangavkar.knowledgecopilot.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

    // This key is derived from the configured secret and is used to sign and verify JWTs.
    private final SecretKey secretKey;

    // Token lifetime is kept configurable so it can differ between environments.
    private final long accessTokenExpirationMinutes;

    public JwtUtil(ApplicationProperties applicationProperties) {
        this.accessTokenExpirationMinutes = applicationProperties.security().jwt().accessTokenExpirationMinutes();
        this.secretKey = buildKey(applicationProperties.security().jwt().secret());
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenExpirationMinutes, ChronoUnit.MINUTES);

        // The token subject is the username, and extra claims hold information the client
        // or future services may find useful.
        return Jwts.builder()
                .subject(user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claims(Map.of(
                        "roles", user.getRoles().stream().map(Enum::name).collect(Collectors.toSet()),
                        "email", user.getEmail()
                ))
                .signWith(secretKey)
                .compact();
    }

    public String extractUsername(String token) {
        // We read the standard JWT subject claim to know which user the token belongs to.
        return extractAllClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        // A valid token must belong to the same user and must not be expired.
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public long getAccessTokenExpirationMinutes() {
        return accessTokenExpirationMinutes;
    }

    private boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    private Claims extractAllClaims(String token) {
        // Signature verification happens here before claims are returned.
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey buildKey(String secret) {
        byte[] keyBytes;
        try {
            // Support a Base64-encoded secret if one is configured.
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (DecodingException ex) {
            // Fall back to plain text secrets for local development convenience.
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
