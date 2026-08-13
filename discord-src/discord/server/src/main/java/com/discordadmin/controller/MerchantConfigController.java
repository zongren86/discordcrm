package com.discordadmin.controller;

import com.discordadmin.entity.MerchantConfig;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.GuildService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/merchant-config")
public class MerchantConfigController {

    private final GuildService guildService;

    public MerchantConfigController(GuildService guildService) {
        this.guildService = guildService;
    }

    /** 获取当前商户的配置 */
    @GetMapping
    public Map<String, Object> getConfig() {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (merchantId == null) {
            return toDefaultMap();
        }
        MerchantConfig config = guildService.getOrCreateConfig(merchantId);
        return toMap(config);
    }

    /** 更新商户配置 */
    @PutMapping
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> payload) {
        Long merchantId = SecurityUtils.currentMerchantId();
        if (merchantId == null) {
            return toDefaultMap();
        }
        MerchantConfig config = new MerchantConfig();
        
        if (payload.containsKey("fetchLimit")) {
            config.setFetchLimit(Integer.valueOf(payload.get("fetchLimit").toString()));
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

        MerchantConfig updated = guildService.updateConfig(merchantId, config);
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
        map.put("updatedAt", config.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toDefaultMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", null);
        map.put("merchantId", null);
        map.put("fetchLimit", 2000000);
        map.put("requestInterval", 3);
        map.put("requestCount", 100);
        map.put("maxDepth", 5);
        map.put("maxRequests", 1000);
        map.put("archiveDays", 30);
        return map;
    }
}
