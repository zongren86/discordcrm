package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 模拟器账号绑定 - 管理商户在模拟器中绑定的Discord账号
 */
@Entity
@Table(name = "emu_account_bindings", indexes = {
    @Index(name = "idx_binding_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_binding_account_id", columnList = "discord_account_id"),
    @Index(name = "idx_binding_user_id", columnList = "user_id"),
    @Index(name = "idx_binding_status", columnList = "status")
})
@Getter
@Setter
public class EmuAccountBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "discord_account_id", nullable = false)
    private Long discordAccountId;

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private BindingStatus status = BindingStatus.ADDED;

    @Column(name = "notes", length = 256)
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public enum BindingStatus {
        PENDING,    // 待添加
        ADDED,      // 已添加
        OCCUPIED    // 被占用（有进行中的任务）
    }
}
