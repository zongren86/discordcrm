package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "reminder_rules")
@Getter
@Setter
public class ReminderRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 触发器：idle_days / tag_change / pinned_idle */
    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    /** 触发参数，如 idle_days=3 */
    @Column(name = "trigger_config", length = 1000)
    private String triggerConfig;

    /** 频率：once / daily / weekly / monthly */
    @Column(name = "frequency", length = 16)
    private String frequency;

    /** 通知渠道：system */
    @Column(name = "channel", length = 16)
    private String channel = "system";

    /** 通知模板 */
    @Column(name = "message_template", length = 500)
    private String messageTemplate;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
