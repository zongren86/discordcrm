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
        @Index(name = "idx_gm_username", columnList = "username(64)")
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
}
