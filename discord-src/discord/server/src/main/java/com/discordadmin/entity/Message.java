package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_conversation", columnList = "conversation_id"),
        @Index(name = "idx_messages_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_messages_conversation_created", columnList = "conversation_id, created_at"),
        @Index(name = "idx_messages_conversation_direction", columnList = "conversation_id, direction, discord_created_at"),
        @Index(name = "idx_messages_created_at", columnList = "created_at"),
        @Index(name = "idx_messages_direction", columnList = "direction"),
        @Index(name = "idx_messages_sender_id", columnList = "sender_discord_user_id"),
        @Index(name = "idx_messages_merchant_created", columnList = "merchant_id, created_at")
    },
    uniqueConstraints = @UniqueConstraint(name = "uk_conversation_discord_msg",
            columnNames = {"conversation_id", "discord_message_id"}))
@Getter
@Setter
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属商户ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "discord_message_id", length = 32)
    private String discordMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 16, nullable = false)
    private Direction direction;

    @Column(name = "sender_name", length = 128)
    private String senderName;

    @Column(name = "sender_discord_user_id", length = 32)
    private String senderDiscordUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_agent_id")
    private Agent senderAgent;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "translated_content", columnDefinition = "TEXT")
    private String translatedContent;

    @Column(name = "attachments_json", columnDefinition = "TEXT")
    private String attachmentsJson;

    @Column(name = "referenced_message_id")
    private Long referencedMessageId;

    @Column(name = "referenced_discord_message_id", length = 32)
    private String referencedDiscordMessageId;

    @Column(name = "reaction_json", columnDefinition = "TEXT")
    private String reactionJson;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    /** Discord 消息的实际创建时间（用于排序） */
    @Column(name = "discord_created_at")
    private Instant discordCreatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Direction {
        INBOUND, OUTBOUND
    }
}
