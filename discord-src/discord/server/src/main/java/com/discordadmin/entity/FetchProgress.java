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
}
