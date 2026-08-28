package com.discordadmin.controller;

import com.discordadmin.discord.member.DiscordMemberService;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.entity.MerchantConfig;
import com.discordadmin.repository.AgentAccountNumberRelRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.DiscordAccountNumberRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.GuildMemberRepository;
import com.discordadmin.repository.GuildServerRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.GuildService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/guild-servers")
public class GuildServerController {

    private final GuildService guildService;
    private final DiscordAccountRepository accountRepository;
    private final GuildServerRepository guildServerRepository;
    private final DiscordMemberService discordMemberService;
    private final AgentRepository agentRepository;
    private final AgentAccountNumberRelRepository relRepository;
    private final DiscordAccountNumberRepository accountNumberRepository;
    private final GuildMemberRepository guildMemberRepository;

    public GuildServerController(GuildService guildService,
                                 DiscordAccountRepository accountRepository,
                                 GuildServerRepository guildServerRepository,
                                 DiscordMemberService discordMemberService,
                                 AgentRepository agentRepository,
                                 AgentAccountNumberRelRepository relRepository,
                                 DiscordAccountNumberRepository accountNumberRepository,
                                 GuildMemberRepository guildMemberRepository) {
        this.guildService = guildService;
        this.accountRepository = accountRepository;
        this.guildServerRepository = guildServerRepository;
        this.discordMemberService = discordMemberService;
        this.agentRepository = agentRepository;
        this.relRepository = relRepository;
        this.accountNumberRepository = accountNumberRepository;
        this.guildMemberRepository = guildMemberRepository;
    }

