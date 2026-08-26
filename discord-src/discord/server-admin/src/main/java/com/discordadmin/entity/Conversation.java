package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conv_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_conv_status", columnList = "status"),
        @Index(name = "idx_conv_last_message_at", columnList = "last_message_at"),
        @Index(name = "idx_conv_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_conv_merchant_last_msg", columnList = "merchant_id, last_message_at"),
        @Index(name = "idx_conv_merchant_status_last_msg", columnList = "merchant_id, status, last_message_at"),
        @Index(name = "idx_conv_merchant_account", columnList = "merchant_id, discord_account_id"),
        @Index(name = "idx_conv_merchant_stage", columnList = "merchant_id, stage"),
        @Index(name = "idx_conv_channel_id", columnList = "channel_id"),
        @Index(name = "idx_conv_discord_user_id", columnList = "discord_user_id"),
        @Index(name = "idx_conv_owner_agent_id", columnList = "owner_agent_id"),
        @Index(name = "idx_conv_account_id", columnList = "discord_account_id"),
        @Index(name = "idx_conv_merchant_agent", columnList = "merchant_id, owner_agent_id"),
        @Index(name = "idx_conv_merchant_account_stage", columnList = "merchant_id, discord_account_id, stage")
})
@Getter
@Setter
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属商户ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private DiscordUser discordUser;

    /** 该会话归属的 Discord 账号，用于出站消息路由 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_account_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private DiscordAccount discordAccount;

    @Column(name = "guild_id", length = 32)
    private String guildId;

    @Column(name = "guild_name", length = 128)
    private String guildName;

    @Column(name = "channel_id", nullable = false, length = 32)
    private String channelId;

    @Column(name = "channel_name", length = 128)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16)
    private ConversationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private ConversationStatus status = ConversationStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_agent_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Agent assignedAgent;

    @Column(name = "last_message_preview", length = 256)
    private String lastMessagePreview;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /** 最后一条消息的方向：INBOUND=好友发来的（未回复），OUTBOUND=客服发出的（已回复） */
    @Column(name = "last_message_direction", length = 16)
    private String lastMessageDirection;

    /** 最后已处理的Discord消息ID（用于增量拉取，持久化到DB避免重启后全量回填） */
    @Column(name = "last_discord_message_id", length = 32)
    private String lastDiscordMessageId;

    /** 销售漏斗阶段 */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 16)
    private Stage stage = Stage.PROSPECT;

    /** 漏斗阶段最后变更时间 */
    @Column(name = "stage_changed_at")
    private Instant stageChangedAt = Instant.now();

    /** 置顶 */
    @Column(name = "pinned")
    private Boolean pinned = false;

    /** 客户备注 */
    @Column(name = "remark", length = 256)
    private String remark;

    /** 客服最后查看该会话的时间，用于计算未读消息数 */
    @Column(name = "last_read_at")
    private Instant lastReadAt;

    /** 当前归属的用户ID（用于权限控制）：
     * 普通用户仅能看到 ownerAgentId = 当前用户ID 的会话；
     * 商户管理员和平台管理员不受此限制 */
    @Column(name = "owner_agent_id")
    private Long ownerAgentId;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public enum ConversationType {
        DM, GUILD_TEXT
    }

    public enum ConversationStatus {
        OPEN, PENDING, CLOSED
    }

    /** 销售漏斗阶段 */
    public enum Stage {
        PROSPECT,    // 通过客户：添加后双方都没发消息，或一方发了另一方没回复
        NEW,         // 回复客户：双方都有发消息
        CONVERTED,   // 注册客户：手工在客户资料里点击注册
        CHURNED,     // 流失客户：被好友删除
        ARCHIVED     // 归档客户：超过配置的天数
    }
}
