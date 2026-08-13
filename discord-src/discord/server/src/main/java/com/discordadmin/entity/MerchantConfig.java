package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "merchant_configs")
@Getter
@Setter
public class MerchantConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private Long merchantId;

    /** 好友数据获取配置 */
    @Column(name = "fetch_limit")
    private Integer fetchLimit = 2000000;

    @Column(name = "request_interval_ms")
    private Integer requestInterval = 3;

    @Column(name = "request_count")
    private Integer requestCount = 100;

    @Column(name = "max_depth")
    private Integer maxDepth = 5;

    @Column(name = "max_requests")
    private Integer maxRequests = 1000;

    /** 归档配置：超过N天无消息往来则自动归档 */
    @Column(name = "archive_days")
    private Integer archiveDays = 30;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
