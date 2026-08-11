package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 一个 Friend 记录表示某个 USER 类型 DiscordAccount 的一个好友或待处理请求。
 *
 * 唯一约束 (discord_account_id, friend_discord_user_id)：
 *  同一账号下同一好友只存一条；同一用户 ID 可能同时是 A 和 B 的好友，则存两条。
 */
@Entity
@Table(name = "discord_friends",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_friend",
                columnNames = {"discord_account_id", "friend_discord_user_id"}))
@Getter
@Setter
public class Friend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属商户ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 该好友归属的 Discord 账号（客服A/B） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_account_id", nullable = false)
    private DiscordAccount discordAccount;

    /** 好友的 Discord 用户 ID */
    @Column(name = "friend_discord_user_id", nullable = false, length = 32)
    private String friendDiscordUserId;

    /** 好友用户名 */
    @Column(name = "username", length = 64)
    private String username;

    /** 好友全局显示名 */
    @Column(name = "global_name", length = 128)
    private String globalName;

    /** 头像 hash，可拼成 CDN URL */
    @Column(name = "avatar", length = 64)
    private String avatar;

    /** 关系状态：ACCEPTED=已接受好友，PENDING_IN=待接收的好友请求 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private FriendStatus status = FriendStatus.ACCEPTED;

    /** 关联的服务器ID（通过 GuildMember 匹配获得） */
    @Column(name = "guild_server_id")
    private Long guildServerId;

    /** 服务器名称（冗余存储，方便查询） */
    @Column(name = "server_name", length = 256)
    private String serverName;

    /** 是否已匹配服务器 */
    @Column(name = "server_matched")
    private Boolean serverMatched = false;

    @Column(name = "synced_at")
    private Instant syncedAt = Instant.now();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public enum FriendStatus {
        ACCEPTED, PENDING_IN
    }
}
