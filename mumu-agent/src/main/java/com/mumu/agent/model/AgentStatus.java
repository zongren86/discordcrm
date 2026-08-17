package com.mumu.agent.model;

import lombok.Data;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentStatus {
    
    private String deviceId;
    private String userId;
    private String os;
    private String osVersion;
    private String muMuPath;
    private boolean muMuAvailable;
    private int emulatorCount;
    private int runningCount;
    private String apkVersion;
    private boolean apkCached;
    private Instant lastHeartbeat;
    private List<EmulatorInfo> emulators = new ArrayList<>();
}
