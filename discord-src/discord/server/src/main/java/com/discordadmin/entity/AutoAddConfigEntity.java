package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "auto_add_config", indexes = {
    @Index(name = "idx_auto_config_merchant", columnList = "merchant_id"),
    @Index(name = "idx_auto_config_user", columnList = "user_id")
})
@Getter
@Setter
public class AutoAddConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds = 900;

    @Column(name = "delay_min_seconds")
    private Integer delayMinSeconds = 0;

    @Column(name = "delay_max_seconds")
    private Integer delayMaxSeconds = 5;

    @Column(name = "auto_crawl_discord_account")
    private Boolean autoCrawlDiscordAccount = false;

    @Column(name = "crawl_interval_seconds")
    private Integer crawlIntervalSeconds = 300;

    @Column(name = "auto_login_discord")
    private Boolean autoLoginDiscord = false;

    @Column(name = "max_concurrent_emulators")
    private Integer maxConcurrentEmulators = 5;

    @Column(name = "emulator_start_interval_sec")
    private Integer emulatorStartIntervalSec = 5;

    @Column(name = "test_mode_enabled")
    private Boolean testModeEnabled = true;

    @Column(name = "add_start_time", length = 8)
    private String addStartTime = "09:00";

    @Column(name = "add_end_time", length = 8)
    private String addEndTime = "18:00";

    @Column(name = "daily_limit")
    private Integer dailyLimit = 6;

    @Column(name = "estimated_single_duration_min")
    private Integer estimatedSingleDurationMin = 5;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
