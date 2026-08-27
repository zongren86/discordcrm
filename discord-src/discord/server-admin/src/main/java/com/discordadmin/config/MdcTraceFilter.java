package com.discordadmin.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路 traceId 过滤器 —— 最前面执行
 * 为每个 HTTP 请求生成唯一 traceId 写入 MDC，logback pattern 通过 %X{traceId} 自动携带
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcTraceFilter implements Filter {

    /** 从 header 读取 traceId 的 key（方便网关转发链路） */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    public static final String TRACE_ID_KEY = "traceId";
    public static final String USER_ID_KEY = "userId";
    public static final String MERCHANT_ID_KEY = "merchantId";
    public static final String USERNAME_KEY = "username";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            // 取 UUID 前 16 位，足够区分且不占空间
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(TRACE_ID_KEY, traceId);
        // 预设空值，JwtAuthFilter 解析完 JWT 后会覆盖
        MDC.put(USER_ID_KEY, "-");
        MDC.put(MERCHANT_ID_KEY, "-");
        MDC.put(USERNAME_KEY, "-");

        try {
            response.setHeader(TRACE_ID_HEADER, traceId);
            chain.doFilter(req, res);
        } finally {
            MDC.remove(TRACE_ID_KEY);
            MDC.remove(USER_ID_KEY);
            MDC.remove(MERCHANT_ID_KEY);
            MDC.remove(USERNAME_KEY);
        }
    }

    /** JwtAuthFilter 解析 JWT 后调用，将用户信息写入 MDC */
    public static void setUserContext(Long userId, Long merchantId, String username) {
        if (userId != null) MDC.put(USER_ID_KEY, String.valueOf(userId));
        if (merchantId != null) MDC.put(MERCHANT_ID_KEY, String.valueOf(merchantId));
        if (username != null) MDC.put(USERNAME_KEY, username);
    }
}