    @GetMapping
    public List<Map<String, Object>> listServers(
            @RequestParam(required = false) Long discordAccountId) {
        Long merchantId = SecurityUtils.currentMerchantId();
        String role = SecurityUtils.currentRole();
        Long currentAgentId = SecurityUtils.currentAgentId();

        // 平台管理员可以查看所有商户的服务器，不按merchantId过滤
        // DB 二次校验：防止 JWT merchantId 丢失导致商户管理员被误判为平台管理员（跨商户泄漏）
        if (SecurityUtils.isPlatformAdmin()) {
            if (currentAgentId != null) {
                Agent dbAgent = agentRepository.findById(currentAgentId).orElse(null);
                if (dbAgent != null && dbAgent.getMerchantId() != null) {
                    // DB 里商户ID有值 → JWT 丢失了 merchantId，用 DB 值按商户隔离
                    merchantId = dbAgent.getMerchantId();
                }
                // else: DB 里 merchant_id 也为 null → 真平台管理员，merchantId 保持 null
            }
        }

        List<GuildServer> servers;

        // 普通用户：只能看到分配给自己账号的服务器
        if (!SecurityUtils.isPlatformAdmin() && !SecurityUtils.isMerchantAdmin() && currentAgentId != null) {
            Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
            if (assignedAccountIds.isEmpty()) {
                return List.of();
            }
            if (discordAccountId != null && !assignedAccountIds.contains(discordAccountId)) {
                return List.of();
            }
            List<Long> accountIdList = new ArrayList<>(assignedAccountIds);
            if (merchantId != null) {
                servers = guildServerRepository.findByMerchantIdAndDiscordAccountIdIn(merchantId, accountIdList);
            } else {
                servers = guildServerRepository.findByDiscordAccountIdIn(accountIdList);
            }
        } else if (SecurityUtils.isPlatformAdmin() && merchantId == null) {
            // 真平台管理员：可以查看所有商户的服务器
            if (discordAccountId != null) {
                servers = guildServerRepository.findByDiscordAccountId(discordAccountId);
            } else {
                servers = guildServerRepository.findAll();
            }
        } else {
            servers = guildService.listGuildServers(merchantId, discordAccountId);
        }
        
        // 批量获取账号信息，避免 N+1 查询
        Set<Long> accountIds = servers.stream()
            .map(GuildServer::getDiscordAccountId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        
        Map<Long, com.discordadmin.entity.DiscordAccount> accountMap = new HashMap<>();
        if (!accountIds.isEmpty()) {
            accountRepository.findByIdIn(new ArrayList<>(accountIds))
                .forEach(acc -> accountMap.put(acc.getId(), acc));
        }
        
        // 批量查询每个服务器的 EXCLUDED (friend_status=4) 数量 — 一次 SQL, 避免 N+1
        List<Long> serverIds = servers.stream().map(GuildServer::getId).filter(Objects::nonNull).collect(Collectors.toList());
        Map<Long, Long> excludedCountMap = new HashMap<>();
        if (!serverIds.isEmpty()) {
            List<Object[]> rows = guildMemberRepository.countExcludedRawByServerIds(serverIds);
            for (Object[] row : rows) {
                excludedCountMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }

        final Map<Long, Long> finalExcludedCountMap = excludedCountMap;
        return servers.stream()
            .map(server -> toServerMap(server, accountMap, finalExcludedCountMap))
            .collect(Collectors.toList());
    }

    /**
     * 账号下拉选项列表（用于筛选条件和新增服务器时选择账号）。
     * 独立于服务器数据，保证尚未关联服务器的账号也能被选择。
     */
    @GetMapping("/discord-accounts")
    public List<Map<String, Object>> listAccountOptions() {
        List<DiscordAccount> accounts;

        Long merchantId = SecurityUtils.currentMerchantId();
        Long currentAgentId = SecurityUtils.currentAgentId();

        if (SecurityUtils.isPlatformAdmin()) {
            // DB 二次校验：防止 JWT merchantId 丢失导致商户管理员被误判为平台管理员
            if (currentAgentId != null) {
                Agent dbAgent = agentRepository.findById(currentAgentId).orElse(null);
                if (dbAgent != null && dbAgent.getMerchantId() != null) {
                    // DB 里商户ID有值 → JWT 丢失了 merchantId，按商户隔离
                    merchantId = dbAgent.getMerchantId();
                    accounts = accountRepository.findByMerchantIdOrNull(merchantId);
                } else {
                    // 真平台管理员：所有账号
                    accounts = accountRepository.findAll();
                }
            } else {
                // 真平台管理员：所有账号
                accounts = accountRepository.findAll();
            }
        } else if (!SecurityUtils.isMerchantAdmin() && currentAgentId != null) {
            // 普通用户：仅自己被分配的账号
            Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
            accounts = assignedAccountIds.isEmpty()
                    ? List.of()
                    : accountRepository.findByIdIn(new ArrayList<>(assignedAccountIds));
        } else if (merchantId != null) {
            // 商户管理员：只能看到本商户账号
            accounts = accountRepository.findByMerchantId(merchantId);
        } else if (SecurityUtils.isPlatformAdmin()) {
            // 真平台管理员：可以查看所有账号
            accounts = accountRepository.findAll();
        } else {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "当前用户未绑定商户");
        }

        return accounts.stream().map(this::toAccountOptionMap).collect(Collectors.toList());
    }

    private Map<String, Object> toAccountOptionMap(DiscordAccount acc) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", acc.getId());
        map.put("name", acc.getName());
        map.put("discordName", acc.getDiscordName());
        map.put("discordId", acc.getDiscordId());
        return map;
    }

    @PostMapping
    public Map<String, Object> saveServer(@RequestBody Map<String, Object> payload) {
        Long merchantId = SecurityUtils.currentMerchantId();

        Long discordAccountId = Long.valueOf(payload.get("discordAccountId").toString());

        // 校验 discordAccountId 必须属于当前商户
        com.discordadmin.entity.DiscordAccount accountForCheck = accountRepository.findById(discordAccountId).orElse(null);
        if (accountForCheck == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "关联账号不存在");
        }
        Long accountMerchantId = accountForCheck.getMerchantId();
        if (!SecurityUtils.isPlatformAdmin() && merchantId != null
            && accountMerchantId != null && !merchantId.equals(accountMerchantId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "无权使用该账号");
        }
        String guildId = payload.containsKey("guildId") && payload.get("guildId") != null
                        ? payload.get("guildId").toString()
                        : null;
        Long excludeId = payload.containsKey("id") && payload.get("id") != null
                        ? Long.valueOf(payload.get("id").toString())
                        : null;

