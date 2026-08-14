package com.discordadmin.controller;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.discord.member.DiscordMemberService;
import com.discordadmin.discord.member.GatewayMemberFetcher;
import com.discordadmin.discord.member.MemberFetchRequest;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.GuildServerRepository;
import com.discordadmin.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/discord/members")
public class DiscordMemberController {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberController.class);

    private final DiscordMemberService service;
    private final GuildServerRepository guildServerRepository;
    private final DiscordUserClient discordUserClient;
    private final DiscordAccountRepository accountRepository;

    public DiscordMemberController(DiscordMemberService service, 
                                   GuildServerRepository guildServerRepository,
                                   DiscordUserClient discordUserClient,
                                   DiscordAccountRepository accountRepository) {
        this.service = service;
        this.guildServerRepository = guildServerRepository;
        this.discordUserClient = discordUserClient;
        this.accountRepository = accountRepository;
    }

    @PostMapping("/fetch")
    public Map<String, Object> fetch(@RequestBody MemberFetchRequest req) {
        if (req.getToken() == null || req.getToken().isBlank()) {
            return Map.of("success", false, "message", "请填写 Discord Token");
        }
        if (req.getLink() == null || req.getLink().isBlank()) {
            return Map.of("success", false, "message", "请填写服务器链接/ID");
        }

        if (req.getGuildServerId() != null && req.getGuildServerId() > 0) {
            checkGuildServerAccess(req.getGuildServerId());
        }

        try {
            String taskId = service.startFetch(req);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("taskId", taskId);
            return r;
        } catch (IllegalStateException e) {
            // 服务器正在同步中的错误
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", e.getMessage());
            return r;
        } catch (Exception e) {
            // 其他未知错误
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "启动同步失败: " + e.getMessage());
            return r;
        }
    }

    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestBody Map<String, String> body) {
        String link = body == null ? null : body.get("link");
        String discordAccountIdStr = body == null ? null : body.get("discordAccountId");
        try {
            String guildId = service.resolveGuildId(link);
            Map<String, String> parsed = service.resolveUrl(link);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("guildId", guildId);
            r.put("channelId", parsed.getOrDefault("channelId", ""));

            // 尝试获取服务器名称
            String serverName = "";
            if (discordAccountIdStr != null && !discordAccountIdStr.isBlank()) {
                try {
                    Long accountId = Long.parseLong(discordAccountIdStr);
                    DiscordAccount account = accountRepository.findById(accountId).orElse(null);
                    if (account != null && account.getToken() != null && !account.getToken().isBlank()) {
                        JsonNode guildInfo = discordUserClient.getGuild(account.getToken(), guildId);
                        if (guildInfo != null) {
                            serverName = guildInfo.path("name").asText("");
                            log.info("解析服务器成功: guildId={}, name={}", guildId, serverName);
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取服务器名称失败: {}", e.getMessage());
                }
            }
            r.put("serverName", serverName);
            return r;
        } catch (GatewayMemberFetcher.GatewayException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @GetMapping("/task/{taskId}")
    public Object task(@PathVariable String taskId) {
        DiscordMemberService.TaskState st = service.getTask(taskId);
        if (st == null) {
            return Map.of("success", false, "message", "任务不存在");
        }

        if (st.guildServerId > 0) {
            checkGuildServerAccess(st.guildServerId);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", st.status);
        r.put("progress", st.progressMessage);
        r.put("progressMessage", st.progressMessage);
        r.put("guildId", st.guildId);
        r.put("serverName", st.serverName);
        r.put("currentPrefix", st.currentPrefix);
        r.put("requestsSent", st.requestsSent);
        r.put("membersUnique", st.membersUnique);
        r.put("prefixesDone", st.prefixesDone);
        r.put("prefixesTotal", st.prefixesTotal);
        r.put("reconnects", st.reconnects);
        r.put("maxDepth", st.maxDepth);
        r.put("maxRequests", st.maxRequests);
        r.put("maxMembers", st.maxMembers);
        r.put("records", st.records);
        r.put("totalFetched", st.totalFetched);
        r.put("error", st.error);
        r.put("guildServerId", st.guildServerId);
        r.put("discordAccountId", st.discordAccountId);
        r.put("lastBatchId", st.lastBatchId);
        r.put("completedPrefixCount", st.completedPrefixes.size());
        r.put("newlyCompletedCount", st.newlyCompletedPrefixes.size());
        // 新增统计字段
        r.put("totalRespondedMembers", st.totalRespondedMembers);
        r.put("totalResponseTimeMs", st.totalResponseTimeMs);
        r.put("lastResponded", st.lastResponded);
        r.put("lastDeduped", st.lastDeduped);
        r.put("lastRequestTimeMs", st.lastRequestTimeMs);
        r.put("elapsedMs", st.elapsedMs);
        r.put("startedAt", st.startedAt);
        r.put("completedAt", st.completedAt);
        r.put("lastPrefix", st.lastPrefix);
        r.put("failureReason", st.failureReason);
        return r;
    }

    @PostMapping("/task/{taskId}/stop")
    public Object stopTask(@PathVariable String taskId) {
        boolean stopped = service.stopFetch(taskId);
        if (stopped) {
            return Map.of("success", true, "message", "已请求停止，当前请求完成后将自动保存并停止");
        } else {
            return Map.of("success", false, "message", "任务不存在或已结束");
        }
    }

    @GetMapping("/tasks")
    public Object tasks() {
        Map<String, DiscordMemberService.TaskState> allTasks = service.getTasks();

        if (SecurityUtils.isPlatformAdmin()) {
            return allTasks;
        }

        Long merchantId = SecurityUtils.currentMerchantId();
        if (merchantId == null) {
            return Map.of();
        }

        Set<Long> merchantServerIds = guildServerRepository.findByMerchantId(merchantId)
            .stream()
            .map(GuildServer::getId)
            .collect(Collectors.toSet());

        Map<String, DiscordMemberService.TaskState> filteredTasks = new LinkedHashMap<>();
        for (Map.Entry<String, DiscordMemberService.TaskState> entry : allTasks.entrySet()) {
            DiscordMemberService.TaskState st = entry.getValue();
            if (st.guildServerId > 0 && merchantServerIds.contains(st.guildServerId)) {
                filteredTasks.put(entry.getKey(), st);
            }
        }
        return filteredTasks;
    }

    /**
     * 获取指定服务器的最近任务记录（从数据库查询，用于后端重启后恢复状态）
     */
    @GetMapping("/server/{serverId}/latest-task")
    public Object latestTask(@PathVariable Long serverId) {
        checkGuildServerAccess(serverId);
        DiscordMemberService.TaskState st = service.getLatestTaskForServer(serverId);
        if (st == null) {
            return Map.of("success", false, "message", "暂无任务记录");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", st.status);
        r.put("progress", st.progressMessage);
        r.put("progressMessage", st.progressMessage);
        r.put("guildId", st.guildId);
        r.put("serverName", st.serverName);
        r.put("currentPrefix", st.currentPrefix);
        r.put("requestsSent", st.requestsSent);
        r.put("membersUnique", st.membersUnique);
        r.put("prefixesDone", st.prefixesDone);
        r.put("prefixesTotal", st.prefixesTotal);
        r.put("reconnects", st.reconnects);
        r.put("error", st.error);
        r.put("guildServerId", st.guildServerId);
        r.put("discordAccountId", st.discordAccountId);
        // 添加详细统计字段
        r.put("maxRequests", st.maxRequests);
        r.put("maxMembers", st.maxMembers);
        r.put("totalRespondedMembers", st.totalRespondedMembers);
        r.put("totalResponseTimeMs", st.totalResponseTimeMs);
        r.put("lastPrefix", st.lastPrefix);
        r.put("failureReason", st.failureReason);
        r.put("startedAt", st.startedAt);
        r.put("completedAt", st.completedAt);
        // 新增本次统计字段
        r.put("lastResponded", st.lastResponded);
        r.put("lastDeduped", st.lastDeduped);
        r.put("lastRequestTimeMs", st.lastRequestTimeMs);
        r.put("elapsedMs", st.elapsedMs);
        return r;
    }

    private void checkGuildServerAccess(Long guildServerId) {
        if (SecurityUtils.isPlatformAdmin()) {
            return;
        }
        Long merchantId = SecurityUtils.currentMerchantId();
        if (merchantId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "无访问权限");
        }
        GuildServer server = guildServerRepository.findById(guildServerId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "服务器不存在"));
        if (!merchantId.equals(server.getMerchantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "无权访问此服务器");
        }
    }
}
