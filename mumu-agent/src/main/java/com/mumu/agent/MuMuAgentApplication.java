package com.mumu.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MuMuAgentApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(MuMuAgentApplication.class, args);
    }
}
