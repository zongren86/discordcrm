package com.discordadmin.discord;

import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordUser;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.DiscordUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时同步好友的原生 Discord Presence。
 * 延迟要求可接受（分钟级），每 10 分钟跑一次。
 */
@Service
public class PresenceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PresenceSyncScheduler.class);

    private final DiscordAccountRepository accountRepository;
    private final DiscordUserRepository discordUserRepository;
    private final DiscordUserClient discordUserClient;

    public PresenceSyncScheduler(DiscordAccountRepository accountRepository,
                                 DiscordUserRepository discordUserRepository,
                                 DiscordUserClient discordUserClient) {
        this.accountRepository = accountRepository;
        this.discordUserRepository = discordUserRepository;
        this.discordUserClient = discordUserClient;
    }

    /** 每 10 分钟同步一次所有 USER 账号下好友的 Presence。 */
    @Scheduled(fixedRate = 600_000, initialDelay = 30_000)
    @Transactional
    public void syncFriendPresences() {
        List<DiscordAccount> userAccounts = accountRepository.findByStatus(DiscordAccount.AccountStatus.ACTIVE)
                .stream()
                .filter(a -> a.getAccountType() == DiscordAccount.AccountType.USER)
                .filter(a -> !"AGENT".equals(a.getSource()))
                .filter(a -> Boolean.TRUE.equals(a.getTokenValid()))
                .toList();

        if (userAccounts.isEmpty()) return;

        for (DiscordAccount account : userAccounts) {
            if (account.getToken() == null || account.getToken().isBlank()) continue;
            try {
                JsonNode friends = discordUserClient.listFriendsWithPresence(account.getToken());
                if (friends == null || !friends.isArray()) continue;

                int updated = 0;
                Instant now = Instant.now();

                // 先批量查本地已存在的用户
                Map<String, DiscordUser> existingMap = new HashMap<>();
                for (JsonNode rel : friends) {
                    String uid = rel.path("user").path("id").asText(null);
                    if (uid != null) existingMap.put(uid, null);
                }
                List<DiscordUser> existingUsers = discordUserRepository.findAllByDiscordUserIdIn(
                        existingMap.keySet().stream().toList());
                for (DiscordUser u : existingUsers) existingMap.put(u.getDiscordUserId(), u);

                for (JsonNode rel : friends) {
                    JsonNode user = rel.path("user");
                    String userId = user.path("id").asText(null);
                    if (userId == null) continue;

                    // 尝试多种可能的 Presence 字段位置
                    String status = null;
                    // 1. 直接在 rel 中查找 presence
                    if (rel.has("presence")) {
                        status = rel.path("presence").path("status").asText(null);
                    }
                    // 2. 在 user 对象中查找 presence
                    if (status == null && user.has("presence")) {
                        status = user.path("presence").path("status").asText(null);
                    }
                    // 3. 检查 status 字段是否直接在 rel 或 user 中
                    if (status == null && rel.has("status")) {
                        status = rel.path("status").asText(null);
                    }
                    if (status == null && user.has("status")) {
                        status = user.path("status").asText(null);
                    }
                    // 4. 记录调试信息（只记录一次）
                    if (status == null && updated == 0) {
                        log.debug("Presence 字段位置检查: rel.has(presence)={}, user.has(presence)={}, rel.has(status)={}, user.has(status)={}",
                                rel.has("presence"), user.has("presence"), rel.has("status"), user.has("status"));
                    }

                    DiscordUser du = existingMap.get(userId);
                    if (du == null) {
                        du = new DiscordUser();
                        du.setDiscordUserId(userId);
                        du.setUsername(user.path("username").asText(null));
                        du.setGlobalName(user.path("global_name").asText(null));
                        du.setAvatarUrl(avatarUrlOf(user.path("avatar").asText(null), userId));
                        du.setFirstSeenAt(now);
                    }
                    if (status != null && !status.isBlank()) {
                        du.setPresence(status);
                        du.setPresenceUpdatedAt(now);
                    } else if (du.getPresenceUpdatedAt() == null
                            || now.getEpochSecond() - du.getPresenceUpdatedAt().getEpochSecond() > 3600) {
                        // 只有当确实无法获取到状态时，才标记为 offline
                        // 并且只在超过1小时没有更新时才这样做
                        du.setPresence("offline");
                        du.setPresenceUpdatedAt(now);
                    }
                    discordUserRepository.save(du);
                    updated++;
                }
                log.info("Presence 同步完成: 账号[{}] 更新 {} 个好友", account.getName(), updated);
            } catch (DiscordUserClient.DiscordUserApiException e) {
                log.warn("Presence 同步失败: 账号[{}] Discord API 错误 (code={})", account.getName(), e.statusCode);
            } catch (Exception e) {
                log.warn("Presence 同步失败: 账号[{}], err={}", account.getName(), e.getMessage());
            }
        }
    }

    private static String avatarUrlOf(String hash, String userId) {
        if (hash == null || hash.isBlank()) return null;
        String ext = hash.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + hash + "." + ext;
    }
}
