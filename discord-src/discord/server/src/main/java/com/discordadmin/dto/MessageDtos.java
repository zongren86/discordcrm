package com.discordadmin.dto;

import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Message;

import java.time.Instant;

public class MessageDtos {

    public record MessageDto(Long id, Long conversationId, String direction, String senderName,
                              String content, String translatedContent, String language, String attachmentsJson,
                              Instant createdAt, Instant discordCreatedAt, String senderAvatarUrl, String discordMessageId,
                              Long referencedMessageId, String referencedDiscordMessageId,
                              String reactions, Instant editedAt, Boolean isDeleted,
                              String messageType, String audioUrl, Integer audioDuration, String audioMimeType,
                              String audioData,
                              String asrText, String asrTranslated, String asrLanguage, String asrStatus, String asrError,
                              String gifUrl) {

        public static MessageDto from(Message m) {
            String avatarUrl = resolveAvatarUrl(m);
            return new MessageDto(
                    m.getId(),
                    m.getConversation().getId(),
                    m.getDirection().name(),
                    m.getSenderName(),
                    m.getContent(),
                    m.getTranslatedContent(),
                    m.getLanguage(),
                    m.getAttachmentsJson(),
                    m.getCreatedAt(),
                    m.getDiscordCreatedAt() != null ? m.getDiscordCreatedAt() : m.getCreatedAt(),
                    avatarUrl,
                    m.getDiscordMessageId(),
                    m.getReferencedMessageId(),
                    m.getReferencedDiscordMessageId(),
                    m.getReactionJson(),
                    m.getEditedAt(),
                    m.getIsDeleted() != null && m.getIsDeleted(),
                    m.getMessageType(),
                    m.getAudioUrl(),
                    m.getAudioDuration(),
                    m.getAudioMimeType(),
                    m.getAudioData(),
                    m.getAsrText(),
                    m.getAsrTranslated(),
                    m.getAsrLanguage(),
                    m.getAsrStatus(),
                    m.getAsrError(),
                    m.getGifUrl()
            );
        }

        public static MessageDto from(Message m, Conversation conv, DiscordAccount account) {
            String avatarUrl = resolveAvatarUrl(m, conv, account);
            return new MessageDto(
                    m.getId(),
                    m.getConversation().getId(),
                    m.getDirection().name(),
                    m.getSenderName(),
                    m.getContent(),
                    m.getTranslatedContent(),
                    m.getLanguage(),
                    m.getAttachmentsJson(),
                    m.getCreatedAt(),
                    m.getDiscordCreatedAt() != null ? m.getDiscordCreatedAt() : m.getCreatedAt(),
                    avatarUrl,
                    m.getDiscordMessageId(),
                    m.getReferencedMessageId(),
                    m.getReferencedDiscordMessageId(),
                    m.getReactionJson(),
                    m.getEditedAt(),
                    m.getIsDeleted() != null && m.getIsDeleted(),
                    m.getMessageType(),
                    m.getAudioUrl(),
                    m.getAudioDuration(),
                    m.getAudioMimeType(),
                    m.getAudioData(),
                    m.getAsrText(),
                    m.getAsrTranslated(),
                    m.getAsrLanguage(),
                    m.getAsrStatus(),
                    m.getAsrError(),
                    m.getGifUrl()
            );
        }

        private static String resolveAvatarUrl(Message m) {
            Conversation conv = m.getConversation();
            DiscordAccount account = conv != null ? conv.getDiscordAccount() : null;
            return resolveAvatarUrl(m, conv, account);
        }

        private static String resolveAvatarUrl(Message m, Conversation conv, DiscordAccount account) {
            if (m.getDirection() == Message.Direction.INBOUND) {
                if (conv != null && conv.getDiscordUser() != null) {
                    return conv.getDiscordUser().getAvatarUrl();
                }
            } else {
                if (account != null && account.getAvatarUrl() != null) {
                    return account.getAvatarUrl();
                }
            }
            return null;
        }
    }

    public record SendMessageRequest(String content, String targetLanguage, String messageType,
                                      String audioData, String audioMimeType, Integer audioDuration, String audioFileName) {
    }
}
