package com.discordadmin.controller;

import com.discordadmin.entity.EmuAccountBinding;
import com.discordadmin.entity.EmuFriendPool;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟器相关API - 账号管理、服务器管理、好友号池
 */
@RestController
@RequestMapping("/api/emu")
public class EmuManagementController {

    private final EmuAccountBindingService accountBindingService;
    private final EmuServerBindingService serverBindingService;
    private final EmuFriendPoolService friendPoolService;
    private final GuildServerService guildServerService;

    public EmuManagementController(EmuAccountBindingService accountBindingService,
                                    EmuServerBindingService serverBindingService,
                                    EmuFriendPoolService friendPoolService,
                                    GuildServerService guildServerService) {
        this.accountBindingService = accountBindingService;
        this.serverBindingService = serverBindingService;
        this.friendPoolService = friendPoolService;
        this.guildServerService = guildServerService;
    }

    // ========== 账号管理 ==========

    /**
     * 获取已添加的账号列表
     */
    @GetMapping("/accounts/added")
    public List<Map<String, Object>> getAddedAccounts() {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        String userId = SecurityUtils.getCurrentUserId();
        return accountBindingService.getAddedAccounts(merchantId, userId);
    }

    /**
     * 获取可用的账号列表（可添加的）
     */
    @GetMapping("/accounts/available")
    public List<Map<String, Object>> getAvailableAccounts(@RequestParam(required = false) String keyword) {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        String userId = SecurityUtils.getCurrentUserId();
        return accountBindingService.getAvailableAccounts(merchantId, userId, keyword);
    }

    /**
     * 添加账号
     */
    @PostMapping("/accounts/add")
    public Map<String, Object> addAccount(@RequestBody Map<String, Long> body) {
        Long discordAccountId = body.get("discordAccountId");
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        String userId = SecurityUtils.getCurrentUserId();

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
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        String userId = SecurityUtils.getCurrentUserId();
        return serverBindingService.getAddedServers(merchantId, userId);
    }

    /**
     * 获取可用的服务器列表（可添加的）
     */
    @GetMapping("/servers/available")
    public List<Map<String, Object>> getAvailableServers(@RequestParam(required = false) String keyword) {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        String userId = SecurityUtils.getCurrentUserId();
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
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        String userId = SecurityUtils.getCurrentUserId();

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
        Long merchantId = SecurityUtils.getCurrentMerchantId();
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
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        return friendPoolService.getFriendPool(merchantId, status);
    }

    /**
     * 获取好友号池统计
     */
    @GetMapping("/friend-pool/stats")
    public Map<String, Object> getFriendPoolStats() {
        Long merchantId = SecurityUtils.getCurrentMerchantId();
        return friendPoolService.getFriendPoolStats(merchantId);
    }

    /**
     * 分配好友给任务
     */
    @PostMapping("/friend-pool/assign")
    public Map<String, Object> assignFriendsToTask(@RequestBody Map<String, Object> body) {
        Long taskId = Long.valueOf(body.get("taskId").toString());
        int count = Integer.parseInt(body.get("count").toString());
        Long merchantId = SecurityUtils.getCurrentMerchantId();

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
