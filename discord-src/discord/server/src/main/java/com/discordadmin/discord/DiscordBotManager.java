package com.discordadmin.discord;

import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.service.ConversationService;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 聚合多个 Discord 账号：每个账号维护一个独立的 JDA WebSocket 连接，
 * 入站消息通过 listener 带上 accountId，出站消息按会话归属路由到对应 JDA。
 *
 * 所有账号（BOT/USER）都使用官方推荐的 Bot Token + WebSocket 方式，
 * 不再使用 User Token + REST 轮询。
 */
@Service
public class DiscordBotManager {

    private static final Logger log = LoggerFactory.getLogger(DiscordBotManager.class);

    private final DiscordAccountRepository accountRepository;
    @Autowired @Lazy
    private ConversationService conversationService;

    /** accountId -> 已就绪的 JDA 实例 */
    private final Map<Long, JDA> jdaMap = new ConcurrentHashMap<>();
    /** 正在连接中的账号，防止重复启动 */
    private final Set<Long> connecting = ConcurrentHashMap.newKeySet();
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool();

    public DiscordBotManager(DiscordAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        var active = accountRepository.findByStatus(DiscordAccount.AccountStatus.ACTIVE);
        if (active.isEmpty()) {
            log.warn("数据库中没有 ACTIVE 的 Discord 账号，跳过连接。可在后台「账号管理」中添加。");
            return;
        }
        for (DiscordAccount acc : active) {
            try {
                startAccount(acc.getId());
            } catch (Exception e) {
                log.error("提交 Discord 账号 [id={}, name={}] 的连接任务失败", acc.getId(), acc.getName(), e);
                markConnectionError(acc.getId(), e.getMessage());
            }
        }
    }

    /**
     * 启动指定账号的 JDA 连接。全部异步：方法本身不抛 JDA 相关异常，
     * 构建 build() + awaitReady() 都在后台线程执行，失败仅标记 CONNECTION_ERROR。
     */
    public synchronized void startAccount(Long accountId) {
        if (jdaMap.containsKey(accountId) || !connecting.add(accountId)) {
            return;
        }
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Discord 账号不存在"));

        // USER 账号走 REST 轮询（UserMessagePoller），不建立 JDA(Bot) 连接
        if (acc.getAccountType() == DiscordAccount.AccountType.USER) {
            connecting.remove(accountId);
            log.info("USER 账号 [{}] 跳过 JDA 连接，改用 REST 轮询", acc.getName());
            return;
        }

        if (acc.getToken() == null || acc.getToken().isBlank()) {
            connecting.remove(accountId);
            throw new IllegalArgumentException("Bot Token 不能为空");
        }

        final Long accId = accountId;
        final String accountName = acc.getName();
        final String token = acc.getToken();
        final DiscordMessageListener listener = new DiscordMessageListener(accountId, conversationService);

        connectExecutor.submit(() -> {
            JDA jda = null;
            try {
                jda = JDABuilder.createDefault(token)
                        .enableIntents(
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.DIRECT_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT
                        )
                        .addEventListeners(listener)
                        .build();
                jda.awaitReady();
                acc.setDiscordId(jda.getSelfUser().getId());
                acc.setDiscordName(jda.getSelfUser().getName());
                acc.setLastError(null);
                accountRepository.save(acc);
                jdaMap.put(accId, jda);
                log.info("Discord 账号 [{}] 已连接 (botId={}, name={})",
                        accountName, acc.getDiscordId(), acc.getDiscordName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("等待 Discord 账号 [{}] 连接被中断", accountName);
                markConnectionError(accId, "连接被中断");
            } catch (Exception e) {
                log.error("Discord 账号 [{}] 连接失败", accountName, e);
                String reason = e.getMessage();
                if (reason == null || reason.isBlank()) reason = e.getClass().getSimpleName();
                markConnectionError(accId, reason);
                if (jda != null) {
                    try { jda.shutdownNow(); } catch (Exception ignore) {}
                }
            } finally {
                connecting.remove(accId);
            }
        });
    }

    /** 记录连接失败原因，但不修改用户设定的 ACTIVE/INACTIVE */
    private void markConnectionError(Long accountId, String reason) {
        try {
            accountRepository.findById(accountId).ifPresent(acc -> {
                acc.setLastError(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
                accountRepository.save(acc);
            });
        } catch (Exception e) {
            log.warn("更新账号[id={}]失败原因失败", accountId, e);
        }
    }

    /**
     * 断开指定账号的 JDA 连接(不删除数据库记录，不自动修改 DB status)。
     */
    public synchronized void stopAccount(Long accountId) {
        connecting.remove(accountId);
        JDA jda = jdaMap.remove(accountId);
        if (jda != null) {
            try { jda.shutdown(); } catch (Exception e) { log.warn("shutdown JDA[id={}] error", accountId, e); }
            log.info("Discord 账号 [id={}] 已断开连接", accountId);
        }
    }

    public boolean isConnected(Long accountId) {
        return jdaMap.containsKey(accountId);
    }

    public boolean isConnecting(Long accountId) {
        return connecting.contains(accountId);
    }

    public Set<Long> connectedAccountIds() {
        return Set.copyOf(jdaMap.keySet());
    }

    /**
     * 按会话归属的账号路由出站消息。DM 走私信通道，GUILD_TEXT 走频道。
     */
    public void sendMessage(Long accountId, Conversation conversation, String content) {
        JDA jda = jdaMap.get(accountId);
        if (jda == null) {
            throw new IllegalStateException("Discord 账号未连接或正在连接中，无法发送消息");
        }
        if (conversation.getType() == Conversation.ConversationType.DM) {
            jda.retrieveUserById(conversation.getDiscordUser().getDiscordUserId())
                    .flatMap(user -> user.openPrivateChannel())
                    .flatMap(channel -> channel.sendMessage(content))
                    .queue(
                            msg -> log.info("账号[{}] DM 发送成功: {}", accountId, msg.getId()),
                            err -> log.error("账号[{}] DM 发送失败", accountId, err)
                    );
        } else {
            TextChannel channel = jda.getTextChannelById(conversation.getChannelId());
            if (channel == null) {
                throw new IllegalStateException("找不到目标频道，Bot 可能已被移出服务器");
            }
            channel.sendMessage(content).queue(
                    msg -> log.info("账号[{}] 频道消息发送成功: {}", accountId, msg.getId()),
                    err -> log.error("账号[{}] 频道消息发送失败", accountId, err)
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Long id : Set.copyOf(jdaMap.keySet())) {
            stopAccount(id);
        }
        connectExecutor.shutdown();
        try {
            if (!connectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                connectExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectExecutor.shutdownNow();
        }
    }
}
