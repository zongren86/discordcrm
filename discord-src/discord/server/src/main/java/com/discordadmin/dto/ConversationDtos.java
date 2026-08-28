package com.discordadmin.dto;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordUser;

import java.time.Instant;
import org.hibernate.Hibernate;
import java.util.List;

public class ConversationDtos {

    public record DiscordUserDto(Long id, String discordUserId, String username, String globalName,
                                  String avatarUrl, String notes, String tags, String status,
                                  Instant lastActiveAt, String presence, Instant presenceUpdatedAt) {
        public static DiscordUserDto from(DiscordUser u) {
            return new DiscordUserDto(u.getId(), u.getDiscordUserId(), u.getUsername(), u.getGlobalName(),
                    u.getAvatarUrl(), u.getNotes(), u.getTags(), u.getStatus().name(),
                    u.getLastActiveAt(), u.getPresence(), u.getPresenceUpdatedAt());
        }
    }

    public record DiscordAccountDto(Long id, String name, String discordName, String status) {
        public static DiscordAccountDto from(DiscordAccount a) {
            if (a == null) return null;
            return new DiscordAccountDto(a.getId(), a.getName(), a.getDiscordName(),
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
            Instant friendLastActiveAt,
            String friendPresence,
            Instant friendPresenceUpdatedAt,
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
            Integer unreadCount,
            String associatedUserName,
            Instant createdAt
    ) {
        public static ConversationDto from(Conversation c) {
            return from(c, 0, null);
        }

        public static ConversationDto from(Conversation c, int unreadCount) {
            return from(c, unreadCount, null);
        }

        public static ConversationDto from(Conversation c, int unreadCount, String associatedUserName) {
            List<MessageDtos.MessageDto> emptyMessages = List.of();
            // 使用 Hibernate.isInitialized 防止 LAZY 代理在事务关闭后抛 LazyInitializationException
            DiscordUser du = (c.getDiscordUser() != null && Hibernate.isInitialized(c.getDiscordUser()))
                    ? c.getDiscordUser() : null;
            String agentName = null;
            String agentUsername = null;
            Long agentId = null;

            // 仅当会话有明确分配的客服（转移后）时才设置agent信息
            if (c.getAssignedAgent() != null && Hibernate.isInitialized(c.getAssignedAgent())) {
                Agent agent = c.getAssignedAgent();
                agentName = agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername();
                agentUsername = agent.getUsername();
                agentId = agent.getId();
            }
            return new ConversationDto(
                    c.getId(),
                    c.getChannelId(),
                    du != null ? du.getId() : null,
                    du != null ? du.getDiscordUserId() : null,
                    du != null ? du.getUsername() : null,
                    du != null ? du.getGlobalName() : null,
                    du != null ? du.getAvatarUrl() : null,
                    du != null ? du.getLastActiveAt() : null,
                    du != null ? du.getPresence() : null,
                    du != null ? du.getPresenceUpdatedAt() : null,
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
                    unreadCount,
                    associatedUserName,
                    c.getCreatedAt()
            );
        }

        public ConversationDto withMessages(List<MessageDtos.MessageDto> messages) {
            return new ConversationDto(
                    id, channelId, discordUserId, friendDiscordUserId, username, globalName, avatarUrl,
                    friendLastActiveAt, friendPresence, friendPresenceUpdatedAt,
                    discordAccountId, discordAccountName, type, status,
                    lastMessagePreview, lastMessageAt, lastMessageDirection,
                    stage, stageChangedAt, pinned, remark, lastReadAt, messages,
                    agentName, agentUsername, agentId, unreadCount, associatedUserName, createdAt
            );
        }

        public ConversationDto withUnreadCount(int unread) {
            return new ConversationDto(
                    id, channelId, discordUserId, friendDiscordUserId, username, globalName, avatarUrl,
                    friendLastActiveAt, friendPresence, friendPresenceUpdatedAt,
                    discordAccountId, discordAccountName, type, status,
                    lastMessagePreview, lastMessageAt, lastMessageDirection,
                    stage, stageChangedAt, pinned, remark, lastReadAt, messages,
                    agentName, agentUsername, agentId, unread, associatedUserName, createdAt
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
