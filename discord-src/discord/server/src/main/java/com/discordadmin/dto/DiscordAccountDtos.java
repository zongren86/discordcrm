package com.discordadmin.dto;

import com.discordadmin.entity.DiscordAccount;

import java.util.List;

public class DiscordAccountDtos {

    public record CreateAccountRequest(String name, String botToken, String email, String remark,
                                       Long merchantId, String accountType) {
    }

    public record UpdateAccountRequest(String name, String botToken, String status, String remark,
                                       Long merchantId) {
    }

    public record ImportTokenRequest(String name, String token, String accountType,
                                      String discordUserId, String username, String globalName,
                                      String avatar) {
    }

    public record BatchLoginItem(String email, String password) {
    }

    public record BatchImportRequest(List<BatchLoginItem> accounts) {
    }

    public record BatchImportResultItem(String email, String password, boolean success, String message, String accountName) {
    }

    public record BatchImportResponse(int total, int success, int failed, List<BatchImportResultItem> results) {
    }

    public record AccountDto(Long id, String name, String botTokenMasked, String accountType,
                              String discordBotId, String discordBotName, String avatarUrl,
                              String status, boolean connected, boolean connecting,
                              String lastError, boolean tokenValid,
                              Long friendCount, Long conversationCount, Long messageCount,
                              String email, String remark, Long merchantId,
                              String agentName, String agentUsername, Long agentId,
                              String tokenExpiresAt, String tokenCheckedAt) {
        public static AccountDto from(DiscordAccount a, boolean connected, boolean connecting) {
            return from(a, connected, connecting, true, 0L, 0L, 0L, null, null, null);
        }

        public static AccountDto from(DiscordAccount a, boolean connected, boolean connecting,
                                      Long friendCount, Long conversationCount, Long messageCount) {
            return from(a, connected, connecting, true, friendCount, conversationCount, messageCount, null, null, null);
        }

        public static AccountDto from(DiscordAccount a, boolean connected, boolean connecting,
                                      boolean tokenValid, Long friendCount, Long conversationCount, Long messageCount,
                                      String agentName, String agentUsername, Long agentId) {
            return new AccountDto(a.getId(), a.getName(), maskToken(a.getBotToken()),
                    a.getAccountType() != null ? a.getAccountType().name() : "BOT",
                    a.getDiscordBotId(), a.getDiscordBotName(), a.getAvatarUrl(),
                    a.getStatus() != null ? a.getStatus().name() : null,
                    connected, connecting, a.getLastError(), tokenValid,
                    friendCount != null ? friendCount : 0L,
                    conversationCount != null ? conversationCount : 0L,
                    messageCount != null ? messageCount : 0L,
                    a.getEmail(), a.getRemark(), a.getMerchantId(),
                    agentName, agentUsername, agentId,
                    a.getTokenExpiresAt() != null ? a.getTokenExpiresAt().toString() : null,
                    a.getTokenCheckedAt() != null ? a.getTokenCheckedAt().toString() : null);
        }

        private static String maskToken(String token) {
            if (token == null || token.length() <= 8) {
                return token == null ? "" : "****";
            }
            return "****" + token.substring(token.length() - 4);
        }
    }

    public record ImportTokenResponse(AccountDto account, boolean alreadyExisted, String message) {
    }

    public record RefreshTokenRequest(String email, String password) {
    }

    public record RefreshTokenResponse(AccountDto account, String message) {
    }
}
