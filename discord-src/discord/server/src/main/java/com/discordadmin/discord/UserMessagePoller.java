package com.discordadmin.discord;

import com.discordadmin.dto.ConversationDtos;
import com.discordadmin.dto.MessageDtos;
import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import com.discordadmin.service.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserMessagePoller {

    private static final Logger log = LoggerFactory.getLogger(UserMessagePoller.class);

    @Autowired @Lazy
    private UserMessagePoller self;

    private final DiscordAccountRepository accountRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final DiscordUserRepository discordUserRepository;
    private final DiscordUserClient discordUserClient;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    private final Map<String, String> lastMessageIdByChannel = new ConcurrentHashMap<>();

    public UserMessagePoller(DiscordAccountRepository accountRepository,
                              ConversationRepository conversationRepository,
                              MessageRepository messageRepository,
                              DiscordUserRepository discordUserRepository,
                              DiscordUserClient discordUserClient,
                              MessageService messageService,
                              SimpMessagingTemplate messagingTemplate) {
        this.accountRepository = accountRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.discordUserRepository = discordUserRepository;
        this.discordUserClient = discordUserClient;
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
    }

    public void init() {
        log.info("USER 账号 DM 消息轮询器已启动（每 2 秒轮询一次）");
    }

    @Scheduled(fixedRate = 2000)
    public void pollNewMessages() {
        List<DiscordAccount> userAccounts = accountRepository.findByStatus(DiscordAccount.AccountStatus.ACTIVE)
                .stream()
                .filter(a -> a.getAccountType() == DiscordAccount.AccountType.USER)
                .toList();

        if (userAccounts.isEmpty()) {
            return;
        }

        for (DiscordAccount account : userAccounts) {
            try {
                List<Conversation> conversations = conversationRepository
                        .findByDiscordAccountAndType(account, Conversation.ConversationType.DM);

                log.info("轮询账号[{}](id={}) 发现 {} 个 DM 会话, discordId={}",
                        account.getName(), account.getId(), conversations.size(), account.getDiscordId());

                for (Conversation conv : conversations) {
                    try {
                        self.pollOneConversationInTx(account.getId(), conv.getId());
                    } catch (Exception e) {
                        log.warn("轮询会话 [convId={}, channelId={}] 失败: {}", conv.getId(), conv.getChannelId(), e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                log.warn("轮询账号 [{}] 消息失败: {}", account.getName(), e.getMessage(), e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pollOneConversationInTx(Long accountId, Long conversationId) {
        DiscordAccount account = accountRepository.findById(accountId).orElse(null);
        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (account == null || conv == null) {
            log.warn("轮询失败: account={}, conv={}", account, conv);
            return;
        }
        pollOneConversation(account, conv);
    }

    private void pollOneConversation(DiscordAccount account, Conversation conv) {
        DiscordUser user = conv.getDiscordUser();
        if (user == null) {
            log.warn("会话 [convId={}] 关联的 DiscordUser 为 null", conv.getId());
            return;
        }

        String channelId = conv.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            log.warn("会话 [convId={}] channelId 为空", conv.getId());
            return;
        }

        String lastMsgKey = account.getId() + ":" + channelId;

        List<Message> existingMessages = messageRepository.findByConversationOrderByCreatedAtAsc(conv);
        log.info("会话 [convId={}, channelId={}] 已有 {} 条历史消息", conv.getId(), channelId, existingMessages.size());

        // 如果已有双方消息，且当前漏斗阶段是 PROSPECT，则更新为 NEW
        if (conv.getStage() == Conversation.Stage.PROSPECT) {
            boolean hasInbound = existingMessages.stream()
                    .anyMatch(m -> m.getDirection() == Message.Direction.INBOUND);
            boolean hasOutbound = existingMessages.stream()
                    .anyMatch(m -> m.getDirection() == Message.Direction.OUTBOUND);
            if (hasInbound && hasOutbound) {
                conv.setStage(Conversation.Stage.NEW);
                conv.setStageChangedAt(java.time.Instant.now());
                conversationRepository.save(conv);
                log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", conv.getId());
            }
        }

        JsonNode messages;
        try {
            messages = discordUserClient.listMessages(account.getToken(), channelId, 50);
        } catch (DiscordUserClient.DiscordUserApiException e) {
            if (e.statusCode == 401) {
                account.setLastError("Token 已过期，请用 Chrome 插件重新导入 Token 续期");
                accountRepository.save(account);
                log.warn("账号[{}] Token 已过期", account.getName());
            } else {
                log.warn("拉取频道消息失败: status={}, channelId={}", e.statusCode, channelId);
            }
            throw new RuntimeException("拉取频道消息失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("拉取频道消息失败: " + e.getMessage(), e);
        }
        if (messages == null || !messages.isArray()) {
            log.info("会话 [convId={}] 返回消息为空或非数组", conv.getId());
            return;
        }

        // 成功拉取消息，更新用户的最后活跃时间
        user.setLastActiveAt(Instant.now());
        discordUserRepository.save(user);

        log.info("会话 [convId={}] 拉取到 {} 条消息", conv.getId(), messages.size());

        String lastKnownId = lastMessageIdByChannel.get(lastMsgKey);
        int newCount = 0;

        for (JsonNode msgNode : messages) {
            String msgId = msgNode.path("id").asText(null);
            if (msgId == null) continue;

            if (msgId.equals(lastKnownId)) break;

            if (messageRepository.findByConversationAndDiscordMessageId(conv, msgId).isPresent()) {
                continue;
            }

            JsonNode author = msgNode.path("author");
            String authorId = author.path("id").asText(null);
            String authorName = author.path("global_name").asText(
                    author.path("username").asText("Unknown"));
            String content = msgNode.path("content").asText("");

            // 获取 Discord 消息的实际发送时间
            String timestampStr = msgNode.path("timestamp").asText(null);
            Instant discordCreatedAt = null;
            if (timestampStr != null && !timestampStr.isBlank()) {
                try {
                    discordCreatedAt = Instant.parse(timestampStr);
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
            if (discordCreatedAt == null) {
                discordCreatedAt = Instant.now();
            }

            boolean isOutbound = authorId != null
                    && account.getDiscordId() != null
                    && authorId.equals(account.getDiscordId());

            log.info("处理新消息 [convId={}, msgId={}, direction={}, author={}, contentLen={}]",
                    conv.getId(), msgId, (isOutbound ? "OUTBOUND" : "INBOUND"), authorName, content.length());

            Message msgEntity = new Message();
            msgEntity.setConversation(conv);
            msgEntity.setDiscordMessageId(msgId);
            msgEntity.setDirection(isOutbound ? Message.Direction.OUTBOUND : Message.Direction.INBOUND);
            msgEntity.setSenderName(isOutbound ? account.getName() : authorName);
            msgEntity.setSenderDiscordUserId(authorId);

            // ===== 解析 attachments，识别 Discord 原生语音消息 =====
            JsonNode attachments = msgNode.get("attachments");
            String attachmentsJson = null;
            String resolvedMessageType = "text";
            String resolvedAudioUrl = null;
            String resolvedAudioMime = null;
            Integer resolvedAudioDuration = null;
            String resolvedAudioData = null;
            if (attachments != null && attachments.isArray() && !attachments.isEmpty()) {
                List<String> urls = new java.util.ArrayList<>();
                JsonNode voiceAtt = null;
                for (JsonNode a : attachments) {
                    String u = a.path("url").asText(null);
                    if (u != null) urls.add(u);
                    if (voiceAtt != null) continue;
                    String fn = a.path("filename").asText("").toLowerCase();
                    // 兼容三种判定：Discord 官方用 filename=voice-message.ogg；
                    // 也兼容 content_type 以 audio/ 开头；或显式 duration_secs 字段
                    boolean isAudioByCt = a.path("content_type").asText("").toLowerCase().startsWith("audio/");
                    boolean hasVoiceName = fn.startsWith("voice-message");
                    boolean hasDuration = a.has("duration_secs");
                    if (hasVoiceName || isAudioByCt || hasDuration) {
                        voiceAtt = a;
                    }
                }
                attachmentsJson = String.join(",", urls);
                if (voiceAtt != null) {
                    resolvedMessageType = "voice";
                    String url = voiceAtt.path("url").asText(null);
                    String proxy = voiceAtt.path("proxy_url").asText(null);
                    resolvedAudioUrl = url != null ? url : proxy;
                    resolvedAudioMime = resolveAttachmentMime(voiceAtt);
                    // Discord v10 语音消息附件有两种字段：duration_secs（整数）或 duration（浮点）
                    int dur = -1;
                    if (voiceAtt.has("duration_secs")) {
                        dur = (int) Math.round(voiceAtt.path("duration_secs").asDouble(0.0));
                    } else if (voiceAtt.has("duration")) {
                        dur = (int) Math.round(voiceAtt.path("duration").asDouble(0.0));
                    }
                    resolvedAudioDuration = dur > 0 ? dur : null;
                    // 后端主动下载音频为 base64，避免前端浏览器直连 CDN 被 403/Referer 拦
                    if (resolvedAudioUrl != null) {
                        resolvedAudioData = discordUserClient.downloadAsBase64(resolvedAudioUrl);
                    }
                    // 原生语音 content 一般是 ""，但后台列表要能预览，给个占位
                    if (content == null || content.isBlank()) {
                        content = "[语音消息]";
                    }
                }
            }
            msgEntity.setContent(content);
            msgEntity.setAttachmentsJson(attachmentsJson);
            msgEntity.setMessageType(resolvedMessageType);
            if ("voice".equals(resolvedMessageType)) {
                msgEntity.setAudioUrl(resolvedAudioUrl);
                msgEntity.setAudioMimeType(resolvedAudioMime);
                msgEntity.setAudioDuration(resolvedAudioDuration);
                msgEntity.setAudioData(resolvedAudioData);
            }
            msgEntity.setDiscordCreatedAt(discordCreatedAt);
            msgEntity.setCreatedAt(discordCreatedAt);

            if (!isOutbound) {
                try {
                    messageService.translateAndSave(msgEntity, "zh-CN");
                } catch (Exception e) {
                    log.warn("翻译消息失败，使用原文: {}", e.getMessage());
                    msgEntity.setTranslatedContent(content);
                }
            } else {
                msgEntity.setTranslatedContent(content);
            }

            messageRepository.save(msgEntity);
            newCount++;

            String preview = content.length() > 200 ? content.substring(0, 200) : content;
            conv.setLastMessagePreview(preview);
            conv.setLastMessageDirection(isOutbound ? "OUTBOUND" : "INBOUND");
            conv.setLastMessageAt(Instant.now());

            // 双方都有消息时，自动升级漏斗阶段为"回复客户"
            if (conv.getStage() == Conversation.Stage.PROSPECT) {
                boolean hasInbound = existingMessages.stream().anyMatch(m -> m.getDirection() == Message.Direction.INBOUND);
                boolean hasOutbound = existingMessages.stream().anyMatch(m -> m.getDirection() == Message.Direction.OUTBOUND);
                // 加上当前这条消息后检查双方是否都有消息
                if (isOutbound) {
                    hasOutbound = true;
                } else {
                    hasInbound = true;
                }
                if (hasInbound && hasOutbound) {
                    conv.setStage(Conversation.Stage.NEW);
                    conv.setStageChangedAt(Instant.now());
                    log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", conv.getId());
                }
            }

            conversationRepository.save(conv);

            MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(msgEntity);
            // 推送到全局消息主题（前端订阅 /topic/messages）
            messagingTemplate.convertAndSend("/topic/messages", dto);
            // 同时推送到特定会话主题（兼容按会话订阅的场景）
            messagingTemplate.convertAndSend(
                    "/topic/conversations/" + conv.getId(),
                    dto);
        }

        JsonNode first = messages.size() > 0 ? messages.get(0) : null;
        if (first != null) {
            lastMessageIdByChannel.put(lastMsgKey, first.path("id").asText());
        }

        if (newCount > 0) {
            // 计算未读消息数并推送到前端
            int unreadCount = calculateUnreadCount(conv.getId());
            log.info("会话 [convId={}] 新增 {} 条消息, 未读数: {}", conv.getId(), newCount, unreadCount);

            ConversationDtos.ConversationDto convDto = ConversationDtos.ConversationDto.from(conv, unreadCount);
            messagingTemplate.convertAndSend("/topic/conversations", convDto);
        } else {
            log.info("会话 [convId={}] 无新消息 (lastMsgId={})", conv.getId(), lastMessageIdByChannel.get(lastMsgKey));
        }
    }

    /**
     * 计算单个会话的未读消息数
     */
    private int calculateUnreadCount(Long convId) {
        try {
            List<Object[]> results = messageRepository.countUnreadByConversationIds(List.of(convId));
            if (!results.isEmpty()) {
                return ((Number) results.get(0)[1]).intValue();
            }
        } catch (Exception e) {
            log.warn("计算未读数失败: convId={}, error={}", convId, e.getMessage());
        }
        return 0;
    }

    /**
     * 解析 attachment 的 content_type / filename 推断 MIME，兜底 audio/ogg。
     */
    private String resolveAttachmentMime(JsonNode att) {
        if (att == null) return "audio/ogg";
        String ct = att.path("content_type").asText("").toLowerCase();
        if (!ct.isBlank() && ct.startsWith("audio/")) return ct;
        String fn = att.path("filename").asText("").toLowerCase();
        if (fn.endsWith(".webm")) return "audio/webm";
        if (fn.endsWith(".mp3")) return "audio/mpeg";
        if (fn.endsWith(".wav")) return "audio/wav";
        if (fn.endsWith(".m4a") || fn.endsWith(".mp4")) return "audio/mp4";
        return "audio/ogg";
    }
}
