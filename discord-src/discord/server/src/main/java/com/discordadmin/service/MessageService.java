package com.discordadmin.service;

import com.discordadmin.discord.DiscordBotManager;
import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.dto.ConversationDtos.ConversationDto;
import com.discordadmin.dto.MessageDtos.MessageDto;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Message;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.translation.TranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class MessageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageService.class);

    @Autowired @Lazy
    private MessageService self;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final DiscordBotManager discordBotManager;
    private final DiscordUserClient discordUserClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final TranslationService translationService;

    public MessageService(ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           DiscordAccountRepository discordAccountRepository,
                           DiscordBotManager discordBotManager,
                           DiscordUserClient discordUserClient,
                           SimpMessagingTemplate messagingTemplate,
                           TranslationService translationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.discordBotManager = discordBotManager;
        this.discordUserClient = discordUserClient;
        this.messagingTemplate = messagingTemplate;
        this.translationService = translationService;
    }

    @Transactional
    public List<Message> listMessages(Long conversationId) {
        Conversation conversation = getConversation(conversationId);
        List<Message> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conversation);
        for (Message message : messages) {
            if (message.getTranslatedContent() == null) {
                String targetLanguage = message.getDirection() == Message.Direction.INBOUND ? "zh-CN" : "en";
                translationService.translate(message.getContent(), targetLanguage)
                        .ifPresent(message::setTranslatedContent);
            }
        }
        messages = messageRepository.saveAll(messages);
        // Force lazy loading of discordAccount for avatar URL resolution
        for (Message msg : messages) {
            Conversation conv = msg.getConversation();
            if (conv != null && conv.getDiscordAccount() != null) {
                conv.getDiscordAccount().getAvatarUrl(); // force init
            }
            if (conv != null && conv.getDiscordUser() != null) {
                conv.getDiscordUser().getAvatarUrl(); // force init
            }
        }
        return messages;
    }

    /**
     * 加载更多历史消息：从Discord API拉取指定消息ID之前的消息，保存到数据库并返回。
     */
    @Transactional
    public List<Message> loadMoreHistory(Long conversationId, String beforeMsgId) {
        Conversation conv = getConversation(conversationId);
        DiscordAccount account = conv.getDiscordAccount();
        if (account == null || account.getAccountType() != DiscordAccount.AccountType.USER) {
            return List.of();
        }
        String channelId = conv.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return List.of();
        }

        JsonNode messages;
        try {
            messages = discordUserClient.listMessagesBefore(account.getBotToken(), channelId, beforeMsgId, 50);
        } catch (Exception e) {
            log.warn("加载更多历史消息失败: convId={}, before={}, err={}", conversationId, beforeMsgId, e.getMessage());
            return List.of();
        }
        if (messages == null || !messages.isArray() || messages.size() == 0) {
            return List.of();
        }

        List<Message> result = new java.util.ArrayList<>();
        for (JsonNode msgNode : messages) {
            String msgId = msgNode.path("id").asText(null);
            if (msgId == null) continue;
            if (messageRepository.findByConversationAndDiscordMessageId(conv, msgId).isPresent()) continue;

            JsonNode author = msgNode.path("author");
            String authorId = author.path("id").asText(null);
            String authorName = author.path("global_name").asText(
                    author.path("username").asText("Unknown"));
            String content = msgNode.path("content").asText("");

            boolean isOutbound = authorId != null
                    && account.getDiscordBotId() != null
                    && authorId.equals(account.getDiscordBotId());

            Message msgEntity = new Message();
            msgEntity.setConversation(conv);
            msgEntity.setDiscordMessageId(msgId);
            msgEntity.setDirection(isOutbound ? Message.Direction.OUTBOUND : Message.Direction.INBOUND);
            msgEntity.setSenderName(isOutbound ? account.getName() : authorName);
            msgEntity.setSenderDiscordUserId(authorId);
            msgEntity.setContent(content);
            msgEntity.setCreatedAt(Instant.now());

            if (!isOutbound) {
                try {
                    translateAndSave(msgEntity, "zh-CN");
                } catch (Exception e) {
                    msgEntity.setTranslatedContent(content);
                }
            } else {
                msgEntity.setTranslatedContent(content);
            }

            msgEntity = messageRepository.save(msgEntity);
            result.add(msgEntity);
        }
        // Force lazy loading
        for (Message msg : result) {
            Conversation c = msg.getConversation();
            if (c != null && c.getDiscordAccount() != null) c.getDiscordAccount().getAvatarUrl();
            if (c != null && c.getDiscordUser() != null) c.getDiscordUser().getAvatarUrl();
        }
        // 按时间正序返回（最早在前）
        result.sort(java.util.Comparator.comparing(Message::getCreatedAt));
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Conversation ensureAccountAssigned(Long conversationId) {
        Conversation conversation = getConversation(conversationId);
        if (conversation.getDiscordAccount() == null) {
            var activeAccounts = discordAccountRepository.findByStatus(com.discordadmin.entity.DiscordAccount.AccountStatus.ACTIVE);
            if (activeAccounts.isEmpty()) {
                throw new IllegalStateException("没有可用的 Discord 账号，请在「账号管理」中添加并启用");
            }
            var assigned = activeAccounts.stream()
                    .filter(a -> a.getAccountType() == com.discordadmin.entity.DiscordAccount.AccountType.USER
                            || discordBotManager.isConnected(a.getId()))
                    .findFirst()
                    .orElse(activeAccounts.get(0));
            conversation.setDiscordAccount(assigned);
            conversationRepository.save(conversation);
            log.info("会话 [{}] 分配账号: {}", conversationId, assigned.getName());
        }
        return conversation;
    }

    @Transactional
    public Message sendReply(Long conversationId, String content, String agentDisplayName) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        self.ensureAccountAssigned(conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

        // 仅当内容包含中文时才翻译为英文，否则直接发送原文
        String textToSend = containsChinese(content)
                ? translationService.translate(content, "en").orElse(content)
                : content;

        DiscordAccount account = discordAccountRepository.findById(
                        conversation.getDiscordAccount().getId())
                .orElseThrow(() -> new IllegalStateException("Discord 账号不存在"));

        String discordMessageId = null;
        if (account.getAccountType() == DiscordAccount.AccountType.USER) {
            try {
                discordMessageId = discordUserClient.sendMessage(account.getBotToken(), conversation.getChannelId(), textToSend);
            } catch (Exception e) {
                // 检查是否是 token 失效错误
                if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                    log.error("账号 [{}] token 已失效，无法发送消息", account.getName());
                    throw new IllegalStateException("账号「" + account.getName() + "」的 Discord 授权已失效，请重新登录该账号", e);
                }
                throw new IllegalStateException("消息发送失败: " + e.getMessage(), e);
            }
        } else {
            discordBotManager.sendMessage(account.getId(), conversation, textToSend);
        }

        Message message = new Message();
        message.setConversation(conversation);
        message.setDirection(Message.Direction.OUTBOUND);
        message.setSenderName(account.getName());
        message.setContent(content);
        Instant now = Instant.now();
        message.setDiscordCreatedAt(now);
        message.setCreatedAt(now);
        if (discordMessageId != null) {
            message.setDiscordMessageId(discordMessageId);
        }
        if (!textToSend.equals(content)) {
            message.setTranslatedContent(textToSend);
        }
        message = messageRepository.save(message);

        conversation.setLastMessagePreview(content.length() > 200 ? content.substring(0, 200) : content);
        conversation.setLastMessageDirection("OUTBOUND");
        conversation.setLastMessageAt(Instant.now());
        conversationRepository.save(conversation);

        messagingTemplate.convertAndSend(
                "/topic/conversations/" + conversationId,
                MessageDto.from(message, conversation, account));
        messagingTemplate.convertAndSend("/topic/messages",
                MessageDto.from(message, conversation, account));
        messagingTemplate.convertAndSend("/topic/conversations", ConversationDto.from(conversation));
        return message;
    }

    public void translateAndSave(Message message, String targetLanguage) {
        if (message.getTranslatedContent() == null) {
            translationService.translate(message.getContent(), targetLanguage)
                    .ifPresent(message::setTranslatedContent);
            if (message.getTranslatedContent() == null) {
                message.setTranslatedContent(message.getContent());
            }
        }
    }

    /** 手动翻译指定消息 */
    @Transactional
    public Message translateMessage(Long messageId, String targetLanguage) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        translationService.translate(message.getContent(), targetLanguage)
                .ifPresent(message::setTranslatedContent);
        if (message.getTranslatedContent() == null) {
            message.setTranslatedContent(message.getContent());
        }
        return messageRepository.save(message);
    }

    /** 编辑消息内容 */
    @Transactional
    public Message editMessage(Long messageId, String newContent) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        message.setContent(newContent);
        message.setTranslatedContent(null);
        message.setEditedAt(Instant.now());
        // 如果是出站消息包含中文，重新翻译
        if (message.getDirection() == Message.Direction.OUTBOUND && containsChinese(newContent)) {
            translationService.translate(newContent, "en")
                    .ifPresent(message::setTranslatedContent);
            if (message.getTranslatedContent() == null) message.setTranslatedContent(newContent);
        } else if (message.getDirection() == Message.Direction.INBOUND) {
            translationService.translate(newContent, "zh-CN")
                    .ifPresent(message::setTranslatedContent);
            if (message.getTranslatedContent() == null) message.setTranslatedContent(newContent);
        } else {
            message.setTranslatedContent(newContent);
        }
        Message saved = messageRepository.save(message);
        messagingTemplate.convertAndSend(
                "/topic/conversations/" + message.getConversation().getId(),
                MessageDto.from(saved));
        return saved;
    }

    /** 软删除消息 */
    @Transactional
    public Message deleteMessage(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        message.setIsDeleted(true);
        Message saved = messageRepository.save(message);
        messagingTemplate.convertAndSend(
                "/topic/conversations/" + message.getConversation().getId(),
                MessageDto.from(saved));
        return saved;
    }

    /** 添加/移除表情反应 */
    @Transactional
    public Message addReaction(Long messageId, String emoji, boolean remove) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        String currentJson = message.getReactionJson();
        java.util.Map<String, Integer> reactions = new java.util.LinkedHashMap<>();
        if (currentJson != null && !currentJson.isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = om.readTree(currentJson);
                node.fields().forEachRemaining(e -> reactions.put(e.getKey(), e.getValue().asInt()));
            } catch (Exception e) {
                // ignore parse errors
            }
        }
        if (remove) {
            reactions.remove(emoji);
        } else {
            reactions.merge(emoji, 1, Integer::sum);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            message.setReactionJson(om.writeValueAsString(reactions));
        } catch (Exception e) {
            throw new RuntimeException("序列化失败", e);
        }
        Message saved = messageRepository.save(message);
        messagingTemplate.convertAndSend(
                "/topic/conversations/" + message.getConversation().getId(),
                MessageDto.from(saved));
        return saved;
    }

    /** 带引用回复消息 */
    @Transactional
    public Message sendReplyWithReference(Long conversationId, String content, String agentDisplayName, Long referencedMessageId) {
        Message replyMsg = sendReply(conversationId, content, agentDisplayName);
        if (referencedMessageId != null) {
            replyMsg.setReferencedMessageId(referencedMessageId);
            try {
                Message refMsg = messageRepository.findById(referencedMessageId).orElse(null);
                if (refMsg != null && refMsg.getDiscordMessageId() != null) {
                    replyMsg.setReferencedDiscordMessageId(refMsg.getDiscordMessageId());
                }
            } catch (Exception e) {
                log.warn("设置引用消息ID失败: {}", e.getMessage());
            }
            replyMsg = messageRepository.save(replyMsg);
            messagingTemplate.convertAndSend(
                    "/topic/conversations/" + conversationId,
                    MessageDto.from(replyMsg));
        }
        return replyMsg;
    }

    private Conversation getConversation(Long id) {
        return conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
    }

    /**
     * 检测文本是否包含中文字符
     */
    private boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

}
