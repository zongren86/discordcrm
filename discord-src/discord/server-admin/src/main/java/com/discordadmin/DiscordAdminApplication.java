package com.discordadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class DiscordAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiscordAdminApplication.class, args);
    }
}
