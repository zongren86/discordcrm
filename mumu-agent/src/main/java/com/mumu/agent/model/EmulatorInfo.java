package com.mumu.agent.model;

import lombok.Data;
import java.time.Instant;

@Data
public class EmulatorInfo {
    
    private int index;
    private int adbPort;
    private String status; // CREATED, RUNNING, STOPPED, ERROR
    private String discordInstalled; // true, false
    private String discordLoggedIn; // true, false
    private String discordUsername;
    private String lastError;
    private Instant createdAt;
    private Instant lastStartedAt;
}
