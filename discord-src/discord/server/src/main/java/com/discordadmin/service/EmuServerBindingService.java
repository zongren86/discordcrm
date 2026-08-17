package com.discordadmin.service;

import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EmuServerBindingService {

    private final EmuServerBindingRepository bindingRepository;
    private final GuildServerRepository serverRepository;
    private final DiscordAccountRepository accountRepository;
    private final OccupancyCheckService occupancyCheckService;

    public EmuServerBindingService(EmuServerBindingRepository bindingRepository,
                                    GuildServerRepository serverRepository,
                                    DiscordAccountRepository accountRepository,
                                    OccupancyCheckService occupancyCheckService) {
        this.bindingRepository = bindingRepository;
        this.serverRepository = serverRepository;
        this.accountRepository = accountRepository;
        this.occupancyCheckService = occupancyCheckService;
    }

    /**
     * 获取商户已添加的服务器列表
     */
    public List<Map<String, Object>> getAddedServers(Long merchantId, String userId) {
        List<EmuServerBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (EmuServerBinding binding : bindings) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", binding.getId());
            item.put("serverId", binding.getServerId());
            item.put("discordAccountId", binding.getDiscordAccountId());
            item.put("status", binding.getStatus().name());
            item.put("statusText", getStatusText(binding.getStatus()));
            item.put("serverName", binding.getServerName());
            item.put("memberCount", binding.getMemberCount());
            item.put("lastSyncAt", binding.getLastSyncAt());
            item.put("createdAt", binding.getCreatedAt());

            // 获取账号信息
            if (binding.getDiscordAccountId() != null) {
                accountRepository.findById(binding.getDiscordAccountId()).ifPresent(account -> {
                    item.put("accountName", account.getName());
                    item.put("accountEmail", account.getEmail());
                });
            }

            result.add(item);
        }
        return result;
    }

    /**
     * 获取可用的服务器列表（未被当前商户添加）
     */
    public List<Map<String, Object>> getAvailableServers(Long merchantId, String userId, String keyword) {
        // 获取商户已添加的服务器ID
        List<EmuServerBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        Set<Long> addedServerIds = bindings.stream()
            .map(EmuServerBinding::getServerId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // 查询所有服务器
        List<GuildServer> servers = serverRepository.findAll();

        return servers.stream()
            .filter(s -> !addedServerIds.contains(s.getId()))
            .filter(s -> keyword == null || keyword.isEmpty() ||
                (s.getName() != null && s.getName().toLowerCase().contains(keyword.toLowerCase())) ||
                (s.getGuildId() != null && s.getGuildId().toLowerCase().contains(keyword.toLowerCase())))
            .map(server -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", server.getId());
                item.put("serverId", server.getId());
                item.put("guildId", server.getGuildId());
                item.put("name", server.getName());
                item.put("memberCount", server.getMemberCount());
                item.put("discordAccountId", server.getDiscordAccountId());
                item.put("status", server.getStatus());
                item.put("canAdd", true);

                // 检查是否可以添加（关联的账号是否被占用）
                if (server.getDiscordAccountId() != null) {
                    item.put("accountOccupied", occupancyCheckService.isDiscordAccountOccupied(server.getDiscordAccountId()));
                }

                return item;
            })
            .collect(Collectors.toList());
    }

    /**
     * 添加服务器绑定
     */
    @Transactional
    public EmuServerBinding addServer(Long merchantId, String userId, Long serverId, Long discordAccountId) {
        // 检查服务器是否存在
        GuildServer server = serverRepository.findById(serverId)
            .orElseThrow(() -> new RuntimeException("服务器不存在"));

        // 检查是否已添加
        boolean exists = bindingRepository.findByMerchantId(merchantId).stream()
            .anyMatch(b -> b.getServerId() != null && b.getServerId().equals(serverId));
        if (exists) {
            throw new RuntimeException("该服务器已添加");
        }

        // 检查账号是否被占用
        if (discordAccountId != null && occupancyCheckService.isDiscordAccountOccupied(discordAccountId)) {
            throw new RuntimeException("关联的账号已被其他进行中的任务占用");
        }

        EmuServerBinding binding = new EmuServerBinding();
        binding.setMerchantId(merchantId);
        binding.setUserId(userId);
        binding.setServerId(serverId);
        binding.setGuildId(server.getGuildId());
        binding.setServerName(server.getName());
        binding.setMemberCount(server.getMemberCount());
        binding.setDiscordAccountId(discordAccountId);
        binding.setStatus(EmuServerBinding.BindingStatus.ADDED);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());

        return bindingRepository.save(binding);
    }

    /**
     * 移除服务器绑定
     */
    @Transactional
    public void removeServer(Long bindingId) {
        bindingRepository.deleteById(bindingId);
    }

    /**
     * 获取状态文本
     */
    private String getStatusText(EmuServerBinding.BindingStatus status) {
        return switch (status) {
            case PENDING -> "待添加";
            case ADDED -> "已添加";
            case OCCUPIED -> "已占用";
        };
    }
}
