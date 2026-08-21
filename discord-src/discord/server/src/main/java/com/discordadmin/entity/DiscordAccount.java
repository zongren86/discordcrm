package com.discordadmin.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "discord_accounts", indexes = {
        @Index(name = "idx_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_account_type", columnList = "account_type"),
        @Index(name = "idx_discord_id", columnList = "discord_id"),
        @Index(name = "idx_email", columnList = "email"),
        @Index(name = "idx_merchant_status_type", columnList = "merchant_id, status, account_type")
})
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

    @Column(name = "token", nullable = false, unique = true, length = 256)
    @JsonIgnore
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 8)
    private AccountType accountType = AccountType.BOT;

    @Column(name = "discord_id", length = 32)
    private String discordId;

    @Column(name = "discord_name", length = 128)
    private String discordName;

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

    /**
     * Token 是否有效（数据库持久化字段，列表直接读，不实时调用Discord API验证）。
     * true=有效，false=已失效(仅401会置为false)；其他网络/限流错误不改变此值。
     */
    @Column(name = "token_valid")
    private Boolean tokenValid = true;

    @ManyToMany(mappedBy = "discordAccounts")
    @JsonIgnore
    private Set<Agent> agents = new HashSet<>();

    /** 缓存的好友数量（同步好友时更新） */
    @Column(name = "friend_count")
    private Long friendCount = 0L;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public enum AccountStatus {
        ACTIVE, INACTIVE
    }

    public enum AccountType {
        BOT, USER
    }
}
