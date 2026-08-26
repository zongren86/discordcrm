package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "gif_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_gif_url",
                columnNames = {"discord_account_id", "gif_url_hash"}))
@Getter
@Setter
public class GifFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_account_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private DiscordAccount discordAccount;

    @Column(name = "discord_account_id", insertable = false, updatable = false)
    private Long discordAccountId;

    @Column(name = "gif_url", nullable = false, length = 2048)
    private String gifUrl;

    @Column(name = "gif_url_hash", nullable = false, length = 64)
    private String gifUrlHash;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "source_domain", length = 128)
    private String sourceDomain;

    @Column(name = "type", length = 32)
    private String type = "gif";

    @Column(name = "converted_gif_url", length = 2048)
    private String convertedGifUrl;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
