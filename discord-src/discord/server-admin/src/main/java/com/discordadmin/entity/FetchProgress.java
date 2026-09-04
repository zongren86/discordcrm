package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "fetch_progress", indexes = {
        @Index(name = "idx_fp_guild_server_id", columnList = "guild_server_id"),
        @Index(name = "idx_fp_status", columnList = "status"),
        @Index(name = "idx_fp_guild_server_status", columnList = "guild_server_id, status"),
        @Index(name = "idx_fp_discord_account_id", columnList = "discord_account_id")
})
@Getter
@Setter
public class FetchProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_server_id", nullable = false)
    private Long guildServerId;

    @Column(name = "discord_account_id")
    private Long discordAccountId;

    @Column(name = "guild_id", length = 64)
    private String guildId;

    @Column(name = "status", length = 16)
    private String status = "PENDING";

    @Column(name = "current_page")
    private Integer currentPage = 0;

    @Column(name = "total_pages")
    private Integer totalPages = 0;

    @Column(name = "request_count")
    private Integer requestCount = 0;

    @Column(name = "raw_member_count")
    private Integer rawMemberCount = 0;

    @Column(name = "deduped_member_count")
    private Integer dedupedMemberCount = 0;

    @Column(name = "completed_pages")
    private Integer completedPages = 0;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_batch_id", length = 128)
    private String lastBatchId;

    /** 本次同步停止时尚未处理完的前缀队列（JSON 数组），用于下次断点续抓 */
    @Column(name = "resume_frontier", columnDefinition = "LONGTEXT")
    private String resumeFrontier;

    /** 已成功处理的所有前缀集合（JSON 数组），下次同步跳过这些前缀 */
    @Column(name = "completed_prefixes", columnDefinition = "LONGTEXT")
    private String completedPrefixes;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 累计响应成员数（不去重） */
    @Column(name = "total_responded_members")
    private Integer totalRespondedMembers = 0;

    /** 累计响应时间（ms） */
    @Column(name = "total_response_time_ms")
    private Long totalResponseTimeMs = 0L;

    /** 最后处理的前缀 */
    @Column(name = "last_prefix", length = 64)
    private String lastPrefix;

    /** 前缀执行清单：完整有序的前缀序列 + 每个前缀的执行状态（JSON 数组，元素: {"p":"前缀","s":"PENDING|DONE|FAILED"}） */
    @Column(name = "prefix_sequence_list", columnDefinition = "LONGTEXT")
    private String prefixSequenceList;

    /** 失败/中断原因 */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** 最大请求数配置 */
    @Column(name = "max_requests")
    private Integer maxRequests = 1000;

    /** 最大成员数配置 */
    @Column(name = "max_members")
    private Integer maxMembers = 2000000;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    /** Token 来源: EXISTING_ACCOUNT(使用关联的 DiscordAccount token) | MANUAL(手工输入) */
    @Column(name = "token_source", length = 32)
    private String tokenSource = "EXISTING_ACCOUNT";

    /** 手工输入的 token（仅 tokenSource=MANUAL 时有值） */
    @Column(name = "manual_token", length = 512)
    private String manualToken;

    /** 采集出口: SERVER_DIRECT(应用服务器直连) | PROXY_AGENT(通过在线 mumu-agent) */
    @Column(name = "fetch_exit", length = 32)
    private String fetchExit = "SERVER_DIRECT";

    /** PROXY_AGENT 模式下选中的 mumu-agent deviceId */
    @Column(name = "agent_device_id", length = 128)
    private String agentDeviceId;
}
