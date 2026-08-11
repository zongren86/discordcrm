package com.discordadmin.discord;

import com.discordadmin.service.ConversationService;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * 每个被聚合的 Discord 账号拥有独立的 listener 实例，
 * 通过 accountId 标识消息来自哪个账号，便于 ConversationService 关联归属。
 */
public class DiscordMessageListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordMessageListener.class);

    private final Long accountId;
    private final ConversationService conversationService;

    public DiscordMessageListener(Long accountId, ConversationService conversationService) {
        this.accountId = accountId;
        this.conversationService = conversationService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            return;
        }

        String attachmentsJson = event.getMessage().getAttachments().isEmpty()
                ? null
                : event.getMessage().getAttachments().stream()
                        .map(Attachment::getUrl)
                        .collect(Collectors.joining(","));

        InboundMessage inbound = new InboundMessage(
                accountId,
                event.getMessageId(),
                event.getAuthor().getId(),
                event.getAuthor().getName(),
                event.getAuthor().getGlobalName(),
                event.getAuthor().getEffectiveAvatarUrl(),
                !event.isFromGuild(),
                event.isFromGuild() ? event.getGuild().getId() : null,
                event.isFromGuild() ? event.getGuild().getName() : null,
                event.getChannel().getId(),
                event.isFromGuild() ? event.getChannel().getName() : "私信",
                event.getMessage().getContentDisplay(),
                attachmentsJson
        );

        try {
            conversationService.handleInbound(inbound);
        } catch (Exception e) {
            log.error("处理入站Discord消息失败 (accountId={})", accountId, e);
        }
    }
}
