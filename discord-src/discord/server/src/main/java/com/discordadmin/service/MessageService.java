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
import com.discordadmin.entity.TranslationCache;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.repository.TranslationCacheRepository;
import com.discordadmin.translation.LanguageDetectionService;
import com.discordadmin.translation.TranslationService;
import com.discordadmin.translation.TranslationServiceFactory;
import com.fasterxml.jackson.databind.JsonNode;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.discordadmin.asr.SpeechRecognitionService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageService.class);

    // GIF/媒体文件发送相关常量
    private static final List<String> DIRECT_MEDIA_EXTENSIONS = List.of(".gif", ".webm", ".mp4", ".mov", ".webp", ".png", ".jpg", ".jpeg");
    private static final List<String> GIF_SHARE_DOMAINS = List.of("klipy.com", "tenor.com", "giphy.com", "imgur.com", "futuri.io");
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired @Lazy
    private MessageService self;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final DiscordBotManager discordBotManager;
    private final DiscordUserClient discordUserClient;
    @Autowired @Lazy
    private AgentTaskService agentTaskService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TranslationService translationService;
    private final TranslationServiceFactory translationServiceFactory;
    private final LanguageDetectionService languageDetectionService;
    private final SpeechRecognitionService speechRecognitionService;
    private final TranslationCacheRepository translationCacheRepository;
    @Value("${app.base-url:http://localhost:8090}")
    private String baseUrl;


    public MessageService(ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           DiscordAccountRepository discordAccountRepository,
                           DiscordBotManager discordBotManager,
                           DiscordUserClient discordUserClient,
                           SimpMessagingTemplate messagingTemplate,
                           TranslationService translationService,
                           TranslationServiceFactory translationServiceFactory,
                           LanguageDetectionService languageDetectionService,
                           SpeechRecognitionService speechRecognitionService,
                           TranslationCacheRepository translationCacheRepository) {
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
        this.translationCacheRepository = translationCacheRepository;
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
        int days = (daysBack == null || daysBack <= 0) ? 7 : daysBack;
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

    public static final int DEFAULT_PAGE_SIZE = 20;

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
                    msgEntity.setMessageType("text");
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
        if (!isVoiceMessage && content != null && !content.isBlank() && !isPureNumber(content)) {
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

        String discordMessageId;
        String discordAttachmentUrl = null;
        try {
            if (isVoiceMessage) {
                byte[] audioBytes = java.util.Base64.getDecoder().decode(audioData);
                String fileName = normalizeVoiceFileName(audioFileName, audioMimeType);
                String mimeType = normalizeVoiceMimeType(audioMimeType);
                com.fasterxml.jackson.databind.JsonNode resp = discordUserClient.sendMessageWithFile(
                        account.getToken(), conversation.getChannelId(), "",
                        fileName, audioBytes, mimeType, audioDuration, null);
                discordMessageId = resp.path("id").asText(null);
                discordAttachmentUrl = extractAttachmentUrl(resp);
            } else if ("AGENT".equals(account.getSource()) && account.getAgentServerId() != null) {
                // ✅ 代理模式采集的账号 —— 从 agent 机器发，IP 是用户家庭宽带，不会触发风控
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    String paramsJson = om.writeValueAsString(java.util.Map.of(
                            "token", account.getToken(),
                            "channelId", conversation.getChannelId(),
                            "content", textToSend != null ? textToSend : ""
                    ));
                    com.discordadmin.entity.AgentTask task = agentTaskService.createTask(
                            account.getAgentServerId(), "SEND_MESSAGE", paramsJson);
                    log.info("[Agent路由] 发消息走 agent taskId={}, account={}, channel={}",
                            task.getId(), account.getName(), conversation.getChannelId());
                    String resultJson = agentTaskService.waitForTaskResult(task.getId(), 15000);
                    com.fasterxml.jackson.databind.JsonNode r = om.readTree(resultJson);
                    discordMessageId = r.path("discordMessageId").asText(null);
                    if (discordMessageId == null) {
                        throw new IllegalStateException("Agent 未返回 discordMessageId");
                    }
                } catch (IllegalStateException e) {
                    throw e; // 自己抛的直接上抛
                } catch (Exception e) {
                    throw new IllegalStateException("Agent 发消息失败: " + e.getMessage(), e);
                }
            } else {
                // ✅ 手工/批量导入 —— 服务端裸调（原来的逻辑）
                discordMessageId = discordUserClient.sendMessage(account.getToken(), conversation.getChannelId(), textToSend);
            }
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                log.error("账号 [{}] token 已失效，无法发送消息", account.getName());
                throw new IllegalStateException("账号「" + account.getName() + "」的 Discord 授权已失效，请重新登录该账号", e);
            }
            throw new IllegalStateException("消息发送失败: " + e.getMessage(), e);
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
            // Check for duplicate before saving to avoid unique constraint violation
            Optional<Message> existingMsg = messageRepository.findByConversationAndDiscordMessageId(conversation, discordMessageId);
            if (existingMsg.isPresent()) {
                log.warn("消息已存在，跳过保存: conversationId={}, discordMessageId={}", conversationId, discordMessageId);
                // Update existing message instead of creating duplicate
                Message existing = existingMsg.get();
                existing.setContent(message.getContent());
                existing.setTranslatedContent(message.getTranslatedContent());
                existing.setDiscordCreatedAt(message.getDiscordCreatedAt());
                if (!isVoiceMessage && !textToSend.equals(content)) {
                    existing.setTranslatedContent(textToSend);
                }
                message = messageRepository.save(existing);
            } else {
                message.setDiscordMessageId(discordMessageId);
                if (!isVoiceMessage && !textToSend.equals(content)) {
                    message.setTranslatedContent(textToSend);
                }
                message = messageRepository.save(message);
            }
        } else {
            if (!isVoiceMessage && !textToSend.equals(content)) {
                message.setTranslatedContent(textToSend);
            }
            message = messageRepository.save(message);
        }

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

    /** 带附件的发送方法 */
    @Transactional
    public Message sendReply(Long conversationId, String content, String targetLanguage,
                              String messageType, String audioData, String audioMimeType,
                              Integer audioDuration, String audioFileName, String agentDisplayName,
                              java.util.List<java.util.Map<String, String>> attachments) {
        Message result = null;
        // 如果有附件，先发送附件消息
        if (attachments != null && !attachments.isEmpty()) {
            Message attMsg = sendAttachments(conversationId, attachments, agentDisplayName);
            // 如果没有文字内容或语音，返回附件消息
            if (attMsg != null) {
                result = attMsg;
            }
        }
        // 只有当有文字内容或语音时，才发送文本/语音消息
        boolean hasTextOrVoice = (content != null && !content.isBlank()) 
                                || ("voice".equals(messageType) && audioData != null && !audioData.isBlank());
        if (hasTextOrVoice) {
            result = sendReply(conversationId, content, targetLanguage,
                    messageType, audioData, audioMimeType, audioDuration, audioFileName, agentDisplayName);
        }
        return result;
    }

    /** 发送附件到 Discord 并保存本地记录，返回保存的消息 */
    private Message sendAttachments(Long conversationId, java.util.List<java.util.Map<String, String>> attachments, String agentDisplayName) {
        try {
            Conversation conversation = conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
            DiscordAccount account = discordAccountRepository.findById(
                            conversation.getDiscordAccount().getId())
                    .orElseThrow(() -> new IllegalStateException("Discord 账号不存在"));

            java.util.List<java.util.Map<String, String>> sentAttachments = new java.util.ArrayList<>();
            String discordMessageId = null;

            for (java.util.Map<String, String> att : attachments) {
                String url = att.get("url");
                String fileName = att.get("name");
                String contentType = att.get("contentType");
                
                if (url == null || url.isBlank()) continue;
                // 如果是相对路径，补全为绝对URL
                if (url.startsWith("/")) {
                    url = "http://localhost:8090" + url;
                }
                
                try {
                    // 下载文件
                    HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .build();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(60))
                            .GET()
                            .build();
                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    
                    if (response.statusCode() == 200) {
                        byte[] fileBytes = response.body();
                        String mimeType = contentType != null ? contentType : "application/octet-stream";
                        String name = fileName != null ? fileName : "attachment";
                        
                        // 发送到 Discord，并获取 API 响应
                        if (account.getAccountType() == DiscordAccount.AccountType.USER) {
                            com.fasterxml.jackson.databind.JsonNode resp = discordUserClient.sendMessageWithFile(
                                    account.getToken(), conversation.getChannelId(), "",
                                    name, fileBytes, mimeType, null, null);
                            // 获取 discordMessageId（只取最后一个附件对应的消息ID）
                            if (resp != null && resp.path("id").isTextual()) {
                                discordMessageId = resp.path("id").asText();
                            }
                            // 使用 Discord CDN 返回的真实附件 URL（更可靠，跨设备可访问）
                            if (resp != null && resp.path("attachments").isArray() && resp.path("attachments").size() > 0) {
                                String cdnUrl = resp.path("attachments").get(0).path("url").asText(null);
                                if (cdnUrl != null && !cdnUrl.isBlank()) {
                                    url = cdnUrl;
                                }
                            }
                        } else {
                            log.warn("Bot 账号暂不支持文件附件发送");
                        }
                        // 记录已发送的附件
                        Map<String, String> sent = new java.util.HashMap<>();
                        sent.put("url", url);
                        sent.put("name", name);
                        sent.put("contentType", mimeType);
                        sent.put("size", String.valueOf(fileBytes.length));
                        sentAttachments.add(sent);
                    }
                } catch (Exception e) {
                    log.error("发送附件失败: {}", e.getMessage());
                }
            }
            
            // 保存附件消息到数据库（让前端能显示）
            if (!sentAttachments.isEmpty()) {
                Message attMsg = new Message();
                attMsg.setConversation(conversation);
                attMsg.setDirection(Message.Direction.OUTBOUND);
                attMsg.setSenderName(account.getName());
                attMsg.setContent("");
                attMsg.setAttachmentsJson(toJson(sentAttachments));
                attMsg.setTranslatedContent("");
                attMsg.setDiscordCreatedAt(Instant.now());
                attMsg.setMessageType("attachment");
                attMsg.setCreatedAt(Instant.now());
                
                // 如果拿到了 discordMessageId，先检查是否已存在（Gateway 可能已经入库）
                if (discordMessageId != null) {
                    Optional<Message> existingMsg = messageRepository.findByConversationAndDiscordMessageId(conversation, discordMessageId);
                    if (existingMsg.isPresent()) {
                        log.info("附件消息已由 Gateway 入库，跳过: discordMessageId={}", discordMessageId);
                        return existingMsg.get();
                    }
                    attMsg.setDiscordMessageId(discordMessageId);
                }
                
                Message savedAtt = messageRepository.save(attMsg);
                
                // 推送到前端
                MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(savedAtt);
                messagingTemplate.convertAndSend("/topic/messages", dto);
                
                return savedAtt;
            }
        } catch (Exception e) {
            log.error("发送附件整体失败: {}", e.getMessage());
        }
        return null;
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
    @Transactional
    public void translateMessageAsync(Long messageId, String targetLanguage) {
        try {
            Message message = messageRepository.findById(messageId).orElse(null);
            if (message == null) return;
            // 已经有翻译内容（不是原文占位）则不重复翻译
            if (message.getTranslatedContent() != null
                    && !message.getTranslatedContent().equals(message.getContent())) {
                return;
            }

            String content = message.getContent();
            if (content == null || content.isBlank()) return;

            // 1. 检查翻译缓存
            String sourceHash = sha256(content);
            TranslationCache cached = translationCacheRepository
                    .findBySourceHashAndTargetLanguage(sourceHash, targetLanguage)
                    .orElse(null);

            if (cached != null) {
                // 缓存命中，直接使用
                String translated = cached.getTranslatedContent();
                message.setTranslatedContent(translated);
                messageRepository.save(message);
                log.info("翻译缓存命中 msgId={}, hash={}, translatedLen={}", messageId, sourceHash.substring(0, 8), translated.length());
                // 推送更新给前端
                MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(message);
                messagingTemplate.convertAndSend("/topic/messages", dto);
                // 更新缓存命中统计
                cached.setHitCount(cached.getHitCount() + 1);
                cached.setLastHitAt(Instant.now());
                translationCacheRepository.save(cached);
                return;
            }

            // 2. 缓存未命中，调用翻译API
            log.info("异步翻译消息 msgId={}, contentLen={}, hash={}", messageId, content.length(), sourceHash.substring(0, 8));
            Long merchantId = message.getMerchantId();
            // 如果merchantId为null，直接使用null，不访问懒加载的conversation
            String translated = translationServiceFactory.translate(
                    content, targetLanguage, merchantId)
                    .orElse(content);
            message.setTranslatedContent(translated);
            messageRepository.save(message);

            // 3. 存入缓存（异步，避免阻塞主流程）
            try {
                TranslationCache cache = new TranslationCache();
                cache.setSourceHash(sourceHash);
                cache.setSourceContent(content.length() > 5000 ? content.substring(0, 5000) : content);
                cache.setTargetLanguage(targetLanguage);
                cache.setTranslatedContent(translated);
                cache.setCreatedAt(Instant.now());
                translationCacheRepository.save(cache);
                log.info("翻译缓存已存储 hash={}, translatedLen={}", sourceHash.substring(0, 8), translated.length());
            } catch (Exception e) {
                log.warn("翻译缓存存储失败 hash={}: {}", sourceHash.substring(0, 8), e.getMessage());
            }

            log.info("异步翻译完成 msgId={}", messageId);
            // 推送更新给前端
            MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(message);
            messagingTemplate.convertAndSend("/topic/messages", dto);
        } catch (Exception e) {
            log.warn("异步翻译失败 msgId={}: {}", messageId, e.getMessage());
        }
    }

    /** SHA-256 哈希，用于翻译缓存查找 */
    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // fallback: 用 hashCode 转十六进制
            return Integer.toHexString(text.hashCode());
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
     * 检测文本是否为纯数字（可包含小数点、正负号等数学符号）
     * 纯数字不应该被翻译
     */
    private boolean isPureNumber(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        return text.trim().matches("[\\d\\s.,;:!?+\\-*/()\\[\\]{}]+");
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

    // ==================== GIF 发送相关方法 ====================

    /**
     * 发送 GIF 消息（智能处理：直接URL发送 or 下载后上传）
     * 确保对方 Discord 客户端能正常显示动画
     */
    @Transactional

    /**
     * 判断 URL 是否为本地服务器上传的文件（需要下载后重新上传到 Discord CDN）
     */
        /**
     * 从Discord Sticker CDN URL中提取sticker ID。
     * URL格式: https://cdn.discordapp.com/stickers/{stickerId}?format=json
     */
    private String extractStickerId(String url) {
        if (url == null) return null;
        try {
            // Extract path after /stickers/
            int stickersIdx = url.indexOf("/stickers/");
            if (stickersIdx < 0) return null;
            String afterStickers = url.substring(stickersIdx + "/stickers/".length());
            // Remove query parameters
            int queryIdx = afterStickers.indexOf('?');
            if (queryIdx > 0) {
                afterStickers = afterStickers.substring(0, queryIdx);
            }
            // The sticker ID is the path segment, remove any file extension like .json
            String stickerId = afterStickers.split("/")[0];
            int dotIdx = stickerId.indexOf('.');
            if (dotIdx > 0) {
                stickerId = stickerId.substring(0, dotIdx);
            }
            log.info("从URL提取Sticker ID: url={}, id={}", url, stickerId);
            return stickerId;
        } catch (Exception e) {
            log.error("提取Sticker ID失败: {}", e.getMessage());
            return null;
        }
    }

private boolean isLocalUploadUrl(String url) {
        if (url == null || url.isBlank() || baseUrl == null) return false;
        try {
            String base = baseUrl.replaceAll("/+$", "");
            return url.startsWith(base + "/uploads/") || url.startsWith(base + "/api/gif-favorites/");
        } catch (Exception e) {
            return false;
        }
    }

    public Message sendGifMessage(Long conversationId, String gifUrl, String title) {
        if (gifUrl == null || gifUrl.isBlank()) {
            throw new IllegalArgumentException("GIF URL 不能为空");
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));

        DiscordAccount account = discordAccountRepository.findById(
                        conversation.getDiscordAccount().getId())
                .orElseThrow(() -> new IllegalStateException("Discord 账号不存在"));

        String discordMessageId;
        String discordAttachmentUrl;
        String sentContent;

        // Discord Sticker CDN URL：提取stickerId，通过原生API发送，Discord会渲染为动画
        boolean isDiscordStickerUrl = gifUrl != null && 
            gifUrl.toLowerCase().contains("cdn.discordapp.com/stickers");

        boolean isLocalUpload = isLocalUploadUrl(gifUrl);

        if (isDiscordStickerUrl) {
            // Sticker CDN URL：提取stickerId，使用原生sticker_ids API发送
            String stickerId = extractStickerId(gifUrl);
            log.info("发送Discord Sticker, id={}, url={}", stickerId, gifUrl);
            try {
                JsonNode resp = discordUserClient.sendStickerMessage(
                        account.getToken(), conversation.getChannelId(), stickerId);
                discordMessageId = resp.path("id").asText(null);
                discordAttachmentUrl = gifUrl;
                sentContent = gifUrl;
                log.info("Sticker发送成功, messageId={}", discordMessageId);
            } catch (Exception e) {
                log.error("Sticker发送失败: {}", e.getMessage());
                throw new RuntimeException("Sticker发送失败: " + e.getMessage(), e);
            }
        } else if (isLocalUpload) {
            // 本地服务器上传的文件：下载后重新上传到 Discord CDN
            log.info("发送本地上传文件: {}", gifUrl);
            var localResult = downloadAndUploadGif(account, conversation, gifUrl, title);
            discordMessageId = localResult.messageId();
            discordAttachmentUrl = localResult.attachmentUrl();
            sentContent = localResult.sentContent();
        } else if (isDirectMediaUrl(gifUrl)) {
            // 直接媒体URL：直接发送，Discord 会自动 embedding 显示
            log.info("发送直接媒体URL: {}", gifUrl);
            try {
                discordMessageId = discordUserClient.sendMessage(
                        account.getToken(), conversation.getChannelId(), gifUrl);
                discordAttachmentUrl = gifUrl;
                sentContent = gifUrl;
            } catch (Exception e) {
                log.warn("直接发送URL失败，尝试下载上传: {}", e.getMessage());
                // 降级：下载后上传
                var result = downloadAndUploadGif(account, conversation, gifUrl, title);
                discordMessageId = result.messageId();
                discordAttachmentUrl = result.attachmentUrl();
                sentContent = result.sentContent();
            }
        } else {
            // 分享链接：下载后作为附件上传
            log.info("下载并上传GIF: {}", gifUrl);
            var result = downloadAndUploadGif(account, conversation, gifUrl, title);
            discordMessageId = result.messageId();
            discordAttachmentUrl = result.attachmentUrl();
            sentContent = result.sentContent();
        }

        // 保存消息记录（先查重，避免唯一索引冲突）
        Message saved;
        if (discordMessageId != null) {
            // 检查是否已存在该消息（防止重复保存）
            Optional<Message> existing = messageRepository.findByConversationAndDiscordMessageId(conversation, discordMessageId);
            if (existing.isPresent()) {
                log.warn("消息已存在，跳过保存: conversationId={}, discordMessageId={}", conversationId, discordMessageId);
                saved = existing.get();
            } else {
                Message message = new Message();
                message.setConversation(conversation);
                message.setDirection(Message.Direction.OUTBOUND);
                message.setSenderName(account.getName());
                message.setContent("");
                message.setMessageType("gif");
                message.setGifUrl(discordAttachmentUrl);
                Instant now = Instant.now();
                message.setDiscordCreatedAt(now);
                message.setCreatedAt(now);
                message.setDiscordMessageId(discordMessageId);
                saved = messageRepository.save(message);
            }
        } else {
            Message message = new Message();
            message.setConversation(conversation);
            message.setDirection(Message.Direction.OUTBOUND);
            message.setSenderName(account.getName());
            message.setContent("");
            message.setMessageType("gif");
            message.setGifUrl(discordAttachmentUrl);
            Instant now = Instant.now();
            message.setDiscordCreatedAt(now);
            message.setCreatedAt(now);
            saved = messageRepository.save(message);
        }

        // 推送 WebSocket 消息（使用 DTO 避免 Hibernate 懒加载序列化问题）
        MessageDtos.MessageDto dto = MessageDto.from(saved, conversation, account);
        messagingTemplate.convertAndSend("/topic/conversations/" + conversationId, dto);
        messagingTemplate.convertAndSend("/topic/messages", dto);

        log.info("GIF 消息发送成功: conversationId={}, gifUrl={}, discordMessageId={}",
                conversationId, gifUrl, discordMessageId);

        return saved;
    }

    /**
     * 判断 URL 是否为直接媒体链接（Discord 可直接 embedding）
     */
    public boolean isDirectMediaUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lowerUrl = url.toLowerCase();

        // 检查是否以媒体扩展名结尾（忽略查询参数）
        for (String ext : DIRECT_MEDIA_EXTENSIONS) {
            int queryIdx = lowerUrl.indexOf('?');
            String pathPart = queryIdx > 0 ? lowerUrl.substring(0, queryIdx) : lowerUrl;
            if (pathPart.endsWith(ext)) {
                return true;
            }
        }

        // 检查是否是已知的分享域名
        for (String domain : GIF_SHARE_DOMAINS) {
            if (lowerUrl.contains(domain)) {
                return false; // 分享链接需要下载
            }
        }

        // 检查是否包含其他常见图片CDN特征
        if (lowerUrl.contains("cdn.discordapp.com") ||
                lowerUrl.contains("media.discordapp.net") ||
                lowerUrl.contains("i.imgur.com") ||
                lowerUrl.contains("cdn.tenor.com") ||
                lowerUrl.contains("media.giphy.com")) {
            return true;
        }

        // 默认为分享链接，需要下载
        return false;
    }

    /**
     * 下载 GIF 文件并上传到 Discord
     */
    private GifSendResult downloadAndUploadGif(DiscordAccount account, Conversation conversation,
                                                 String gifUrl, String title) {
        try {
            // 1. 下载 GIF 文件
            byte[] gifData = downloadGifFile(gifUrl);
            if (gifData == null || gifData.length == 0) {
                throw new IllegalStateException("GIF 文件下载失败");
            }

            // 2. 确定文件名和 MIME 类型
            String fileName = determineFileName(gifUrl, title);
            String mimeType = determineMimeType(gifUrl, gifData);

            // 3. 上传到 Discord
            JsonNode resp = discordUserClient.sendMessageWithFile(
                    account.getToken(),
                    conversation.getChannelId(),
                    "", // content 为空，避免 Discord 显示 URL 文本
                    fileName,
                    gifData,
                    mimeType,
                    null,
                    null
            );

            String messageId = resp.path("id").asText(null);
            String attachmentUrl = extractAttachmentUrl(resp);

            return new GifSendResult(messageId, attachmentUrl, "");

        } catch (Exception e) {
            log.error("GIF 下载上传失败: {}", e.getMessage(), e);
            // 降级：直接发送 URL
            log.warn("降级为直接发送URL");
            try {
                String msgId = discordUserClient.sendMessage(
                        account.getToken(), conversation.getChannelId(), gifUrl);
                return new GifSendResult(msgId, gifUrl, gifUrl);
            } catch (Exception ex) {
                throw new IllegalStateException("GIF 发送失败: " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * 从 URL 下载 GIF 文件（带大小限制，最大 10MB）
     */
    private static final int MAX_DOWNLOAD_SIZE = 10 * 1024 * 1024; // 10MB
    private byte[] downloadGifFile(String url) {
        try {
            // First try HEAD request to check file size
            try {
                HttpRequest headRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "Mozilla/5.0")
                        .build();
                HttpResponse<Void> headResponse = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
                if (headResponse.statusCode() >= 200 && headResponse.statusCode() < 300) {
                    String contentLength = headResponse.headers().firstValue("Content-Length").orElse(null);
                    if (contentLength != null) {
                        long size = Long.parseLong(contentLength);
                        if (size > MAX_DOWNLOAD_SIZE) {
                            log.warn("文件过大，跳过下载: url={}, size={}MB", url, size / 1024 / 1024);
                            return null;
                        }
                    }
                }
            } catch (Exception ignored) {
                // HEAD request failed, proceed with GET anyway
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "image/gif, image/webp, video/webm, video/mp4, */*")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                byte[] body = response.body();
                if (body != null && body.length > 0) {
                    // Check actual downloaded size
                    if (body.length > MAX_DOWNLOAD_SIZE) {
                        log.warn("下载文件过大: url={}, size={}MB", url, body.length / 1024 / 1024);
                        return null;
                    }
                    log.info("GIF 下载成功: url={}, size={}bytes", url, body.length);
                    return body;
                }
            }

            log.warn("GIF 下载失败: url={}, status={}", url, response.statusCode());
            return null;

        } catch (Exception e) {
            log.error("GIF 下载异常: url={}, error={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 根据 URL 确定文件名
     */
    private String determineFileName(String url, String title) {
        // 尝试从 URL 提取文件名
        int lastSlash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
        int queryIdx = url.indexOf('?');
        String namePart = queryIdx > 0 ? url.substring(lastSlash + 1, queryIdx) : url.substring(lastSlash + 1);

        // 验证文件名是否合法
        if (namePart != null && !namePart.isBlank() && namePart.length() < 100) {
            // 确保有扩展名
            if (!namePart.contains(".")) {
                namePart += ".gif";
            }
            return namePart;
        }

        // 使用标题或默认名称
        String baseName = (title != null && !title.isBlank()) ? title : "gif";
        // 清理文件名
        baseName = baseName.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
        if (baseName.length() > 50) {
            baseName = baseName.substring(0, 50);
        }
        return baseName + ".gif";
    }

    /**
     * 确定 MIME 类型
     */
    private String determineMimeType(String url, byte[] data) {
        String lowerUrl = url.toLowerCase();

        // 优先根据 URL 扩展名判断
        if (lowerUrl.endsWith(".gif") || lowerUrl.contains(".gif?")) {
            return "image/gif";
        }
        if (lowerUrl.endsWith(".webm") || lowerUrl.contains(".webm?")) {
            return "video/webm";
        }
        if (lowerUrl.endsWith(".mp4") || lowerUrl.contains(".mp4?")) {
            return "video/mp4";
        }
        if (lowerUrl.endsWith(".mov") || lowerUrl.contains(".mov?")) {
            return "video/quicktime";
        }
        if (lowerUrl.endsWith(".webp") || lowerUrl.contains(".webp?")) {
            return "image/webp";
        }

        // 根据文件头判断
        if (data != null && data.length >= 4) {
            // GIF: GIF87a or GIF89a
            if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
                return "image/gif";
            }
            // WebM: RIFF....WEBM
            if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
                    data.length >= 12 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'M') {
                return "video/webm";
            }
            // MP4: ftyp box
            if (data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
                return "video/mp4";
            }
            // PNG: \x89PNG
            if (data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
                return "image/png";
            }
            // JPEG: FF D8 FF
            if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
                return "image/jpeg";
            }
        }

        // 默认返回 GIF
        return "image/gif";
    }

    /**
     * GIF 发送结果记录
     */
    private record GifSendResult(String messageId, String attachmentUrl, String sentContent) {
    }

    /**
     * 将对象列表序列化为 JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            log.error("序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 生成Sticker动画GIF
     */
    private byte[] generateStickerGif(String stickerName) {
        try {
            int size = 256;
            int frameCount = 8;
            int delayMs = 100;
            
            // 生成每帧的BufferedImage
            BufferedImage[] frames = new BufferedImage[frameCount];
            
            for (int frame = 0; frame < frameCount; frame++) {
                BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = image.createGraphics();
                
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                // 动画参数
                double progress = (double) frame / frameCount;
                double bounce = Math.sin(progress * Math.PI * 2);
                double scale = 0.85 + 0.15 * Math.abs(bounce);
                
                // 背景渐变色
                int hueShift = (int) (frame * 15);
                Color bgColor = new Color(
                        Math.min(255, 255 - hueShift), 
                        Math.max(0, 113 - hueShift / 2), 
                        Math.max(0, 128 - hueShift / 3));
                
                // 绘制圆角矩形背景
                g2d.setColor(bgColor);
                g2d.fillRoundRect(8, 8, size - 16, size - 16, 40, 40);
                
                // 绘制Sticker名称
                g2d.setColor(Color.WHITE);
                
                String displayName = stickerName;
                int fontSize = 28;
                if (stickerName.length() > 12) {
                    fontSize = 22;
                    displayName = stickerName.substring(0, Math.min(stickerName.length(), 14)) + "...";
                }
                if (stickerName.length() > 20) {
                    fontSize = 18;
                    displayName = stickerName.substring(0, Math.min(stickerName.length(), 18)) + "...";
                }
                
                Font font = new Font("SansSerif", Font.BOLD, (int)(fontSize * scale));
                g2d.setFont(font);
                
                FontMetrics metrics = g2d.getFontMetrics(font);
                int textWidth = metrics.stringWidth(displayName);
                int x = (size - textWidth) / 2;
                int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
                
                int yOffset = (int) (bounce * 10);
                g2d.drawString(displayName, x, y + yOffset);
                
                g2d.dispose();
                frames[frame] = image;
            }
            
            // 使用ImageIO生成动画GIF（需要自定义GIF写出器）
            return encodeAnimatedGif(frames, delayMs);
            
        } catch (Exception e) {
            log.error("生成Sticker GIF失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 将多帧BufferedImage编码为动画GIF
     */
    private byte[] encodeAnimatedGif(BufferedImage[] frames, int delayMs) throws Exception {
        int width = frames[0].getWidth();
        int height = frames[0].getHeight();
        
        // 将所有帧转为RGB数据
        byte[][] rgbData = new byte[frames.length][];
        for (int f = 0; f < frames.length; f++) {
            rgbData[f] = new byte[width * height * 3];
            int idx = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = frames[f].getRGB(x, y);
                    rgbData[f][idx++] = (byte) ((pixel >> 16) & 0xFF); // R
                    rgbData[f][idx++] = (byte) ((pixel >> 8) & 0xFF);  // G
                    rgbData[f][idx++] = (byte) (pixel & 0xFF);         // B
                }
            }
        }
        
        // 量化颜色（简化版：使用固定调色板）
        // 生成调色板
        byte[] colorTable = generateColorTable(rgbData);
        int colorsPerSlot = 3;
        int numColors = colorTable.length / colorsPerSlot;
        
        // 将每帧映射到调色板索引
        byte[][] indexedFrames = new byte[frames.length][];
        for (int f = 0; f < frames.length; f++) {
            indexedFrames[f] = mapToColorTable(rgbData[f], colorTable, numColors);
        }
        
        // 组装GIF文件
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 1. Header
        baos.write("GIF89a".getBytes());
        
        // 2. Logical Screen Descriptor
        baos.write(width & 0xFF);        // Width low byte
        baos.write((width >> 8) & 0xFF);  // Width high byte
        baos.write(height & 0xFF);        // Height low byte
        baos.write((height >> 8) & 0xFF); // Height high byte
        
        // Global Color Table Flag = 1, Color Resolution = 7, Sort Flag = 0, Size of GCT
        int gctSize = (int) Math.ceil(Math.log(numColors) / Math.log(2)) - 1;
        if (gctSize < 0) gctSize = 0;
        if (gctSize > 7) gctSize = 7;
        byte packed = (byte) (0x80 | (7 << 4) | gctSize);
        baos.write(packed);
        baos.write(0); // Background Color Index
        baos.write(0); // Pixel Aspect Ratio
        
        // 3. Global Color Table
        baos.write(colorTable);
        // 填充到完整的2^(gctSize+1)个颜色
        int totalColors = 1 << (gctSize + 1);
        int paddingNeeded = totalColors * 3 - colorTable.length;
        for (int i = 0; i < paddingNeeded; i++) {
            baos.write(0);
        }
        
        // 4. Netscape 2.0 Loop Extension
        baos.write(0x21); // Extension Introducer
        baos.write(0xFF); // Application Extension Label
        baos.write(11);    // Block Size
        baos.write("NETSCAPE2.0".getBytes());
        baos.write(3);     // Block Size
        baos.write(1);     // Sub-block ID (loop)
        baos.write(0);     // Loop count (0 = infinite)
        baos.write(0);
        baos.write(0);     // Block Terminator
        
        // 5. 每帧
        for (int f = 0; f < frames.length; f++) {
            // Graphic Control Extension
            baos.write(0x21); // Extension Introducer
            baos.write(0xF9); // Graphic Control Label
            baos.write(4);    // Block Size
            
            // Disposal Method = 2 (restore to background), Transparent Color Index = 0
            baos.write(0x28); // packed: Disposal=2, Transparent=1
            int delayCentiseconds = delayMs / 10;
            baos.write(delayCentiseconds & 0xFF);
            baos.write((delayCentiseconds >> 8) & 0xFF);
            baos.write(0); // Transparent Color Index
            
            baos.write(0); // Block Terminator
            
            // Image Descriptor
            baos.write(0x2C); // Image Separator
            baos.write(0);    // Left Position
            baos.write(0);
            baos.write(0);    // Top Position
            baos.write(0);
            baos.write(width & 0xFF);
            baos.write((width >> 8) & 0xFF);
            baos.write(height & 0xFF);
            baos.write((height >> 8) & 0xFF);
            baos.write(0);    // Packed Field (no local color table)
            
            // LZW Compressed Data
            int minCodeSize = Math.max(2, gctSize + 1);
            byte[] compressed = lzwCompress(indexedFrames[f], minCodeSize);
            writeDataSubBlocks(baos, compressed);
        }
        
        // 6. Trailer
        baos.write(0x3B);
        
        return baos.toByteArray();
    }
    
    /**
     * 生成量化后的调色板（简化版：收集所有颜色并量化）
     */
    private byte[] generateColorTable(byte[][] rgbData) {
        // 收集所有唯一颜色
        java.util.Set<Integer> uniqueColors = new java.util.HashSet<>();
        for (byte[] frame : rgbData) {
            for (int i = 0; i < frame.length; i += 3) {
                int r = frame[i] & 0xFF;
                int g = frame[i + 1] & 0xFF;
                int b = frame[i + 2] & 0xFF;
                uniqueColors.add((r << 16) | (g << 8) | b);
            }
        }
        
        // 限制颜色数量（最多256色）
        if (uniqueColors.size() > 256) {
            // 简化：直接量化到256色（通过降低精度）
            byte[] result = new byte[256 * 3];
            int idx = 0;
            for (int r = 0; r < 256; r += 32) {
                for (int g = 0; g < 256; g += 32) {
                    for (int b = 0; b < 256; b += 32) {
                        result[idx++] = (byte) r;
                        result[idx++] = (byte) g;
                        result[idx++] = (byte) b;
                        if (idx >= 256 * 3) break;
                    }
                    if (idx >= 256 * 3) break;
                }
                if (idx >= 256 * 3) break;
            }
            return result;
        }
        
        // 构建调色板
        byte[] result = new byte[uniqueColors.size() * 3];
        int idx = 0;
        for (int color : uniqueColors) {
            result[idx++] = (byte) ((color >> 16) & 0xFF);
            result[idx++] = (byte) ((color >> 8) & 0xFF);
            result[idx++] = (byte) (color & 0xFF);
        }
        
        // 确保至少有2个颜色
        if (uniqueColors.size() < 2) {
            byte[] padded = new byte[6];
            System.arraycopy(result, 0, padded, 0, result.length);
            return padded;
        }
        
        return result;
    }
    
    /**
     * 将RGB数据映射到调色板索引
     */
    private byte[] mapToColorTable(byte[] rgbData, byte[] colorTable, int numColors) {
        byte[] result = new byte[rgbData.length / 3];
        
        // 构建RGB到索引的映射
        java.util.Map<Integer, Byte> colorToIndex = new java.util.HashMap<>();
        for (int i = 0; i < numColors; i++) {
            int r = colorTable[i * 3] & 0xFF;
            int g = colorTable[i * 3 + 1] & 0xFF;
            int b = colorTable[i * 3 + 2] & 0xFF;
            colorToIndex.put((r << 16) | (g << 8) | b, (byte) i);
        }
        
        for (int i = 0, j = 0; i < rgbData.length; i += 3, j++) {
            int r = rgbData[i] & 0xFF;
            int g = rgbData[i + 1] & 0xFF;
            int b = rgbData[i + 2] & 0xFF;
            int rgb = (r << 16) | (g << 8) | b;
            
            Byte idx = colorToIndex.get(rgb);
            if (idx != null) {
                result[j] = idx;
            } else {
                // 找最接近的颜色
                int bestIdx = 0;
                int bestDist = Integer.MAX_VALUE;
                for (int k = 0; k < numColors; k++) {
                    int cr = colorTable[k * 3] & 0xFF;
                    int cg = colorTable[k * 3 + 1] & 0xFF;
                    int cb = colorTable[k * 3 + 2] & 0xFF;
                    int dist = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestIdx = k;
                    }
                }
                result[j] = (byte) bestIdx;
            }
        }
        
        return result;
    }
    
    /**
     * LZW压缩（GIF标准）
     */
    private byte[] lzwCompress(byte[] data, int minCodeSize) {
        int clearCode = 1 << minCodeSize;
        int eoiCode = clearCode + 1;
        int codeSize = minCodeSize + 1;
        int nextCode = eoiCode + 1;
        int maxCode = 1 << codeSize;
        
        // 字典
        java.util.Map<String, Integer> dictionary = new java.util.HashMap<>();
        for (int i = 0; i < clearCode; i++) {
            dictionary.put(String.valueOf((char) i), i);
        }
        
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BitWriter bitWriter = new BitWriter();
        
        // 写入Clear Code
        bitWriter.write(clearCode, codeSize);
        
        String current = "";
        int code = -1;
        
        for (byte b : data) {
            String symbol = String.valueOf((char) (b & 0xFF));
            String currentSymbol = current + symbol;
            
            if (dictionary.containsKey(currentSymbol)) {
                code = dictionary.get(currentSymbol);
                current = currentSymbol;
            } else {
                bitWriter.write(code, codeSize);
                
                // 添加新条目
                if (nextCode < 4096) {
                    dictionary.put(currentSymbol, nextCode++);
                    if (nextCode > maxCode && codeSize < 12) {
                        codeSize++;
                        maxCode = 1 << codeSize;
                    }
                } else {
                    // 字典已满，发送Clear Code
                    bitWriter.write(clearCode, codeSize);
                    codeSize = minCodeSize + 1;
                    maxCode = 1 << codeSize;
                    nextCode = eoiCode + 1;
                    dictionary.clear();
                    for (int i = 0; i < clearCode; i++) {
                        dictionary.put(String.valueOf((char) i), i);
                    }
                }
                
                current = symbol;
                code = dictionary.get(symbol);
            }
        }
        
        // 写入最后一个code
        if (code != -1) {
            bitWriter.write(code, codeSize);
        }
        
        // 写入EOI
        bitWriter.write(eoiCode, codeSize);
        
        bitWriter.flush(output);
        return output.toByteArray();
    }
    
    /**
     * 写入GIF数据子块
     */
    private void writeDataSubBlocks(ByteArrayOutputStream baos, byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int blockSize = Math.min(255, data.length - offset);
            baos.write(blockSize);
            baos.write(data, offset, blockSize);
            offset += blockSize;
        }
        baos.write(0); // Block Terminator
    }
    
    /**
     * 位写入器（用于LZW编码输出）
     */
    private static class BitWriter {
        private int buffer = 0;
        private int bits = 0;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        void write(int code, int codeSize) {
            buffer |= (code << bits);
            bits += codeSize;
            while (bits >= 8) {
                output.write(buffer & 0xFF);
                buffer >>= 8;
                bits -= 8;
            }
        }
        
        void flush(ByteArrayOutputStream baos) {
            if (bits > 0) {
                output.write(buffer & 0xFF);
                buffer = 0;
                bits = 0;
            }
            byte[] data = output.toByteArray();
            int offset = 0;
            while (offset < data.length) {
                int blockSize = Math.min(255, data.length - offset);
                baos.write(blockSize);
                baos.write(data, offset, blockSize);
                offset += blockSize;
            }
            baos.write(0); // Block Terminator
        }
    }

}
