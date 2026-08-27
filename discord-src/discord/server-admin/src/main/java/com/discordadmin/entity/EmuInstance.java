package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 模拟器实例 - 存储mumu模拟器信息
 */
@Entity
@Table(name = "emu_instances", indexes = {
    @Index(name = "idx_emu_merchant", columnList = "merchant_id"),
    @Index(name = "idx_emu_status", columnList = "status"),
    @Index(name = "idx_emu_account", columnList = "discord_account_id"),
    @Index(name = "idx_emu_index", columnList = "instance_index"),
    @Index(name = "idx_emu_merchant_user_idx", columnList = "merchant_id, user_id, instance_index", unique = true)
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_merchant_user_instance", columnNames = {"merchant_id", "user_id", "instance_index"})
})
@Getter
@Setter
public class EmuInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "instance_index", nullable = false)
    private Integer instanceIndex;

    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private EmuStatus status = EmuStatus.CREATED;

    @Column(name = "cpu_cores")
    private Integer cpuCores = 1;

    @Column(name = "memory_gb")
    private Integer memoryGb = 1;

    @Column(name = "resolution", length = 32)
    private String resolution = "720x1280";

    @Column(name = "adb_port")
    private Integer adbPort;

    @Column(name = "discord_installed")
    private Boolean discordInstalled = false;

    @Column(name = "discord_logged_in")
    private Boolean discordLoggedIn = false;

    @Column(name = "discord_on_home")
    private Boolean discordOnHome = false;

    @Column(name = "discord_account_id")
    private Long discordAccountId;

    @Column(name = "discord_account_name", length = 128)
    private String discordAccountName;

    @Column(name = "auto_running", columnDefinition = "TINYINT(1)")
    private Boolean autoRunning = false;

    @Column(name = "added_count")
    private Integer addedCount = 0;

    @Column(name = "next_add_at")
    private Instant nextAddAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "auto_last_result", length = 256)
    private String autoLastResult;

    @Column(name = "guild_server_id")
    private Long guildServerId;

    /** 绑定的Discord账号编号（即DiscordAccountNumber.number，1、2、3...），支持显式编辑覆盖instanceIndex默认对应关系 */
    @Column(name = "discord_account_number")
    private Integer discordAccountNumber;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public enum EmuStatus {
        CREATED,    // 已创建
        RUNNING,    // 运行中
        STOPPED,    // 已停止
        CREATING,   // 创建中
        ERROR       // 错误
    }

    // 显式 getter 确保 Boolean 类型正确映射
    public Boolean getAutoRunning() {
        return autoRunning != null && autoRunning;
    }

    // 显式 setter
    public void setAutoRunning(Boolean autoRunning) {
        this.autoRunning = autoRunning;
    }
}
