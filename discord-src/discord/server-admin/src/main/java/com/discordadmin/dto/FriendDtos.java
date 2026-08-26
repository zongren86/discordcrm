package com.discordadmin.dto;

import com.discordadmin.entity.Friend;

public class FriendDtos {

    public record FriendDto(Long id, Long discordAccountId, String discordAccountName,
                             String friendDiscordUserId, String username, String globalName,
                             String avatarUrl, String status, String syncedAt) {
        public static FriendDto from(Friend f) {
            return new FriendDto(
                    f.getId(),
                    f.getDiscordAccount().getId(),
                    f.getDiscordAccount().getName(),
                    f.getFriendDiscordUserId(),
                    f.getUsername(),
                    f.getGlobalName(),
                    buildAvatarUrl(f.getFriendDiscordUserId(), f.getAvatar()),
                    f.getStatus() != null ? f.getStatus().name() : "ACCEPTED",
                    f.getSyncedAt() != null ? f.getSyncedAt().toString() : null
            );
        }

        /** Discord CDN 头像 URL；avatar 为 null 时给默认头像 */
        private static String buildAvatarUrl(String userId, String avatar) {
            if (userId == null) return null;
            if (avatar == null || avatar.isBlank()) {
                // 默认头像：用 user_id discriminant 取模选 embed 蓝灰头像
                return "https://cdn.discordapp.com/embed/avatars/0.png";
            }
            String ext = avatar.startsWith("a_") ? "gif" : "png";
            return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + "." + ext;
        }
    }
}
