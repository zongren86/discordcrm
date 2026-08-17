package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "auto_add_task_items", indexes = {
    @Index(name = "idx_item_task_id", columnList = "task_id"),
    @Index(name = "idx_item_status", columnList = "status"),
    @Index(name = "idx_item_discord_user_id", columnList = "discord_user_id")
})
@Getter
@Setter
public class AutoAddTaskItem {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    
    @Column(name = "discord_user_id", nullable = false, length = 64)
    private String discordUserId;
    
    @Column(name = "username", length = 128)
    private String username;
    
    @Column(name = "status", length = 16)
    @Enumerated(EnumType.STRING)
    private ItemStatus status = ItemStatus.PENDING;
    
    @Column(name = "assigned_agent_id", length = 256)
    private String assignedAgentId;
    
    @Column(name = "emulator_index")
    private Integer emulatorIndex;
    
    @Column(name = "last_result", length = 512)
    private String lastResult;
    
    @Column(name = "assigned_at")
    private Instant assignedAt;
    
    @Column(name = "completed_at")
    private Instant completedAt;
    
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
    
    public enum ItemStatus {
        PENDING, PROCESSING, SUCCESS, FAILED, SKIPPED
    }
}
