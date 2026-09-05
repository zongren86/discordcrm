package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 账号 Token 生命周期事件表。
 * 每次 token 被创建、检查、401失效、续期都会写一条。
 * 比 audit_logs 更聚焦，查单个账号 token 全链路时直接查这张表。
 */
@Entity
@Table(name = "account_token_events", indexes = {
        @Index(name = "idx_te_account", columnList = "account_id"),
        @Index(name = "idx_te_event_type", columnList = "event_type"),
        @Index(name = "idx_te_time", columnList = "created_at"),
        @Index(name = "idx_te_merchant", columnList = "merchant_id")
})
@Getter
@Setter
public class AccountTokenEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "account_name", length = 128)
    private String accountName;

    /**
     * CREATED          — 账号入库时初始 token
     * CHECKED          — 定时体检或手动校验，结果 200
     * EXPIRED_401_AGENT — agent 上报 401
     * EXPIRED_401_POLLER — UserMessagePoller 轮询拉消息时 401
     * EXPIRED_401_SCHEDULER — TokenCheckScheduler 定时体检时 401
     * REFRESHED        — 手动续期 / 更新 token
     * RECOVERED        — token_valid 从 0 恢复到 1
     */
    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;

    /** 事件来源：AGENT_SERVER / TOKEN_CHECK_SCHEDULER / MESSAGE_POLLER / AGENT_UPLOAD / MANUAL / SYSTEM */
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "agent_server_id")
    private Long agentServerId;

    @Column(name = "agent_server_name", length = 64)
    private String agentServerName;

    /** HTTP 状态码或结果码：200 / 401 / 429 / NETWORK / JPA_ERR 等 */
    @Column(name = "result_code", length = 16)
    private String resultCode;

    /** 完整上下文 JSON */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "operator", length = 64)
    private String operator;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
