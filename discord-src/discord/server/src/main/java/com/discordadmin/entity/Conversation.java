package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversations")
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
    private DiscordUser discordUser;

    /** 该会话归属的 Discord 账号，用于出站消息路由 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discord_account_id")
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
    private Agent assignedAgent;

    @Column(name = "last_message_preview", length = 256)
    private String lastMessagePreview;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /** 最后一条消息的方向：INBOUND=好友发来的（未回复），OUTBOUND=客服发出的（已回复） */
    @Column(name = "last_message_direction", length = 16)
    private String lastMessageDirection;

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
        PROSPECT,    // 通过客户
        NEW,         // 回复客户
        ACTIVE,      // 换包客户
        CONVERTED,   // 注册客户
        PAYING,      // 付费客户
        DORMANT,     // 休眠客户
        CHURNED,     // 流失客户
        ARCHIVED     // 归档客户
    }
}
