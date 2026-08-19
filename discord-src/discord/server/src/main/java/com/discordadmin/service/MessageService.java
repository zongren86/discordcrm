package com.discordadmin.service;

import com.discordadmin.discord.DiscordBotManager;
import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.dto.ConversationDtos;
import com.discordadmin.dto.ConversationDtos.ConversationDto;
import com.discordadmin.dto.MessageDtos;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.discordadmin.asr.SpeechRecognitionService;

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
    private final SpeechRecognitionService speechRecognitionService;

    public MessageService(ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           DiscordAccountRepository discordAccountRepository,
                           DiscordBotManager discordBotManager,
                           DiscordUserClient discordUserClient,
                           SimpMessagingTemplate messagingTemplate,
                           TranslationService translationService,
                           TranslationServiceFactory translationServiceFactory,
                           LanguageDetectionService languageDetectionService,
                           SpeechRecognitionService speechRecognitionService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.discordBotManager = discordBotManager;
        this.discordUserClient = discordUserClient;
        this.messagingTemplate = messagingTemplate;
        this.translationService = translationService;
        this.translationServiceFactory = translationServiceFactory;
        this.languageDetectionService = languageDetectionService;
        this.speechRecognitionService = speechRecognitionService;
    }

    @Transactional(readOnly = true)
    public List<Message> listMessages(Long conversationId) {
        // 保持向后兼容，但默认走最近 N 天（这里相当于不限制天数，仍做性能优化：不调用外部翻译/语言检测、不saveAll）
        return listRecentOrAll(conversationId, null, null, null, Integer.MAX_VALUE).messages();
    }

    /** 消息列表（默认最近最近 daysBack 天，0/null 表示默认当天=1天；使用游标分页以支持上滑加载更早历史）。
     *  当指定天数内（常见是当天）没有消息时，会自动向前回退，展示"最后一天有消息"的最近一页（最多回退 30 天）。 */
    @Transactional(readOnly = true)
    public MessageSlice listRecentMessages(Long conversationId, Integer daysBack, Integer pageSize) {
        int days = (daysBack == null || daysBack <= 0) ? 1 : daysBack;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        Instant since = Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        MessageSlice slice = listRecentOrAll(conversationId, since, null, null, size);
        if (!slice.messages().isEmpty()) {
            return slice;
        }
        // 指定天数内没消息 → 自动回退：不限制时间范围，直接取最新一页（展示最后一天有消息的那条天）
        Instant fallbackSince = Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
        MessageSlice fallback = listRecentOrAll(conversationId, fallbackSince, null, null, size);
        if (!fallback.messages().isEmpty()) {
            // 仍标记有更早（因为我们可能只展示了最近20条，DB还有更老）
            boolean hasOlder = fallback.hasOlder() || !fallback.messages().isEmpty();
            return new MessageSlice(fallback.messages(), hasOlder);
        }
        // 30 天内都没消息，才返回空
        return fallback;
    }

    /** 游标分页：加载比 (oldestCreatedAt, oldestId) 更早的一页消息 */
    @Transactional(readOnly = true)
    public MessageSlice listOlderMessages(Long conversationId, Instant oldestCreatedAt, Long oldestId, Integer pageSize) {
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (oldestCreatedAt == null || oldestId == null) {
            // 客户端没传游标（比如刚打开会话当天没消息）：退化成最近 30 天最近一页
            return listRecentMessages(conversationId, 30, size);
        }
        return listRecentOrAll(conversationId, null, oldestCreatedAt, oldestId, size);
    }

    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 统一的消息列表加载：
     *   1) 不做实时语言检测 / 实时翻译（改为按需：点按钮翻译、前端先展示原文/已缓存译文）；
     *   2) 不调用 saveAll，避免每条消息产生 UPDATE；
     *   3) 支持最近 since 时间范围 / 游标分页 / 限制页大小；
     *   4) 返回前按 created_at ASC 排序（方便前端顺序渲染）；
     *   5) 对 conversation/discordUser/discordAccount 做统一 eager 获取，避免 N+1（默认 LAZY，但不强制初始化 audioData）。
     */
    private MessageSlice listRecentOrAll(Long conversationId,
                                         Instant since,
                                         Instant cursorBeforeAt,
                                         Long cursorBeforeId,
                                         int limit) {
        Conversation conversation = getConversation(conversationId);
        org.springframework.data.domain.Pageable page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        org.springframework.data.domain.Slice<Message> slice;

        if (cursorBeforeAt != null && cursorBeforeId != null) {
            slice = messageRepository.findOlderByConversation(conversation, cursorBeforeAt, cursorBeforeId, page);
        } else {
            slice = messageRepository.findLatestByConversation(conversation, page);
        }

        // Slice 按 createdAt DESC 返回。先"裁剪 since 过滤"（若指定）
        java.util.List<Message> collected = new java.util.ArrayList<>();
        boolean allExhausted = false;
        boolean cursorHitEarlierLimit = false;
        for (Message m : slice.getContent()) {
            if (since != null && m.getCreatedAt() != null && m.getCreatedAt().isBefore(since)) {
                // 比"最近N天"还早的消息在默认加载时不返回；但仍然标记"还有更早"
                cursorHitEarlierLimit = true;
                continue;
            }
            collected.add(m);
        }
        // 按 created_at ASC 排（前端渲染从旧到新）
        collected.sort(java.util.Comparator.comparing(Message::getCreatedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(Message::getId, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));

        boolean hasOlder = slice.hasNext() || cursorHitEarlierLimit;

        // 加载对话必要的 avatar（discordUser/discordAccount 是 LAZY，但这部分字段都很小）
        for (Message m : collected) {
            Conversation conv = m.getConversation();
            if (conv != null) {
                if (conv.getDiscordAccount() != null) conv.getDiscordAccount().getAvatarUrl();
                if (conv.getDiscordUser() != null) conv.getDiscordUser().getAvatarUrl();
            }
        }
        // slice.hasNext() 只代表 DB 里下一页；如果我们用 since 过滤出还有更早但没被加载的，也应算 hasOlder=true
        // 但若当前 collected 为空且 slice.hasNext()=true, 说明下一页可能全在 since 之前；也保留 hasOlder，允许用户继续滚（届时 since 已不再用，cursor 控制）
        return new MessageSlice(collected, hasOlder);
    }

    /** Slice 形式的消息列表（避免依赖 Spring Data Slice 对外层 API） */
    public static class MessageSlice {
        private final java.util.List<Message> messages;
        private final boolean hasOlder;
        public MessageSlice(java.util.List<Message> messages, boolean hasOlder) {
            this.messages = messages; this.hasOlder = hasOlder;
        }
        public java.util.List<Message> messages() { return messages; }
        public boolean hasOlder() { return hasOlder; }
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
                // 语音消息：content 还是占位 "[语音消息]" 或空，不要立刻翻译占位文本，
                // 等 ASR 转写+翻译完成后由 runAsrAsync 统一写入 translated_content / asrTranslated / content
                boolean likelyVoice = msgEntity.getAudioUrl() != null && !msgEntity.getAudioUrl().isBlank();
                if (likelyVoice || (content != null && "[语音消息]".equals(content))) {
                    msgEntity.setTranslatedContent(content);
                    msgEntity.setMessageType("voice");
                } else {
                    try {
                        translateAndSave(msgEntity, "zh-CN");
                    } catch (Exception e) {
                        msgEntity.setTranslatedContent(content);
                    }
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

        String targetLang = normalizeLanguageCode(targetLanguage);
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

        // 语音消息：自动触发 ASR 转写（后台用户也可以手动点，不依赖这个自动流程）
        // INBOUND：转写+自动翻译成中文；OUTBOUND：只转写原文（用户发的原文，翻译看发送前已处理）
        if (isVoiceMessage) {
            final Long mId = message.getId();
            final Long mMerchantId = merchantId;
            final boolean inboundAuto = false; // outbound 不自动翻译 ASR 文本
            try {
                // 使用事务同步：在事务提交后再调用 @Async
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            self.runAsrAsync(mId, mMerchantId, inboundAuto);
                        }
                    });
                } else {
                    self.runAsrAsync(mId, mMerchantId, inboundAuto);
                }
            } catch (Exception e) {
                log.warn("触发 OUTBOUND 语音自动转写失败 mId={}: {}", mId, e.getMessage());
            }
        }

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

    /** 异步翻译消息（供轮询器在事务提交后调用，不阻塞主流程） */
    @Async
    public void translateMessageAsync(Long messageId, String targetLanguage) {
        try {
            Message message = messageRepository.findById(messageId).orElse(null);
            if (message == null) return;
            // 已经有翻译内容（不是原文占位）则不重复翻译
            if (message.getTranslatedContent() != null
                    && !message.getTranslatedContent().equals(message.getContent())) {
                return;
            }
            log.info("异步翻译消息 msgId={}, contentLen={}", messageId,
                    message.getContent() != null ? message.getContent().length() : 0);
            Long merchantId = message.getMerchantId();
            if (merchantId == null && message.getConversation() != null) {
                merchantId = message.getConversation().getMerchantId();
            }
            String translated = translationServiceFactory.translate(
                    message.getContent(), targetLanguage, merchantId)
                    .orElse(message.getContent());
            message.setTranslatedContent(translated);
            messageRepository.save(message);
            log.info("异步翻译完成 msgId={}", messageId);
            // 推送更新给前端
            MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(message);
            messagingTemplate.convertAndSend("/topic/messages", dto);
        } catch (Exception e) {
            log.warn("异步翻译失败 msgId={}: {}", messageId, e.getMessage());
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

        String normalizedTargetLang = normalizeLanguageCode(targetLanguage);

        // 语音消息：专门翻译 asrText，写入 asrTranslated
        if ("voice".equalsIgnoreCase(message.getMessageType())) {
            String asrText = message.getAsrText();
            if (asrText == null || asrText.isBlank()) {
                throw new IllegalStateException("语音尚未转文字，请先转写或等系统自动转文字完成");
            }
            String lang = message.getAsrLanguage();
            if (lang == null || lang.isBlank()) {
                LanguageDetectionService.LanguageResult lr = languageDetectionService.detect(asrText, merchantId);
                if (lr != null && lr.isDetected()) {
                    message.setAsrLanguage(lr.getCode());
                }
            }
            translationServiceFactory.translate(asrText, normalizedTargetLang, merchantId)
                    .ifPresent(message::setAsrTranslated);
            if (message.getAsrTranslated() == null) message.setAsrTranslated(asrText);
            return saveAndBroadcast(message);
        }

        // 自动检测语言并保存
        if (message.getLanguage() == null || message.getLanguage().isEmpty()) {
            LanguageDetectionService.LanguageResult langResult = languageDetectionService.detect(message.getContent(), merchantId);
            if (langResult != null && langResult.isDetected()) {
                message.setLanguage(langResult.getCode());
            }
        }
        
        translationServiceFactory.translate(message.getContent(), normalizedTargetLang, merchantId)
                .ifPresent(message::setTranslatedContent);
        if (message.getTranslatedContent() == null) {
            message.setTranslatedContent(message.getContent());
        }
        return saveAndBroadcast(message);
    }

    /**
     * 方便在 handleInbound 末尾（事务已提交）调用：把 pending 写入 DB 并广播出去，再触发异步 runAsrAsync。
     * 注意：外部若在事务中，也可直接调 transcribeAsr(Long,boolean)。
     */
    @Transactional
    public Message transcribeAsrAsync(Long messageId, Long merchantId, boolean autoTranslate) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        if (!"voice".equalsIgnoreCase(message.getMessageType())) {
            return message;
        }
        Long mId = merchantId;
        if (mId == null) {
            if (message.getConversation() != null) {
                mId = message.getConversation().getMerchantId();
                if (mId == null && message.getConversation().getDiscordAccount() != null) {
                    mId = message.getConversation().getDiscordAccount().getMerchantId();
                }
            }
        }
        // 避免重复触发：如果已经在 pending 就不再重置/不重复提交
        boolean needTrigger = !"pending".equalsIgnoreCase(message.getAsrStatus());
        if (needTrigger) {
            message.setAsrStatus("pending");
            message.setAsrError(null);
            message = messageRepository.save(message);
            String convId = message.getConversation() != null ? message.getConversation().getId().toString() : "0";
            messagingTemplate.convertAndSend("/topic/conversations/" + convId, MessageDto.from(message));
            messagingTemplate.convertAndSend("/topic/messages", MessageDto.from(message));
        }
        final boolean fTranslate = autoTranslate || message.getDirection() == Message.Direction.INBOUND;
        final Long fMerchantId = mId;
        final Long fMsgId = message.getId();
        // 使用事务同步：在事务提交后再调用 @Async
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.runAsrAsync(fMsgId, fMerchantId, fTranslate);
                }
            });
        } else {
            self.runAsrAsync(fMsgId, fMerchantId, fTranslate);
        }
        return message;
    }

    /**
     * 触发语音消息的语音转文字。
     * @param messageId 消息ID
     * @param autoTranslate INBOUND 时如果为 true，则在识别完成后自动把 asrText 翻译为中文（后台默认语言）
     * @return 更新后的 Message
     */
    @Transactional
    public Message transcribeAsr(Long messageId, boolean autoTranslate) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        if (!"voice".equalsIgnoreCase(message.getMessageType())) {
            return message;
        }
        Long merchantId = message.getMerchantId();
        if (merchantId == null && message.getConversation() != null) {
            merchantId = message.getConversation().getMerchantId();
            if (merchantId == null && message.getConversation().getDiscordAccount() != null) {
                merchantId = message.getConversation().getDiscordAccount().getMerchantId();
            }
        }

        message.setAsrStatus("pending");
        message.setAsrError(null);
        message = messageRepository.save(message);
        final Long mId = message.getId();
        final Long fMerchant = merchantId;
        final boolean fTranslate = autoTranslate || message.getDirection() == Message.Direction.INBOUND;
        // 使用事务同步：在事务提交后再调用 @Async
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    self.runAsrAsync(mId, fMerchant, fTranslate);
                }
            });
        } else {
            self.runAsrAsync(mId, fMerchant, fTranslate);
        }
        return message;
    }

    /**
     * 异步执行 ASR + 自动翻译，并广播更新。
     * 必须跑在新事务里（propagation=REQUIRES_NEW）否则 save 看不到。
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runAsrAsync(Long messageId, Long merchantId, boolean autoTranslate) {
        Message m;
        try {
            m = messageRepository.findById(messageId).orElse(null);
        } catch (Exception e) {
            log.warn("ASR 查消息失败: msgId={} err={}", messageId, e.getMessage());
            return;
        }
        if (m == null) return;
        try {
            byte[] audioBytes = resolveAudioBytes(m);
            // 允许 audioBytes 为空：此时仍用 audioUrl 走公网 URL 的异步 paraformer/sensevoice 策略（DashScope 去拉 CDN）
            if ((audioBytes == null || audioBytes.length == 0)
                    && (m.getAudioUrl() == null || m.getAudioUrl().isBlank())) {
                m.setAsrStatus("failed");
                m.setAsrError("无法取得音频数据（audioData/audioUrl 均为空或下载失败）");
                saveAndBroadcast(m);
                return;
            }
            String audioLogSize = audioBytes == null ? "null" : (audioBytes.length / 1024) + "KB";
            log.info("ASR 开始转写: msgId={} direction={} audioSize={} audioMime={} hasUrl={} merchantId={}",
                    m.getId(), m.getDirection(), audioLogSize, m.getAudioMimeType(),
                    m.getAudioUrl() != null && !m.getAudioUrl().isBlank(), merchantId);
            SpeechRecognitionService.AsrResult r = speechRecognitionService
                    .transcribe(merchantId, audioBytes, m.getAudioMimeType(), m.getAudioDuration(), m.getAudioUrl())
                    .orElse(null);
            if (r == null || r.text() == null || r.text().isBlank()) {
                m.setAsrStatus("failed");
                m.setAsrError("语音识别结果为空（可能是空白录音或 ASR 无可用配置，请配置百炼翻译/语音识别key）");
                saveAndBroadcast(m);
                return;
            }
            log.info("ASR 转写成功: msgId={} textLen={} lang={}", m.getId(), r.text().length(), r.language());
            m.setAsrText(r.text());
            m.setAsrLanguage(r.language());
            // 同步前端展示字段：content = ASR原文（覆盖"[语音消息]"占位），否则前端看不到转写结果
            if (m.getContent() == null || "[语音消息]".equals(m.getContent()) || m.getContent().isBlank()) {
                m.setContent(r.text());
            }
            if (m.getAsrLanguage() == null || m.getAsrLanguage().isBlank()) {
                LanguageDetectionService.LanguageResult lr = languageDetectionService.detect(r.text(), merchantId);
                if (lr != null && lr.isDetected()) {
                    m.setAsrLanguage(lr.getCode());
                }
            }
            m.setAsrStatus("done");
            m.setAsrError(null);

            // 自动翻译：INBOUND 默认翻译为中文（客服看得懂）
            if (autoTranslate) {
                String target = "zh-CN";
                boolean needTranslate = true;
                // 如果 asr 原文检测语言是中文，不需要真正翻译，直接复用
                if (m.getAsrLanguage() != null
                        && ("zh".equalsIgnoreCase(m.getAsrLanguage())
                            || "zh-cn".equalsIgnoreCase(m.getAsrLanguage())
                            || "zh-CN".equalsIgnoreCase(m.getAsrLanguage()))) {
                    m.setAsrTranslated(m.getAsrText());
                    needTranslate = false;
                }
                // 启发式：仅当语言检测未识别（null/空）时，才用 containsChinese 兜底
                // 如果已经检测为非中文（如 ja/en/ko 等），必须翻译，不能被 containsChinese 误判
                if (needTranslate && (m.getAsrLanguage() == null || m.getAsrLanguage().isBlank())
                        && containsChinese(m.getAsrText())) {
                    m.setAsrTranslated(m.getAsrText());
                    needTranslate = false;
                }
                if (needTranslate) {
                    try {
                        translationServiceFactory.translate(m.getAsrText(), target, merchantId)
                                .ifPresent(m::setAsrTranslated);
                    } catch (Exception te) {
                        log.warn("ASR 自动翻译异常 msgId={}: {}", m.getId(), te.getMessage());
                    }
                    // 翻译缺失兜底：保证 translated_content/asrTranslated 至少等于 ASR 原文（不会因为翻译异常而展示旧占位）
                    if (m.getAsrTranslated() == null || m.getAsrTranslated().isBlank()) {
                        m.setAsrTranslated(m.getAsrText());
                    }
                }
                // 【关键】：前端默认优先显示 translated_content。
                // 语音消息：保证 translated_content 一定等于 asrTranslated（中文译文或中文原文兜底）
                // 避免因 asrTranslated == content 时被跳过，导致 translated_content 仍是旧的 "[语音消息]"
                m.setTranslatedContent(m.getAsrTranslated() != null ? m.getAsrTranslated() : m.getContent());
                log.info("ASR 自动翻译完成: msgId={} asrLang={} hasTranslated={} translatedDiff={}",
                        m.getId(), m.getAsrLanguage(),
                        (m.getAsrTranslated() != null && !m.getAsrTranslated().equals(m.getAsrText())),
                        !m.getAsrText().equals(m.getTranslatedContent()));
            } else {
                // 不自动翻译：前端默认仍展示 translated_content，至少和 content（ASR 原文）对齐
                if ((m.getTranslatedContent() == null || m.getTranslatedContent().isBlank()
                        || "[语音消息]".equals(m.getTranslatedContent()))
                        && m.getContent() != null && !"[语音消息]".equals(m.getContent())) {
                    m.setTranslatedContent(m.getContent());
                }
            }
            saveAndBroadcast(m);
        } catch (Exception e) {
            log.error("ASR 异步执行失败: msgId={} err={}", messageId, e.getMessage(), e);
            try {
                m.setAsrStatus("failed");
                m.setAsrError("处理异常: " + truncate(e.getMessage(), 400));
                saveAndBroadcast(m);
            } catch (Exception ignore) {}
        }
    }

    /** 根据 asrText / asrTranslated 触发对已转文字语音的翻译（单独入口） */
    @Transactional
    public Message translateAsrText(Long messageId, String targetLanguage) {
        Message m = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        String asr = m.getAsrText();
        if (asr == null || asr.isBlank()) {
            throw new IllegalStateException("语音尚未转文字，请先点击「转文字」");
        }
        Long merchantId = m.getMerchantId();
        if (merchantId == null && m.getConversation() != null) {
            merchantId = m.getConversation().getMerchantId();
        }
        String normalizedTargetLang = normalizeLanguageCode(targetLanguage);
        translationServiceFactory.translate(asr, normalizedTargetLang, merchantId)
                .ifPresent(m::setAsrTranslated);
        if (m.getAsrTranslated() == null) m.setAsrTranslated(asr);
        // 同步 translatedContent：前端优先展示此字段，翻译结果能直接让客服看到
        if (m.getAsrTranslated() != null && !m.getAsrTranslated().isBlank()
                && !m.getAsrTranslated().equals(m.getContent())) {
            m.setTranslatedContent(m.getAsrTranslated());
        }
        return saveAndBroadcast(m);
    }

    private Message saveAndBroadcast(Message m) {
        Message saved = messageRepository.save(m);
        Long convId = saved.getConversation().getId();
        Conversation conv = saved.getConversation();
        DiscordAccount acc = conv != null ? conv.getDiscordAccount() : null;
        MessageDto dto = acc != null ? MessageDto.from(saved, conv, acc) : MessageDto.from(saved);
        messagingTemplate.convertAndSend("/topic/conversations/" + convId, dto);
        messagingTemplate.convertAndSend("/topic/messages", dto);
        return saved;
    }

    /** 从 audioData(优先) 或 audioUrl(回退下载) 取出音频字节 */
    private byte[] resolveAudioBytes(Message m) {
        if (m.getAudioData() != null && !m.getAudioData().isBlank()) {
            try {
                return java.util.Base64.getDecoder().decode(m.getAudioData());
            } catch (Exception e) {
                log.warn("ASR audioData base64解码失败 msgId={}: {}", m.getId(), e.getMessage());
            }
        }
        if (m.getAudioUrl() != null && !m.getAudioUrl().isBlank()) {
            try {
                byte[] bytes = discordUserClient.downloadBytes(m.getAudioUrl());
                if (bytes != null && bytes.length > 0) return bytes;
            } catch (Exception e) {
                log.warn("ASR 下载CDN失败 msgId={} url={}: {}", m.getId(), m.getAudioUrl(), e.getMessage());
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
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
     * 标准化语言代码：将前端短格式转换为后端兼容的标准格式
     * zh → zh-CN, en → en, ja → ja, ko → ko
     */
    private String normalizeLanguageCode(String lang) {
        if (lang == null || lang.isBlank()) {
            return lang;
        }
        String code = lang.trim().toLowerCase();
        return switch (code) {
            case "zh", "zh-cn", "zh-tw", "zh-hk" -> "zh-CN";
            case "en", "en-us", "en-gb" -> "en";
            case "ja", "jp" -> "ja";
            case "ko", "kr" -> "ko";
            default -> lang;
        };
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
