package com.discordadmin.service;

import com.discordadmin.model.EmulatorInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MumuClientService {

    private final EmulatorService emulatorService;
    private final DiscordService discordService;

    public MumuClientService(EmulatorService emulatorService, DiscordService discordService) {
        this.emulatorService = emulatorService;
        this.discordService = discordService;
    }

    /**
     * 将后台 1-based 索引转换为 Mumu 0-based 索引
     */
    private int toMuMuIndex(int instanceIndex) {
        return instanceIndex - 1;
    }

    private Map<String, Object> toMap(EmulatorInfo info) {
        if (info == null) return new HashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", info.getIndex());
        m.put("name", info.getName());
        m.put("status", info.getStatus());
        m.put("adbPort", info.getAdbPort());
        m.put("androidPort", info.getAndroidPort());
        m.put("frontendPort", info.getFrontendPort());
        m.put("discordInstalled", info.isDiscordInstalled());
        m.put("discordLoggedIn", info.isDiscordLoggedIn());
        m.put("damaged", info.isDamaged());
        m.put("damageReason", info.getDamageReason());
        m.put("lastError", info.getLastError());
        m.put("screenshot", info.getScreenshot());
        m.put("cpuCount", info.getCpuCount());
        m.put("memoryMB", info.getMemoryMB());
        m.put("resolution", info.getResolution());
        m.put("discordAccount", info.getDiscordAccount());
        m.put("discordActualUser", info.getDiscordActualUser());
        m.put("discordLoginFailed", info.isDiscordLoginFailed());
        m.put("discordLoginError", info.getDiscordLoginError());
        m.put("addedCount", info.getAddedCount());
        m.put("nextAddAt", info.getNextAddAt());
        m.put("autoRunning", info.isAutoRunning());
        m.put("autoLastResult", info.getAutoLastResult());
        return m;
    }

    private List<Map<String, Object>> toMapList(List<EmulatorInfo> list) {
        if (list == null) return new ArrayList<>();
        return list.stream().map(this::toMap).collect(Collectors.toList());
    }

    /**
     * 检查 Mumu 服务是否可达
     */
    public boolean isReachable() {
        try {
            List<EmulatorInfo> emus = emulatorService.getAllEmulators();
            return emus != null;
        } catch (Exception e) {
            log.debug("Mumu 不可达: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定索引的模拟器是否存在
     */
    public boolean emulatorExists(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            EmulatorInfo info = emulatorService.getEmulator(muMuIndex);
            return info != null;
        } catch (Exception e) {
            log.warn("检查模拟器 #{} 是否存在失败: {}", index, e.getMessage());
            return false;
        }
    }

    /**
     * 获取所有模拟器列表（带错误信息）
     */
    public List<Map<String, Object>> getAllEmulatorsWithError() {
        try {
            List<EmulatorInfo> emus = emulatorService.getAllEmulators();
            return toMapList(emus);
        } catch (Exception e) {
            throw new RuntimeException("无法获取模拟器列表: " + e.getMessage());
        }
    }

    /**
     * 设置模拟器数量
     */
    public List<Map<String, Object>> setEmulatorCount(int count, int cpuCores, int memoryGb) {
        try {
            int memoryMB = memoryGb * 1024;
            List<EmulatorInfo> emus = emulatorService.ensureEmulatorCount(count, cpuCores, memoryMB);
            return toMapList(emus);
        } catch (Exception e) {
            log.error("设置模拟器数量失败: {}", e.getMessage());
            throw new RuntimeException("设置模拟器数量失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有模拟器列表
     */
    public List<Map<String, Object>> getAllEmulators() {
        try {
            List<EmulatorInfo> emus = emulatorService.getAllEmulators();
            return toMapList(emus);
        } catch (Exception e) {
            log.error("获取模拟器列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 启动指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> startEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            EmulatorInfo info = emulatorService.startEmulator(muMuIndex);
            return toMap(info);
        } catch (Exception e) {
            log.error("启动模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 停止指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> stopEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            EmulatorInfo info = emulatorService.stopEmulator(muMuIndex);
            return toMap(info);
        } catch (Exception e) {
            log.error("停止模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 重启指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> restartEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            EmulatorInfo info = emulatorService.restartEmulator(muMuIndex);
            return toMap(info);
        } catch (Exception e) {
            log.error("重启模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 删除指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> deleteEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            boolean success = emulatorService.deleteEmulator(muMuIndex);
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            return result;
        } catch (Exception e) {
            log.error("删除模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 启动所有模拟器
     */
    public List<Map<String, Object>> startAllEmulators(Integer count) {
        try {
            if (count != null && count > 0) {
                emulatorService.setActiveCount(count);
            }
            List<EmulatorInfo> emus = emulatorService.startAllEmulators();
            return toMapList(emus);
        } catch (Exception e) {
            log.error("启动所有模拟器失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 停止所有模拟器
     */
    public List<Map<String, Object>> stopAllEmulators() {
        try {
            List<EmulatorInfo> emus = emulatorService.stopAllEmulators();
            return toMapList(emus);
        } catch (Exception e) {
            log.error("停止所有模拟器失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 在指定模拟器安装 Discord
     */
    public Map<String, Object> installDiscord(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            String result = discordService.installDiscord(muMuIndex).get(180, java.util.concurrent.TimeUnit.SECONDS);
            Map<String, Object> r = new HashMap<>();
            r.put("success", "SUCCESS".equals(result));
            r.put("message", result);
            return r;
        } catch (Exception e) {
            log.error("安装 Discord #{} 失败: {}", index, e.getMessage());
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "安装失败: " + e.getMessage());
            return r;
        }
    }

    /**
     * 启动 Discord
     */
    public Map<String, Object> launchDiscord(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            String result = discordService.launchDiscord(muMuIndex);
            Map<String, Object> r = new HashMap<>();
            r.put("success", "SUCCESS".equals(result));
            r.put("message", result);
            return r;
        } catch (Exception e) {
            log.error("启动 Discord #{} 失败: {}", index, e.getMessage());
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "启动失败: " + e.getMessage());
            return r;
        }
    }

    /**
     * 上传 APK 文件
     */
    public Map<String, Object> uploadApk(byte[] fileData, String filename) {
        try {
            java.nio.file.Path apkFile = java.nio.file.Paths.get(
                System.getProperty("user.home") + "/.discord-admin/discord.apk");
            java.nio.file.Files.createDirectories(apkFile.getParent());
            java.nio.file.Files.write(apkFile, fileData);
            Map<String, Object> r = new HashMap<>();
            r.put("success", true);
            r.put("message", "APK 上传成功");
            return r;
        } catch (Exception e) {
            log.error("上传 APK 失败: {}", e.getMessage());
            Map<String, Object> r = new HashMap<>();
            r.put("success", false);
            r.put("message", "上传失败: " + e.getMessage());
            return r;
        }
    }

    /**
     * 检查 APK 状态
     */
    public Map<String, Boolean> checkApkStatus() {
        try {
            boolean downloaded = discordService.isApkDownloaded();
            Map<String, Boolean> r = new HashMap<>();
            r.put("downloaded", downloaded);
            return r;
        } catch (Exception e) {
            log.error("检查 APK 状态失败: {}", e.getMessage());
            Map<String, Boolean> r = new HashMap<>();
            r.put("downloaded", false);
            return r;
        }
    }
}
