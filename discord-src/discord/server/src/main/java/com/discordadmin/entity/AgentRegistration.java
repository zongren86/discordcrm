package com.discordadmin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_registrations", indexes = {
    @Index(name = "idx_agent_user_id", columnList = "user_id"),
    @Index(name = "idx_agent_device_id", columnList = "device_id"),
    @Index(name = "idx_agent_status", columnList = "status"),
    @Index(name = "idx_agent_merchant_id", columnList = "merchant_id"),
    @Index(name = "idx_agent_user_device", columnList = "user_id, device_id")
})
@Getter
@Setter
public class AgentRegistration {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "merchant_id")
    private Long merchantId;
    
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;
    
    @Column(name = "device_id", nullable = false, length = 256)
    private String deviceId;
    
    @Column(name = "os", length = 64)
    private String os;
    
    @Column(name = "os_version", length = 128)
    private String osVersion;
    
    @Column(name = "mumu_path", length = 512)
    private String muMuPath;
    
    @Column(name = "mumu_player_running")
    private Boolean mumuPlayerRunning = false;
    
    @Column(name = "emulator_count")
    private Integer emulatorCount = 0;
    
    @Column(name = "running_emulator_count")
    private Integer runningEmulatorCount = 0;
    
    @Column(name = "status", length = 16)
    private String status = "ONLINE"; // ONLINE, OFFLINE
    
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;
    
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
