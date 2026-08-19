package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "guild_members", indexes = {
        @Index(name = "idx_gm_guild_server_id", columnList = "guild_server_id"),
        @Index(name = "idx_gm_user_id", columnList = "user_id"),
        @Index(name = "idx_gm_guild_user", columnList = "guild_server_id, user_id"),
        @Index(name = "idx_gm_display_name", columnList = "display_name(64)"),
        @Index(name = "idx_gm_username", columnList = "username(64)"),
        @Index(name = "idx_gm_friend_status", columnList = "friend_status"),
        @Index(name = "idx_gm_server_friend_status", columnList = "guild_server_id, friend_status"),
        @Index(name = "idx_gm_discord_status", columnList = "discord_status")
})
@Getter
@Setter
public class GuildMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_server_id", nullable = false)
    private Long guildServerId;

    @Column(name = "guild_id", length = 64)
    private String guildId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "nick", length = 128)
    private String nick;

    @Column(name = "global_name", length = 128)
    private String globalName;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "is_bot")
    private Boolean isBot = false;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "roles", columnDefinition = "TEXT")
    private String roles;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // ========== 好友池相关字段 ==========

    /**
     * 好友添加状态：0-待添加, 1-已分配, 2-添加成功, 3-添加失败
     */
    @Column(name = "friend_status")
    private Integer friendStatus = 0;

    /**
     * 用于添加好友的 Discord 账号ID（分配后使用的账号）
     */
    @Column(name = "discord_account_id")
    private Long discordAccountId;

    /**
     * 关联的自动加好友任务ID
     */
    @Column(name = "assigned_task_id")
    private Long assignedTaskId;

    /**
     * 处理该好友的模拟器索引
     */
    @Column(name = "emulator_index")
    private Integer emulatorIndex;

    /**
     * 最后一次添加好友的错误信息
     */
    @Column(name = "last_error", length = 512)
    private String lastError;

    /**
     * 开始处理时间
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * 完成时间
     */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
     * 重试次数
     */
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ========== Discord 原生状态字段 ==========

    /**
     * Discord 原生状态：online, idle, dnd, offline
     */
    @Column(name = "discord_status", length = 32)
    private String discordStatus;

    /**
     * 好友添加状态枚举
     */
    public enum FriendStatus {
        PENDING(0),      // 待添加
        ASSIGNED(1),     // 已分配
        SUCCESS(2),      // 添加成功
        FAILED(3);       // 添加失败

        private final int value;

        FriendStatus(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static FriendStatus fromValue(int value) {
            for (FriendStatus status : values()) {
                if (status.value == value) {
                    return status;
                }
            }
            return PENDING;
        }
    }
}
