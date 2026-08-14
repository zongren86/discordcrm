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
import com.discordadmin.translation.LanguageDetectionService;
import com.discordadmin.translation.TranslationService;
import com.discordadmin.translation.TranslationServiceFactory;
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
    private final TranslationServiceFactory translationServiceFactory;
    private final LanguageDetectionService languageDetectionService;

    public MessageService(ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           DiscordAccountRepository discordAccountRepository,
                           DiscordBotManager discordBotManager,
                           DiscordUserClient discordUserClient,
                           SimpMessagingTemplate messagingTemplate,
                           TranslationService translationService,
                           TranslationServiceFactory translationServiceFactory,
                           LanguageDetectionService languageDetectionService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.discordBotManager = discordBotManager;
        this.discordUserClient = discordUserClient;
        this.messagingTemplate = messagingTemplate;
        this.translationService = translationService;
        this.translationServiceFactory = translationServiceFactory;
        this.languageDetectionService = languageDetectionService;
    }

    @Transactional
    public List<Message> listMessages(Long conversationId) {
        Conversation conversation = getConversation(conversationId);
        Long merchantId = conversation.getMerchantId();
        if (merchantId == null && conversation.getDiscordAccount() != null) {
            merchantId = conversation.getDiscordAccount().getMerchantId();
        }
        List<Message> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conversation);
        for (Message message : messages) {
            // 检测语言（如果尚未检测）
            if (message.getLanguage() == null || message.getLanguage().isEmpty()) {
                LanguageDetectionService.LanguageResult langResult = languageDetectionService.detect(message.getContent(), merchantId);
                if (langResult != null && langResult.isDetected()) {
                    message.setLanguage(langResult.getCode());
                }
            }
            // 翻译（如果尚未翻译）
            if (message.getTranslatedContent() == null) {
                String targetLanguage = message.getDirection() == Message.Direction.INBOUND ? "zh-CN" : "en";
                translationServiceFactory.translate(message.getContent(), targetLanguage, merchantId)
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
            messages = discordUserClient.listMessagesBefore(account.getToken(), channelId, beforeMsgId, 50);
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
                    && account.getDiscordId() != null
                    && authorId.equals(account.getDiscordId());

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
    public Message sendReply(Long conversationId, String content, String targetLanguage,
                              String messageType, String audioData, String audioMimeType,
                              Integer audioDuration, String audioFileName, String agentDisplayName) {
        boolean isVoiceMessage = "voice".equals(messageType) && audioData != null && !audioData.isBlank();

        if (!isVoiceMessage && (content == null || content.isBlank())) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        self.ensureAccountAssigned(conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

        Long merchantId = conversation.getMerchantId();
        if (merchantId == null && conversation.getDiscordAccount() != null) {
            merchantId = conversation.getDiscordAccount().getMerchantId();
        }

        String targetLang = targetLanguage;
        if ((targetLang == null || targetLang.isBlank()) && content != null && !content.isBlank()) {
            targetLang = containsChinese(content) ? "en" : "zh-CN";
        }

        String textToSend = content;
        if (!isVoiceMessage && content != null && !content.isBlank()) {
            textToSend = translationServiceFactory.translate(content, targetLang, merchantId)
                    .orElse(content);
        } else if (isVoiceMessage) {
            textToSend = content != null ? content : "[语音消息]";
        }

        LanguageDetectionService.LanguageResult langResult = null;
        if (content != null && !content.isBlank()) {
            langResult = languageDetectionService.detect(content, merchantId);
        }
        String detectedLang = (langResult != null && langResult.isDetected()) ? langResult.getCode() : null;

        DiscordAccount account = discordAccountRepository.findById(
                        conversation.getDiscordAccount().getId())
                .orElseThrow(() -> new IllegalStateException("Discord 账号不存在"));

        String discordMessageId = null;
        String discordAttachmentUrl = null;
        if (account.getAccountType() == DiscordAccount.AccountType.USER) {
            try {
                if (isVoiceMessage) {
                    byte[] audioBytes = java.util.Base64.getDecoder().decode(audioData);
                    // Discord 原生语音消息要求文件名必须以 voice-message. 开头
                    // 官方客户端默认扩展名 ogg，这里统一规范化
                    String fileName = normalizeVoiceFileName(audioFileName, audioMimeType);
                    String mimeType = normalizeVoiceMimeType(audioMimeType);
                    // 语音消息 content 必须为空，客户端才会自动显示原生语音条
                    String discordContent = "";
                    com.fasterxml.jackson.databind.JsonNode resp = discordUserClient.sendMessageWithFile(
                            account.getToken(), conversation.getChannelId(), discordContent,
                            fileName, audioBytes, mimeType, audioDuration, null);
                    discordMessageId = resp.path("id").asText(null);
                    discordAttachmentUrl = extractAttachmentUrl(resp);
                } else {
                    discordMessageId = discordUserClient.sendMessage(account.getToken(), conversation.getChannelId(), textToSend);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                    log.error("账号 [{}] token 已失效，无法发送消息", account.getName());
                    throw new IllegalStateException("账号「" + account.getName() + "」的 Discord 授权已失效，请重新登录该账号", e);
                }
                throw new IllegalStateException("消息发送失败: " + e.getMessage(), e);
            }
        } else {
            if (isVoiceMessage) {
                byte[] audioBytes = java.util.Base64.getDecoder().decode(audioData);
                String fileName = normalizeVoiceFileName(audioFileName, audioMimeType);
                String mimeType = normalizeVoiceMimeType(audioMimeType);
                try {
                    com.fasterxml.jackson.databind.JsonNode resp = discordUserClient.sendMessageWithFile(
                            account.getToken(), conversation.getChannelId(), "",
                            fileName, audioBytes, mimeType, audioDuration, null);
                    discordMessageId = resp.path("id").asText(null);
                    discordAttachmentUrl = extractAttachmentUrl(resp);
                } catch (Exception e) {
                    log.warn("Bot账号发送语音消息失败，尝试纯文本: {}", e.getMessage());
                    discordBotManager.sendMessage(account.getId(), conversation, textToSend);
                }
            } else {
                discordBotManager.sendMessage(account.getId(), conversation, textToSend);
            }
        }

        Message message = new Message();
        message.setConversation(conversation);
        message.setDirection(Message.Direction.OUTBOUND);
        message.setSenderName(account.getName());
        message.setContent(content != null ? content : "[语音消息]");
        message.setMessageType(isVoiceMessage ? "voice" : "text");
        if (isVoiceMessage) {
            message.setAudioDuration(audioDuration);
            message.setAudioMimeType(audioMimeType != null ? normalizeVoiceMimeType(audioMimeType) : "audio/ogg");
            message.setAudioData(audioData);
            // 优先用 Discord 返回的真实附件 URL（CDN 链接），前端才能跨设备 / 跨端播放
            if (discordAttachmentUrl != null) {
                message.setAudioUrl(discordAttachmentUrl);
            } else if (discordMessageId != null) {
                // 兜底：拼接跳转链接（注意：这个链接浏览器通常不能直接播放音频，只是一个消息定位）
                message.setAudioUrl("https://discord.com/channels/@me/"
                        + conversation.getChannelId() + "/" + discordMessageId);
            }
        }
        if (detectedLang != null) {
            message.setLanguage(detectedLang);
        }
        Instant now = Instant.now();
        message.setDiscordCreatedAt(now);
        message.setCreatedAt(now);
        if (discordMessageId != null) {
            message.setDiscordMessageId(discordMessageId);
        }
        if (!isVoiceMessage && !textToSend.equals(content)) {
            message.setTranslatedContent(textToSend);
        }
        message = messageRepository.save(message);

        String previewContent = content != null && !content.isBlank() ? content : "[语音消息]";
        conversation.setLastMessagePreview(previewContent.length() > 200 ? previewContent.substring(0, 200) : previewContent);
        conversation.setLastMessageDirection("OUTBOUND");
        conversation.setLastMessageAt(Instant.now());

        if (conversation.getStage() == Conversation.Stage.PROSPECT) {
            long inboundCount = messageRepository.countInboundMessages(conversation);
            if (inboundCount > 0) {
                conversation.setStage(Conversation.Stage.NEW);
                conversation.setStageChangedAt(Instant.now());
                log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", conversation.getId());
            }
        }

        conversationRepository.save(conversation);

        messagingTemplate.convertAndSend(
                "/topic/conversations/" + conversationId,
                MessageDto.from(message, conversation, account));
        messagingTemplate.convertAndSend("/topic/messages",
                MessageDto.from(message, conversation, account));
        messagingTemplate.convertAndSend("/topic/conversations", ConversationDto.from(conversation));
        return message;
    }

    /** 向后兼容的旧方法（3参数） */
    @Transactional
    public Message sendReply(Long conversationId, String content, String agentDisplayName) {
        return sendReply(conversationId, content, null, null, null, null, null, null, agentDisplayName);
    }

    /** 向后兼容的旧方法（4参数） */
    @Transactional
    public Message sendReply(Long conversationId, String content, String targetLanguage, String agentDisplayName) {
        return sendReply(conversationId, content, targetLanguage, null, null, null, null, null, agentDisplayName);
    }

    public void translateAndSave(Message message, String targetLanguage) {
        if (message.getTranslatedContent() == null) {
            Long merchantId = message.getMerchantId();
            if (merchantId == null && message.getConversation() != null) {
                merchantId = message.getConversation().getMerchantId();
            }
            translationServiceFactory.translate(message.getContent(), targetLanguage, merchantId)
                    .ifPresent(message::setTranslatedContent);
            if (message.getTranslatedContent() == null) {
                message.setTranslatedContent(message.getContent());
            }
        }
    }

    /** 手动翻译指定消息（自动检测语言） */
    @Transactional
    public Message translateMessage(Long messageId, String targetLanguage) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        Long merchantId = message.getMerchantId();
        if (merchantId == null && message.getConversation() != null) {
            merchantId = message.getConversation().getMerchantId();
        }
        
        // 自动检测语言并保存
        if (message.getLanguage() == null || message.getLanguage().isEmpty()) {
            LanguageDetectionService.LanguageResult langResult = languageDetectionService.detect(message.getContent(), merchantId);
            if (langResult != null && langResult.isDetected()) {
                message.setLanguage(langResult.getCode());
            }
        }
        
        translationServiceFactory.translate(message.getContent(), targetLanguage, merchantId)
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
        Long merchantId = message.getMerchantId();
        if (merchantId == null && message.getConversation() != null) {
            merchantId = message.getConversation().getMerchantId();
        }
        // 如果是出站消息包含中文，重新翻译
        if (message.getDirection() == Message.Direction.OUTBOUND && containsChinese(newContent)) {
            translationServiceFactory.translate(newContent, "en", merchantId)
                    .ifPresent(message::setTranslatedContent);
            if (message.getTranslatedContent() == null) message.setTranslatedContent(newContent);
        } else if (message.getDirection() == Message.Direction.INBOUND) {
            translationServiceFactory.translate(newContent, "zh-CN", merchantId)
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
    public Message sendReplyWithReference(Long conversationId, String content, String targetLanguage,
                                           String messageType, String audioData, String audioMimeType,
                                           Integer audioDuration, String audioFileName,
                                           String agentDisplayName, Long referencedMessageId) {
        Message replyMsg = sendReply(conversationId, content, targetLanguage,
                messageType, audioData, audioMimeType, audioDuration, audioFileName, agentDisplayName);
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

    /**
     * Discord 原生语音消息文件名必须以 "voice-message." 开头。
     * 官方客户端默认扩展名是 .ogg（Ogg+Opus）。我们尽量统一成 voice-message.ogg，
     * 如果用户自定义了扩展名，且不是常见音频格式，才原样保留（但仍强制前缀）。
     */
    private String normalizeVoiceFileName(String audioFileName, String audioMimeType) {
        String mime = audioMimeType == null ? "" : audioMimeType.toLowerCase();
        String fn = audioFileName == null ? "" : audioFileName.toLowerCase();
        // 先决定扩展名
        String ext = "ogg";
        if (fn.endsWith(".ogg") || mime.contains("ogg") || mime.contains("opus")) ext = "ogg";
        else if (fn.endsWith(".webm") || mime.contains("webm")) ext = "webm";
        else if (fn.endsWith(".mp3") || mime.contains("mpeg")) ext = "mp3";
        else if (fn.endsWith(".wav") || mime.contains("wav")) ext = "wav";
        else if (fn.endsWith(".m4a") || mime.contains("mp4") || mime.contains("m4a")) ext = "m4a";
        // 强制前缀 voice-message.
        return "voice-message." + ext;
    }

    /**
     * 按扩展名匹配规范化 MIME type，避免 webm 被标成 ogg 导致播不了。
     */
    private String normalizeVoiceMimeType(String audioMimeType) {
        String m = audioMimeType == null ? "" : audioMimeType.toLowerCase();
        if (m.contains("webm")) return "audio/webm";
        if (m.contains("mp3") || m.contains("mpeg")) return "audio/mpeg";
        if (m.contains("wav")) return "audio/wav";
        if (m.contains("mp4") || m.contains("m4a")) return "audio/mp4";
        // 兜底：ogg / opus / 空字符串 → ogg
        return "audio/ogg";
    }

    /**
     * 从 sendMessageWithFile 返回的响应里取出 attachments[0].url（真实 CDN 链接）。
     * 这个 URL 是永久可用的，前端可以直接播放。
     */
    private String extractAttachmentUrl(com.fasterxml.jackson.databind.JsonNode sendResp) {
        if (sendResp == null) return null;
        com.fasterxml.jackson.databind.JsonNode atts = sendResp.get("attachments");
        if (atts == null || !atts.isArray() || atts.isEmpty()) return null;
        String url = atts.get(0).path("url").asText(null);
        if (url != null) return url;
        return atts.get(0).path("proxy_url").asText(null);
    }

}
