package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 好友号池 - 管理待添加的好友列表
 */
@Entity
@Table(name = "emu_friend_pool", indexes = {
    @Index(name = "idx_pool_merchant", columnList = "merchant_id"),
    @Index(name = "idx_pool_server", columnList = "server_id"),
    @Index(name = "idx_pool_status", columnList = "status"),
    @Index(name = "idx_pool_user_id", columnList = "discord_user_id")
})
@Getter
@Setter
public class EmuFriendPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "discord_user_id", length = 64, nullable = false)
    private String discordUserId;

    @Column(name = "username", length = 256)
    private String username;

    @Column(name = "global_name", length = 256)
    private String globalName;

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private FriendStatus status = FriendStatus.PENDING;

    @Column(name = "assigned_task_id")
    private Long assignedTaskId;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public enum FriendStatus {
        PENDING,        // 待添加
        ASSIGNED,       // 已分配添加
        SUCCESS,        // 添加成功
        FAILED          // 添加失败
    }
}
