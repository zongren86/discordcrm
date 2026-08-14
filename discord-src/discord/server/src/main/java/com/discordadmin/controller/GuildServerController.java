package com.discordadmin.controller;

import com.discordadmin.discord.member.DiscordMemberService;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.entity.MerchantConfig;
import com.discordadmin.repository.DiscordAccountRepository;
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

    public GuildServerController(GuildService guildService,
                                 DiscordAccountRepository accountRepository,
                                 GuildServerRepository guildServerRepository,
                                 DiscordMemberService discordMemberService) {
        this.guildService = guildService;
        this.accountRepository = accountRepository;
        this.guildServerRepository = guildServerRepository;
        this.discordMemberService = discordMemberService;
    }

    @GetMapping
    public List<Map<String, Object>> listServers(
            @RequestParam(required = false) Long discordAccountId) {
        Long merchantId = SecurityUtils.currentMerchantId();
        // 平台管理员可以查看所有商户的服务器，不按merchantId过滤
        if (SecurityUtils.isPlatformAdmin()) {
            merchantId = null;
        }
        List<GuildServer> servers = guildService.listGuildServers(merchantId, discordAccountId);
        
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
        
        return servers.stream()
            .map(server -> toServerMap(server, accountMap))
            .collect(Collectors.toList());
    }

    @PostMapping
    public Map<String, Object> saveServer(@RequestBody Map<String, Object> payload) {
        Long merchantId = SecurityUtils.currentMerchantId();

        Long discordAccountId = Long.valueOf(payload.get("discordAccountId").toString());
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
        if (payload.containsKey("id") && payload.get("id") != null) {
            server.setId(excludeId);
        }

        Long serverMerchantId = merchantId;

        if (serverMerchantId == null && payload.containsKey("discordAccountId")) {
            Long accountId = Long.valueOf(payload.get("discordAccountId").toString());
            com.discordadmin.entity.DiscordAccount account = accountRepository.findById(accountId).orElse(null);
            if (account != null && account.getMerchantId() != null) {
                serverMerchantId = account.getMerchantId();
            }
        }

        if (serverMerchantId == null && payload.containsKey("merchantId") && payload.get("merchantId") != null) {
            serverMerchantId = Long.valueOf(payload.get("merchantId").toString());
        }

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
            @RequestParam(required = false) String keyword) {
        checkServerAccess(id);
        Page<GuildMember> memberPage = guildService.listMembersPaginated(id, keyword, page, size);
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
            map.put("id", null);
            map.put("merchantId", null);
            map.put("fetchLimit", 2000000);
            map.put("requestInterval", 3);
            map.put("requestCount", 100);
            map.put("maxDepth", 5);
            map.put("maxRequests", 1000);
            map.put("archiveDays", 30);
        }
        return map;
    }

    private void checkServerAccess(Long serverId) {
        if (SecurityUtils.isPlatformAdmin()) {
            return;
        }
        Long merchantId = SecurityUtils.currentMerchantId();
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
        map.put("lastFetchAt", server.getLastFetchAt());
        map.put("status", server.getStatus());
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
        return map;
    }
}
