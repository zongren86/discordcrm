package com.discordadmin.service;

import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import com.discordadmin.security.SecurityUtils;
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
    private final AgentAccountNumberRelRepository relRepository;
    private final DiscordAccountNumberRepository numberRepository;
    private final EmuAccountBindingRepository accountBindingRepository;

    public EmuServerBindingService(EmuServerBindingRepository bindingRepository,
                                    GuildServerRepository serverRepository,
                                    DiscordAccountRepository accountRepository,
                                    OccupancyCheckService occupancyCheckService,
                                    AgentAccountNumberRelRepository relRepository,
                                    DiscordAccountNumberRepository numberRepository,
                                    EmuAccountBindingRepository accountBindingRepository) {
        this.bindingRepository = bindingRepository;
        this.serverRepository = serverRepository;
        this.accountRepository = accountRepository;
        this.occupancyCheckService = occupancyCheckService;
        this.relRepository = relRepository;
        this.numberRepository = numberRepository;
        this.accountBindingRepository = accountBindingRepository;
    }

    /**
     * 获取商户已添加的服务器列表
     */
    public List<Map<String, Object>> getAddedServers(Long merchantId, Long userId) {
        List<EmuServerBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        List<Map<String, Object>> result = new ArrayList<>();

        // 批量查询账号信息
        Set<Long> accountIds = bindings.stream()
            .map(EmuServerBinding::getDiscordAccountId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, DiscordAccount> accountMap = new HashMap<>();
        if (!accountIds.isEmpty()) {
            accountRepository.findByIdIn(new ArrayList<>(accountIds)).forEach(a -> accountMap.put(a.getId(), a));
        }

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
            DiscordAccount account = accountMap.get(binding.getDiscordAccountId());
            if (account != null) {
                item.put("accountName", account.getName());
                item.put("accountEmail", account.getEmail());
                item.put("discordName", account.getDiscordName());
            }

            result.add(item);
        }
        return result;
    }

    /**
     * 获取可用的服务器列表
     * 返回所有未添加的服务器，账号筛选变为可选的过滤条件
     */
    public List<Map<String, Object>> getAvailableServers(Long merchantId, Long userId, 
                                                          String keyword, Long accountId) {
        String role = SecurityUtils.currentRole();
        
        // 获取商户已添加的服务器ID
        List<EmuServerBinding> bindings = bindingRepository.findByMerchantId(merchantId);
        Set<Long> addedServerIds = bindings.stream()
            .map(EmuServerBinding::getServerId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // 获取服务器（按角色/商户过滤，防止跨商户）
        List<GuildServer> servers;
        if (accountId != null) {
            // 如果指定了账号ID，只获取该账号的服务器
            servers = serverRepository.findByDiscordAccountId(accountId);
        } else if ("PLATFORM_ADMIN".equals(role)) {
            // 平台管理员：可看全部
            servers = serverRepository.findAll();
        } else {
            // 商户管理员/普通用户：只看本商户下的服务器（按 merchantId 或 该商户 DiscordAccount 关联的）
            servers = serverRepository.findByMerchantId(merchantId);
        }

        // 获取可用的Discord账号ID列表（用于显示账号信息）
        Set<Long> availableAccountIds = getAvailableAccountIds(merchantId, userId, role);
        if (accountId != null) {
            availableAccountIds = new HashSet<>();
            availableAccountIds.add(accountId);
        }

        // 批量查询账号信息
        Map<Long, DiscordAccount> accountMap = new HashMap<>();
        if (!availableAccountIds.isEmpty()) {
            accountRepository.findByIdIn(new ArrayList<>(availableAccountIds)).forEach(a -> accountMap.put(a.getId(), a));
        }

        // 也查询所有服务器关联的账号信息
        Set<Long> serverAccountIds = servers.stream()
            .map(GuildServer::getDiscordAccountId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (!serverAccountIds.isEmpty()) {
            accountRepository.findByIdIn(new ArrayList<>(serverAccountIds)).forEach(a -> accountMap.put(a.getId(), a));
        }

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

                // 获取关联账号信息
                DiscordAccount account = accountMap.get(server.getDiscordAccountId());
                if (account != null) {
                    item.put("accountName", account.getName());
                    item.put("accountEmail", account.getEmail());
                    item.put("discordName", account.getDiscordName());
                }

                return item;
            })
            .collect(Collectors.toList());
    }

    /**
     * 获取当前用户可用的Discord账号ID集合
     */
    private Set<Long> getAvailableAccountIds(Long merchantId, Long userId, String role) {
        Set<Long> accountIds = new HashSet<>();
        
        if ("MERCHANT_ADMIN".equals(role) || userId == null) {
            // 商户管理员或默认用户：获取商户已添加的账号
            List<EmuAccountBinding> accountBindings = accountBindingRepository.findByMerchantId(merchantId);
            accountIds = accountBindings.stream()
                .map(EmuAccountBinding::getDiscordAccountId)
                .collect(Collectors.toSet());
        } else {
            // 普通用户：获取其关联账号中已添加的账号
            List<AgentAccountNumberRel> rels = relRepository.findByAgentId(userId);
            Set<Long> numberIds = rels.stream()
                .map(AgentAccountNumberRel::getAccountNumberId)
                .collect(Collectors.toSet());
            
            List<DiscordAccountNumber> numbers = numberRepository.findByIdIn(new ArrayList<>(numberIds));
            Set<Long> discordAccountIds = numbers.stream()
                .map(DiscordAccountNumber::getDiscordAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            
            // 只保留已添加的账号
            List<EmuAccountBinding> accountBindings = accountBindingRepository.findByMerchantId(merchantId);
            Set<Long> addedAccountIds = accountBindings.stream()
                .map(EmuAccountBinding::getDiscordAccountId)
                .collect(Collectors.toSet());
            
            accountIds = discordAccountIds.stream()
                .filter(addedAccountIds::contains)
                .collect(Collectors.toSet());
        }
        
        return accountIds;
    }

    /**
     * 添加服务器绑定
     */
    @Transactional
    public EmuServerBinding addServer(Long merchantId, Long userId, Long serverId, Long discordAccountId) {
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
