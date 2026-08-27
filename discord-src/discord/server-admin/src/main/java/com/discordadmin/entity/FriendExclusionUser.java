package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 好友排除指定清单 — 从 Excel 上传的用户名
 */
@Entity
@Table(name = "friend_exclusion_users", indexes = {
    @Index(name = "idx_excl_merchant_user", columnList = "merchant_id, user_id"),
    @Index(name = "idx_excl_username", columnList = "username")
}, uniqueConstraints = @UniqueConstraint(name = "uk_excl_merchant_user_username", columnNames = {"merchant_id", "user_id", "username"}))
@Getter
@Setter
public class FriendExclusionUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Discord 用户名 (username 字段, 不含 # 号) */
    @Column(name = "username", nullable = false, length = 128)
    private String username;

    /** 备注 / 来源 (如: "upload_20260828") */
    @Column(name = "source", length = 128)
    private String source;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
