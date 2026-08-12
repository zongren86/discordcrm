package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "discord_account_numbers")
@Getter
@Setter
public class DiscordAccountNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的 Discord 账号ID */
    @Column(name = "discord_account_id")
    private Long discordAccountId;

    /** 绑定的账号名称（冗余存储，方便查询） */
    @Column(name = "bound_account", length = 256)
    private String boundAccount;

    /** 创建人ID */
    @Column(name = "creator_id")
    private Long creatorId;

    /** 创建人用户名（冗余存储） */
    @Column(name = "creator_name", length = 64)
    private String creatorName;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
