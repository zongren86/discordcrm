package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent 代理任务
 * 前端/Server 创建任务 → 分配给某个 agent → agent 执行并回传结果
 */
@Entity
@Table(name = "agent_tasks", indexes = {
        @Index(name = "idx_agent_task_status", columnList = "status"),
        @Index(name = "idx_agent_task_server", columnList = "agent_server_id"),
        @Index(name = "idx_agent_task_created", columnList = "created_at")
})
@Getter
@Setter
public class AgentTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务类型: CAPTURE_DISCORD_ACCOUNT / ... */
    @Column(name = "type", nullable = false, length = 64)
    private String type;

    /** 分配给哪个代理节点 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_server_id")
    private AgentServer agentServer;

    /** 状态: PENDING / RUNNING / SUCCESS / FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    /** 任务参数（JSON，随 type 不同而不同） */
    @Column(name = "params", columnDefinition = "TEXT")
    private String params;

    /** 执行结果（JSON） */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /** 任务发起人 userId */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    /** 关联 DiscordAccount（如果任务结果已存为账号） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_account_id")
    private DiscordAccount discordAccount;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
