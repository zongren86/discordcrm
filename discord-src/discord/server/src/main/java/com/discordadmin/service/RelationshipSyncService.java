package com.discordadmin.service;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 拉取 USER 类型账号的好友列表并入库。
 *
 * BOT 账号没有"好友"概念，跳过。
 * 每次同步：拉远端好友列表 + 待接收请求 -> 与本地比对 -> upsert。
 * 同时同步 DM 频道为 Conversation，使消息轮询器能拉取到好友私信。
 * 也提供 syncOne(accountId) 给接口手动触发。
 */
@Service
public class RelationshipSyncService {

    private static final Logger log = LoggerFactory.getLogger(RelationshipSyncService.class);

    private final DiscordAccountRepository accountRepository;
    private final FriendRepository friendRepository;
    private final DiscordUserClient userClient;
    private final ConversationRepository conversationRepository;
    private final DiscordUserRepository discordUserRepository;

    public RelationshipSyncService(DiscordAccountRepository accountRepository,
                                    FriendRepository friendRepository,
                                    DiscordUserClient userClient,
                                    ConversationRepository conversationRepository,
                                    DiscordUserRepository discordUserRepository) {
        this.accountRepository = accountRepository;
        this.friendRepository = friendRepository;
        this.userClient = userClient;
        this.conversationRepository = conversationRepository;
        this.discordUserRepository = discordUserRepository;
    }

