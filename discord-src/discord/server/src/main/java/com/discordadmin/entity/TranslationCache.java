package com.discordadmin.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 翻译缓存：存储相同原文 + 目标语言的翻译结果，避免重复调用翻译API。
 *  - sourceHash: 原文内容的SHA-256哈希（用于快速查找）
 *  - sourceContent: 原文内容（完整存储，用于二次校验）
 *  - targetLanguage: 目标语言代码（如 zh-CN, en）
 *  - translatedContent: 翻译结果
 */
@Entity
@Table(name = "translation_cache", indexes = {
        @Index(name = "idx_source_hash_lang", columnList = "sourceHash, targetLanguage", unique = true)
})
public class TranslationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原文内容的哈希值（用于快速查找） */
    @Column(nullable = false, length = 64)
    private String sourceHash;

    /** 原文内容（完整存储，用于二次校验） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceContent;

    /** 目标语言代码 */
    @Column(nullable = false, length = 16)
    private String targetLanguage;

    /** 翻译结果 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String translatedContent;

    /** 创建时间 */
    @Column(nullable = false)
    private Instant createdAt;

    /** 命中次数（用于统计和LRU淘汰） */
    @Column
    private Long hitCount = 0L;

    /** 最后命中时间 */
    @Column
    private Instant lastHitAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }

    public String getTargetLanguage() { return targetLanguage; }
    public void setTargetLanguage(String targetLanguage) { this.targetLanguage = targetLanguage; }

    public String getTranslatedContent() { return translatedContent; }
    public void setTranslatedContent(String translatedContent) { this.translatedContent = translatedContent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getHitCount() { return hitCount; }
    public void setHitCount(Long hitCount) { this.hitCount = hitCount; }

    public Instant getLastHitAt() { return lastHitAt; }
    public void setLastHitAt(Instant lastHitAt) { this.lastHitAt = lastHitAt; }
}
