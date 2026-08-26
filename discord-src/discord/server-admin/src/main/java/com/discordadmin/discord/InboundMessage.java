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
        String attachmentsJson,
        String messageType,
        String audioUrl,
        String audioMimeType,
        Integer audioDuration,
        String audioData,
        String stickerItemsJson,
        String gifUrl
) {
    /** 兼容旧构造：无语音和sticker字段时调用 */
    public InboundMessage(Long discordAccountId, String discordMessageId, String authorUserId,
                          String authorUsername, String authorGlobalName, String authorAvatarUrl,
                          boolean isDirectMessage, String guildId, String guildName,
                          String channelId, String channelName, String content, String attachmentsJson) {
        this(discordAccountId, discordMessageId, authorUserId, authorUsername, authorGlobalName,
                authorAvatarUrl, isDirectMessage, guildId, guildName, channelId, channelName,
                content, attachmentsJson, "text", null, null, null, null, null, null);
    }

    /** 兼容语音消息构造：无sticker字段时调用 */
    public InboundMessage(Long discordAccountId, String discordMessageId, String authorUserId,
                          String authorUsername, String authorGlobalName, String authorAvatarUrl,
                          boolean isDirectMessage, String guildId, String guildName,
                          String channelId, String channelName, String content, String attachmentsJson,
                          String messageType, String audioUrl, String audioMimeType,
                          Integer audioDuration, String audioData) {
        this(discordAccountId, discordMessageId, authorUserId, authorUsername, authorGlobalName,
                authorAvatarUrl, isDirectMessage, guildId, guildName, channelId, channelName,
                content, attachmentsJson, messageType, audioUrl, audioMimeType,
                audioDuration, audioData, null, null);
    }

    /** 兼容sticker消息构造：无gifUrl字段时调用 */
    public InboundMessage(Long discordAccountId, String discordMessageId, String authorUserId,
                          String authorUsername, String authorGlobalName, String authorAvatarUrl,
                          boolean isDirectMessage, String guildId, String guildName,
                          String channelId, String channelName, String content, String attachmentsJson,
                          String messageType, String audioUrl, String audioMimeType,
                          Integer audioDuration, String audioData, String stickerItemsJson) {
        this(discordAccountId, discordMessageId, authorUserId, authorUsername, authorGlobalName,
                authorAvatarUrl, isDirectMessage, guildId, guildName, channelId, channelName,
                content, attachmentsJson, messageType, audioUrl, audioMimeType,
                audioDuration, audioData, stickerItemsJson, null);
    }
}
