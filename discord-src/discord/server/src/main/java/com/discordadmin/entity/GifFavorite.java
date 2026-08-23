package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * GIF 收藏记录
 * 
 * 按账号隔离，每个 Discord 账号可以收藏自己喜欢的 GIF URL。
 * 收藏的 GIF 可在聊天界面的 GIF 选择器中快速使用。
 */
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

    /** 所属商户ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 关联的 Discord 账号（按账号隔离收藏） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_account_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private DiscordAccount discordAccount;

    /** 直接存储 accountId 以避免懒加载问题 */
    @Column(name = "discord_account_id", insertable = false, updatable = false)
    private Long discordAccountId;

    /** GIF 原始 URL */
    @Column(name = "gif_url", nullable = false, length = 2048)
    private String gifUrl;

    /** GIF URL 的哈希值（用于唯一约束，避免 URL 太长） */
    @Column(name = "gif_url_hash", nullable = false, length = 64)
    private String gifUrlHash;

    /** GIF 标题/描述（可选） */
    @Column(name = "title", length = 256)
    private String title;

    /** GIF 来源域名 */
    @Column(name = "source_domain", length = 128)
    private String sourceDomain;

    /** 收藏类型：gif=GIF动画, sticker=贴纸/Lottie */
    @Column(name = "type", length = 32)
    private String type = "gif";

    /** 收藏时间 */
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
