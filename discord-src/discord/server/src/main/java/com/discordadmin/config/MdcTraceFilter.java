package com.discordadmin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路追踪 Filter
 * - 生成 traceId 写入 MDC，贯穿整个请求
 * - 从 Header 接受上游传入的 traceId（如果有）
 * - 请求结束后清理 MDC，防止线程池复用导致脏数据
 *
 * logback pattern 引用: [%X{traceId:-} u=%X{userId:-} m=%X{merchantId:-}]
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcTraceFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String MERCHANT_ID = "merchantId";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 1. 生成或接收 traceId
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        // 2. 从可选 Header 预取 userId / merchantId（JwtAuthFilter 会再精确覆盖）
        String preUserId = request.getHeader("X-User-Id");
        if (preUserId != null && !preUserId.isBlank()) MDC.put(USER_ID, preUserId);
        String preMerchantId = request.getHeader("X-Merchant-Id");
        if (preMerchantId != null && !preMerchantId.isBlank()) MDC.put(MERCHANT_ID, preMerchantId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 3. 清理 MDC，线程池复用时不脏
            MDC.clear();
        }
    }

    /**
     * 供 JwtAuthFilter 调用：解析完 JWT 后写入精确的 userId / merchantId
     */
    public static void setUserContext(Long userId, Long merchantId) {
        if (userId != null) MDC.put(USER_ID, String.valueOf(userId));
        if (merchantId != null) MDC.put(MERCHANT_ID, String.valueOf(merchantId));
    }
}