        if (guildId != null && !guildId.isEmpty()) {
            boolean exists;
            if (excludeId != null) {
                exists = guildServerRepository.findByDiscordAccountIdAndGuildIdAndIdNot(
                    discordAccountId, guildId, excludeId).isPresent();
            } else {
                exists = guildServerRepository.existsByDiscordAccountIdAndGuildId(
                    discordAccountId, guildId);
            }
            if (exists) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "同一账号（ID:" + discordAccountId + "）下已存在相同服务器（Guild ID:" + guildId + "）的配置");
                error.put("message", "该账号已添加过此服务器，请勿重复添加");
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "同一账号同一服务器只能创建一次");
            }
        }

        GuildServer server = new GuildServer();

        // DB 二次校验：防止 JWT merchantId 丢失导致商户管理员被误判为平台管理员
        Long currentAgentIdForSave = SecurityUtils.currentAgentId();
        if (merchantId == null && currentAgentIdForSave != null) {
            Agent dbAgent = agentRepository.findById(currentAgentIdForSave).orElse(null);
            if (dbAgent != null && dbAgent.getMerchantId() != null) {
                merchantId = dbAgent.getMerchantId();
            }
        }

        // 更新时校验：现有服务器必须属于当前商户（或平台管理员）
        if (excludeId != null) {
            GuildServer existing = guildServerRepository.findById(excludeId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "服务器不存在"));
            if (!SecurityUtils.isPlatformAdmin() && merchantId != null
                && !merchantId.equals(existing.getMerchantId())) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无权修改此服务器");
            }
            server = existing;
        }

        if (payload.containsKey("id") && payload.get("id") != null) {
            server.setId(excludeId);
        }

        // 严格按当前登录用户的 merchantId 设置，禁止任何回退
        if (merchantId == null) {
            if (SecurityUtils.isPlatformAdmin()) {
                // 平台管理员：从账号获取 merchantId
                if (accountForCheck != null && accountForCheck.getMerchantId() != null) {
                    merchantId = accountForCheck.getMerchantId();
                } else {
                    throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "账号未绑定商户，无法创建服务器");
                }
            } else {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "当前用户未绑定商户，无法添加服务器");
            }
        }
        Long serverMerchantId = merchantId;
        server.setMerchantId(serverMerchantId);
        server.setDiscordAccountId(discordAccountId);

        if (payload.containsKey("guildId") && payload.get("guildId") != null) {
            server.setGuildId(payload.get("guildId").toString());
        }
        if (payload.containsKey("channelId") && payload.get("channelId") != null) {
            server.setChannelId(payload.get("channelId").toString());
        }
        if (payload.containsKey("guildUrl") && payload.get("guildUrl") != null) {
            server.setGuildUrl(payload.get("guildUrl").toString());
        }
        if (payload.containsKey("name") && payload.get("name") != null) {
            server.setName(payload.get("name").toString());
        }
        if (payload.containsKey("iconUrl") && payload.get("iconUrl") != null) {
            server.setIconUrl(payload.get("iconUrl").toString());
        }

        GuildServer saved = guildService.saveGuildServer(server);
        return toServerMap(saved);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteServer(@PathVariable Long id) {
        checkServerAccess(id);
        guildService.deleteGuildServer(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/{id}/members")
    public Map<String, Object> listMembers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer friendStatus,
            @RequestParam(required = false) String discordStatus) {
        checkServerAccess(id);
        Page<GuildMember> memberPage = guildService.listMembersPaginated(id, keyword, friendStatus, discordStatus, page, size);
        List<Map<String, Object>> members = memberPage.getContent().stream().map(this::toMemberMap).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("members", members);
        result.put("total", memberPage.getTotalElements());
        result.put("totalPages", memberPage.getTotalPages());
        result.put("currentPage", memberPage.getNumber());
        result.put("size", memberPage.getSize());
        return result;
    }

    @GetMapping("/{id}/members/count")
    public Map<String, Object> countMembers(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword) {
        checkServerAccess(id);
        long count = guildService.countMembers(id, keyword);
        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        return result;
    }

    @PostMapping("/{id}/match-friends")
    public Map<String, Object> matchFriends(@PathVariable Long id) {
        checkServerAccess(id);
        GuildServer server = guildServerRepository.findById(id)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "服务器不存在"));

        long matchedCount = discordMemberService.matchAllFriendsToServer(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("matchedCount", matchedCount);
        result.put("serverName", server.getName());
        return result;
    }

    @GetMapping("/merchant-config")
    public Map<String, Object> getMerchantConfig() {
        Long merchantId = SecurityUtils.currentMerchantId();
        Long currentAgentId = SecurityUtils.currentAgentId();

        // DB 二次校验：防止 JWT merchantId 丢失导致商户管理员被误判为平台管理员
        if (merchantId == null && currentAgentId != null) {
            Agent dbAgent = agentRepository.findById(currentAgentId).orElse(null);
            if (dbAgent != null && dbAgent.getMerchantId() != null) {
                merchantId = dbAgent.getMerchantId();
            }
        }

        Map<String, Object> map = new HashMap<>();

        if (merchantId != null) {
            MerchantConfig config = guildService.getOrCreateConfig(merchantId);
            map.put("id", config.getId());
            map.put("merchantId", config.getMerchantId());
            map.put("fetchLimit", config.getFetchLimit());
            map.put("requestInterval", config.getRequestInterval());
            map.put("requestCount", config.getRequestCount());
            map.put("maxDepth", config.getMaxDepth());
            map.put("maxRequests", config.getMaxRequests());
            map.put("archiveDays", config.getArchiveDays());
        } else {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "当前用户未绑定商户");
        }
        return map;
    }

    private void checkServerAccess(Long serverId) {
        // DB 二次校验：防止 JWT merchantId 丢失导致商户管理员被误判为平台管理员（跨商户泄漏）
        Long merchantId = SecurityUtils.currentMerchantId();
        Long currentAgentId = SecurityUtils.currentAgentId();

        if (SecurityUtils.isPlatformAdmin()) {
            if (currentAgentId != null) {
                Agent dbAgent = agentRepository.findById(currentAgentId).orElse(null);
                if (dbAgent != null && dbAgent.getMerchantId() != null) {
                    // DB 里商户ID有值 → JWT 丢失了 merchantId，用 DB 值按商户隔离
                    merchantId = dbAgent.getMerchantId();
                }
                // else: DB 里 merchant_id 也为 null → 真平台管理员，直接放行
                if (merchantId == null) {
                    return;
                }
            } else {
                return;
            }
        }

        if (merchantId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "无访问权限");
        }
        GuildServer server = guildServerRepository.findById(serverId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "服务器不存在"));
        if (!merchantId.equals(server.getMerchantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "无权访问此服务器");
        }
    }

    private Map<String, Object> toServerMap(GuildServer server) {
        // 保留旧方法兼容（单个服务器保存时调用）
        Map<Long, com.discordadmin.entity.DiscordAccount> accountMap = new HashMap<>();
        if (server.getDiscordAccountId() != null) {
            accountRepository.findById(server.getDiscordAccountId())
                .ifPresent(acc -> accountMap.put(acc.getId(), acc));
        }
        return toServerMap(server, accountMap);
    }

    private Map<String, Object> toServerMap(GuildServer server, Map<Long, com.discordadmin.entity.DiscordAccount> accountMap) {
        return toServerMap(server, accountMap, java.util.Collections.emptyMap());
    }

    private Map<String, Object> toServerMap(GuildServer server, Map<Long, com.discordadmin.entity.DiscordAccount> accountMap, Map<Long, Long> excludedCountMap) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", server.getId());
        map.put("merchantId", server.getMerchantId());
        map.put("discordAccountId", server.getDiscordAccountId());
        map.put("guildId", server.getGuildId());
        map.put("channelId", server.getChannelId());
        map.put("guildUrl", server.getGuildUrl());
        map.put("name", server.getName());
        map.put("iconUrl", server.getIconUrl());
        map.put("memberCount", server.getMemberCount());

        // 排除数 + 占比
        long excluded = excludedCountMap.getOrDefault(server.getId(), 0L);
        map.put("excludedCount", excluded);
        long total = server.getMemberCount() != null ? server.getMemberCount() : 0;
        double ratio = total > 0 ? Math.round(excluded * 10000.0 / total) / 100.0 : 0;
        map.put("excludedRatio", ratio);  // 百分比, 如 12.34

        map.put("lastFetchAt", server.getLastFetchAt());
        map.put("status", server.getStatus());
        map.put("statusText", getStatusTextZh(server.getStatus()));
        map.put("createdAt", server.getCreatedAt());

        com.discordadmin.entity.DiscordAccount acc = accountMap.get(server.getDiscordAccountId());
        if (acc != null) {
            map.put("accountName", acc.getName());
            map.put("accountDiscordName", acc.getDiscordName());
            map.put("accountDiscordId", acc.getDiscordId());
            map.put("accountToken", acc.getToken());
        } else {
            map.put("accountName", "未知账号");
        }

        return map;
    }

    /**
     * 状态中文映射
     */
    private String getStatusTextZh(String status) {
        if (status == null) return "未知";
        return switch (status.toUpperCase()) {
            case "ACTIVE" -> "活跃";
            case "INACTIVE" -> "未激活";
            case "FETCHING" -> "获取中";
            case "ERROR" -> "错误";
            case "DELETED" -> "已删除";
            default -> status;
        };
    }

    private Map<String, Object> toMemberMap(GuildMember member) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", member.getId());
        map.put("guildServerId", member.getGuildServerId());
        map.put("userId", member.getUserId());
        map.put("username", member.getUsername());
        map.put("nick", member.getNick());
        map.put("globalName", member.getGlobalName());
        map.put("displayName", member.getDisplayName());
        map.put("avatarUrl", member.getAvatarUrl());
        map.put("isBot", member.getIsBot());
        map.put("joinedAt", member.getJoinedAt());
        map.put("roles", member.getRoles());
        map.put("lastFetchedAt", member.getLastFetchedAt());
        
        // Discord 原生状态字段
        map.put("discordStatus", member.getDiscordStatus());
        
        // 添加好友池状态字段
        Integer friendStatus = member.getFriendStatus();
        map.put("friendStatus", friendStatus);
        
        // 状态文字映射为中文
        String friendStatusText;
        if (friendStatus == null || friendStatus == 0) {
            friendStatusText = "待添加";
        } else if (friendStatus == 1) {
            friendStatusText = "已分配";
        } else if (friendStatus == 2) {
            friendStatusText = "添加成功";
        } else if (friendStatus == 3) {
            friendStatusText = "添加失败";
        } else {
            friendStatusText = "未知";
        }
        map.put("friendStatusText", friendStatusText);
        
        // 其他好友池相关字段
        map.put("discordAccountId", member.getDiscordAccountId());
        map.put("assignedTaskId", member.getAssignedTaskId());
        map.put("emulatorIndex", member.getEmulatorIndex());
        map.put("lastError", member.getLastError());
        map.put("startedAt", member.getStartedAt());
        map.put("finishedAt", member.getFinishedAt());
        map.put("retryCount", member.getRetryCount());
        map.put("updatedAt", member.getUpdatedAt());
        
        return map;
    }

    /**
     * 统计跨服务器重复成员
     */
    @GetMapping("/duplicates/count")
    public Map<String, Object> countCrossServerDuplicates() {
        return guildService.countCrossServerDuplicates();
    }

    /**
     * 清理跨服务器重复成员（保留最早采集的，删除其余）
     */
    @PostMapping("/duplicates/clean")
    public Map<String, Object> cleanCrossServerDuplicates() {
        return guildService.cleanCrossServerDuplicates();
    }

    /** 获取当前用户有权限的账号ID列表（用于权限过滤） */
    private Set<Long> getAssignedAccountIds(Long currentAgentId) {
        Set<Long> assignedAccountIds = new HashSet<>();

        Optional<Agent> agentOpt = agentRepository.findById(currentAgentId);
        if (agentOpt.isEmpty()) {
            return assignedAccountIds;
        }

        Agent agent = agentOpt.get();

        // 1. 直接关联的账号（agent_discord_accounts）
        assignedAccountIds.addAll(
                agent.getDiscordAccounts().stream()
                        .map(DiscordAccount::getId)
                        .collect(Collectors.toSet()));

        // 2. 通过编号链路关联的账号（AgentAccountNumberRel → DiscordAccountNumber → DiscordAccount）
        List<Long> assignedNumberIds = relRepository.findAccountNumberIdsByAgentId(currentAgentId);
        if (!assignedNumberIds.isEmpty()) {
            List<DiscordAccountNumber> numbers = accountNumberRepository.findByIdIn(assignedNumberIds);
            for (DiscordAccountNumber num : numbers) {
                if (num.getDiscordAccountId() != null) {
                    assignedAccountIds.add(num.getDiscordAccountId());
                }
            }
        }

        return assignedAccountIds;
    }
}
