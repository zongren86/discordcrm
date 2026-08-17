package com.mumu.agent.controller;

import com.mumu.agent.model.EmulatorInfo;
import com.mumu.agent.service.EmulatorService;
import com.mumu.agent.service.DiscordAutomationService;
import com.mumu.agent.service.ApkCacheService;
import com.mumu.agent.service.BatchOperationService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/local")
public class LocalController {
    
    private final EmulatorService emulatorService;
    private final DiscordAutomationService discordService;
    private final ApkCacheService apkCacheService;
    private final BatchOperationService batchService;
    
    public LocalController(EmulatorService emulatorService,
                          DiscordAutomationService discordService,
                          ApkCacheService apkCacheService,
                          BatchOperationService batchService) {
        this.emulatorService = emulatorService;
        this.discordService = discordService;
        this.apkCacheService = apkCacheService;
        this.batchService = batchService;
    }
    
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("muMuPath", emulatorService.getMuMuPath());
        status.put("muMuAvailable", emulatorService.isMuMuAvailable());
        status.put("emulatorCount", emulatorService.getEmulatorCount());
        status.put("runningCount", emulatorService.getRunningCount());
        status.put("emulators", emulatorService.getEmulatorList());
        status.put("apkCached", apkCacheService.isApkCached());
        status.put("apkVersion", apkCacheService.getApkVersion());
        
        // 添加自动加好友状态
        Map<String, Object> autoAddStates = new HashMap<>();
        for (EmulatorInfo emu : emulatorService.getEmulatorList()) {
            if (batchService.isAutoAddRunning(emu.getIndex())) {
                autoAddStates.put(String.valueOf(emu.getIndex()), batchService.getAutoAddState(emu.getIndex()));
            }
        }
        status.put("autoAddStates", autoAddStates);
        
        return status;
    }
    
    // ==================== 单个模拟器操作 ====================
    
    @PostMapping("/emulators/create")
    public Map<String, String> createEmulator(@RequestBody Map<String, Integer> body) {
        int index = body.get("index");
        String result = emulatorService.createEmulator(index);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/start")
    public Map<String, String> startEmulator(@PathVariable int index) {
        String result = emulatorService.startEmulator(index);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/stop")
    public Map<String, String> stopEmulator(@PathVariable int index) {
        String result = emulatorService.stopEmulator(index);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/delete")
    public Map<String, String> deleteEmulator(@PathVariable int index) {
        String result = emulatorService.deleteEmulator(index);
        return Map.of("result", result);
    }
    
    @GetMapping("/emulators/{index}")
    public EmulatorInfo getEmulator(@PathVariable int index) {
        return emulatorService.getEmulator(index);
    }
    
    @PostMapping("/emulators/{index}/install-apk")
    public Map<String, String> installApk(@PathVariable int index, 
                                           @RequestBody Map<String, String> body) {
        String apkUrl = body.get("apkUrl");
        String result = discordService.installDiscord(index, apkUrl);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/launch-discord")
    public Map<String, String> launchDiscord(@PathVariable int index) {
        String result = discordService.launchDiscord(index);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/add-friend")
    public Map<String, String> addFriend(@PathVariable int index,
                                          @RequestBody Map<String, String> body) {
        String username = body.get("username");
        String result = discordService.addFriendByUsername(index, username);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/start-auto-add")
    public Map<String, String> startAutoAdd(@PathVariable int index,
                                             @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> usernames = (List<String>) body.get("usernames");
        String taskId = (String) body.get("taskId");
        String result = batchService.startAutoAdd(index, usernames, taskId);
        return Map.of("result", result);
    }
    
    @PostMapping("/emulators/{index}/stop-auto-add")
    public Map<String, String> stopAutoAdd(@PathVariable int index) {
        String result = batchService.stopAutoAdd(index);
        return Map.of("result", result);
    }
    
    @GetMapping("/emulators/{index}/auto-add-status")
    public Map<String, Object> getAutoAddStatus(@PathVariable int index) {
        Map<String, Object> status = new HashMap<>();
        status.put("running", batchService.isAutoAddRunning(index));
        if (batchService.isAutoAddRunning(index)) {
            status.put("state", batchService.getAutoAddState(index));
        }
        return status;
    }
    
    @GetMapping("/emulators/{index}/discord-status")
    public Map<String, Object> getDiscordStatus(@PathVariable int index) {
        Map<String, Object> status = new HashMap<>();
        status.put("foreground", discordService.isDiscordForeground(index));
        status.put("loggedIn", discordService.isDiscordLoggedIn(index));
        status.put("onLoginPage", discordService.isOnLoginPage(index));
        status.put("username", discordService.getLoggedInUser(index));
        status.put("installed", apkCacheService.checkDiscordInstalled(
            emulatorService.getAdbPort(index)));
        return status;
    }
    
    // ==================== 批量操作 ====================
    
    @PostMapping("/batch/start")
    public Map<String, Object> batchStart(@RequestBody Map<String, List<Integer>> body) {
        return batchService.batchStart(body.get("indices"));
    }
    
    @PostMapping("/batch/stop")
    public Map<String, Object> batchStop(@RequestBody Map<String, List<Integer>> body) {
        return batchService.batchStop(body.get("indices"));
    }
    
    @PostMapping("/batch/restart")
    public Map<String, Object> batchRestart(@RequestBody Map<String, List<Integer>> body) {
        return batchService.batchRestart(body.get("indices"));
    }
    
    @PostMapping("/batch/delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, List<Integer>> body) {
        return batchService.batchDelete(body.get("indices"));
    }
    
    @PostMapping("/batch/install-apk")
    public Map<String, Object> batchInstallApk(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> indices = (List<Integer>) body.get("indices");
        String apkUrl = (String) body.get("apkUrl");
        return batchService.batchInstallApk(indices, apkUrl);
    }
    
    @PostMapping("/batch/start-auto-add")
    public Map<String, Object> batchStartAutoAdd(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> indices = (List<Integer>) body.get("indices");
        @SuppressWarnings("unchecked")
        List<String> usernames = (List<String>) body.get("usernames");
        String taskId = (String) body.get("taskId");
        return batchService.batchStartAutoAdd(indices, usernames, taskId);
    }
    
    @PostMapping("/batch/stop-auto-add")
    public Map<String, Object> batchStopAutoAdd(@RequestBody Map<String, List<Integer>> body) {
        return batchService.batchStopAutoAdd(body.get("indices"));
    }
    
    // ==================== APK 管理 ====================
    
    @GetMapping("/apk/cached")
    public Map<String, Object> getApkInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("cached", apkCacheService.isApkCached());
        info.put("version", apkCacheService.getApkVersion());
        info.put("path", apkCacheService.getLatestApkPath());
        return info;
    }
    
    @PostMapping("/apk/download")
    public Map<String, String> downloadApk(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        String path = apkCacheService.downloadAndCache(url);
        if (path != null) {
            return Map.of("status", "SUCCESS", "path", path);
        }
        return Map.of("status", "FAILED", "message", "下载失败");
    }
}
