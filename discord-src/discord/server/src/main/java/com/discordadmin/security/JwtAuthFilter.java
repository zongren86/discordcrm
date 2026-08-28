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
import com.discordadmin.config.MdcTraceFilter;
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
                Integer accountType = claims.get("accountType", Integer.class);
                Long agentId = claims.get("agentId", Long.class);
                Long merchantId = claims.get("merchantId", Long.class);
                Long userId = claims.get("userId", Long.class);

                var authToken = new UsernamePasswordAuthenticationToken(
                        new AuthenticatedAgent(agentId, userId, username, accountType, merchantId),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + (accountType == 0 ? "ADMIN" : "USER")))
                );
                SecurityContextHolder.getContext().setAuthentication(authToken);
                MdcTraceFilter.setUserContext(userId, merchantId);
                log.debug("JWT 验证成功: 用户={}, URI={}", username, uri);
            } catch (JwtException | IllegalArgumentException e) {
                log.warn("JWT 验证失败: URI={}, 原因={}", uri, e.getMessage());
                SecurityContextHolder.clearContext();
                // Token 无效/过期：向前端返回 401 JSON，前端拦截器会提示"登录已过期，请重新登录"并跳转登录页。
                // 放行登录/注册等白名单接口（如 /api/auth/login）。
                if (!isWhitelisted(uri)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                        "{\"success\":false,\"code\":401,\"message\":\"登录token已过期，请重新登录\"}"
                    );
                    response.getWriter().flush();
                    return;
                }
            }
        } else {
            log.debug("无 Authorization 头: URI={}", uri);
        }
        filterChain.doFilter(request, response);
    }

    /** 判断 URI 是否在认证白名单内（放行登录等接口） */
    private boolean isWhitelisted(String uri) {
        if (uri == null) return false;
        return uri.startsWith("/api/auth/")
                || uri.startsWith("/ws/")
                || uri.endsWith("/error");
    }

    public record AuthenticatedAgent(Long agentId, Long userId, String username, Integer accountType, Long merchantId) {
    }
}
