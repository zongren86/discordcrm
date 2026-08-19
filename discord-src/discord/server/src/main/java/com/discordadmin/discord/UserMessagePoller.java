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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
    private final Executor pollExecutor;
    private final TransactionTemplate transactionTemplate;

    /** 已处理的消息ID缓存（防止并发轮询重复处理），key=convId:msgId */
    private final Map<String, Boolean> processedMsgIds = new ConcurrentHashMap<>();

    /** 上次同步DM频道的时间戳（每个账号独立记录） */
    private final Map<Long, Long> lastSyncDmTimeByAccount = new ConcurrentHashMap<>();

    /** 频道最新消息ID快速缓存（用于快速跳过已处理的会话） */
    private final Map<String, String> lastMessageIdByChannel = new ConcurrentHashMap<>();

    /** 熔断器：会话连续失败次数，超过阈值则进入冷却期 */
    private final Map<Long, Integer> conversationFailCount = new ConcurrentHashMap<>();

    /** 熔断器：会话进入冷却期的时间戳（毫秒），冷却期内跳过该会话 */
    private final Map<Long, Long> conversationCooldownUntil = new ConcurrentHashMap<>();

    /** 轮询批次游标：Round-robin方式分批轮询所有会话 */
    private final AtomicInteger pollCursor = new AtomicInteger(0);

    // 熔断参数（放宽以避免网络抖动导致长时间跳过）
    private static final int FAIL_THRESHOLD = 8;        // 连续8次失败才触发熔断
    private static final long COOLDOWN_MS = 5_000;       // 初始冷却5秒
    private static final long COOLDOWN_STEP_MS = 2_000;  // 每次递增2秒
    private static final long TRANSLATE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000; // 7天自动翻译窗口

    // 任务超时10秒（匹配HTTP 8s + 重试+缓冲）
    private static final long TASK_TIMEOUT_SECONDS = 10;

    // 每轮最多提交的会话数（减小批次以提高轮询频率）
    private static final int MAX_BATCH_SIZE = 30;

    public UserMessagePoller(DiscordAccountRepository accountRepository,
                              ConversationRepository conversationRepository,
                              MessageRepository messageRepository,
                              DiscordUserRepository discordUserRepository,
                              DiscordUserClient discordUserClient,
                              MessageService messageService,
                              SimpMessagingTemplate messagingTemplate,
                              @Qualifier("pollExecutor") Executor pollExecutor,
                              PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.discordUserRepository = discordUserRepository;
        this.discordUserClient = discordUserClient;
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
        this.pollExecutor = pollExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Token过期账号ID缓存（10分钟内不再尝试） */
    private final Map<Long, Long> tokenExpiredAccountCooldown = new ConcurrentHashMap<>();

    public void init() {
        log.info("USER 账号 DM 消息轮询器已启动（分批轮询模式，每 1 秒触发，每批 {} 会话）", MAX_BATCH_SIZE);
    }

    /**
     * 主轮询任务：每 1 秒触发，采用分批轮询策略。
     *
     * 策略：
     * 1. 每轮最多提交 MAX_BATCH_SIZE 个会话，使用 Round-robin 游标循环
     * 2. 避免一次性提交所有会话导致线程池过载
     * 3. 每个会话约每 (总会话数/MAX_BATCH_SIZE)*1 秒被轮询一次
     *    例如 322 会话 / 30 * 1s ≈ 10.7s 轮询一次
     * 4. 单会话 HTTP 超时 8s + 重试，任务超时 10s
     */
    @Scheduled(fixedRate = 1000, initialDelay = 5000)
    public void pollNewMessages() {
        long cycleStart = System.currentTimeMillis();
        long now = System.currentTimeMillis();

        List<DiscordAccount> userAccounts = accountRepository.findByStatus(DiscordAccount.AccountStatus.ACTIVE)
                .stream()
                .filter(a -> a.getAccountType() == DiscordAccount.AccountType.USER)
                .toList();

        if (userAccounts.isEmpty()) {
            return;
        }

        // 非阻塞：DM频道同步在独立线程执行，不阻塞消息轮询
        for (DiscordAccount account : userAccounts) {
            // Token过期账号跳过DM同步
            Long tokenExpireUntil = tokenExpiredAccountCooldown.get(account.getId());
            if (tokenExpireUntil != null && now < tokenExpireUntil) {
                continue;
            }

            Long lastSync = lastSyncDmTimeByAccount.get(account.getId());
            boolean needSyncDm = lastSync == null || (now - lastSync) > 30_000;

            if (needSyncDm) {
                lastSyncDmTimeByAccount.put(account.getId(), now);
                CompletableFuture.runAsync(() -> {
                    try {
                        int newConvs = self.syncDmChannelsInTx(account.getId());
                        if (newConvs > 0) {
                            log.info("账号[{}](id={}) DM频道同步完成, 新建 {} 个会话", account.getName(), account.getId(), newConvs);
                        }
                    } catch (Exception e) {
                        log.warn("DM频道异步同步失败 account={}: {}", account.getName(), e.getMessage());
                    }
                }, pollExecutor).orTimeout(5, TimeUnit.SECONDS)
                  .exceptionally(ex -> {
                      log.debug("DM频道同步超时 account={}: {}", account.getName(), ex.getMessage());
                      return null;
                  });
            }
        }

        // 收集所有活跃会话（扁平化列表，便于分批轮询）
        List<Conversation> allConversations = new ArrayList<>();
        Map<Long, DiscordAccount> convAccountMap = new HashMap<>();

        for (DiscordAccount account : userAccounts) {
            try {
                List<Conversation> conversations = conversationRepository
                        .findByDiscordAccountAndType(account, Conversation.ConversationType.DM);
                for (Conversation conv : conversations) {
                    allConversations.add(conv);
                    convAccountMap.put(conv.getId(), account);
                }
            } catch (Exception e) {
                log.warn("加载账号 [{}] 会话失败: {}", account.getName(), e.getMessage());
            }
        }

        if (allConversations.isEmpty()) return;

        int totalConversations = allConversations.size();
        int skippedByCircuitBreaker = 0;
        int submitted = 0;

        // Round-robin 分批：从游标位置开始，最多提交 MAX_BATCH_SIZE 个
        int startIdx = pollCursor.get();
        List<Conversation> batch = new ArrayList<>();
        int visitedCount = 0;  // 实际遍历的会话数

        for (int i = 0; i < totalConversations && batch.size() < MAX_BATCH_SIZE; i++) {
            int idx = (startIdx + i) % totalConversations;
            Conversation conv = allConversations.get(idx);
            visitedCount++;

            // 熔断器检查
            Long cooldownUntil = conversationCooldownUntil.get(conv.getId());
            if (cooldownUntil != null && now < cooldownUntil) {
                skippedByCircuitBreaker++;
                continue;
            }

            // 注：不再使用lastMessageId缓存跳过会话
            // 因为新消息可能在处理完后才到达，缓存会导致新消息被遗漏
            // pollOneConversation内部会做增量拉取（使用after参数），无需跳过

            batch.add(conv);
        }

        // 根据实际遍历的会话数更新游标（确保每个会话都能被轮询到）
        pollCursor.set((startIdx + Math.max(visitedCount, 1)) % totalConversations);

        // 提交批次到线程池
        long nowMs = System.currentTimeMillis();
        for (Conversation conv : batch) {
            DiscordAccount account = convAccountMap.get(conv.getId());
            if (account == null) continue;

            // Token过期账号跳过（10分钟内不再尝试，避免反复触发熔断）
            Long tokenExpireUntil = tokenExpiredAccountCooldown.get(account.getId());
            if (tokenExpireUntil != null && nowMs < tokenExpireUntil) {
                log.debug("账号[{}] Token已过期，跳过会话[convId={}]", account.getName(), conv.getId());
                continue;
            }

            submitted++;
            CompletableFuture.runAsync(() -> {
                try {
                    int newCount = self.pollOneConversationInTx(account.getId(), conv.getId());
                    conversationFailCount.remove(conv.getId());
                    conversationCooldownUntil.remove(conv.getId());
                    if (newCount > 0) {
                        log.debug("会话 [convId={}] 轮询完成, 新增 {} 条消息", conv.getId(), newCount);
                    }
                } catch (Exception e) {
                    int fails = conversationFailCount.merge(conv.getId(), 1, Integer::sum);
                    if (fails >= FAIL_THRESHOLD) {
                        long cooldown = COOLDOWN_MS + (long)(fails - FAIL_THRESHOLD) * COOLDOWN_STEP_MS;
                        conversationCooldownUntil.put(conv.getId(), System.currentTimeMillis() + cooldown);
                        log.warn("会话 [convId={}] 连续失败{}次，进入熔断冷却{}ms: {}",
                                conv.getId(), fails, cooldown, e.getMessage());
                    } else {
                        log.debug("轮询会话 [convId={}] 失败({}/{}): {}",
                                conv.getId(), fails, FAIL_THRESHOLD, e.getMessage());
                    }
                }
            }, pollExecutor).orTimeout(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .exceptionally(ex -> {
                  log.debug("会话 [convId={}] 轮询超时或异常: {}", conv.getId(), ex.getMessage());
                  return null;
              });
        }

        long elapsed = System.currentTimeMillis() - cycleStart;
        if (elapsed > 100 || submitted > 0 || skippedByCircuitBreaker > 0) {
            log.info("消息轮询周期: 耗时={}ms, 总会话={}, 本批提交={}, 熔断跳过={}, 游标={}/{}",
                    elapsed, totalConversations, submitted, skippedByCircuitBreaker,
                    pollCursor.get(), totalConversations);
        }

        // 清理过期缓存
        if (processedMsgIds.size() > 50000) processedMsgIds.clear();
        if (lastMessageIdByChannel.size() > 100000) lastMessageIdByChannel.clear();

        // 清理已过期的Token冷却记录
        if (!tokenExpiredAccountCooldown.isEmpty()) {
            long currentTime = System.currentTimeMillis();
            tokenExpiredAccountCooldown.entrySet().removeIf(entry -> currentTime >= entry.getValue());
        }
    }

    /**
     * 轮询单个会话：先用独立事务加载必要数据，释放连接后再做HTTP调用。
     * 避免在长耗时HTTP调用期间占用数据库连接。
     * @return 新增的消息数量
     */
    public int pollOneConversationInTx(Long accountId, Long conversationId) {
        // Phase 1: 在短事务中加载数据（加载后立即释放连接）
        LoadedConversationData data = transactionTemplate.execute(status -> {
            DiscordAccount account = accountRepository.findById(accountId).orElse(null);
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (account == null || conv == null) {
                return null;
            }
            // 直接从Repository加载完整的DiscordUser（避免懒加载代理问题）
            DiscordUser user = null;
            if (conv.getDiscordUser() != null && conv.getDiscordUser().getId() != null) {
                user = discordUserRepository.findById(conv.getDiscordUser().getId()).orElse(null);
            }
            String channelId = conv.getChannelId();
            String lastMsgId = conv.getLastDiscordMessageId();
            Long convId = conv.getId();
            
            return new LoadedConversationData(account, conv, user, channelId, lastMsgId, convId);
        });

        if (data == null) {
            log.warn("轮询失败: account={}, conv={}", accountId, conversationId);
            return 0;
        }

        // Phase 2: HTTP调用（此时DB连接已释放）
        return pollOneConversation(data);
    }

    /**
     * 同步DM频道到本地conversations表。
     * 独立运行，不阻塞消息轮询。
     * @return 本次新建的 Conversation 数量
     */
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
                // 标记账号Token过期，10分钟内不再尝试
                tokenExpiredAccountCooldown.put(account.getId(), System.currentTimeMillis() + 10 * 60 * 1000L);
                log.warn("账号[{}](id={}) Token 已过期（同步DM频道失败），已标记为10分钟内跳过", account.getName(), account.getId());
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
            if (type != 1) continue;
            String channelId = ch.path("id").asText(null);
            if (channelId == null || channelId.isBlank()) continue;
            Optional<Conversation> existing = conversationRepository
                    .findByChannelIdAndDiscordAccount_Id(channelId, account.getId());
            if (existing.isPresent()) continue;

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
            user.setUsername(friendNode.path("username").asText(user.getUsername()));
            String gn = friendNode.path("global_name").asText(null);
            if (gn != null && !gn.isBlank()) user.setGlobalName(gn);
            String av = friendNode.path("avatar").asText(null);
            if (av != null && !av.isBlank()) {
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

    /**
     * 轮询单个会话，拉取并处理新消息。
     *
     * 优化策略（增量拉取模式）：
     * 1. 用 lastMessageIdByChannel 缓存每个频道上次处理的最新消息ID
     * 2. 首次拉取：获取最新100条消息，缓存最新消息ID
     * 3. 后续拉取：用 after 参数只拉取新消息（单次API调用，消除快速检查+全量拉取的双重调用）
     * 4. 增量模式下无需查询DB全量消息ID，因为API已保证只返回新消息
     *
     * 性能对比：
     * - 优化前：每轮 2次API调用 + 1次DB全量查询 ≈ 1.6-2.4s
     * - 优化后：每轮 1次API调用（增量） ≈ 0.8-1.2s（有新消息时），0.3-0.5s（无新消息时）
     *
     * @return 新增的消息数量
     */
    private int pollOneConversation(LoadedConversationData data) {
        DiscordAccount account = data.account();
        Conversation conv = data.conv();
        DiscordUser user = data.user();
        String channelId = data.channelId();
        String lastKnownMsgId = data.lastMsgId();
        
        long convStart = System.currentTimeMillis();

        if (user == null) {
            log.warn("会话 [convId={}] 关联的 DiscordUser 为 null", conv.getId());
            return 0;
        }

        if (channelId == null || channelId.isBlank()) {
            log.warn("会话 [convId={}] channelId 为空", conv.getId());
            return 0;
        }

        final int PAGE_SIZE = 20;
        final int MAX_INITIAL_PAGES = 2;

        // 内存缓存更新为最新值（DB可能滞后）
        String cacheKey = account.getId() + ":" + channelId;
        String cachedId = lastMessageIdByChannel.get(cacheKey);
        if (cachedId != null && !cachedId.isBlank()) {
            lastKnownMsgId = cachedId;
        }

        // 构建增量拉取请求（HTTP调用，不在DB事务内）
        JsonNode pageMessages;
        try {
            if (lastKnownMsgId != null && !lastKnownMsgId.isBlank()) {
                // 增量拉取：只获取lastKnownMsgId之后的新消息（单次API调用）
                pageMessages = discordUserClient.listMessagesAfter(
                        account.getToken(), channelId, lastKnownMsgId, PAGE_SIZE);
            } else {
                // 首次拉取：获取最新20条消息
                pageMessages = discordUserClient.listMessages(
                        account.getToken(), channelId, PAGE_SIZE);
            }
        } catch (DiscordUserClient.DiscordUserApiException e) {
            if (e.statusCode == 401) {
                account.setLastError("Token 已过期，请用 Chrome 插件重新导入 Token 续期");
                transactionTemplate.execute(status -> {
                    accountRepository.save(account);
                    return null;
                });
                // 标记账号Token过期，10分钟内不再尝试（避免反复触发熔断）
                tokenExpiredAccountCooldown.put(account.getId(), System.currentTimeMillis() + 10 * 60 * 1000L);
                log.warn("账号[{}](id={}) Token 已过期，已标记为10分钟内跳过", account.getName(), account.getId());
            } else {
                log.warn("拉取频道消息失败: status={}, channelId={}", e.statusCode, channelId);
            }
            throw new RuntimeException("拉取频道消息失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("拉取频道消息失败: " + e.getMessage(), e);
        }

        if (pageMessages == null || !pageMessages.isArray() || pageMessages.isEmpty()) {
            // 没有新消息，直接返回
            long elapsed = System.currentTimeMillis() - convStart;
            if (elapsed > 100) {
                log.debug("会话 [convId={}] 无新消息, 耗时={}ms", conv.getId(), elapsed);
            }
            return 0;
        }

        // 更新用户活跃度（轻量DB操作）
        user.setLastActiveAt(Instant.now());
        transactionTemplate.execute(status -> {
            discordUserRepository.save(user);
            return null;
        });

        // 构建已有消息ID集合用于去重
        Set<String> existingMsgIdSet = new HashSet<>();
        boolean isInitialFetch = (lastKnownMsgId == null);

        if (!isInitialFetch) {
            // 增量模式：只需将lastKnownMsgId加入集合作为边界
            existingMsgIdSet.add(lastKnownMsgId);
        } else {
            // 首次拉取：从DB加载已有消息ID用于去重
            List<String> existingMsgIds = messageRepository.findDiscordMessageIdsByConversation(conv.getId());
            existingMsgIdSet.addAll(existingMsgIds);
        }

        int totalNew = 0;
        int totalPages = 1;
        String pageOldestId = null;

        // 处理第一页
        {
            ProcessResult pr = processFetchedMessages(account, conv, pageMessages, existingMsgIdSet);
            totalNew += pr.newCount;
            pageOldestId = pr.oldestMsgId;
        }

        // 更新缓存的最新消息ID（Discord返回倒序，index 0是最新的）
        String newestId = null;
        if (pageMessages.size() > 0) {
            newestId = pageMessages.get(0).path("id").asText();
            lastMessageIdByChannel.put(cacheKey, newestId);
        }

        // 历史回填翻页逻辑（仅首次拉取时需要，增量模式跳过）
        if (isInitialFetch) {
            while (totalPages < MAX_INITIAL_PAGES && pageOldestId != null && totalNew > 0) {
                boolean hasMoreInDiscord = pageMessages.size() >= PAGE_SIZE;
                if (!hasMoreInDiscord) break;

                JsonNode olderPage;
                try {
                    olderPage = discordUserClient.listMessagesBefore(
                            account.getToken(), channelId, pageOldestId, PAGE_SIZE);
                } catch (Exception e) {
                    log.warn("翻页拉更早消息失败(停止回填): conv={} page={} err={}",
                            conv.getId(), totalPages + 1, e.getMessage());
                    break;
                }
                if (olderPage == null || !olderPage.isArray() || olderPage.isEmpty()) break;

                ProcessResult pr = processFetchedMessages(account, conv, olderPage, existingMsgIdSet);
                totalNew += pr.newCount;
                totalPages++;
                if (pr.newCount == 0) break;
                pageOldestId = pr.oldestMsgId;
                pageMessages = olderPage;
            }
        }

        // 会话更新 + lastDiscordMessageId持久化（在独立事务中执行）
        final int finalTotalNew = totalNew;
        final String finalNewestId = newestId;
        final long elapsedMs = System.currentTimeMillis() - convStart;

        transactionTemplate.execute(status -> {
            // 刷新会话引用（避免懒加载问题）
            Conversation convRef = conversationRepository.findById(conv.getId()).orElse(conv);
            
            // 持久化最后处理的Discord消息ID
            if (finalNewestId != null && !finalNewestId.isBlank()) {
                convRef.setLastDiscordMessageId(finalNewestId);
            }

            // 检查漏斗阶段升级
            if (convRef.getStage() == Conversation.Stage.PROSPECT) {
                boolean hasIn = messageRepository.countInboundMessages(convRef) > 0;
                boolean hasOut = messageRepository.countOutboundMessages(convRef) > 0;
                if (hasIn && hasOut) {
                    convRef.setStage(Conversation.Stage.NEW);
                    convRef.setStageChangedAt(Instant.now());
                    log.info("会话 [convId={}] 双方已互动，漏斗阶段升级为 NEW", convRef.getId());
                }
            }

            // 会话更新推送
            if (finalTotalNew > 0) {
                int unreadCount = calculateUnreadCount(convRef.getId());
                log.info("会话 [convId={}] 新增 {} 条消息, 未读数: {}, 耗时={}ms, 模式={}",
                        convRef.getId(), finalTotalNew, unreadCount, elapsedMs,
                        isInitialFetch ? "initial" : "incremental");
                ConversationDtos.ConversationDto convDto = ConversationDtos.ConversationDto.from(convRef, unreadCount);
                messagingTemplate.convertAndSend("/topic/conversations", convDto);
            }

            conversationRepository.save(convRef);
            return null;
        });

        return totalNew;
    }

    /**
     * 处理一页 Discord 消息（批量事务优化：一页消息共享一个数据库连接）
     */
    private ProcessResult processFetchedMessages(DiscordAccount account, Conversation conv, JsonNode messages, Set<String> existingMsgIdSet) {
        String convKey = conv.getId() + ":";

        // ===== Phase 1: 解析所有消息，构建实体（无数据库操作） =====
        record ParsedMessage(Message entity, boolean isOutbound, String content, Instant discordCreatedAt, boolean isVoice) {}
        List<ParsedMessage> parsedList = new ArrayList<>();
        String oldestId = null;

        for (int i = 0; i < messages.size(); i++) {
            JsonNode msgNode = messages.get(i);
            String msgId = msgNode.path("id").asText(null);
            if (msgId == null) continue;
            if (i == messages.size() - 1) oldestId = msgId;

            // 内存去重
            if (existingMsgIdSet.contains(msgId)) continue;

            // 并发去重
            String processedKey = convKey + msgId;
            if (processedMsgIds.containsKey(processedKey)) continue;
            processedMsgIds.put(processedKey, Boolean.TRUE);

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

            // 解析附件（网络调用：下载语音数据在此处完成，不在事务内）
            JsonNode attachments = msgNode.get("attachments");
            String attachmentsJson = null;
            String resolvedMessageType = "text";
            String resolvedAudioUrl = null;
            String resolvedAudioMime = null;
            Integer resolvedAudioDuration = null;
            String resolvedAudioData = null;

            if (attachments != null && attachments.isArray() && !attachments.isEmpty()) {
                List<String> urls = new ArrayList<>();
                JsonNode voiceAtt = null;
                for (JsonNode a : attachments) {
                    String u = a.path("url").asText(null);
                    if (u != null) urls.add(u);
                    String fn = a.path("filename").asText("").toLowerCase();
                    boolean isAudioByCt = a.path("content_type").asText("").toLowerCase().startsWith("audio/");
                    boolean hasVoiceName = fn.startsWith("voice-message");
                    boolean hasDuration = a.has("duration_secs");
                    if ((hasVoiceName || isAudioByCt || hasDuration) && voiceAtt == null) {
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
                msgEntity.setAsrStatus("pending");
            }
            msgEntity.setDiscordCreatedAt(discordCreatedAt);
            msgEntity.setCreatedAt(discordCreatedAt);
            msgEntity.setTranslatedContent(content);

            parsedList.add(new ParsedMessage(msgEntity, isOutbound, content, discordCreatedAt, "voice".equals(resolvedMessageType)));
            existingMsgIdSet.add(msgId);
        }

        if (parsedList.isEmpty()) {
            return new ProcessResult(0, oldestId);
        }

        // ===== Phase 2: 批量保存（单事务，共享一个数据库连接） =====
        List<Message> savedList = transactionTemplate.execute(status -> {
            List<Message> result = new ArrayList<>();
            for (ParsedMessage pm : parsedList) {
                Message saved = messageRepository.save(pm.entity());
                result.add(saved);
            }
            return result;
        });

        // ===== Phase 3: 先推送前端（立即展示原文），再异步触发翻译/ASR =====
        int newCount = 0;
        for (int idx = 0; idx < savedList.size(); idx++) {
            Message msgEntity = savedList.get(idx);
            ParsedMessage pm = parsedList.get(idx);
            newCount++;

            Long finalMsgId = msgEntity.getId();

            // ① 先推送到前端（用户立即可见原文）
            MessageDtos.MessageDto dto = MessageDtos.MessageDto.from(msgEntity);
            messagingTemplate.convertAndSend("/topic/messages", dto);

            // ② 再异步触发翻译（@Async，不阻塞主流程，翻译完成后自动推送更新版本）
            if (!pm.isOutbound() && !pm.isVoice() && pm.content() != null && !pm.content().isBlank()) {
                boolean withinTranslateWindow = pm.discordCreatedAt() != null
                        && (System.currentTimeMillis() - pm.discordCreatedAt().toEpochMilli()) <= TRANSLATE_WINDOW_MS;
                if (withinTranslateWindow) {
                    messageService.translateMessageAsync(finalMsgId, "zh-CN");
                } else {
                    log.debug("消息超过7天窗口，跳过自动翻译 msgId={}, createdAt={}",
                            finalMsgId, pm.discordCreatedAt());
                }
            }

            // ③ 异步触发ASR（@Async）
            if (pm.isVoice()) {
                try {
                    Long merchantId = conv.getMerchantId();
                    if (merchantId == null && conv.getDiscordAccount() != null) {
                        merchantId = conv.getDiscordAccount().getMerchantId();
                    }
                    boolean autoTranslate = !pm.isOutbound();
                    messageService.runAsrAsync(finalMsgId, merchantId, autoTranslate);
                } catch (Exception e) {
                    log.warn("触发语音 ASR 失败 conv={} msg={}: {}", conv.getId(),
                            msgEntity.getDiscordMessageId(), e.getMessage());
                }
            }

            // ④ 更新会话最后消息信息
            String preview = pm.content().length() > 200 ? pm.content().substring(0, 200) : pm.content();
            if (msgEntity.getDiscordCreatedAt() != null
                    && (conv.getLastMessageAt() == null || !msgEntity.getDiscordCreatedAt().isBefore(conv.getLastMessageAt()))) {
                conv.setLastMessagePreview(preview);
                conv.setLastMessageDirection(pm.isOutbound() ? Message.Direction.OUTBOUND.name() : Message.Direction.INBOUND.name());
                conv.setLastMessageAt(msgEntity.getDiscordCreatedAt());
            }
        }

        return new ProcessResult(newCount, oldestId);
    }

    private static final class ProcessResult {
        final int newCount;
        final String oldestMsgId;
        ProcessResult(int newCount, String oldestMsgId) {
            this.newCount = newCount;
            this.oldestMsgId = oldestMsgId;
        }
    }

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

    /** 预加载的会话数据（在短事务中完成加载，供后续HTTP调用使用） */
    private record LoadedConversationData(
            DiscordAccount account,
            Conversation conv,
            DiscordUser user,
            String channelId,
            String lastMsgId,
            Long convId
    ) {}
}
