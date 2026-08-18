package com.discordadmin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class MumuAutoAddProxyService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${mumu.manager.url:http://localhost:8088}")
    private String mumuManagerUrl;

    public Map<String, Object> startAutoAdd(int index) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/autoadd/" + index + "/start",
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("启动自动加好友失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> stopAutoAdd(int index) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/autoadd/" + index + "/stop",
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("停止自动加好友失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> startAllAutoAdd() {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/autoadd/startAll",
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("启动全部自动加好友失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> stopAllAutoAdd() {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/autoadd/stopAll",
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("停止全部自动加好友失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> installDiscord(int index) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/discord/install/" + index,
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("安装Discord失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> downloadDiscordApk() {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/discord/download",
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("下载Discord APK失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> getApkStatus() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    mumuManagerUrl + "/api/discord/apk-status",
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("获取APK状态失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public Map<String, Object> installDiscordOnAll() {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    mumuManagerUrl + "/api/discord/installAll",
                    null,
                    Map.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("批量安装Discord失败: {}", e.getMessage());
            return Map.of("status", "ERROR: " + e.getMessage());
        }
    }

    public boolean isReachable() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    mumuManagerUrl + "/api/emulators",
                    String.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