    /** 每 10 分钟自动同步一次所有 USER 类型的 ACTIVE 账号 */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 30 * 1000L)
    public void autoSyncAll() {
        for (DiscordAccount acc : accountRepository.findByStatus(DiscordAccount.AccountStatus.ACTIVE)) {
            if (acc.getAccountType() == DiscordAccount.AccountType.USER) {
                try {
                    syncOne(acc.getId());
                } catch (Exception e) {
                    log.warn("自动同步账号[id={}, name={}]好友失败: {}", acc.getId(), acc.getName(), e.getMessage());
                }
            }
        }
    }

    /**
     * 同步单个账号的好友列表 + 待接收请求 + DM 频道。
     * @return 同步后的好友数量；token 失效会抛 DiscordUserApiException(401)
     */
    @Transactional
    public int syncOne(Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        if (acc.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalStateException("仅 USER 类型账号可同步好友，BOT 账号无好友概念");
        }

        List<JsonNode> remoteFriends;
        List<JsonNode> remotePending;
        try {
            remoteFriends = userClient.listFriends(acc.getToken());
            remotePending = userClient.listPendingFriendRequests(acc.getToken());
        } catch (DiscordUserClient.DiscordUserApiException e) {
            acc.setLastError("拉好友失败: " + e.statusCode + " " + truncate(e.rawBody, 200));
            accountRepository.save(acc);
            throw new RuntimeException("Discord API " + e.statusCode, e);
        } catch (Exception e) {
            acc.setLastError("拉好友网络异常: " + e.getMessage());
            accountRepository.save(acc);
            throw new RuntimeException("拉好友失败", e);
        }

        // 收集远端好友ID集合
        java.util.Set<String> remoteFriendIds = new java.util.HashSet<>();
        for (JsonNode user : remoteFriends) {
            String friendId = user.path("id").asText(null);
            if (friendId != null) remoteFriendIds.add(friendId);
        }

        int updated = 0;
        // 1) 已接受好友 (type=1)：upsert 为 ACCEPTED
        for (JsonNode user : remoteFriends) {
            String friendId = user.path("id").asText(null);
            if (friendId == null) continue;

            Optional<Friend> existOpt = friendRepository
                    .findByDiscordAccountAndFriendDiscordUserId(acc, friendId);
            Friend f = existOpt.orElseGet(Friend::new);
            if (f.getDiscordAccount() == null) f.setDiscordAccount(acc);
            f.setMerchantId(acc.getMerchantId());
            f.setFriendDiscordUserId(friendId);
            f.setUsername(user.path("username").asText(null));
            f.setGlobalName(user.path("global_name").asText(null));
            f.setAvatar(user.path("avatar").asText(null));
            f.setStatus(Friend.FriendStatus.ACCEPTED);
            f.setSyncedAt(Instant.now());
            friendRepository.save(f);
            updated++;
        }

        // 2) 待接收请求 (type=3)：upsert 为 PENDING_IN
        for (JsonNode user : remotePending) {
            String friendId = user.path("id").asText(null);
            if (friendId == null) continue;

            Optional<Friend> existOpt = friendRepository
                    .findByDiscordAccountAndFriendDiscordUserId(acc, friendId);
            Friend f = existOpt.orElseGet(Friend::new);
            if (f.getDiscordAccount() == null) f.setDiscordAccount(acc);
            f.setMerchantId(acc.getMerchantId());
            f.setFriendDiscordUserId(friendId);
            f.setUsername(user.path("username").asText(null));
            f.setGlobalName(user.path("global_name").asText(null));
            f.setAvatar(user.path("avatar").asText(null));
            f.setStatus(Friend.FriendStatus.PENDING_IN);
            f.setSyncedAt(Instant.now());
            friendRepository.save(f);
            updated++;
        }

        // 3) 检测被删除的好友：本地存在但远端不存在 → 标记为流失客户
        List<Friend> localFriends = friendRepository.findByDiscordAccount(acc);
        int churned = 0;
        for (Friend f : localFriends) {
            if (f.getStatus() == Friend.FriendStatus.ACCEPTED
                    && !remoteFriendIds.contains(f.getFriendDiscordUserId())) {
                // 好友被删除，更新关联会话的漏斗状态为 CHURNED
                List<Conversation> convs = conversationRepository
                        .findByDiscordUserAndDiscordAccount(f.getFriendDiscordUserId(), acc.getId());
                for (Conversation conv : convs) {
                    if (conv.getStage() != Conversation.Stage.CHURNED) {
                        conv.setStage(Conversation.Stage.CHURNED);
                        conv.setStageChangedAt(Instant.now());
                        conversationRepository.save(conv);
                        churned++;
                        log.info("会话 [convId={}] 好友已删除，漏斗阶段更新为 CHURNED", conv.getId());
                    }
                }
                // 删除该好友记录
                friendRepository.delete(f);
                log.info("好友 [friendId={}] 已删除，标记为流失客户", f.getFriendDiscordUserId());
            }
        }
        if (churned > 0) {
            log.info("账号[id={}] 检测到 {} 个流失客户", accountId, churned);
        }

        // 4) 同步 DM 频道为 Conversation（使消息轮询器能拉到好友私信）
        int dmCount = 0;
        try {
            dmCount = syncDmChannels(acc);
        } catch (Exception e) {
            log.warn("账号[id={}, name={}] 同步 DM 频道失败: {}", acc.getId(), acc.getName(), e.getMessage());
        }

        // 同步成功清掉 lastError
        acc.setLastError(null);
        accountRepository.save(acc);
        log.info("账号[id={}, name={}] 同步好友+待处理请求 {} 个, DM 频道 {} 个", accountId, acc.getName(), updated, dmCount);
        return updated;
    }

    /**
     * 拉取 USER 账号的所有 DM 频道（GET /users/@me/channels），
     * 为每个 DM 频道创建/更新 Conversation 记录，关联到对应 DiscordUser 和 DiscordAccount。
     * 这样 UserMessagePoller 才能轮询到好友发来的私信。
     */
    private int syncDmChannels(DiscordAccount acc) throws Exception {
        JsonNode channels = userClient.listDmChannels(acc.getToken());
        if (channels == null || !channels.isArray()) return 0;

        int count = 0;
        for (JsonNode ch : channels) {
            // type=1 是 1:1 DM，type=3 是群组 DM，这里只处理 1:1
            int type = ch.path("type").asInt(-1);
            if (type != 1) continue;

            String channelId = ch.path("id").asText(null);
            if (channelId == null) continue;

            JsonNode recipients = ch.path("recipients");
            if (!recipients.isArray() || recipients.size() == 0) continue;

            JsonNode recipient = recipients.get(0);
            String userId = recipient.path("id").asText(null);
            if (userId == null) continue;

            // upsert DiscordUser
            DiscordUser user = discordUserRepository.findByDiscordUserId(userId).orElse(null);
            if (user == null) {
                user = new DiscordUser();
                user.setDiscordUserId(userId);
                user.setFirstSeenAt(Instant.now());
            }
            user.setUsername(recipient.path("username").asText(null));
            user.setGlobalName(recipient.path("global_name").asText(null));
            String avatar = recipient.path("avatar").asText(null);
            if (avatar != null) {
                user.setAvatarUrl("https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + ".png");
            }
            user = discordUserRepository.save(user);

            // per-account去重：同一账号下同一频道只保留一个会话
            Conversation conv = conversationRepository
                    .findByChannelIdAndDiscordAccount_Id(channelId, acc.getId())
                    .orElse(null);
            if (conv == null) {
                conv = new Conversation();
                conv.setChannelId(channelId);
                conv.setType(Conversation.ConversationType.DM);
                conv.setStatus(Conversation.ConversationStatus.OPEN);
                conv.setCreatedAt(Instant.now());
            }
            conv.setDiscordUser(user);
            conv.setDiscordAccount(acc);
            conv.setMerchantId(acc.getMerchantId());
            String displayName = user.getGlobalName() != null ? user.getGlobalName() : user.getUsername();
            conv.setChannelName(displayName);
            conversationRepository.save(conv);
            count++;
        }
        return count;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
