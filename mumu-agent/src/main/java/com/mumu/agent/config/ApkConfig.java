package com.mumu.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "apk")
public class ApkConfig {
    
    private String cacheDir = System.getProperty("user.home") + "/.mumu-agent/cache/apk";
    private int downloadTimeout = 300;
}
