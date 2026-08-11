package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "agents")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 32)
    private AgentRole role = AgentRole.SALES;

    /** 关联的自定义角色ID集合（支持多角色分配） */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_role_ids", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "role_id")
    private Set<Long> roleIds = new HashSet<>();

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @ManyToMany
    @JoinTable(
        name = "agent_discord_accounts",
        joinColumns = @JoinColumn(name = "agent_id"),
        inverseJoinColumns = @JoinColumn(name = "discord_account_id")
    )
    private Set<DiscordAccount> discordAccounts = new HashSet<>();

    public enum AgentRole {
        PLATFORM_ADMIN,
        MERCHANT_ADMIN,
        MANAGER,
        SALES,
        SERVICE
    }
}
