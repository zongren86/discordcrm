package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notif_agent", columnList = "agent_id, is_read")
})
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "merchant_id")
    private Long merchantId;

    /** 类型：system / rule / mention / warning */
    @Column(name = "type", length = 32)
    private String type = "system";

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "content", length = 1000)
    private String content;

    /** 跳转链接，如 /#/chat?conversationId=1 */
    @Column(name = "target", length = 255)
    private String target;

    @Column(name = "is_read")
    private Boolean isRead = false;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
