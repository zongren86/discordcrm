package com.discordadmin.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从SecurityContext中提取当前登录用户信息
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static JwtAuthFilter.AuthenticatedAgent currentAgent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtAuthFilter.AuthenticatedAgent agent) {
            return agent;
        }
        return null;
    }

    public static Long currentMerchantId() {
        JwtAuthFilter.AuthenticatedAgent agent = currentAgent();
        return agent != null ? agent.merchantId() : null;
    }

    public static Long currentAgentId() {
        JwtAuthFilter.AuthenticatedAgent agent = currentAgent();
        return agent != null ? agent.agentId() : null;
    }

    public static String currentUserId() {
        JwtAuthFilter.AuthenticatedAgent agent = currentAgent();
        return agent != null ? agent.username() : null;
    }

    public static String currentRole() {
        JwtAuthFilter.AuthenticatedAgent agent = currentAgent();
        return agent != null ? agent.role() : null;
    }

    public static boolean isPlatformAdmin() {
        return "PLATFORM_ADMIN".equals(currentRole());
    }

    /**
     * 校验资源所属商户与当前登录用户是否匹配。
     * 平台管理员可访问所有资源；商户管理员可访问本商户资源及无商户归属的资源；普通用户只能访问本商户的资源。
     * @param resourceMerchantId 资源所属商户ID（可为 null，表示旧数据，商户管理员可访问）
     */
    public static void checkMerchantAccess(Long resourceMerchantId) {
        if (isPlatformAdmin()) return;
        Long merchantId = currentMerchantId();
        // 商户管理员：可访问本商户资源及无商户归属的资源（向后兼容）
        if ("MERCHANT_ADMIN".equals(currentRole())) {
            if (resourceMerchantId == null || merchantId == null || merchantId.equals(resourceMerchantId)) {
                return;
            }
        }
        // 普通用户：严格校验商户归属
        if (merchantId == null || !merchantId.equals(resourceMerchantId)) {
            throw new AccessDeniedException("无权访问该资源");
        }
    }
}
