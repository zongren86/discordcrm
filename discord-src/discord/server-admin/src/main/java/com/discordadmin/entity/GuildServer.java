package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "guild_servers", indexes = {
        @Index(name = "idx_guild_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_guild_discord_account_id", columnList = "discord_account_id"),
        @Index(name = "idx_guild_status", columnList = "status"),
        @Index(name = "idx_guild_merchant_status", columnList = "merchant_id, status")
})
@Getter
@Setter
public class GuildServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "discord_account_id", nullable = true)
    private Long discordAccountId;

    @Column(name = "guild_id", length = 64)
    private String guildId;

    @Column(name = "channel_id", length = 64)
    private String channelId;

    @Column(name = "guild_url", length = 512)
    private String guildUrl;

    @Column(name = "name", length = 256)
    private String name;

    @Column(name = "icon_url", length = 512)
    private String iconUrl;

    @Column(name = "member_count")
    private Integer memberCount = 0;

    @Column(name = "last_fetch_at")
    private Instant lastFetchAt;

    @Column(name = "status", length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
