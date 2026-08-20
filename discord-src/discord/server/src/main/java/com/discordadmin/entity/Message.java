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

    @Column(name = "language", length = 16)
    private String language;

    @Column(name = "message_type", length = 16)
    private String messageType;

    /** GIF 消息的 URL（上传到 Discord 后的 CDN 链接或原始 URL） */
    @Column(name = "gif_url", length = 2048)
    private String gifUrl;

    @Column(name = "audio_url", length = 512)
    private String audioUrl;

    @Column(name = "audio_duration")
    private Integer audioDuration;

    @Column(name = "audio_mime_type", length = 64)
    private String audioMimeType;

    /** 发送方本地保存的音频 base64（用于前端回放自己发送的语音）。
     *  懒加载：默认列表查询不读取，避免序列化大对象导致响应变慢。 */
    @org.hibernate.annotations.LazyToOne(org.hibernate.annotations.LazyToOneOption.NO_PROXY)
    @jakarta.persistence.Basic(fetch = jakarta.persistence.FetchType.LAZY)
    @Column(name = "audio_data", columnDefinition = "LONGTEXT")
    private String audioData;

    @Column(name = "attachments_json", columnDefinition = "TEXT")
    private String attachmentsJson;

    /** 语音转文字(ASR)的原文：语音识别后的原始文字，用于前端"查看原文" */
    @Column(name = "asr_text", columnDefinition = "TEXT")
    private String asrText;

    /** asrText 的翻译结果：把语音识别原文翻译为目标语言 */
    @Column(name = "asr_translated", columnDefinition = "TEXT")
    private String asrTranslated;

    /** asrText 的原始语言（检测到的），如 ja / en / ko */
    @Column(name = "asr_language", length = 16)
    private String asrLanguage;

    /** asr 转写状态：pending / done / failed */
    @Column(name = "asr_status", length = 16)
    private String asrStatus;

    /** asr 转写失败的错误信息（方便前端提示） */
    @Column(name = "asr_error", length = 512)
    private String asrError;

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
