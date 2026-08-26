package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "discord_users")
@Getter
@Setter
public class DiscordUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discord_user_id", nullable = false, unique = true, length = 32)
    private String discordUserId;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "global_name", length = 128)
    private String globalName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "tags", length = 256)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private UserStatus status = UserStatus.NORMAL;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /** 原生 Discord Presence: online / idle / dnd / offline */
    @Column(name = "presence", length = 16)
    private String presence;

    /** Presence 最后同步时间 */
    @Column(name = "presence_updated_at")
    private Instant presenceUpdatedAt;

    public enum UserStatus {
        NORMAL, BLOCKED
    }
}
