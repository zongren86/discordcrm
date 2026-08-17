package com.discordadmin.controller;

import com.discordadmin.entity.EmuAccountBinding;
import com.discordadmin.entity.EmuFriendPool;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟器相关API - 账号管理、服务器管理、好友号池、实例管理、APK管理
 */
@RestController
@RequestMapping("/api/emu")
public class EmuManagementController {

    private final EmuAccountBindingService accountBindingService;
    private final EmuServerBindingService serverBindingService;
    private final EmuFriendPoolService friendPoolService;
    private final EmuInstanceService instanceService;
    private final ApkManagementService apkManagementService;

    public EmuManagementController(EmuAccountBindingService accountBindingService,
                                    EmuServerBindingService serverBindingService,
                                    EmuFriendPoolService friendPoolService,
                                    EmuInstanceService instanceService,
                                    ApkManagementService apkManagementService) {
        this.accountBindingService = accountBindingService;
        this.serverBindingService = serverBindingService;
        this.friendPoolService = friendPoolService;
        this.instanceService = instanceService;
        this.apkManagementService = apkManagementService;
    }

    // ========== APK 管理 ==========

    /**
     * 检查APK状态
     */
    @GetMapping("/discord/apk-status")
    public Map<String, Object> checkApkStatus() {
        return apkManagementService.checkApkStatus();
    }

