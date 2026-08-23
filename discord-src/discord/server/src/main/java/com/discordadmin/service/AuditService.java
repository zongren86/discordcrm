package com.discordadmin.service;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.AuditLog;
import com.discordadmin.repository.AuditLogRepository;
import com.discordadmin.security.JwtAuthFilter.AuthenticatedAgent;
import com.discordadmin.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final HttpServletRequest request;

    public AuditService(AuditLogRepository auditLogRepository, HttpServletRequest request) {
        this.auditLogRepository = auditLogRepository;
        this.request = request;
    }

    public void log(String module, String action, String resourceType, String resourceId, String detail) {
        try {
            AuditLog log = new AuditLog();
            Long merchantId = SecurityUtils.currentMerchantId();
            boolean isPlatform = SecurityUtils.isPlatformAdmin();
            log.setMerchantId(isPlatform ? null : merchantId);

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthenticatedAgent agent) {
                log.setOperator(agent.username());
                log.setOperatorRole(agent.accountType() != null ? agent.accountType().toString() : "1");
            }

            log.setModule(module);
            log.setAction(action);
            log.setResourceType(resourceType);
            log.setResourceId(resourceId);
            log.setDetail(detail);
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
            log.setIp(ip);
            log.setResult("SUCCESS");
            log.setCreatedAt(Instant.now());
            auditLogRepository.save(log);
        } catch (Exception ignored) {
            // 审计失败不影响主流程
        }
    }
}
