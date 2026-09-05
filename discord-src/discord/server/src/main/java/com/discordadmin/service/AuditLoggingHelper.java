package com.discordadmin.service;

import com.discordadmin.entity.AccountTokenEvent;
import com.discordadmin.entity.AuditLog;
import com.discordadmin.repository.AccountTokenEventRepository;
import com.discordadmin.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 全链路操作日志写入辅助类 —— 异步，不阻塞主流程。
 * 两条通道：
 *   1. audit_logs        — 通用操作审计（账号CRUD / 消息发送 / 绑定变更等）
 *   2. account_token_events — Token 生命周期专项表（每个 token 事件有完整链路）
 * 写入失败只打 warn，绝不让主流程失败。
 */
@Component
public class AuditLoggingHelper {

    private static final Logger log = LoggerFactory.getLogger(AuditLoggingHelper.class);

    private final AuditLogRepository auditRepo;
    private final AccountTokenEventRepository tokenEventRepo;
    private final Executor auditExecutor;
    private final ObjectMapper om;

    public AuditLoggingHelper(AuditLogRepository auditRepo,
                              AccountTokenEventRepository tokenEventRepo,
                              @Qualifier("auditLogExecutor") Executor auditExecutor,
                              ObjectMapper om) {
        this.auditRepo = auditRepo;
        this.tokenEventRepo = tokenEventRepo;
        this.auditExecutor = auditExecutor;
        this.om = om;
    }

    // ===================== audit_logs =====================

    /** 异步写 audit_logs。detail 传 Map 会自动转 JSON */
    public void log(String module, String action, String resourceType, String resourceId,
                    Object detail, String operator, Long merchantId, String result) {
        auditExecutor.execute(() -> {
            try {
                AuditLog a = new AuditLog();
                a.setModule(module);
                a.setAction(action);
                a.setResourceType(resourceType);
                a.setResourceId(resourceId);
                a.setDetail(toJson(detail));
                a.setOperator(operator != null ? operator : "SYSTEM");
                a.setMerchantId(merchantId);
                a.setResult(result != null ? result : "SUCCESS");
                auditRepo.save(a);
            } catch (Exception e) {
                log.warn("[审计日志] 写入失败(忽略): module={} action={} err={}", module, action, e.getMessage());
            }
        });
    }

    public void log(String module, String action, String resourceType, String resourceId,
                    Object detail, String operator, Long merchantId) {
        log(module, action, resourceType, resourceId, detail, operator, merchantId, "SUCCESS");
    }

    // ===================== account_token_events =====================

    /** 异步写 token 生命周期事件 */
    public void tokenEvent(Long accountId, String accountName, String eventType,
                           String source, Long agentServerId, String agentServerName,
                           String resultCode, Object detail, String operator, Long merchantId) {
        auditExecutor.execute(() -> {
            try {
                AccountTokenEvent e = new AccountTokenEvent();
                e.setAccountId(accountId);
                e.setAccountName(accountName);
                e.setEventType(eventType);
                e.setSource(source);
                e.setAgentServerId(agentServerId);
                e.setAgentServerName(agentServerName);
                e.setResultCode(resultCode);
                e.setDetail(toJson(detail));
                e.setOperator(operator != null ? operator : "SYSTEM");
                e.setMerchantId(merchantId);
                tokenEventRepo.save(e);
            } catch (Exception ex) {
                log.warn("[Token事件] 写入失败(忽略): accountId={} event={} err={}", accountId, eventType, ex.getMessage());
            }
        });
    }

    // ===================== 工具 =====================

    private String toJson(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** 生成通用 detail Map */
    public static Map<String, Object> detail(Object... kvs) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put(String.valueOf(kvs[i]), kvs[i + 1]);
        }
        return m;
    }
}
