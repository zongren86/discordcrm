package com.discordadmin.service;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.discord.InboundMessage;
import com.discordadmin.dto.ConversationDtos.ConversationDto;
import com.discordadmin.dto.ConversationDtos.OpenDmRequest;
import com.discordadmin.dto.MessageDtos.MessageDto;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordUser;
import com.discordadmin.entity.Friend;
import com.discordadmin.entity.Message;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.DiscordUserRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.translation.LanguageDetectionService;
import com.discordadmin.translation.TranslationService;
import com.discordadmin.translation.TranslationServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final DiscordUserRepository discordUserRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final AgentRepository agentRepository;
    private final FriendRepository friendRepository;
    private final DiscordUserClient discordUserClient;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TranslationService translationService;
    private final TranslationServiceFactory translationServiceFactory;
    private final LanguageDetectionService languageDetectionService;

    public ConversationService(DiscordUserRepository discordUserRepository,
                               ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               DiscordAccountRepository discordAccountRepository,
                               AgentRepository agentRepository,
                               FriendRepository friendRepository,
                               DiscordUserClient discordUserClient,
                               MessageService messageService,
                               SimpMessagingTemplate messagingTemplate,
                               TranslationService translationService,
                               TranslationServiceFactory translationServiceFactory,
                               LanguageDetectionService languageDetectionService) {
        this.discordUserRepository = discordUserRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.agentRepository = agentRepository;
        this.friendRepository = friendRepository;
        this.discordUserClient = discordUserClient;
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
        this.translationService = translationService;
        this.translationServiceFactory = translationServiceFactory;
        this.languageDetectionService = languageDetectionService;
    }

    @Transactional
    public void handleInbound(InboundMessage inbound) {
        if (messageRepository.findByDiscordMessageId(inbound.discordMessageId()).isPresent()) {
            return;
        }

        DiscordUser user = discordUserRepository.findByDiscordUserId(inbound.authorUserId())
                .orElseGet(DiscordUser::new);
        boolean isNewUser = user.getId() == null;
        user.setDiscordUserId(inbound.authorUserId());
        user.setUsername(inbound.authorUsername());
        user.setGlobalName(inbound.authorGlobalName());
        user.setAvatarUrl(inbound.authorAvatarUrl());
        user.setLastActiveAt(Instant.now());
        if (isNewUser) {
            user.setFirstSeenAt(Instant.now());
        }
        final DiscordUser savedUser = discordUserRepository.save(user);

        Conversation conversation = conversationRepository.findByChannelId(inbound.channelId())
                .orElseGet(() -> {
                    Conversation c = new Conversation();
                    c.setDiscordUser(savedUser);
                    c.setChannelId(inbound.channelId());
                    c.setType(inbound.isDirectMessage() ? Conversation.ConversationType.DM : Conversation.ConversationType.GUILD_TEXT);
                    return c;
                });
        if (conversation.getDiscordAccount() == null && inbound.discordAccountId() != null) {
            final Conversation target = conversation;
            discordAccountRepository.findById(inbound.discordAccountId())
                    .ifPresent(acc -> {
                        target.setDiscordAccount(acc);
                        if (target.getMerchantId() == null) {
                            target.setMerchantId(acc.getMerchantId());
                        }
                    });
        }
        conversation.setChannelName(inbound.channelName());
        conversation.setGuildId(inbound.guildId());
        conversation.setGuildName(inbound.guildName());
        conversation.setLastMessagePreview(truncate(inbound.content()));
        conversation.setLastMessageDirection("INBOUND");
        conversation.setLastMessageAt(Instant.now());
        if (conversation.getStatus() == Conversation.ConversationStatus.CLOSED) {
            conversation.setStatus(Conversation.ConversationStatus.PENDING);
        }

        // 自动升级阶段：如果是PROSPECT阶段且双方都有消息，升级为NEW
        if (conversation.getStage() == Conversation.Stage.PROSPECT) {
            long outboundCount = messageRepository.countOutboundMessages(conversation);
            if (outboundCount > 0) {
                conversation.setStage(Conversation.Stage.NEW);
                conversation.setStageChangedAt(Instant.now());
                log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", conversation.getId());
            }
        }

        conversation = conversationRepository.save(conversation);

        Message message = new Message();
        message.setConversation(conversation);
        message.setDiscordMessageId(inbound.discordMessageId());
        message.setDirection(Message.Direction.INBOUND);
        message.setSenderName(inbound.authorGlobalName() != null ? inbound.authorGlobalName() : inbound.authorUsername());
        message.setSenderDiscordUserId(inbound.authorUserId());
        message.setContent(inbound.content());
        message.setAttachmentsJson(inbound.attachmentsJson());

        // 语音消息字段
        boolean isVoice = "voice".equals(inbound.messageType());
        message.setMessageType(isVoice ? "voice" : "text");
        if (isVoice) {
            message.setAudioUrl(inbound.audioUrl());
            message.setAudioMimeType(inbound.audioMimeType());
            message.setAudioDuration(inbound.audioDuration());
            message.setAudioData(inbound.audioData());
        }

        // 自动检测语言并保存
        Long merchantId = conversation.getMerchantId();
        if (merchantId == null && conversation.getDiscordAccount() != null) {
            merchantId = conversation.getDiscordAccount().getMerchantId();
        }
        LanguageDetectionService.LanguageResult langResult = languageDetectionService.detect(inbound.content(), merchantId);
        if (langResult != null && langResult.isDetected()) {
            message.setLanguage(langResult.getCode());
        }

        // 使用翻译工厂（支持 AI 翻译 + 降级免费翻译）
        translationServiceFactory.translate(inbound.content(), "zh-CN", merchantId)
                .ifPresent(message::setTranslatedContent);
        message = messageRepository.save(message);

        messagingTemplate.convertAndSend("/topic/conversations/" + conversation.getId(), MessageDto.from(message));
        messagingTemplate.convertAndSend("/topic/messages", MessageDto.from(message));

        // 推送带未读计数的会话更新
        int unread = calculateUnreadCount(conversation.getId());
        messagingTemplate.convertAndSend("/topic/conversations", ConversationDto.from(conversation, unread));
    }

    /**
     * 计算单个会话的未读消息数（最后一条为INBOUND的消息数）
     */
    private int calculateUnreadCount(Long convId) {
        try {
            List<Object[]> results = messageRepository.countUnreadByConversationIds(List.of(convId));
            if (!results.isEmpty()) {
                return ((Number) results.get(0)[1]).intValue();
            }
        } catch (Exception e) {
            // 忽略计算失败
        }
        return 0;
    }

    private String truncate(String content) {
        if (content == null) return "";
        return content.length() > 200 ? content.substring(0, 200) : content;
    }

    public ConversationDto openDm(OpenDmRequest request) {
        if (request.accountId() == null || request.friendDiscordUserId() == null || request.friendDiscordUserId().isBlank()) {
            throw new IllegalArgumentException("accountId 和 friendDiscordUserId 不能为空");
        }

        DiscordAccount account = discordAccountRepository.findById(request.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Discord 账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());

        Friend friend = friendRepository
                .findByDiscordAccountAndFriendDiscordUserId(account, request.friendDiscordUserId())
                .orElseThrow(() -> new IllegalArgumentException("好友不存在，请先同步好友列表"));

        DiscordUser user = discordUserRepository.findByDiscordUserId(request.friendDiscordUserId())
                .orElseGet(() -> {
                    DiscordUser u = new DiscordUser();
                    u.setDiscordUserId(request.friendDiscordUserId());
                    u.setUsername(friend.getUsername());
                    u.setGlobalName(friend.getGlobalName());
                    if (friend.getAvatar() != null && !friend.getAvatar().isBlank()) {
                        String ext = friend.getAvatar().startsWith("a_") ? "gif" : "png";
                        u.setAvatarUrl("https://cdn.discordapp.com/avatars/"
                                + request.friendDiscordUserId() + "/" + friend.getAvatar() + "." + ext);
                    }
                    u.setFirstSeenAt(Instant.now());
                    u.setLastActiveAt(Instant.now());
                    return discordUserRepository.save(u);
                });

        Optional<Conversation> existing = conversationRepository
                .findByDiscordUserAndDiscordAccountAndType(user, account, Conversation.ConversationType.DM);
        if (existing.isPresent()) {
            return ConversationDto.from(existing.get());
        }

        String channelId;
        if (account.getAccountType() == DiscordAccount.AccountType.USER) {
            try {
                channelId = discordUserClient.openDmChannel(account.getToken(), request.friendDiscordUserId());
            } catch (Exception e) {
                throw new IllegalStateException("创建 DM 频道失败: " + e.getMessage(), e);
            }
        } else {
            throw new IllegalStateException("仅 USER 账号支持主动发起 DM 会话");
        }
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalStateException("Discord 返回的 channelId 为空");
        }

        Optional<Conversation> existingForAccount = conversationRepository
                .findByChannelIdAndDiscordAccount_Id(channelId, account.getId());
        if (existingForAccount.isPresent()) {
            Conversation conv = existingForAccount.get();
            conv.setDiscordUser(user);
            String displayName = user.getGlobalName() != null ? user.getGlobalName() : user.getUsername();
            conv.setChannelName(displayName);
            return ConversationDto.from(conversationRepository.save(conv));
        }

        Conversation conv = new Conversation();
        conv.setDiscordUser(user);
        conv.setDiscordAccount(account);
        conv.setChannelId(channelId);
        conv.setType(Conversation.ConversationType.DM);
        conv.setStatus(Conversation.ConversationStatus.OPEN);
        conv.setMerchantId(account.getMerchantId());
        conv.setStage(Conversation.Stage.PROSPECT);
        conv = conversationRepository.save(conv);
        return ConversationDto.from(conv);
    }

    @Transactional(readOnly = true)
    public List<ConversationDto> listConversations(Long accountId, String stage, String keyword, Boolean pinnedOnly,
                                                    String dateFrom, String dateTo) {
        Long merchantId = SecurityUtils.currentMerchantId();
        Long currentAgentId = SecurityUtils.currentAgentId();

        // Parse date filters
        java.time.Instant fromInstant = null;
        java.time.Instant toInstant = null;
        if (dateFrom != null && !dateFrom.isBlank()) {
            fromInstant = java.time.LocalDate.parse(dateFrom)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (dateTo != null && !dateTo.isBlank()) {
            toInstant = java.time.LocalDate.parse(dateTo)
                    .plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            List<Conversation> convs;
            if (accountId != null || stage != null) {
                Conversation.Stage stageEnum = stage != null && !stage.isBlank()
                        ? Conversation.Stage.valueOf(stage.toUpperCase()) : null;
                convs = conversationRepository.searchByMerchantAndFilters(
                        merchantId, accountId, stageEnum, kw);
            } else {
                convs = conversationRepository.searchByMerchantAndKeyword(
                        merchantId, kw);
            }
            convs = filterByAgentAccess(convs, currentAgentId);
            if (fromInstant != null || toInstant != null) {
                convs = filterByDate(convs, fromInstant, toInstant);
            }
            if (Boolean.TRUE.equals(pinnedOnly)) {
                convs = convs.stream().filter(c -> Boolean.TRUE.equals(c.getPinned())).toList();
            }
            return buildConversationDtos(convs);
        }

        List<Conversation> convs = queryConversations(accountId, stage, merchantId);
        convs = filterByAgentAccess(convs, currentAgentId);
        if (fromInstant != null || toInstant != null) {
            convs = filterByDate(convs, fromInstant, toInstant);
        }
        if (Boolean.TRUE.equals(pinnedOnly)) {
            convs = convs.stream().filter(c -> Boolean.TRUE.equals(c.getPinned())).toList();
        }
        return buildConversationDtos(convs);
    }

    private List<Conversation> filterByDate(List<Conversation> convs, java.time.Instant from, java.time.Instant to) {
        return convs.stream()
                .filter(c -> {
                    java.time.Instant lastMsg = c.getLastMessageAt() != null
                            ? c.getLastMessageAt()
                            : (c.getCreatedAt() != null ? c.getCreatedAt() : null);
                    if (lastMsg == null) return false;
                    if (from != null && lastMsg.isBefore(from)) return false;
                    if (to != null && lastMsg.isAfter(to)) return false;
                    return true;
                })
                .toList();
    }

    /**
     * 批量计算每个会话的未读消息数并构建DTO
     */
    private List<ConversationDto> buildConversationDtos(List<Conversation> convs) {
        if (convs.isEmpty()) {
            return List.of();
        }
        List<Long> convIds = convs.stream().map(Conversation::getId).toList();
        Map<Long, Integer> unreadMap = new HashMap<>();
        try {
            List<Object[]> results = messageRepository.countUnreadByConversationIds(convIds);
            for (Object[] row : results) {
                Long convId = (Long) row[0];
                Long count = (Long) row[1];
                unreadMap.put(convId, count.intValue());
            }
        } catch (Exception e) {
            // 未读计算失败时降级为0，不影响主流程
        }
        return convs.stream()
                .map(c -> ConversationDto.from(c, unreadMap.getOrDefault(c.getId(), 0)))
                .toList();
    }

    /**
     * 按分配客服过滤会话：所有角色仅能看到分配给自己的或未分配的会话。
     */
    private List<Conversation> filterByAgentAccess(List<Conversation> convs, Long currentAgentId) {
        if (convs.isEmpty()) return convs;
        return convs.stream()
                .filter(c -> c.getAssignedAgent() == null
                        || (currentAgentId != null && currentAgentId.equals(c.getAssignedAgent().getId())))
                .toList();
    }

    private List<Conversation> queryConversations(Long accountId, String stage, Long merchantId) {
        boolean isPlatform = SecurityUtils.isPlatformAdmin();
        Long effectiveMerchantId = isPlatform ? null : merchantId;

        if (accountId != null && stage != null && !stage.isBlank()) {
            Conversation.Stage stageEnum = Conversation.Stage.valueOf(stage.toUpperCase());
            if (isPlatform) {
                return conversationRepository.findAllByOrderByLastMessageAtDesc().stream()
                        .filter(c -> accountId.equals(c.getDiscordAccount() != null ? c.getDiscordAccount().getId() : null))
                        .filter(c -> stageEnum.equals(c.getStage()))
                        .toList();
            }
            return conversationRepository.findByMerchantIdAndDiscordAccount_IdAndStageOrderByLastMessageAtDesc(
                    effectiveMerchantId, accountId, stageEnum);
        } else if (accountId != null) {
            if (isPlatform) {
                return conversationRepository.findAllByOrderByLastMessageAtDesc().stream()
                        .filter(c -> accountId.equals(c.getDiscordAccount() != null ? c.getDiscordAccount().getId() : null))
                        .toList();
            }
            return conversationRepository.findByMerchantIdAndDiscordAccount_IdOrderByLastMessageAtDesc(effectiveMerchantId, accountId);
        } else if (stage != null && !stage.isBlank()) {
            Conversation.Stage stageEnum = Conversation.Stage.valueOf(stage.toUpperCase());
            if (isPlatform) {
                return conversationRepository.findAllByOrderByLastMessageAtDesc().stream()
                        .filter(c -> stageEnum.equals(c.getStage()))
                        .toList();
            }
            return conversationRepository.findByMerchantIdAndStageOrderByLastMessageAtDesc(effectiveMerchantId, stageEnum);
        } else {
            if (isPlatform) {
                return conversationRepository.findAllByOrderByLastMessageAtDesc();
            }
            return conversationRepository.findByMerchantIdOrderByPinnedAndLastMessageAtDesc(effectiveMerchantId);
        }
    }

    public Conversation loadOwnedConversation(Long id) {
        Conversation conversation = conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        SecurityUtils.checkMerchantAccess(conversation.getMerchantId());

        // 所有角色：仅能访问分配给自己的或未分配的会话
        if (conversation.getAssignedAgent() != null) {
            Long currentAgentId = SecurityUtils.currentAgentId();
            if (currentAgentId == null || !currentAgentId.equals(conversation.getAssignedAgent().getId())) {
                throw new AccessDeniedException("无权访问该会话");
            }
        }
        return conversation;
    }

    public List<MessageDto> listMessages(Long id) {
        loadOwnedConversation(id);
        return messageService.listMessages(id).stream()
                .map(MessageDto::from)
                .toList();
    }

    public List<MessageDto> loadMoreHistory(Long id, String msgId) {
        loadOwnedConversation(id);
        return messageService.loadMoreHistory(id, msgId).stream()
                .map(MessageDto::from)
                .toList();
    }

    public MessageDto sendMessage(Long id, String content, String targetLanguage,
                                   String messageType, String audioData, String audioMimeType,
                                   Integer audioDuration, String audioFileName, String senderName) {
        loadOwnedConversation(id);
        return MessageDto.from(messageService.sendReply(id, content, targetLanguage,
                messageType, audioData, audioMimeType, audioDuration, audioFileName, senderName));
    }

    public ConversationDto updateStatus(Long id, String status) {
        Conversation conversation = loadOwnedConversation(id);
        conversation.setStatus(Conversation.ConversationStatus.valueOf(status));
        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public ConversationDto assignToMe(Long id, Long agentId) {
        Conversation conversation = loadOwnedConversation(id);
        Agent foundAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("客服账号不存在"));
        conversation.setAssignedAgent(foundAgent);
        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public ConversationDto assignToAgent(Long id, Long agentId) {
        Conversation conversation = loadOwnedConversation(id);
        Agent targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("客服不存在"));
        SecurityUtils.checkMerchantAccess(targetAgent.getMerchantId());
        conversation.setAssignedAgent(targetAgent);
        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public ConversationDto transferConversation(Long id, Long agentId, String reason) {
        Conversation conversation = loadOwnedConversation(id);
        Agent targetAgent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("目标客服不存在"));
        SecurityUtils.checkMerchantAccess(targetAgent.getMerchantId());

        String oldAgent = conversation.getAssignedAgent() != null
                ? conversation.getAssignedAgent().getDisplayName()
                : "未分配";
        String newAgent = targetAgent.getDisplayName();
        conversation.setAssignedAgent(targetAgent);

        String remark = conversation.getRemark() != null ? conversation.getRemark() : "";
        String transferNote = String.format("[转移记录: %s → %s, 原因: %s] ",
                oldAgent, newAgent, reason != null ? reason : "未说明");
        conversation.setRemark(transferNote + remark);

        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public List<Map<String, Object>> listAvailableAgents() {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<Agent> agents;
        if (SecurityUtils.isPlatformAdmin()) {
            agents = agentRepository.findAll();
        } else {
            agents = agentRepository.findByMerchantId(merchantId);
        }
        return agents.stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("username", a.getUsername());
            item.put("displayName", a.getDisplayName());
            item.put("role", a.getRole().name());
            item.put("enabled", a.getEnabled());
            return item;
        }).toList();
    }

    public ConversationDto updateStage(Long id, String stage) {
        Conversation conversation = loadOwnedConversation(id);
        if (stage != null) {
            conversation.setStage(Conversation.Stage.valueOf(stage));
            conversation.setStageChangedAt(java.time.Instant.now());
        }
        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public ConversationDto updatePin(Long id, Boolean pinned) {
        Conversation conversation = loadOwnedConversation(id);
        if (pinned != null) {
            conversation.setPinned(pinned);
        }
        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public ConversationDto updateRemark(Long id, String remark) {
        Conversation conversation = loadOwnedConversation(id);
        conversation.setRemark(remark);
        Conversation saved = conversationRepository.save(conversation);
        int unreadCount = messageRepository.countUnreadByConversationId(saved.getId());
        return ConversationDto.from(saved, unreadCount);
    }

    public ConversationDto markAsRead(Long id) {
        Conversation conversation = loadOwnedConversation(id);
        conversation.setLastReadAt(java.time.Instant.now());
        return ConversationDto.from(conversationRepository.save(conversation), 0);
    }

    public MessageDto translateMessage(Long messageId, String targetLang) {
        return MessageDto.from(messageService.translateMessage(messageId, targetLang));
    }

    @Transactional
    public MessageDto detectAndSetLanguage(Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("消息不存在"));
        
        // 检测语言
        Long merchantId = SecurityUtils.currentMerchantId();
        LanguageDetectionService.LanguageResult result = languageDetectionService.detect(message.getContent(), merchantId);
        message.setLanguage(result.getCode());
        
        // 如果有语言且不是目标语言，自动翻译
        if (result.isDetected() && !"zh-cn".equalsIgnoreCase(result.getCode())) {
            String targetLang = "zh-CN";
            translationServiceFactory.translate(message.getContent(), targetLang, merchantId)
                    .ifPresent(message::setTranslatedContent);
            if (message.getTranslatedContent() == null) {
                message.setTranslatedContent(message.getContent());
            }
        } else if (message.getTranslatedContent() == null) {
            message.setTranslatedContent(message.getContent());
        }
        
        Message saved = messageRepository.save(message);
        
        // WebSocket 推送更新
        messagingTemplate.convertAndSend("/topic/messages", MessageDto.from(saved));
        
        return MessageDto.from(saved);
    }

    public MessageDto editMessage(Long messageId, String content) {
        return MessageDto.from(messageService.editMessage(messageId, content));
    }

    public MessageDto deleteMessage(Long messageId) {
        return MessageDto.from(messageService.deleteMessage(messageId));
    }

    public MessageDto addReaction(Long messageId, String emoji, Boolean remove) {
        return MessageDto.from(messageService.addReaction(messageId, emoji, remove));
    }

    public MessageDto replyMessage(Long id, String content, String targetLanguage,
                                    String messageType, String audioData, String audioMimeType,
                                    Integer audioDuration, String audioFileName, String senderName, Long messageId) {
        loadOwnedConversation(id);
        return MessageDto.from(messageService.sendReplyWithReference(id, content, targetLanguage,
                messageType, audioData, audioMimeType, audioDuration, audioFileName, senderName, messageId));
    }
}
