package com.discordadmin.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * HTTP 请求访问日志 —— 每个请求一行
 * 格式: [traceId u=userId m=merchantId] GET /api/xxx → 200  128ms  1.2KB  ip
 */
@Component
public class AccessLogFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        long start = System.currentTimeMillis();
        long startBytes = 0;
        try { startBytes = request.getContentLengthLong(); } catch (Exception ignored) {}

        try {
            chain.doFilter(req, res);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String method = request.getMethod();
            String uri = request.getRequestURI();
            int status = response.getStatus();
            long bytes = response.getBufferSize();
            String ip = getClientIp(request);

            // 高频健康检查和静态资源降到 DEBUG，避免日志爆炸
            boolean skip = uri.contains("/ws/") || uri.endsWith("/actuator/health") || uri.endsWith("/favicon.ico");
            if (skip) return;

            if (duration > 2000 || status >= 400) {
                // 慢请求 (>2s) 或错误 → WARN
                log.warn("{} {} → {}  {}ms  {}B  {}", method, uri, status, duration, bytes, ip);
            } else if (duration > 500) {
                // 中慢 (500ms~2s) → INFO
                log.info("{} {} → {}  {}ms  {}B  {}", method, uri, status, duration, bytes, ip);
            } else {
                // 快请求 → DEBUG（prod 不会被打印）
                log.debug("{} {} → {}  {}ms  {}B  {}", method, uri, status, duration, bytes, ip);
            }
        }
    }

    private static String getClientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                return v.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
