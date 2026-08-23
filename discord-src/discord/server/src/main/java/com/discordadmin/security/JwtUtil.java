package com.discordadmin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-hours}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireHours * 3600 * 1000;
    }

    public String generateToken(Long agentId, String username, Integer accountType, Long merchantId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireMillis);
        var builder = Jwts.builder()
                .subject(username)
                .claim("agentId", agentId)
                .claim("accountType", accountType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key);
        if (merchantId != null) {
            builder.claim("merchantId", merchantId);
        }
        return builder.compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
