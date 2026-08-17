package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 模拟器服务器绑定 - 管理商户在模拟器中绑定的Discord服务器
 */
@Entity
@Table(name = "emu_server_bindings", indexes = {
    @Index(name = "idx_server_binding_merchant", columnList = "merchant_id"),
    @Index(name = "idx_server_binding_server", columnList = "server_id"),
    @Index(name = "idx_server_binding_account", columnList = "discord_account_id"),
    @Index(name = "idx_server_binding_status", columnList = "status")
})
@Getter
@Setter
public class EmuServerBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "discord_account_id")
    private Long discordAccountId;

    @Column(name = "guild_id", length = 64)
    private String guildId;

    @Column(name = "server_name", length = 256)
    private String serverName;

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private BindingStatus status = BindingStatus.ADDED;

    @Column(name = "member_count")
    private Integer memberCount = 0;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public enum BindingStatus {
        PENDING,    // 待添加
        ADDED,      // 已添加
        OCCUPIED    // 被占用（有进行中的任务）
    }
}
