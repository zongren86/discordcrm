package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ai_settings")
@Getter
@Setter
public class AISetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    /** 功能键：translate / reply_suggest / summary */
    @Column(name = "feature", length = 32)
    private String feature;

    @Column(name = "enabled")
    private Boolean enabled = false;

    /** AI提供商：deepseek / qwen / openai / custom */
    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "api_endpoint", length = 255)
    private String apiEndpoint;

    @Column(name = "api_key", length = 255)
    private String apiKey;

    @Column(name = "temperature")
    private Double temperature = 0.7;

    @Column(name = "max_tokens")
    private Integer maxTokens = 1024;

    @Column(name = "system_prompt", length = 2000)
    private String systemPrompt;

    @Column(name = "thinking")
    private Boolean thinking = false;

    @Column(name = "web_search")
    private Boolean webSearch = false;

    /** 语言检测模式：first_message=好友首次信息 / every_message=好友每条信息 */
    @Column(name = "language_detection_mode", length = 32)
    private String languageDetectionMode = "first_message";

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
