package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversation_tags", indexes = {
        @Index(name = "idx_tag_merchant", columnList = "merchant_id"),
        @Index(name = "idx_tag_conversation", columnList = "conversation_id"),
        @Index(name = "idx_tag_name", columnList = "name")
})
@Getter
@Setter
public class ConversationTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "color", length = 16)
    private String color;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
