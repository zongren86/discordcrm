package com.discordadmin.dto;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordUser;

import java.time.Instant;
import java.util.List;

public class ConversationDtos {

    public record DiscordUserDto(Long id, String discordUserId, String username, String globalName,
                                  String avatarUrl, String notes, String tags, String status) {
        public static DiscordUserDto from(DiscordUser u) {
            return new DiscordUserDto(u.getId(), u.getDiscordUserId(), u.getUsername(), u.getGlobalName(),
                    u.getAvatarUrl(), u.getNotes(), u.getTags(), u.getStatus().name());
        }
    }

    public record DiscordAccountDto(Long id, String name, String discordBotName, String status) {
        public static DiscordAccountDto from(DiscordAccount a) {
            if (a == null) return null;
            return new DiscordAccountDto(a.getId(), a.getName(), a.getDiscordBotName(),
                    a.getStatus().name());
        }
    }

    public record ConversationDto(
            Long id,
            String channelId,
            Long discordUserId,
            String friendDiscordUserId,
            String username,
            String globalName,
            String avatarUrl,
            Long discordAccountId,
            String discordAccountName,
            String type,
            String status,
            String lastMessagePreview,
            Instant lastMessageAt,
            String lastMessageDirection,
            String stage,
            Instant stageChangedAt,
            Boolean pinned,
            String remark,
            Instant lastReadAt,
            List<MessageDtos.MessageDto> messages,
            String agentName,
            String agentUsername,
            Long agentId,
            Integer unreadCount
    ) {
        public static ConversationDto from(Conversation c) {
            return from(c, 0);
        }

        public static ConversationDto from(Conversation c, int unreadCount) {
            List<MessageDtos.MessageDto> emptyMessages = List.of();
            DiscordUser du = c.getDiscordUser();
            String agentName = null;
            String agentUsername = null;
            Long agentId = null;

            // 优先使用会话分配的客服（转移后），否则用账号下的第一个客服
            if (c.getAssignedAgent() != null) {
                Agent agent = c.getAssignedAgent();
                agentName = agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername();
                agentUsername = agent.getUsername();
                agentId = agent.getId();
            } else if (c.getDiscordAccount() != null && c.getDiscordAccount().getAgents() != null 
                    && !c.getDiscordAccount().getAgents().isEmpty()) {
                Agent agent = c.getDiscordAccount().getAgents().iterator().next();
                agentName = agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername();
                agentUsername = agent.getUsername();
                agentId = agent.getId();
            }
            return new ConversationDto(
                    c.getId(),
                    c.getChannelId(),
                    du.getId(),
                    du.getDiscordUserId(),
                    du.getUsername(),
                    du.getGlobalName(),
                    du.getAvatarUrl(),
                    c.getDiscordAccount() != null ? c.getDiscordAccount().getId() : null,
                    c.getDiscordAccount() != null ? c.getDiscordAccount().getName() : null,
                    c.getType().name(),
                    c.getStatus().name(),
                    c.getLastMessagePreview(),
                    c.getLastMessageAt(),
                    c.getLastMessageDirection(),
                    c.getStage() != null ? c.getStage().name() : null,
                    c.getStageChangedAt(),
                    c.getPinned(),
                    c.getRemark(),
                    c.getLastReadAt(),
                    emptyMessages,
                    agentName,
                    agentUsername,
                    agentId,
                    unreadCount
            );
        }

        public ConversationDto withMessages(List<MessageDtos.MessageDto> messages) {
            return new ConversationDto(
                    id, channelId, discordUserId, friendDiscordUserId, username, globalName, avatarUrl,
                    discordAccountId, discordAccountName, type, status,
                    lastMessagePreview, lastMessageAt, lastMessageDirection,
                    stage, stageChangedAt, pinned, remark, lastReadAt, messages,
                    agentName, agentUsername, agentId, unreadCount
            );
        }

        public ConversationDto withUnreadCount(int unread) {
            return new ConversationDto(
                    id, channelId, discordUserId, friendDiscordUserId, username, globalName, avatarUrl,
                    discordAccountId, discordAccountName, type, status,
                    lastMessagePreview, lastMessageAt, lastMessageDirection,
                    stage, stageChangedAt, pinned, remark, lastReadAt, messages,
                    agentName, agentUsername, agentId, unread
            );
        }
    }

    public record UpdateStatusRequest(String status) {
    }

    public record UpdateStageRequest(String stage) {
    }

    public record UpdatePinRequest(Boolean pinned) {
    }

    public record UpdateRemarkRequest(String remark) {
    }

    public record UpdateUserRequest(String notes, String tags, String status) {
    }

    public record OpenDmRequest(Long accountId, String friendDiscordUserId) {
    }
}
