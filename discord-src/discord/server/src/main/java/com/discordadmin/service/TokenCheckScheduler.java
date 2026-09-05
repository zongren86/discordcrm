package com.discordadmin.service;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.repository.DiscordAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.discordadmin.service.AuditLoggingHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Token 有效性「体检」定时任务。
 *
 * 核心原则（降低账号被封风险，减少非必要 Discord API 调用）：
 * 1. 仅对 status=ACTIVE AND accountType=USER AND token_valid=true 的账号做检查；
 *    token_valid=0（已明确失效）的账号完全跳过，等用户手动点「续期Token」。
 * 2. 仅在 Discord API 返回 HTTP 401 时才把 token_valid 置 0 + 写 lastError；
 *    其它错误（429 限流 / 5xx / 网络异常 / Cloudflare 拦截）全部忽略，保持 token_valid 不变。
 * 3. 批量并行调 Discord API（getMe /users/@me），降低整体耗时；超时 20s。
 * 4. 每 10 分钟执行一次（initialDelay=2min，避免启动时立刻占用资源）。
 */
@Service
public class TokenCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenCheckScheduler.class);

    private final DiscordAccountRepository accountRepository;
    private final DiscordUserClient userClient;
    private final AuditLoggingHelper auditLoggingHelper;

    public TokenCheckScheduler(DiscordAccountRepository accountRepository,
                               DiscordUserClient userClient,
                                 AuditLoggingHelper auditLoggingHelper) {
        this.accountRepository = accountRepository;
        this.userClient = userClient;
            this.auditLoggingHelper = auditLoggingHelper;
}

    /**
     * 每 10 分钟执行一次 Token 有效性体检。
     * fixedDelay = 10min；initialDelay = 2min。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void healthCheck() {
        // 1. 只取 token 有效且活跃的 USER 账号
        List<DiscordAccount> accounts = accountRepository.findForTokenHealthCheck();
        if (accounts.isEmpty()) return;

        log.info("[Token体检] 开始检查 {} 个有效账号", accounts.size());
        long startAt = System.currentTimeMillis();

        List<DiscordAccount> toSave = new ArrayList<>();
        int[] stats = new int[]{0, 0, 0, 0}; // [valid, expired(401), otherErr, timeout]

        try {
            CompletableFuture.allOf(accounts.stream()
                    .map(a -> CompletableFuture.runAsync(() -> {
                        try {
                            JsonNode me = userClient.getMe(a.getToken());
                            // HTTP 200：有效
                            a.setTokenCheckedAt(Instant.now());
                            a.setTokenValid(true);
                            // 没有 lastError 是 401 导致的就清掉
                            if (a.getLastError() != null && a.getLastError().contains("401")) {
                                a.setLastError(null);
                            }
                            synchronized (toSave) { toSave.add(a); }
                            stats[0]++;
                        } catch (DiscordUserClient.DiscordUserApiException e) {
                            if (e.statusCode == 401) {
                                // 唯一判定失效的情况
                                a.setTokenValid(false);
                                a.setTokenCheckedAt(Instant.now());
                                a.setLastError("Token 已失效(401)，请点击「更新Token」按钮重新导入");
                                synchronized (toSave) { toSave.add(a); }
                                stats[1]++;
                                log.warn("[Token体检] 账号[id={}, name={}] 已失效(401)，已标记为 token_valid=0",
                                        a.getId(), a.getName());
                                auditLoggingHelper.tokenEvent(a.getId(), a.getName(),
                                        "EXPIRED_401_SCHEDULER", "TOKEN_CHECK_SCHEDULER",
                                        a.getAgentServerId(), null, "401",
                                        AuditLoggingHelper.detail("scheduler", "TokenCheckScheduler.healthCheck"),
                                        "SYSTEM", a.getMerchantId());
                                auditLoggingHelper.log("token", "EXPIRED", "DiscordAccount",
                                        String.valueOf(a.getId()),
                                        AuditLoggingHelper.detail("reason", "scheduler_401", "account", a.getName()),
                                        "SYSTEM", a.getMerchantId(), "FAIL");
                            } else {
                                // 429/5xx/403 等：不碰 token_valid，只记日志
                                stats[2]++;
                                log.debug("[Token体检] 账号[id={}, name={}] HTTP {}（非401），保持有效状态",
                                        a.getId(), a.getName(), e.statusCode);
                            }
                        } catch (Exception e) {
                            // 网络异常、超时、Cloudflare 等：完全忽略
                            stats[3]++;
                            log.debug("[Token体检] 账号[id={}, name={}] 网络/异常({})，跳过",
                                    a.getId(), a.getName(), e.getClass().getSimpleName());
                        }
                    }))
                    .toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[Token体检] 并行执行20秒超时，已完成的账号会被保存");
        } catch (Exception e) {
            log.warn("[Token体检] 并行执行异常: {}", e.getMessage());
        }

        if (!toSave.isEmpty()) {
            accountRepository.saveAll(toSave);
        }
        long cost = System.currentTimeMillis() - startAt;
        log.info("[Token体检] 完成: 总={}, 有效={}, 401失效={}, 其他HTTP错={}, 网络/超时={}, 耗时={}ms",
                accounts.size(), stats[0], stats[1], stats[2], stats[3], cost);
    }
}
