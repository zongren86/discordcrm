package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 代理服务器节点
 * 每个 crm_agent 代理程序连接到主服务器时对应一条记录
 */
@Entity
@Table(name = "agent_servers", indexes = {
        @Index(name = "idx_agent_server_name", columnList = "name"),
        @Index(name = "idx_agent_server_status", columnList = "status"),
        @Index(name = "idx_agent_server_merchant", columnList = "merchant_id")
})
@Getter
@Setter
public class AgentServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 节点名称（唯一标识） */
    @Column(name = "name", nullable = false, unique = true, length = 128)
    private String name;

    /** token 明文 — 仅创建后显示一次，用于 agent 认证 */
    @Column(name = "token", nullable = false, length = 128)
    private String token;

    /** 代理服务器地址（agent 上报时可填） */
    @Column(name = "server_address", length = 512)
    private String serverAddress;

    /** 所属商户ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 状态：ONLINE / OFFLINE */
    @Column(name = "status", length = 16)
    private String status = "OFFLINE";

    /** 代理端运行的 Node.js 版本 */
    @Column(name = "node_version", length = 64)
    private String nodeVersion;

    /** 浏览器类型 */
    @Column(name = "browser_type", length = 32)
    private String browserType;

    /** 备注 */
    @Column(name = "notes", length = 500)
    private String notes;

    /** 该代理节点最多允许管理的账号数，默认 500 */
    @Column(name = "max_accounts")
    private Integer maxAccounts = 500;

    /** 最后一次心跳时间 */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
