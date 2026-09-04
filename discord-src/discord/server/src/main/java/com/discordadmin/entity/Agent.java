package com.discordadmin.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "user", indexes = {
        @Index(name = "idx_user_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_user_account_type", columnList = "account_type"),
        @Index(name = "idx_user_merchant_enabled", columnList = "merchant_id, enabled")
})
@Getter
@Setter
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属商户ID，PLATFORM_ADMIN 为 null */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "username", nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "email", length = 128)
    private String email;

    @Column(name = "notes", length = 500)
    private String notes;

    /** 账号类型：0=管理员，1=普通账号 */
    @Column(name = "account_type")
    private Integer accountType = 1;

    /** 关联的自定义角色ID集合（支持多角色分配） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_id")
    private Set<Long> roleIds = new HashSet<>();

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @JsonIgnore
    @ManyToMany
    @JoinTable(
        name = "user_discord_accounts",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "discord_account_id")
    )
    private Set<DiscordAccount> discordAccounts = new HashSet<>();

    /**
     * 获取清理过的显示名 — 自动剥离历史遗留的 accountType 前缀
     * 例如: "云_云飞扬管理员" → "云飞扬管理员"
     */
    public String getCleanDisplayName() {
        String name = displayName != null ? displayName : username;
        if (name != null) {
            // 去掉 1-2 个汉字 + 下划线 的前缀 (历史上 accountType 的中文标签)
            name = name.replaceAll("^(云|客|管|坐|商|平台|云端)_", "");
        }
        return name;
    }

    /**
     * 判断是否为管理员
     */
    public boolean isAdmin() {
        return accountType != null && accountType == 0;
    }

    /**
     * 判断是否为普通账号
     */
    public boolean isRegular() {
        return accountType != null && accountType == 1;
    }

    /**
     * 判断是否为平台管理员（管理员 + 无商户）
     */
    public boolean isPlatformAdmin() {
        return isAdmin() && merchantId == null;
    }

    /**
     * 判断是否为商户管理员（管理员 + 有商户）
     */
    public boolean isMerchantAdmin() {
        return isAdmin() && merchantId != null;
    }
}
