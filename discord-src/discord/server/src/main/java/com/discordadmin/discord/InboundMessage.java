package com.discordadmin.discord;

public record InboundMessage(
        Long discordAccountId,
        String discordMessageId,
        String authorUserId,
        String authorUsername,
        String authorGlobalName,
        String authorAvatarUrl,
        boolean isDirectMessage,
        String guildId,
        String guildName,
        String channelId,
        String channelName,
        String content,
        String attachmentsJson
) {
}
