package com.mumu.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {
    
    private String cloudWebsocketUrl = "ws://localhost:8090/ws/agent";
    private int localHttpPort = 8089;
    private String deviceId;
    private String userId;
    private int heartbeatInterval = 30;
}
