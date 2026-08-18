package com.mumu.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "mumu")
public class MuMuConfig {
    
    private String path;
    private String managerUrl = "http://localhost:8088";
    private int adbPortStart = 16384;
    private int maxInstances = 200;
}
