package com.discordadmin.service;

import com.discordadmin.model.EmulatorInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MumuAutoAddProxyService {

    private final EmulatorService emulatorService;
    private final DiscordService discordService;
    private final MumuClientService mumuClientService;
    private final AutoAddService autoAddService;

    public MumuAutoAddProxyService(EmulatorService emulatorService,
                                   DiscordService discordService,
                                   MumuClientService mumuClientService,
                                   AutoAddService autoAddService) {
        this.emulatorService = emulatorService;
        this.discordService = discordService;
        this.mumuClientService = mumuClientService;
        this.autoAddService = autoAddService;
    }

    private int toMuMuIndex(int instanceIndex) {
        return instanceIndex - 1;
    }

    public Map<String, Object> startAutoAdd(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            int muMuIndex = toMuMuIndex(index);
            String res = autoAddService.start(muMuIndex);
            if ("SUCCESS".equals(res)) {
                result.put("status", "SUCCESS");
                result.put("message", "自动加好友已启动");
            } else {
                result.put("status", "ERROR");
                result.put("message", res);
            }
        } catch (Exception e) {
            log.error("启动自动加好友失败: {}", e.getMessage());
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> stopAutoAdd(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            int muMuIndex = toMuMuIndex(index);
            autoAddService.stop(muMuIndex);
            result.put("status", "SUCCESS");
            result.put("message", "自动加好友已停止");
        } catch (Exception e) {
            log.error("停止自动加好友失败: {}", e.getMessage());
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> installDiscord(int index) {
        return mumuClientService.installDiscord(index);
    }

    public Map<String, Object> downloadDiscordApk() {
        Map<String, Object> result = new HashMap<>();
        try {
            CompletableFuture<String> future = discordService.downloadApk();
            String status = future.get(300, TimeUnit.SECONDS);
            if ("SUCCESS".equals(status)) {
                result.put("status", "SUCCESS");
                result.put("message", "APK下载成功");
            } else {
                result.put("status", "ERROR: " + status);
            }
        } catch (Exception e) {
            log.error("下载Discord APK失败: {}", e.getMessage());
            result.put("status", "ERROR: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getApkStatus() {
        Map<String, Object> result = new HashMap<>();
        boolean downloaded = discordService.isApkDownloaded();
        result.put("downloaded", downloaded);
        result.put("status", downloaded ? "READY" : "NOT_DOWNLOADED");
        return result;
    }

    public Map<String, Object> installDiscordOnAll() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<EmulatorInfo> emus = emulatorService.getAllEmulators();
            int successCount = 0;
            for (EmulatorInfo emu : emus) {
                try {
                    Map<String, Object> r = mumuClientService.installDiscord(emu.getIndex() + 1);
                    if (Boolean.TRUE.equals(r.get("success"))) {
                        successCount++;
                    }
                } catch (Exception e) {
                    log.warn("模拟器{} 安装Discord失败: {}", emu.getIndex(), e.getMessage());
                }
            }
            result.put("status", "SUCCESS");
            result.put("message", "已安装 " + successCount + "/" + emus.size() + " 个模拟器的 Discord");
        } catch (Exception e) {
            log.error("批量安装Discord失败: {}", e.getMessage());
            result.put("status", "ERROR: " + e.getMessage());
        }
        return result;
    }

    public boolean isReachable() {
        return mumuClientService.isReachable();
    }
}
