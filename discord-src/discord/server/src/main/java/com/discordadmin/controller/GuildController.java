package com.discordadmin.controller;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GuildController {

    private static final Logger log = LoggerFactory.getLogger(GuildController.class);

    private final DiscordAccountRepository accountRepository;
    private final DiscordUserClient discordUserClient;

    public GuildController(DiscordAccountRepository accountRepository,
                           DiscordUserClient discordUserClient) {
        this.accountRepository = accountRepository;
        this.discordUserClient = discordUserClient;
    }

    /**
     * 列出指定账号加入的所有服务器（Guild）。
     * 通过USER账号的token实时调用Discord API。
     */
    @GetMapping("/discord-accounts/{accountId}/guilds")
    public List<Map<String, Object>> listGuilds(@PathVariable Long accountId) throws Exception {
        DiscordAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: id=" + accountId));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());
        if (account.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalArgumentException("只有USER类型账号支持查看服务器列表");
        }
        String token = account.getBotToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("该账号未配置Token");
        }

        JsonNode guildsNode;
        try {
            guildsNode = discordUserClient.listGuilds(token);
        } catch (DiscordUserClient.DiscordUserApiException e) {
            if (e.statusCode == 401) {
                account.setLastError("Token已过期，请用Chrome插件重新导入");
                accountRepository.save(account);
            }
            throw e;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        if (guildsNode == null || !guildsNode.isArray()) return result;

        for (JsonNode g : guildsNode) {
            Map<String, Object> map = new HashMap<>();
            String guildId = g.path("id").asText(null);
            String name = g.path("name").asText(null);
            String iconHash = g.path("icon").asText(null);
            boolean owner = g.path("owner").asBoolean(false);
            long permissions = g.path("permissions").asLong(0L);

            String iconUrl = null;
            if (guildId != null && iconHash != null && !iconHash.isBlank()) {
                String ext = iconHash.startsWith("a_") ? "gif" : "png";
                iconUrl = "https://cdn.discordapp.com/icons/" + guildId + "/" + iconHash + "." + ext;
            }

            map.put("id", guildId);
            map.put("name", name);
            map.put("iconUrl", iconUrl);
            map.put("owner", owner);
            map.put("permissions", permissions);
            map.put("isAdmin", (permissions & 0x8) != 0); // ADMINISTRATOR = 0x8
            map.put("memberCount", g.path("approximate_member_count").asInt(0));
            result.add(map);
        }

        log.info("加载账号[id={}]的服务器列表：{}条", accountId, result.size());
        return result;
    }

    /**
     * 分页列出指定服务器的成员列表。
     * @param accountId 用于调用Discord API的USER账号ID（需有成员查看权限）
     * @param guildId 服务器ID
     * @param limit 每页条数（最大1000，默认100）
     * @param after 上一页最后一个成员的userId，用于分页
     */
    @GetMapping("/discord-accounts/{accountId}/guilds/{guildId}/members")
    public Map<String, Object> listMembers(
            @PathVariable Long accountId,
            @PathVariable String guildId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String after) throws Exception {

        DiscordAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: id=" + accountId));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());
        if (account.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalArgumentException("只有USER类型账号支持查看成员列表");
        }
        String token = account.getBotToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("该账号未配置Token");
        }

        JsonNode membersNode;
        try {
            membersNode = discordUserClient.listGuildMembers(token, guildId, limit, after);
        } catch (Exception e) {
            // 尝试从异常链中提取 DiscordUserApiException
            DiscordUserClient.DiscordUserApiException apiEx = null;
            if (e instanceof DiscordUserClient.DiscordUserApiException) {
                apiEx = (DiscordUserClient.DiscordUserApiException) e;
            } else if (e.getCause() instanceof DiscordUserClient.DiscordUserApiException) {
                apiEx = (DiscordUserClient.DiscordUserApiException) e.getCause();
            }

            int statusCode = (apiEx != null) ? apiEx.statusCode : -1;

            if (statusCode == 401) {
                account.setLastError("Token已过期，请用Chrome插件重新导入");
                accountRepository.save(account);
                throw (apiEx != null) ? apiEx : e;
            }
            // 403 是正常情况：USER token 需要 guilds.members.read OAuth scope 才能拉成员
            if (statusCode == 403) {
                log.warn("账号[id={}]无权限读取服务器[{}]成员（Discord code 50001 缺少权限），返回空列表", accountId, guildId);
                Map<String, Object> resp = new HashMap<>();
                resp.put("members", new ArrayList<>());
                resp.put("count", 0);
                resp.put("hasMore", false);
                resp.put("after", null);
                resp.put("error", "缺少读取成员的权限（Discord code 50001）。该 USER 账号的 OAuth token 未授予 guilds.members.read scope。");
                return resp;
            }
            throw e;
        }

        List<Map<String, Object>> members = new ArrayList<>();
        String lastUserId = null;
        if (membersNode != null && membersNode.isArray()) {
            for (JsonNode m : membersNode) {
                JsonNode user = m.path("user");
                if (user.isMissingNode() || user.isNull()) continue;

                Map<String, Object> map = new HashMap<>();
                String userId = user.path("id").asText(null);
                lastUserId = userId;
                String username = user.path("username").asText("Unknown");
                String globalName = user.path("global_name").asText(null);
                String displayName = m.path("nick").asText(null); // 服务器内昵称
                if (displayName == null || displayName.isBlank()) {
                    displayName = (globalName != null && !globalName.isBlank()) ? globalName : username;
                }
                String avatarHash = user.path("avatar").asText(null);
                String iconUrl = null;
                if (userId != null && avatarHash != null && !avatarHash.isBlank()) {
                    String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                    iconUrl = "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + "." + ext;
                }

                map.put("userId", userId);
                map.put("username", username);
                map.put("globalName", globalName);
                map.put("displayName", displayName);
                map.put("avatarUrl", iconUrl);
                map.put("isBot", user.path("bot").asBoolean(false));
                map.put("joinedAt", m.path("joined_at").asText(null));
                // 角色列表
                List<String> roles = new ArrayList<>();
                JsonNode rolesNode = m.path("roles");
                if (rolesNode.isArray()) {
                    for (JsonNode r : rolesNode) {
                        roles.add(r.asText());
                    }
                }
                map.put("roles", roles);
                members.add(map);
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("members", members);
        resp.put("count", members.size());
        resp.put("hasMore", members.size() >= limit);
        resp.put("after", lastUserId);

        log.info("加载服务器[{}]成员：{}条（账号id={}）", guildId, members.size(), accountId);
        return resp;
    }
}
