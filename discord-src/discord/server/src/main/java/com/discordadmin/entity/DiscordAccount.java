package com.discordadmin.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "discord_accounts")
@Getter
@Setter
public class DiscordAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属商户ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 邮箱（批量导入时记录） */
    @Column(name = "email", length = 128)
    private String email;

    /** 备注 */
    @Column(name = "remark", length = 256)
    private String remark;

    @Column(name = "bot_token", nullable = false, unique = true, length = 256)
    @JsonIgnore
    private String botToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 8)
    private AccountType accountType = AccountType.BOT;

    @Column(name = "discord_bot_id", length = 32)
    private String discordBotId;

    @Column(name = "discord_bot_name", length = 128)
    private String discordBotName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "last_error", length = 512)
    private String lastError;

    /** Token 过期时间（Discord USER token 通常有效期有限） */
    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    /** 最后一次检查 token 有效性的时间 */
    @Column(name = "token_checked_at")
    private Instant tokenCheckedAt;

    @ManyToMany(mappedBy = "discordAccounts")
    @JsonIgnore
    private Set<Agent> agents = new HashSet<>();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public enum AccountStatus {
        ACTIVE, INACTIVE
    }

    public enum AccountType {
        BOT, USER
    }
}
