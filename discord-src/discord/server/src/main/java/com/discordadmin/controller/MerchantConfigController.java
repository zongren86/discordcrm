package com.discordadmin.controller;

import com.discordadmin.entity.MerchantConfig;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.MerchantConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/merchant-config")
public class MerchantConfigController {

    private final MerchantConfigService merchantConfigService;

    public MerchantConfigController(MerchantConfigService merchantConfigService) {
        this.merchantConfigService = merchantConfigService;
    }

    @GetMapping
    public Map<String, Object> getConfig() {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (merchantId == null) {
            return toDefaultMap();
        }
        MerchantConfig config = merchantConfigService.getOrCreateConfig(merchantId);
        return toMap(config);
    }

    @PutMapping
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> payload) {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (merchantId == null) {
            return toDefaultMap();
        }
        MerchantConfig config = new MerchantConfig();
        
        if (payload.containsKey("fetchLimit")) {
            config.setFetchLimit(((Number) payload.get("fetchLimit")).intValue());
        }
        if (payload.containsKey("requestInterval")) {
            config.setRequestInterval(Integer.valueOf(payload.get("requestInterval").toString()));
        }
        if (payload.containsKey("requestCount")) {
            config.setRequestCount(Integer.valueOf(payload.get("requestCount").toString()));
        }
        if (payload.containsKey("maxDepth")) {
            config.setMaxDepth(Integer.valueOf(payload.get("maxDepth").toString()));
        }
        if (payload.containsKey("maxRequests")) {
            config.setMaxRequests(Integer.valueOf(payload.get("maxRequests").toString()));
        }
        if (payload.containsKey("archiveDays")) {
            config.setArchiveDays(Integer.valueOf(payload.get("archiveDays").toString()));
        }
        if (payload.containsKey("maxUsers")) {
            config.setMaxUsers(Integer.valueOf(payload.get("maxUsers").toString()));
        }
        if (payload.containsKey("maxLinkedAccounts")) {
            config.setMaxLinkedAccounts(Integer.valueOf(payload.get("maxLinkedAccounts").toString()));
        }

        MerchantConfig updated = merchantConfigService.updateConfig(merchantId, config);
        return toMap(updated);
    }

    @GetMapping("/{merchantId}")
    public Map<String, Object> getConfigByMerchantId(@PathVariable Long merchantId) {
        if (!SecurityUtils.isPlatformAdmin()) {
            throw new IllegalStateException("仅平台管理员可访问");
        }
        MerchantConfig config = merchantConfigService.getOrCreateConfig(merchantId);
        return toMap(config);
    }

    @PutMapping("/{merchantId}")
    public Map<String, Object> updateConfigByMerchantId(
            @PathVariable Long merchantId,
            @RequestBody Map<String, Object> payload) {
        if (!SecurityUtils.isPlatformAdmin()) {
            throw new IllegalStateException("仅平台管理员可修改");
        }
        MerchantConfig config = new MerchantConfig();
        
        if (payload.containsKey("fetchLimit")) {
            config.setFetchLimit(((Number) payload.get("fetchLimit")).intValue());
        }
        if (payload.containsKey("requestInterval")) {
            config.setRequestInterval(Integer.valueOf(payload.get("requestInterval").toString()));
        }
        if (payload.containsKey("requestCount")) {
            config.setRequestCount(Integer.valueOf(payload.get("requestCount").toString()));
        }
        if (payload.containsKey("maxDepth")) {
            config.setMaxDepth(Integer.valueOf(payload.get("maxDepth").toString()));
        }
        if (payload.containsKey("maxRequests")) {
            config.setMaxRequests(Integer.valueOf(payload.get("maxRequests").toString()));
        }
        if (payload.containsKey("archiveDays")) {
            config.setArchiveDays(Integer.valueOf(payload.get("archiveDays").toString()));
        }
        if (payload.containsKey("maxUsers")) {
            config.setMaxUsers(Integer.valueOf(payload.get("maxUsers").toString()));
        }
        if (payload.containsKey("maxLinkedAccounts")) {
            config.setMaxLinkedAccounts(Integer.valueOf(payload.get("maxLinkedAccounts").toString()));
        }

        MerchantConfig updated = merchantConfigService.updateConfig(merchantId, config);
        return toMap(updated);
    }

    private Map<String, Object> toMap(MerchantConfig config) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", config.getId());
        map.put("merchantId", config.getMerchantId());
        map.put("fetchLimit", config.getFetchLimit());
        map.put("requestInterval", config.getRequestInterval());
        map.put("requestCount", config.getRequestCount());
        map.put("maxDepth", config.getMaxDepth());
        map.put("maxRequests", config.getMaxRequests());
        map.put("archiveDays", config.getArchiveDays());
        map.put("maxUsers", config.getMaxUsers());
        map.put("maxLinkedAccounts", config.getMaxLinkedAccounts());
        map.put("updatedAt", config.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toDefaultMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", null);
        map.put("merchantId", null);
        map.put("fetchLimit", 10000);
        map.put("requestInterval", 3);
        map.put("requestCount", 100);
        map.put("maxDepth", 5);
        map.put("maxRequests", 1000);
        map.put("archiveDays", 30);
        map.put("maxUsers", 10);
        map.put("maxLinkedAccounts", 20);
        return map;
    }
}