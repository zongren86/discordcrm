package com.discordadmin.service;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.repository.DiscordAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Token 有效性检测定时任务
 * 每小时检查一次 USER 类型账号的 Token 是否即将过期或已失效
 */
@Service
public class TokenCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenCheckScheduler.class);
    
    // Token 过期前多少小时开始预警
    private static final int WARN_HOURS_BEFORE_EXPIRE = 6;
    
    // Token 有效期（秒），Discord USER token 通常 24 小时
    private static final long TOKEN_VALIDITY_SECONDS = 24 * 60 * 60L;

    private final DiscordAccountRepository accountRepository;
    private final DiscordUserClient userClient;

    public TokenCheckScheduler(DiscordAccountRepository accountRepository,
                               DiscordUserClient userClient) {
        this.accountRepository = accountRepository;
        this.userClient = userClient;
    }

    /**
     * 每小时执行一次 Token 有效性检查
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void checkTokenValidity() {
        log.info("开始检查 USER 账号 Token 有效性...");
        
        List<DiscordAccount> userAccounts = accountRepository.findByAccountType(
                DiscordAccount.AccountType.USER);
        
        int expiredCount = 0;
        int warningCount = 0;
        int validCount = 0;
        
        for (DiscordAccount account : userAccounts) {
            try {
                // 检查 Token 有效性
                boolean tokenValid = checkAndUpdateTokenStatus(account);
                
                if (!tokenValid) {
                    expiredCount++;
                } else if (isTokenExpiringSoon(account)) {
                    warningCount++;
                } else {
                    validCount++;
                }
            } catch (Exception e) {
                log.error("检查账号 [{}] Token 有效性失败: {}", account.getName(), e.getMessage());
            }
        }
        
        log.info("Token 有效性检查完成: 有效={}, 即将过期={}, 已过期={}", 
                validCount, warningCount, expiredCount);
    }
    
    /**
     * 检查单个账号的 Token 有效性并更新状态
     */
    private boolean checkAndUpdateTokenStatus(DiscordAccount account) {
        if (account.getBotToken() == null || account.getBotToken().isBlank()) {
            account.setLastError("Token 为空");
            accountRepository.save(account);
            return false;
        }
        
        try {
            // 尝试调用 Discord API 验证 Token
            JsonNode me = userClient.getMe(account.getBotToken());
            
            // Token 有效，更新检查时间和过期时间
            account.setTokenCheckedAt(Instant.now());
            account.setLastError(null);
            
            // 如果没有设置过期时间，或者上次设置的已过期，则重新设置
            if (account.getTokenExpiresAt() == null || 
                account.getTokenExpiresAt().isBefore(Instant.now())) {
                account.setTokenExpiresAt(Instant.now().plusSeconds(TOKEN_VALIDITY_SECONDS));
            }
            
            accountRepository.save(account);
            return true;
            
        } catch (DiscordUserClient.DiscordUserApiException e) {
            // Token 无效
            account.setLastError("Token 已失效: HTTP " + e.statusCode);
            account.setTokenCheckedAt(Instant.now());
            accountRepository.save(account);
            log.warn("账号 [{}] Token 已失效: {}", account.getName(), e.getMessage());
            return false;
            
        } catch (Exception e) {
            // 网络错误等其他异常，不更新 Token 状态
            log.warn("账号 [{}] Token 检查失败: {}", account.getName(), e.getMessage());
            return isTokenStillValid(account);
        }
    }
    
    /**
     * 检查 Token 是否即将过期（预警）
     */
    private boolean isTokenExpiringSoon(DiscordAccount account) {
        if (account.getTokenExpiresAt() == null) {
            return false;
        }
        
        Instant warnTime = Instant.now().plusSeconds(WARN_HOURS_BEFORE_EXPIRE * 60 * 60L);
        return account.getTokenExpiresAt().isBefore(warnTime);
    }
    
    /**
     * 根据过期时间判断 Token 是否仍然有效
     */
    private boolean isTokenStillValid(DiscordAccount account) {
        if (account.getTokenExpiresAt() == null) {
            // 没有过期时间，假设有效（保留原有状态）
            return account.getLastError() == null || account.getLastError().isBlank();
        }
        return account.getTokenExpiresAt().isAfter(Instant.now());
    }
}
