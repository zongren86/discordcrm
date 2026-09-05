package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * GIF/Sticker 收藏（按 user_id 维度，跨所有 Discord 账号共享）。
 * discord_account_id 仅作"来源账号"记录（nullable），不参与外键约束。
 * 即使原账号删除，收藏记录仍然保留可用。
 */
@Entity
@Table(name = "gif_favorites", indexes = {
        @Index(name = "idx_gf_user_type", columnList = "user_id, type"),
        @Index(name = "idx_gf_type_merchant", columnList = "merchant_id, type")
})
@Getter
@Setter
public class GifFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "user_id")
    private Long userId;

    /** 来源账号 ID（nullable，账号删除后仍保留收藏） */
    @Column(name = "discord_account_id")
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
