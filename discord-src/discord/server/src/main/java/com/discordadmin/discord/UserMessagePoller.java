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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        log.info("USER 账号 DM 消息轮询器已启动（每 1 秒轮询一次）");
    }

    @Scheduled(fixedRate = 1000, initialDelay = 10000)
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
                // 轮询前先同步 DM 频道：避免好友新开的 DM（Discord 客户端有消息但我们没建 Conversation）漏拉
                int newConvs = self.syncDmChannelsInTx(account.getId());
                if (newConvs > 0) {
                    log.info("账号[{}](id={}) 同步DM频道, 新建 {} 个会话", account.getName(), account.getId(), newConvs);
                }

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

    /** 每个账号轮询前先跑一次：把 Discord 当前所有 DM channel 同步到 conversations/discord_users 表，
     *  避免 Discord 客户端有新好友发消息但我们没建 Conversation 而漏拉。
     *  @return 本次新建的 Conversation 数量（用于打日志） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int syncDmChannelsInTx(Long accountId) {
        DiscordAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null || account.getToken() == null || account.getToken().isBlank()) return 0;
        JsonNode channels;
        try {
            channels = discordUserClient.listDmChannels(account.getToken());
        } catch (DiscordUserClient.DiscordUserApiException e) {
            if (e.statusCode == 401) {
                account.setLastError("Token 已过期，请用 Chrome 插件重新导入 Token 续期");
                accountRepository.save(account);
                log.warn("账号[{}] Token 已过期（同步DM频道失败）", account.getName());
            }
            log.warn("同步DM频道 API 失败: account={} status={} err={}", account.getName(), e.statusCode, e.getMessage());
            return 0;
        } catch (Exception e) {
            log.warn("同步DM频道异常: account={} err={}", account.getName(), e.getMessage());
            return 0;
        }
        if (channels == null || !channels.isArray()) return 0;
        int created = 0;
        for (JsonNode ch : channels) {
            int type = ch.path("type").asInt(-1);
            if (type != 1) continue;                    // Discord type=1 → DM
            String channelId = ch.path("id").asText(null);
            if (channelId == null || channelId.isBlank()) continue;
            // 先查 per-account 下是否已有该频道对应 Conversation
            Optional<Conversation> existing = conversationRepository
                    .findByChannelIdAndDiscordAccount_Id(channelId, account.getId());
            if (existing.isPresent()) continue;

            // 取 recipients[0]（DM 对方）；recipients 不包含账号自己（或者可能包含，过滤一下）
            JsonNode recipients = ch.get("recipients");
            if (recipients == null || !recipients.isArray() || recipients.isEmpty()) continue;
            JsonNode friendNode = null;
            for (JsonNode r : recipients) {
                String rid = r.path("id").asText(null);
                if (rid == null) continue;
                if (!rid.equals(account.getDiscordId())) { friendNode = r; break; }
            }
            if (friendNode == null) friendNode = recipients.get(0);

            String friendDiscordId = friendNode.path("id").asText(null);
            if (friendDiscordId == null || friendDiscordId.isBlank()) continue;
            DiscordUser user = discordUserRepository.findByDiscordUserId(friendDiscordId).orElse(null);
            if (user == null) {
                user = new DiscordUser();
                user.setDiscordUserId(friendDiscordId);
                user.setFirstSeenAt(Instant.now());
            }
            // 用最新信息覆盖昵称/头像
            user.setUsername(friendNode.path("username").asText(user.getUsername()));
            String gn = friendNode.path("global_name").asText(null);
            if (gn != null && !gn.isBlank()) user.setGlobalName(gn);
            String av = friendNode.path("avatar").asText(null);
            if (av != null && !av.isBlank()) {
                // avatar hash → CDN URL（尽量写全，方便前端展示）
                String ext = av.startsWith("a_") ? "gif" : "png";
                user.setAvatarUrl("https://cdn.discordapp.com/avatars/" + friendDiscordId + "/" + av + "." + ext);
            }
            user = discordUserRepository.save(user);

            Conversation conv = new Conversation();
            conv.setDiscordAccount(account);
            conv.setChannelId(channelId);
            conv.setDiscordUser(user);
            conv.setType(Conversation.ConversationType.DM);
            conv.setStatus(Conversation.ConversationStatus.OPEN);
            conv.setStage(Conversation.Stage.PROSPECT);
            conv.setMerchantId(account.getMerchantId());
            conv.setCreatedAt(Instant.now());
            conversationRepository.save(conv);
            created++;
        }
        return created;
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

        final String lastMsgKey = account.getId() + ":" + channelId;
        final int PAGE_SIZE = 100;                     // Discord 单页上限 100
        final int MAX_HISTORY_PAGES = 10;              // 单会话单次轮询最多往前翻 10 页 = 1000 条，避免大会话一次拉爆

        Optional<String> optMinId = messageRepository.findMinDiscordMessageIdByConversation(conv);
        Optional<String> optMaxId = messageRepository.findMaxDiscordMessageIdByConversation(conv);
        // 为了漏斗阶段判断 / existingMessages 字段，不再拉全量（性能差），改成一次 COUNT
        long existingInDb  = messageRepository.countInboundMessages(conv)
                           + messageRepository.countOutboundMessages(conv);
        log.info("会话 [convId={}, channelId={}] 已有 {} 条历史消息(DB)，minId={}，maxId={}",
                conv.getId(), channelId, existingInDb,
                optMinId.orElse("null"), optMaxId.orElse("null"));

        // 漏斗阶段判断（只跑一次，轻量 COUNT）
        if (conv.getStage() == Conversation.Stage.PROSPECT) {
            boolean hasIn  = messageRepository.countInboundMessages(conv) > 0;
            boolean hasOut = messageRepository.countOutboundMessages(conv) > 0;
            if (hasIn && hasOut) {
                conv.setStage(Conversation.Stage.NEW);
                conv.setStageChangedAt(Instant.now());
                conversationRepository.save(conv);
                log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", conv.getId());
            }
        }

        // 第 1 页：按倒序拉最近的 PAGE_SIZE 条（Discord 返回 order=new→old）
        JsonNode pageMessages;
        try {
            pageMessages = discordUserClient.listMessages(account.getToken(), channelId, PAGE_SIZE);
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
        if (pageMessages == null || !pageMessages.isArray()) {
            log.info("会话 [convId={}] 返回消息为空或非数组", conv.getId());
            return;
        }

        user.setLastActiveAt(Instant.now());
        discordUserRepository.save(user);

        int totalNew = 0;
        int totalPages = 1;
        String pageOldestId = null;
        {
            ProcessResult pr = processFetchedMessages(account, conv, pageMessages);
            totalNew += pr.newCount;
            pageOldestId = pr.oldestMsgId;
        }
        // 更新快速跳过锚点（内存，仅性能优化；重启不丢失也行，DB 会去重）
        if (pageMessages.size() > 0) {
            lastMessageIdByChannel.put(lastMsgKey, pageMessages.get(0).path("id").asText());
        }

        // =============== 历史回填翻页逻辑 ===============
        // 判断是否需要向前翻页（before=当前页的最早一条，拉更早的）：
        //   1) 第一页拉满 PAGE_SIZE 条 & 第一页新增 == PAGE_SIZE → 全是新的，更老的肯定没覆盖
        //   2) DB 有最旧 minId，但当前页 oldestId > minId（字符串字典序≈Snowflake时间序）→ 说明中间有断层，需要往前翻到接得上
        while (totalPages < MAX_HISTORY_PAGES && pageOldestId != null) {
            boolean firstPageFull = (totalPages == 1) && pageMessages.size() >= PAGE_SIZE;
            boolean allNewOnFirst = firstPageFull && totalNew >= pageMessages.size(); // 近似判断
            boolean gapToMin = false;
            if (optMinId.isPresent()) {
                // Discord message id 是 Snowflake（字符串字典序完全等价时间先后）
                if (pageOldestId.compareTo(optMinId.get()) > 0) gapToMin = true;
            } else {
                // DB 完全没消息 → 允许回填 MAX_HISTORY_PAGES 页最近的历史
                gapToMin = existingInDb == 0;
            }
            boolean needMore = (allNewOnFirst || gapToMin);
            // 第一页已经处理了一页，不满 100 条 & 没命中 gapToMin → 后面没了
            boolean hasMoreInDiscord = pageMessages.size() >= PAGE_SIZE;
            if (!needMore || !hasMoreInDiscord) break;

            JsonNode olderPage;
            try {
                olderPage = discordUserClient.listMessagesBefore(account.getToken(), channelId, pageOldestId, PAGE_SIZE);
            } catch (DiscordUserClient.DiscordUserApiException e) {
                log.warn("翻页拉更早消息失败(停止回填): conv={} page={} status={} err={}",
                        conv.getId(), totalPages + 1, e.statusCode, e.getMessage());
                break;
            } catch (Exception e) {
                log.warn("翻页拉更早消息异常(停止回填): conv={} page={} err={}",
                        conv.getId(), totalPages + 1, e.getMessage());
                break;
            }
            if (olderPage == null || !olderPage.isArray() || olderPage.isEmpty()) break;

            ProcessResult pr = processFetchedMessages(account, conv, olderPage);
            totalNew += pr.newCount;
            totalPages++;
            // 如果翻到的这一页新增为 0 → 已全部衔接 DB 已有，停止翻页
            if (pr.newCount == 0) break;
            pageOldestId = pr.oldestMsgId;
            pageMessages = olderPage;
        }
        log.info("会话 [convId={}] 共处理 {} 页 Discord 消息，新增入库 {} 条",
                conv.getId(), totalPages, totalNew);

        if (totalNew > 0) {
            int unreadCount = calculateUnreadCount(conv.getId());
            log.info("会话 [convId={}] 最终新增 {} 条消息, 未读数: {}", conv.getId(), totalNew, unreadCount);
            ConversationDtos.ConversationDto convDto = ConversationDtos.ConversationDto.from(conv, unreadCount);
            messagingTemplate.convertAndSend("/topic/conversations", convDto);
        } else {
            log.info("会话 [convId={}] 无新消息 (lastMsgId={})", conv.getId(), lastMessageIdByChannel.get(lastMsgKey));
        }
    }

    /** 处理一页 Discord 消息（顺序：Discord 返回的是倒序 new→old，processFetchedMessages 内部会保持这个顺序逐个入库/去重）。
     *  返回本页的处理结果：新增数 + 本页最早的 msgId（即数组最后一个 id，用于 before 翻页继续往前拉）。*/
    private ProcessResult processFetchedMessages(DiscordAccount account, Conversation conv, JsonNode messages) {
        int newCount = 0;
        String oldestId = null;
        // messages 是倒序，index 0 = 最新，最后 = 最早
        for (int i = 0; i < messages.size(); i++) {
            JsonNode msgNode = messages.get(i);
            String msgId = msgNode.path("id").asText(null);
            if (msgId == null) continue;
            if (i == messages.size() - 1) oldestId = msgId;

            // DB 去重（不依赖内存锚点，重启也不丢）
            if (messageRepository.findByConversationAndDiscordMessageId(conv, msgId).isPresent()) {
                continue;
            }

            JsonNode author = msgNode.path("author");
            String authorId = author.path("id").asText(null);
            String authorName = author.path("global_name").asText(
                    author.path("username").asText("Unknown"));
            String content = msgNode.path("content").asText("");

            String timestampStr = msgNode.path("timestamp").asText(null);
            Instant discordCreatedAt = null;
            if (timestampStr != null && !timestampStr.isBlank()) {
                try { discordCreatedAt = Instant.parse(timestampStr); } catch (Exception ignore) {}
            }
            if (discordCreatedAt == null) discordCreatedAt = Instant.now();

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
                    boolean isAudioByCt = a.path("content_type").asText("").toLowerCase().startsWith("audio/");
                    boolean hasVoiceName = fn.startsWith("voice-message");
                    boolean hasDuration = a.has("duration_secs");
                    if (hasVoiceName || isAudioByCt || hasDuration) voiceAtt = a;
                }
                attachmentsJson = String.join(",", urls);
                if (voiceAtt != null) {
                    resolvedMessageType = "voice";
                    String url = voiceAtt.path("url").asText(null);
                    String proxy = voiceAtt.path("proxy_url").asText(null);
                    resolvedAudioUrl = url != null ? url : proxy;
                    resolvedAudioMime = resolveAttachmentMime(voiceAtt);
                    int dur = -1;
                    if (voiceAtt.has("duration_secs")) dur = (int) Math.round(voiceAtt.path("duration_secs").asDouble(0.0));
                    else if (voiceAtt.has("duration")) dur = (int) Math.round(voiceAtt.path("duration").asDouble(0.0));
                    resolvedAudioDuration = dur > 0 ? dur : null;
                    if (resolvedAudioUrl != null) {
                        resolvedAudioData = discordUserClient.downloadAsBase64(resolvedAudioUrl);
                    }
                    if (content == null || content.isBlank()) content = "[语音消息]";
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
                // 语音消息：content 还是占位 "[语音消息]"，不要立刻翻译占位文本，
                // 等 ASR 转写+翻译完成后由 runAsrAsync 统一写入 translated_content / asrTranslated / content
                if ("voice".equals(resolvedMessageType)) {
                    msgEntity.setTranslatedContent(content);
                } else {
                    try {
                        messageService.translateAndSave(msgEntity, "zh-CN");
                    } catch (Exception e) {
                        log.warn("翻译消息失败，使用原文: {}", e.getMessage());
                        msgEntity.setTranslatedContent(content);
                    }
                }
            } else {
                msgEntity.setTranslatedContent(content);
            }

            msgEntity = messageRepository.save(msgEntity);
            newCount++;

            if ("voice".equals(resolvedMessageType)) {
                try {
                    Long merchantId = conv.getMerchantId();
                    if (merchantId == null && conv.getDiscordAccount() != null) {
                        merchantId = conv.getDiscordAccount().getMerchantId();
                    }
                    boolean autoTranslate = !isOutbound;
                    final Long finalMsgId = msgEntity.getId();
                    final Long finalMerchantId = merchantId;
                    // 先标记 pending 并保存
                    msgEntity.setAsrStatus("pending");
                    msgEntity = messageRepository.save(msgEntity);
                    // 使用事务同步：在事务提交后再调用 @Async，确保异步线程能查到已提交的消息
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                log.info("事务提交后触发 ASR: msgId={}", finalMsgId);
                                messageService.runAsrAsync(finalMsgId, finalMerchantId, autoTranslate);
                            }
                        });
                    } else {
                        // 如果没有事务同步（理论上不会发生），直接调用
                        messageService.runAsrAsync(finalMsgId, finalMerchantId, autoTranslate);
                    }
                } catch (Exception e) {
                    log.warn("触发语音 ASR 失败 conv={} msg={}: {}", conv.getId(),
                            msgEntity.getDiscordMessageId(), e.getMessage());
                }
            }

            String preview = content.length() > 200 ? content.substring(0, 200) : content;
            // 只有"比已存更晚的消息"才更新 lastMessagePreview/lastMessageAt；否则翻页回填的旧消息会把会话顶到错误位置
            if (msgEntity.getDiscordCreatedAt() != null
                    && (conv.getLastMessageAt() == null || !msgEntity.getDiscordCreatedAt().isBefore(conv.getLastMessageAt()))) {
                conv.setLastMessagePreview(preview);
                conv.setLastMessageDirection(isOutbound ? "OUTBOUND" : "INBOUND");
                conv.setLastMessageAt(msgEntity.getDiscordCreatedAt());
            }

            if (conv.getStage() == Conversation.Stage.PROSPECT) {
                // 轻量：基于消息实体的方向判断，不再重查 DB
                boolean hasIn  = messageRepository.countInboundMessages(conv) > 0;
                boolean hasOut = messageRepository.countOutboundMessages(conv) > 0;
                if (hasIn && hasOut) {
                    conv.setStage(Conversation.Stage.NEW);
                    conv.setStageChangedAt(Instant.now());
                    log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", conv.getId());
                }
            }
            conversationRepository.save(conv);

            MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(msgEntity);
            messagingTemplate.convertAndSend("/topic/messages", dto);
            messagingTemplate.convertAndSend("/topic/conversations/" + conv.getId(), dto);
        }
        return new ProcessResult(newCount, oldestId);
    }

    /** processFetchedMessages 的轻量返回值：新增消息数 + 本页最早 msgId（用于 before 翻页）*/
    private static final class ProcessResult {
        final int newCount;
        final String oldestMsgId;
        ProcessResult(int newCount, String oldestMsgId) {
            this.newCount = newCount;
            this.oldestMsgId = oldestMsgId;
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
