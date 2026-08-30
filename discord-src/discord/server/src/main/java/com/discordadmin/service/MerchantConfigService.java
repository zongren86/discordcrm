package com.discordadmin.service;

import com.discordadmin.entity.MerchantConfig;
import com.discordadmin.repository.MerchantConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 商户配置 Service — 从原 GuildService 拆分出来，只保留 MerchantConfig 相关方法
 * 原 GuildService 的 GuildServer/GuildMember/成员抓取逻辑已移到 server-admin 子项目
 */
@Service
public class MerchantConfigService {

    private final MerchantConfigRepository merchantConfigRepository;

    public MerchantConfigService(MerchantConfigRepository merchantConfigRepository) {
        this.merchantConfigRepository = merchantConfigRepository;
    }

    public MerchantConfig getOrCreateConfig(Long merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户ID不能为空");
        }
        return merchantConfigRepository.findByMerchantId(merchantId)
                .orElseGet(() -> {
                    MerchantConfig config = new MerchantConfig();
                    config.setMerchantId(merchantId);
                    return merchantConfigRepository.save(config);
                });
    }

    @Transactional
    public MerchantConfig updateConfig(Long merchantId, MerchantConfig config) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户ID不能为空");
        }
        MerchantConfig existing = getOrCreateConfig(merchantId);
        if (config.getFetchLimit() != null) existing.setFetchLimit(config.getFetchLimit());
        if (config.getRequestInterval() != null) existing.setRequestInterval(config.getRequestInterval());
        if (config.getRequestCount() != null) existing.setRequestCount(config.getRequestCount());
        if (config.getMaxDepth() != null) existing.setMaxDepth(config.getMaxDepth());
        if (config.getMaxRequests() != null) existing.setMaxRequests(config.getMaxRequests());
        if (config.getArchiveDays() != null) existing.setArchiveDays(config.getArchiveDays());
        if (config.getMaxUsers() != null) existing.setMaxUsers(config.getMaxUsers());
        if (config.getMaxLinkedAccounts() != null) existing.setMaxLinkedAccounts(config.getMaxLinkedAccounts());
        existing.setUpdatedAt(Instant.now());
        return merchantConfigRepository.save(existing);
    }
}
