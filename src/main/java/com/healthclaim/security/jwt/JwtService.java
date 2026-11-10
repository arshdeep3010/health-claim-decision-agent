package com.healthclaim.security.jwt;

import com.healthclaim.security.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies HS256 JWTs. The token carries the username (subject) and
 * role claim; verification is self-contained (no DB hit), keeping auth stateless.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationSeconds;
    private final String issuer;

    public JwtService(SecurityProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = properties.jwt().expirationMinutes() * 60;
        this.issuer = properties.jwt().issuer();
    }

    public String generateToken(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser().requireIssuer(issuer).verifyWith(key).build().parseSignedClaims(token);
    }

    public Instant expiresAt() {
        return Instant.now().plusSeconds(expirationSeconds);
    }
}
