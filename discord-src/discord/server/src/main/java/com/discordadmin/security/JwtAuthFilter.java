package com.discordadmin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String uri = request.getRequestURI();

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parseClaims(token);
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                Long agentId = claims.get("agentId", Long.class);
                Long merchantId = claims.get("merchantId", Long.class);

                var authToken = new UsernamePasswordAuthenticationToken(
                        new AuthenticatedAgent(agentId, username, role, merchantId),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContextHolder.getContext().setAuthentication(authToken);
                log.debug("JWT 验证成功: 用户={}, URI={}", username, uri);
            } catch (JwtException | IllegalArgumentException e) {
                log.warn("JWT 验证失败: URI={}, 原因={}", uri, e.getMessage());
                SecurityContextHolder.clearContext();
            }
        } else {
            log.debug("无 Authorization 头: URI={}", uri);
        }
        filterChain.doFilter(request, response);
    }

    public record AuthenticatedAgent(Long agentId, String username, String role, Long merchantId) {
    }
}