    /**
     * 上传APK
     */
    @PostMapping("/discord/upload")
    public Map<String, Object> uploadApk(@RequestParam("file") MultipartFile file) {
        try {
            return apkManagementService.uploadApk(file);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 下载APK（模拟）
     */
    @PostMapping("/discord/download")
    public Map<String, Object> downloadApk() {
        return apkManagementService.downloadLatestApk();
    }

    // ========== 实例管理 ==========

    /**
     * 获取当前用户的所有模拟器实例
     */
    @GetMapping("/emulators")
    public List<Map<String, Object>> getEmulators() {
        return instanceService.getCurrentUserInstances();
    }

    /**
     * 设置模拟器数量
     */
    @PostMapping("/emulators/count")
    public List<Map<String, Object>> setEmulatorCount(@RequestBody Map<String, Object> body) {
        int count = Integer.parseInt(body.get("count").toString());
        int cpuCores = body.containsKey("cpuCores") ? Integer.parseInt(body.get("cpuCores").toString()) : 1;
        int memoryGb = body.containsKey("memoryGb") ? Integer.parseInt(body.get("memoryGb").toString()) : 1;
        return instanceService.setInstanceCount(count, cpuCores, memoryGb);
    }

    /**
     * 启动模拟器
     */
    @PostMapping("/emulators/{index}/start")
    public Map<String, Object> startEmulator(@PathVariable int index) {
        return instanceService.startInstance(index);
    }

    /**
     * 停止模拟器
     */
    @PostMapping("/emulators/{index}/stop")
    public Map<String, Object> stopEmulator(@PathVariable int index) {
        return instanceService.stopInstance(index);
    }

    /**
     * 重启模拟器
     */
    @PostMapping("/emulators/{index}/restart")
    public Map<String, Object> restartEmulator(@PathVariable int index) {
        return instanceService.restartInstance(index);
    }

    /**
     * 启动所有模拟器
     */
    @PostMapping("/emulators/startAll")
    public List<Map<String, Object>> startAllEmulators() {
        return instanceService.startAllInstances();
    }

    /**
     * 停止所有模拟器
     */
    @PostMapping("/emulators/stopAll")
    public List<Map<String, Object>> stopAllEmulators() {
        return instanceService.stopAllInstances();
    }

    /**
     * 删除模拟器
     */
    @DeleteMapping("/emulators/{index}")
    public Map<String, Object> deleteEmulator(@PathVariable int index) {
        instanceService.deleteInstance(index);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    // ========== Discord 控制 ==========

    /**
     * 安装Discord到模拟器
     */
    @PostMapping("/discord/install/{index}")
    public Map<String, Object> installDiscord(@PathVariable int index) {
        return instanceService.installDiscord(index);
    }

    /**
     * 安装Discord到所有模拟器
     */
    @PostMapping("/discord/installAll")
    public Map<String, Object> installAllDiscord() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        var instances = instanceService.getCurrentUserInstances();
        
        int successCount = 0;
        StringBuilder errors = new StringBuilder();
        
        for (var inst : instances) {
            try {
                instanceService.installDiscord((Integer) inst.get("index"));
                successCount++;
            } catch (Exception e) {
                if (errors.length() > 0) errors.append("; ");
                errors.append("#").append(inst.get("index")).append(" ").append(e.getMessage());
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", instances.size() - successCount);
        if (errors.length() > 0) {
            result.put("errors", errors.toString());
        }
        return result;
    }

    /**
     * 启动Discord
     */
    @PostMapping("/discord/launch/{index}")
    public Map<String, Object> launchDiscord(@PathVariable int index) {
        return instanceService.launchDiscord(index);
    }

    /**
     * 更新Discord首页状态（由模拟器调用）
     */
    @PostMapping("/discord/home-status/{index}")
    public Map<String, Object> updateDiscordHomeStatus(@PathVariable int index,
                                                       @RequestBody Map<String, Boolean> body) {
        boolean onHome = body.get("onHome");
        return instanceService.updateDiscordHomeStatus(index, onHome);
    }

    /**
     * 更新Discord登录状态（由模拟器调用）
     */
    @PostMapping("/discord/login-status/{index}")
    public Map<String, Object> updateDiscordLoginStatus(@PathVariable int index,
                                                        @RequestBody Map<String, Boolean> body) {
        boolean loggedIn = body.get("loggedIn");
        return instanceService.updateDiscordLoginStatus(index, loggedIn);
    }

    // ========== 自动加好友 ==========

    /**
     * 启动自动加好友
     */
    @PostMapping("/autoadd/{index}/start")
    public Map<String, Object> startAutoAdd(@PathVariable int index) {
        return instanceService.startAutoAdd(index);
    }

    /**
     * 停止自动加好友
     */
    @PostMapping("/autoadd/{index}/stop")
    public Map<String, Object> stopAutoAdd(@PathVariable int index) {
        return instanceService.stopAutoAdd(index);
    }

    /**
     * 全部启动自动加好友
     */
    @PostMapping("/autoadd/startAll")
    public List<Map<String, Object>> startAllAutoAdd() {
        return instanceService.startAllAutoAdd();
    }

    /**
     * 全部停止自动加好友
     */
    @PostMapping("/autoadd/stopAll")
    public List<Map<String, Object>> stopAllAutoAdd() {
        return instanceService.stopAllAutoAdd();
    }

    /**
     * 保存自动加好友配置
     */
    @PostMapping("/data/autoconfig")
    public Map<String, Object> saveAutoConfig(@RequestBody Map<String, Object> config) {
        // 存储配置到商户级别的配置中
        // 这里简化处理，实际应该使用MerchantConfig
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 获取自动加好友配置
     */
    @GetMapping("/data/autoconfig")
    public Map<String, Object> getAutoConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("intervalSeconds", 900);
        result.put("delayMinSeconds", 60);
        result.put("delayMaxSeconds", 800);
        return result;
    }

    // ========== 账号管理 ==========

    /**
     * 获取已添加的账号列表
     */
    @GetMapping("/accounts/added")
    public List<Map<String, Object>> getAddedAccounts() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        return accountBindingService.getAddedAccounts(merchantId, userId);
    }

    /**
     * 获取可用的账号列表（可添加的）
     */
    @GetMapping("/accounts/available")
    public List<Map<String, Object>> getAvailableAccounts(@RequestParam(required = false) String keyword) {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        return accountBindingService.getAvailableAccounts(merchantId, userId, keyword);
    }

    /**
     * 添加账号
     */
    @PostMapping("/accounts/add")
    public Map<String, Object> addAccount(@RequestBody Map<String, Long> body) {
        Long discordAccountId = body.get("discordAccountId");
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        EmuAccountBinding binding = accountBindingService.addAccount(merchantId, userId, discordAccountId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", binding.getId());
        result.put("success", true);
        return result;
    }

    /**
     * 移除账号
     */
    @DeleteMapping("/accounts/{bindingId}")
    public Map<String, Object> removeAccount(@PathVariable Long bindingId) {
        accountBindingService.removeAccount(bindingId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    // ========== 服务器管理 ==========

    /**
     * 获取已添加的服务器列表
     */
    @GetMapping("/servers/added")
    public List<Map<String, Object>> getAddedServers() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        return serverBindingService.getAddedServers(merchantId, userId);
    }

    /**
     * 获取可用的服务器列表（可添加的）
     */
    @GetMapping("/servers/available")
    public List<Map<String, Object>> getAvailableServers(@RequestParam(required = false) String keyword) {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        return serverBindingService.getAvailableServers(merchantId, userId, keyword);
    }

    /**
     * 添加服务器
     */
    @PostMapping("/servers/add")
    public Map<String, Object> addServer(@RequestBody Map<String, Object> body) {
        Long serverId = Long.valueOf(body.get("serverId").toString());
        Long discordAccountId = body.containsKey("discordAccountId") && body.get("discordAccountId") != null 
            ? Long.valueOf(body.get("discordAccountId").toString()) : null;
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();

        EmuServerBinding binding = serverBindingService.addServer(merchantId, userId, serverId, discordAccountId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", binding.getId());
        result.put("success", true);
        return result;
    }

    /**
     * 移除服务器
     */
    @DeleteMapping("/servers/{bindingId}")
    public Map<String, Object> removeServer(@PathVariable Long bindingId) {
        serverBindingService.removeServer(bindingId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 从服务器同步成员到好友号池
     */
    @PostMapping("/servers/{serverId}/sync-friends")
    public Map<String, Object> syncFriendsFromServer(@PathVariable Long serverId) {
        Long merchantId = SecurityUtils.currentMerchantId();
        int count = friendPoolService.syncFriendsFromServer(merchantId, serverId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("addedCount", count);
        result.put("success", true);
        return result;
    }

    // ========== 好友号池 ==========

    /**
     * 获取好友号池
     */
    @GetMapping("/friend-pool")
    public List<Map<String, Object>> getFriendPool(@RequestParam(required = false) String status) {
        Long merchantId = SecurityUtils.currentMerchantId();
        return friendPoolService.getFriendPool(merchantId, status);
    }

    /**
     * 获取好友号池统计
     */
    @GetMapping("/friend-pool/stats")
    public Map<String, Object> getFriendPoolStats() {
        Long merchantId = SecurityUtils.currentMerchantId();
        return friendPoolService.getFriendPoolStats(merchantId);
    }

    /**
     * 分配好友给任务
     */
    @PostMapping("/friend-pool/assign")
    public Map<String, Object> assignFriendsToTask(@RequestBody Map<String, Object> body) {
        Long taskId = Long.valueOf(body.get("taskId").toString());
        int count = Integer.parseInt(body.get("count").toString());
        Long merchantId = SecurityUtils.currentMerchantId();

        List<EmuFriendPool> assigned = friendPoolService.assignFriendsToTask(merchantId, taskId, count);
        
        Map<String, Object> result = new HashMap<>();
        result.put("assignedCount", assigned.size());
        result.put("success", true);
        return result;
    }

    /**
     * 更新好友添加结果
     */
    @PutMapping("/friend-pool/{friendId}/result")
    public Map<String, Object> updateFriendResult(@PathVariable Long friendId, 
                                                   @RequestBody Map<String, String> body) {
        String status = body.get("status");
        String error = body.get("error");
        
        EmuFriendPool.FriendStatus friendStatus = EmuFriendPool.FriendStatus.valueOf(status);
        friendPoolService.updateFriendResult(friendId, friendStatus, error);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }
}
