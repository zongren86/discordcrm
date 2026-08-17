package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "auto_add_tasks", indexes = {
    @Index(name = "idx_task_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_task_user_id", columnList = "user_id"),
    @Index(name = "idx_task_status", columnList = "status"),
    @Index(name = "idx_task_server_id", columnList = "server_id")
})
@Getter
@Setter
public class AutoAddTask {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;
    
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;
    
    @Column(name = "server_id", nullable = false)
    private Long serverId;
    
    @Column(name = "discord_account_id", nullable = false)
    private Long discordAccountId;
    
    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;
    
    @Column(name = "target_count")
    private Integer targetCount = 0;
    
    @Column(name = "success_count")
    private Integer successCount = 0;
    
    @Column(name = "fail_count")
    private Integer failCount = 0;
    
    @Column(name = "pause_duration_seconds")
    private Integer pauseDurationSeconds = 900; // 15分钟
    
    @Column(name = "delay_min_seconds")
    private Integer delayMinSeconds = 60;
    
    @Column(name = "delay_max_seconds")
    private Integer delayMaxSeconds = 300;
    
    @Column(name = "assigned_agent_id", length = 256)
    private String assignedAgentId;
    
    @Column(name = "started_at")
    private Instant startedAt;
    
    @Column(name = "completed_at")
    private Instant completedAt;
    
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
    
    public enum TaskStatus {
        PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    }
}
