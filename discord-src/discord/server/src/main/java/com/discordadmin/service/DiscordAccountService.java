package com.discordadmin.service;

import com.discordadmin.discord.DiscordBotManager;
import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.dto.DiscordAccountDtos.*;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.AgentAccountNumberRel;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.AgentAccountNumberRelRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.ConversationRepository;
import com.discordadmin.repository.DiscordAccountNumberRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.FetchProgressRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.repository.GuildMemberRepository;
import com.discordadmin.repository.GuildServerRepository;
import com.discordadmin.repository.MessageRepository;
import com.discordadmin.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DiscordAccountService {

    private static final Logger log = LoggerFactory.getLogger(DiscordAccountService.class);
    private static final int MAX_DELETE_RETRIES = 3;

    /** Token 有效性缓存：key=accountId, value=[isValid, expireAt] */
    private static final ConcurrentMap<Long, Object[]> TOKEN_VALID_CACHE = new ConcurrentHashMap<>();
    /** Token 缓存有效期（5分钟） */
    private static final long TOKEN_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final DiscordAccountRepository accountRepository;
    private final DiscordBotManager botManager;
    private final DiscordUserClient userClient;
    private final RelationshipSyncService syncService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final FriendRepository friendRepository;
    private final AgentRepository agentRepository;
    private final GuildServerRepository guildServerRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final FetchProgressRepository fetchProgressRepository;
    private final DiscordAccountNumberRepository accountNumberRepository;
    private final AgentAccountNumberRelRepository relRepository;
    private final PlatformTransactionManager transactionManager;

    public DiscordAccountService(DiscordAccountRepository accountRepository,
                                 DiscordBotManager botManager,
                                 DiscordUserClient userClient,
                                 RelationshipSyncService syncService,
                                 ConversationRepository conversationRepository,
                                 MessageRepository messageRepository,
                                 FriendRepository friendRepository,
                                 AgentRepository agentRepository,
                                 GuildServerRepository guildServerRepository,
                                 GuildMemberRepository guildMemberRepository,
                                 FetchProgressRepository fetchProgressRepository,
                                 DiscordAccountNumberRepository accountNumberRepository,
                                 AgentAccountNumberRelRepository relRepository,
                                 PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.botManager = botManager;
        this.userClient = userClient;
        this.syncService = syncService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.friendRepository = friendRepository;
        this.agentRepository = agentRepository;
        this.guildServerRepository = guildServerRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.fetchProgressRepository = fetchProgressRepository;
        this.accountNumberRepository = accountNumberRepository;
        this.relRepository = relRepository;
        this.transactionManager = transactionManager;
    }

    public List<AccountDto> listAccounts(String keyword, String status) {
        Long merchantId = SecurityUtils.currentMerchantId();
        boolean isPlatformAdmin = SecurityUtils.isPlatformAdmin();
        boolean isMerchantAdmin = "MERCHANT_ADMIN".equals(SecurityUtils.currentRole());
        Long currentAgentId = SecurityUtils.currentAgentId();

        List<DiscordAccount> accounts = queryAccounts(keyword, status, merchantId, isPlatformAdmin, isMerchantAdmin, currentAgentId);

        Map<Long, Boolean> tokenValidMap = new HashMap<>();
        boolean changed = false;
        long now = System.currentTimeMillis();
        List<DiscordAccount> toSaveList = new ArrayList<>();

        // 1) 先查缓存：命中缓存的账号直接跳过HTTP验证
        List<DiscordAccount> needValidate = new ArrayList<>();
        List<DiscordAccount> needAvatar = new ArrayList<>();
        for (DiscordAccount a : accounts) {
            // Token 验证：查缓存
            if (a.getAccountType() == DiscordAccount.AccountType.USER
                    && a.getToken() != null && !a.getToken().isBlank()) {
                Object[] cached = TOKEN_VALID_CACHE.get(a.getId());
                if (cached != null && (Long) cached[1] > now) {
                    // 缓存命中
                    tokenValidMap.put(a.getId(), (Boolean) cached[0]);
                } else {
                    needValidate.add(a);
                }
            } else {
                tokenValidMap.put(a.getId(), true);
            }

            // 头像：仅在头像为空时需要获取
            if (a.getAvatarUrl() == null
                    && a.getAccountType() == DiscordAccount.AccountType.USER
                    && a.getToken() != null
                    && a.getDiscordId() != null) {
                needAvatar.add(a);
            }
        }

        // 2) Token 验证：并行执行
        if (!needValidate.isEmpty()) {
            try {
                CompletableFuture.allOf(needValidate.stream()
                        .map(a -> CompletableFuture.runAsync(() -> {
                            boolean isValid = checkTokenValid(a);
                            tokenValidMap.put(a.getId(), isValid);
                            // 写入缓存
                            TOKEN_VALID_CACHE.put(a.getId(), new Object[]{
                                    isValid, System.currentTimeMillis() + TOKEN_CACHE_TTL_MS
                            });
                        }))
                        .toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("并行Token验证失败，使用默认值(true): {}", e.getMessage());
                for (DiscordAccount a : needValidate) {
                    tokenValidMap.putIfAbsent(a.getId(), true);
                }
            }
        }

        // 3) 头像获取：仅对头像为空的账号并行获取
        if (!needAvatar.isEmpty()) {
            try {
                CompletableFuture.allOf(needAvatar.stream()
                        .map(a -> CompletableFuture.runAsync(() -> {
                            try {
                                JsonNode me = userClient.getMe(a.getToken());
                                String avatarHash = me.path("avatar").asText(null);
                                if (avatarHash != null && !avatarHash.isBlank()) {
                                    String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                                    String avatarUrl = "https://cdn.discordapp.com/avatars/"
                                            + a.getDiscordId() + "/" + avatarHash + "." + ext;
                                    a.setAvatarUrl(avatarUrl);
                                    synchronized (toSaveList) {
                                        toSaveList.add(a);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }))
                        .toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("并行获取头像失败: {}", e.getMessage());
            }
        }

        if (!toSaveList.isEmpty()) {
            accountRepository.saveAll(toSaveList);
        }

        // 4) 仅查询会话数量（消息数已移到其他数据功能，好友数从缓存字段读取）
        Map<Long, Long> conversationCountMap = batchCountConversations(accounts);

        Map<Long, Boolean> finalTokenValidMap = tokenValidMap;
        return accounts.stream()
                .map(a -> buildAccountDto(a,
                        finalTokenValidMap.getOrDefault(a.getId(), true),
                        a.getFriendCount() != null ? a.getFriendCount() : 0L,
                        conversationCountMap.getOrDefault(a.getId(), 0L),
                        0L)) // messageCount 不再显示
                .toList();
    }

    /** 检测 USER 账号的 token 有效性（仅对401错误标记为过期，其他错误保留为有效） */
    private boolean checkTokenValid(DiscordAccount a) {
        try {
            userClient.getMe(a.getToken());
            return true;
        } catch (DiscordUserClient.DiscordUserApiException e) {
            if (e.statusCode == 401) {
                log.warn("账号 [{}] token 已失效(401)", a.getName());
                return false;
            } else {
                // 其他HTTP错误(429限流、5xx等)不视为过期，保持有效
                log.warn("账号 [{}] token 验证失败(状态码={})，保留有效状态", a.getName(), e.statusCode);
                return true;
            }
        } catch (Exception e) {
            // 网络异常等非HTTP错误，不视为token过期
            log.warn("账号 [{}] token 验证异常({})，保留有效状态: {}", a.getName(), e.getClass().getSimpleName(), e.getMessage());
            return true;
        }
    }

    private Map<Long, Long> batchCountFriends(List<DiscordAccount> accounts) {
        if (accounts.isEmpty()) return Map.of();
        List<Long> ids = accounts.stream().map(DiscordAccount::getId).toList();
        List<Object[]> results = accountRepository.countFriendsByAccountIds(ids);
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : results) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private Map<Long, Long> batchCountConversations(List<DiscordAccount> accounts) {
        if (accounts.isEmpty()) return Map.of();
        List<Long> ids = accounts.stream().map(DiscordAccount::getId).toList();
        List<Object[]> results = accountRepository.countConversationsByAccountIds(ids);
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : results) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private Map<Long, Long> batchCountMessages(List<DiscordAccount> accounts) {
        if (accounts.isEmpty()) return Map.of();
        List<Long> ids = accounts.stream().map(DiscordAccount::getId).toList();
        List<Object[]> results = accountRepository.countMessagesByAccountIds(ids);
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : results) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private List<DiscordAccount> queryAccounts(String keyword, String status,
                                                Long merchantId, boolean isPlatformAdmin,
                                                boolean isMerchantAdmin, Long currentAgentId) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasStatus = status != null && !status.isBlank();

        // 平台管理员：查看全部
        if (isPlatformAdmin) {
            if (hasKeyword && hasStatus) {
                return accountRepository.searchWithAgentsAllByKeywordAndStatus(
                        keyword.trim(), DiscordAccount.AccountStatus.valueOf(status.toUpperCase()));
            }
            if (hasKeyword) {
                return accountRepository.searchWithAgentsAllByKeyword(keyword.trim());
            }
            if (hasStatus) {
                return accountRepository.findWithAgentsByStatus(
                        DiscordAccount.AccountStatus.valueOf(status.toUpperCase()));
            }
            return accountRepository.findAllWithAgents();
        }

        // 商户管理员：查看商户下所有账号
        if (isMerchantAdmin) {
            if (hasKeyword && hasStatus) {
                return accountRepository.searchWithAgentsByMerchantIdAndKeywordAndStatus(
                        merchantId, keyword.trim(), DiscordAccount.AccountStatus.valueOf(status.toUpperCase()));
            }
            if (hasKeyword) {
                return accountRepository.searchWithAgentsByMerchantId(merchantId, keyword.trim());
            }
            if (hasStatus) {
                return accountRepository.findWithAgentsByMerchantIdAndStatus(
                        merchantId, DiscordAccount.AccountStatus.valueOf(status.toUpperCase()));
            }
            return accountRepository.findWithAgentsByMerchantId(merchantId);
        }

        // 普通用户：只能看到分配给自己的账号
        if (currentAgentId != null) {
            Optional<Agent> agentOpt = agentRepository.findById(currentAgentId);
            if (agentOpt.isPresent()) {
                // 1. 直接关联的账号（agent_discord_accounts）
                Set<Long> assignedAccountIds = new HashSet<>(
                        agentOpt.get().getDiscordAccounts().stream()
                                .map(DiscordAccount::getId)
                                .collect(Collectors.toSet()));

                // 2. 通过编号链路关联的账号（AgentAccountNumberRel → DiscordAccountNumber → DiscordAccount）
                List<Long> assignedNumberIds = relRepository.findAccountNumberIdsByAgentId(currentAgentId);
                if (!assignedNumberIds.isEmpty()) {
                    List<DiscordAccountNumber> numbers = accountNumberRepository.findByIdIn(assignedNumberIds);
                    for (DiscordAccountNumber num : numbers) {
                        if (num.getDiscordAccountId() != null) {
                            assignedAccountIds.add(num.getDiscordAccountId());
                        }
                    }
                }

                if (assignedAccountIds.isEmpty()) {
                    return new ArrayList<>();
                }
                List<DiscordAccount> result = accountRepository.findAllWithAgentsByIdIn(assignedAccountIds);
                if (hasKeyword) {
                    String kw = keyword.trim().toLowerCase();
                    result = result.stream()
                            .filter(a -> a.getName() != null && a.getName().toLowerCase().contains(kw))
                            .collect(Collectors.toList());
                }
                if (hasStatus) {
                    DiscordAccount.AccountStatus statusEnum = DiscordAccount.AccountStatus.valueOf(status.toUpperCase());
                    result = result.stream()
                            .filter(a -> statusEnum.equals(a.getStatus()))
                            .collect(Collectors.toList());
                }
                return result;
            }
            return new ArrayList<>();
        }

        // Fallback: 如果无法获取当前用户ID，返回空列表
        return new ArrayList<>();
    }

    private AccountDto buildAccountDto(DiscordAccount a, boolean tokenValid,
                                       Long friendCount, Long conversationCount, Long messageCount) {
        String agentName = null;
        String agentUsername = null;
        Long agentId = null;

        // 1. 优先通过直接关联（agent_discord_accounts）查找
        if (a.getAgents() != null && !a.getAgents().isEmpty()) {
            Agent agent = null;
            Long curAgentId = SecurityUtils.currentAgentId();
            for (Agent ag : a.getAgents()) {
                if (ag.getId().equals(curAgentId)) { agent = ag; break; }
            }
            if (agent == null) {
                agent = a.getAgents().iterator().next();
            }
            agentName = agent.getDisplayName() != null ? agent.getDisplayName() : agent.getUsername();
            agentUsername = agent.getUsername();
            agentId = agent.getId();
        }

        // 2. 若直接关联为空，通过编号链路（AgentAccountNumberRel → DiscordAccountNumber）查找
        if (agentName == null) {
            List<com.discordadmin.entity.DiscordAccountNumber> numbers = accountNumberRepository.findByDiscordAccountId(a.getId());
            if (!numbers.isEmpty()) {
                List<Long> numberIds = numbers.stream()
                        .map(com.discordadmin.entity.DiscordAccountNumber::getId)
                        .toList();
                List<AgentAccountNumberRel> rels = relRepository.findByAccountNumberIdIn(numberIds);
                if (!rels.isEmpty()) {
                    List<Long> agentIds = rels.stream()
                            .map(AgentAccountNumberRel::getAgentId)
                            .distinct()
                            .toList();
                    Map<Long, Agent> agentMap = agentRepository.findAllById(agentIds).stream()
                            .collect(Collectors.toMap(Agent::getId, ag -> ag));
                    // 优先选择当前用户
                    Agent matchedAgent = null;
                    Long curAgentId = SecurityUtils.currentAgentId();
                    if (curAgentId != null && agentMap.containsKey(curAgentId)) {
                        matchedAgent = agentMap.get(curAgentId);
                    }
                    if (matchedAgent == null && !agentMap.isEmpty()) {
                        matchedAgent = agentMap.values().iterator().next();
                    }
                    if (matchedAgent != null) {
                        agentName = matchedAgent.getDisplayName() != null ? matchedAgent.getDisplayName() : matchedAgent.getUsername();
                        agentUsername = matchedAgent.getUsername();
                        agentId = matchedAgent.getId();
                    }
                }
            }
        }
        
        // 查询账号关联的编号
        Long accountNumberId = null;
        List<com.discordadmin.entity.DiscordAccountNumber> numbers = accountNumberRepository.findByDiscordAccountId(a.getId());
        if (!numbers.isEmpty()) {
            accountNumberId = numbers.get(0).getId();
        }
        
        return AccountDto.from(a, botManager.isConnected(a.getId()), botManager.isConnecting(a.getId()),
                tokenValid, friendCount, conversationCount, messageCount, agentName, agentUsername, agentId, accountNumberId);
    }

    public AccountDto createAccount(CreateAccountRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("账号名称不能为空");
        }
        if (request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("Bot Token 不能为空");
        }
        DiscordAccount account = new DiscordAccount();
        account.setName(request.name().trim());
        account.setToken(request.token().trim());
        // 统一为 USER 账号（不再区分 USER/BOT）
        account.setAccountType(DiscordAccount.AccountType.USER);
        account.setStatus(DiscordAccount.AccountStatus.ACTIVE);
        // 商户：入参优先；否则用当前登录用户所属商户兜底
        if (request.merchantId() != null) {
            account.setMerchantId(request.merchantId());
        } else {
            Long current = SecurityUtils.currentMerchantId();
            if (current != null) account.setMerchantId(current);
        }
        if (request.email() != null) account.setEmail(request.email().trim());
        if (request.remark() != null) account.setRemark(request.remark().trim());
        if (request.discordId() != null && !request.discordId().isBlank()) account.setDiscordId(request.discordId().trim());
        account = accountRepository.save(account);
        botManager.startAccount(account.getId());
        return AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId()));
    }

    /**
     * 手工添加-粘贴解析：通过 discordId 做 upsert。
     * - 如果 discordId 对应的账号已存在且当前用户可访问 → 更新 username/email/token/remark/merchantId
     * - 否则 → 新建 USER 账号
     */
    @Transactional
    public UpsertResponse upsertByDiscordId(UpsertAccountByDiscordIdRequest req) {
        if (req.discordId() == null || req.discordId().isBlank()) {
            throw new IllegalArgumentException("Discord ID (账号ID) 不能为空");
        }
        if (req.token() == null || req.token().isBlank()) {
            throw new IllegalArgumentException("Token 不能为空");
        }
        String discordId = req.discordId().trim();
        String username = req.username() == null ? "" : req.username().trim();
        String email = req.email() == null ? "" : req.email().trim();
        String token = req.token().trim();
        
        // Token 格式校验：不应包含竖线分隔符（说明用户粘贴了完整文本而非纯 token）
        if (token.contains("|")) {
            throw new IllegalArgumentException("Token 格式错误：检测到完整文本格式，请使用粘贴框解析后保存，或只输入纯 Token 值");
        }

        Optional<DiscordAccount> existOpt = accountRepository.findByDiscordId(discordId);
        Long currentMerchant = SecurityUtils.currentMerchantId();
        Long merchantId = req.merchantId() != null ? req.merchantId() : currentMerchant;

        DiscordAccount account;
        boolean created;
        if (existOpt.isPresent()) {
            account = existOpt.get();
            SecurityUtils.checkMerchantAccess(account.getMerchantId());
            created = false;
        } else {
            account = new DiscordAccount();
            account.setDiscordId(discordId);
            account.setStatus(DiscordAccount.AccountStatus.ACTIVE);
            account.setMerchantId(merchantId);
            created = true;
        }
        if (!username.isBlank()) {
            account.setName(username);
            account.setDiscordName(username);
        } else if (account.getName() == null || account.getName().isBlank()) {
            account.setName("未命名账号");
        }
        if (!email.isBlank()) account.setEmail(email);
        account.setToken(token);
        account.setAccountType(DiscordAccount.AccountType.USER);
        if (req.remark() != null) account.setRemark(req.remark().trim());
        if (req.merchantId() != null) {
            account.setMerchantId(req.merchantId());
        } else if (merchantId != null && !created) {
            account.setMerchantId(merchantId);
        }

        // 导入时立即验证 token 有效性
        boolean tokenValid = false;
        String validationMsg = null;
        try {
            JsonNode me = userClient.getMe(token);
            tokenValid = true;
            // 用 API 返回的信息补全账号
            if (me.path("id").asText(null) != null) account.setDiscordId(me.path("id").asText());
            String returnedUsername = me.path("username").asText(null);
            if (returnedUsername != null && !returnedUsername.isBlank()) {
                account.setDiscordName(returnedUsername);
                if (username.isBlank()) {
                    account.setName(returnedUsername);
                }
            }
            String avatarHash = me.path("avatar").asText(null);
            if (avatarHash != null && !avatarHash.isBlank() && account.getDiscordId() != null) {
                String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                account.setAvatarUrl("https://cdn.discordapp.com/avatars/"
                        + account.getDiscordId() + "/" + avatarHash + "." + ext);
            }
            log.info("账号 [{}] token 验证成功", username.isBlank() ? account.getName() : username);
        } catch (DiscordUserClient.DiscordUserApiException e) {
            if (e.statusCode == 401) {
                // token 真的失效了
                tokenValid = false;
                validationMsg = "Token 已失效 (401)";
                log.warn("账号 [{}] token 验证失败: {}", username.isBlank() ? account.getName() : username, validationMsg);
            } else {
                // 其他错误（网络问题等），保存账号但记录错误
                tokenValid = false;
                validationMsg = "Token 验证失败 (状态码=" + e.statusCode + ")，请稍后验证";
                log.warn("账号 [{}] token 验证失败: 状态码={}", username.isBlank() ? account.getName() : username, e.statusCode);
            }
            account.setLastError(validationMsg);
        } catch (Exception e) {
            tokenValid = false;
            validationMsg = "Token 验证异常: " + e.getMessage();
            log.warn("账号 [{}] token 验证异常: {}", username.isBlank() ? account.getName() : username, e.getMessage());
            account.setLastError(validationMsg);
        }

        account = accountRepository.save(account);
        botManager.startAccount(account.getId());
        // USER 账号：首次同步好友关系和 DM 频道，确保消息轮询能立即工作
        if (account.getAccountType() == DiscordAccount.AccountType.USER && account.getToken() != null) {
            try {
                syncService.syncOne(account.getId());
                log.info("账号 [{}](id={}) 首次同步好友关系完成", account.getName(), account.getId());
            } catch (Exception syncErr) {
                log.warn("账号 [{}](id={}) 首次同步好友失败: {}", account.getName(), account.getId(), syncErr.getMessage());
                account.setLastError("首次同步好友失败: " + syncErr.getMessage());
                accountRepository.save(account);
            }
        }
        AccountDto dto = AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId()));
        
        String message;
        if (created) {
            message = tokenValid ? "新增成功" : "新增成功（" + validationMsg + "）";
        } else {
            message = tokenValid ? "更新成功（ID已存在）" : "更新成功（ID已存在，" + validationMsg + "）";
        }
        log.info("账号{}成功: discordId={} id={} tokenValid={}", created ? "新增" : "更新", discordId, account.getId(), tokenValid);
        return new UpsertResponse(dto, created, message);
    }

    public ImportTokenResponse importToken(ImportTokenRequest request) {
        if (request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("Token 不能为空");
        }
        String token = request.token().trim();
        String name = request.name() == null || request.name().isBlank() ? "未命名账号" : request.name().trim();
        boolean hasAuth = SecurityUtils.currentAgent() != null;

        if (request.discordUserId() != null && !request.discordUserId().isBlank()) {
            Optional<DiscordAccount> existByUserId = accountRepository.findByDiscordId(request.discordUserId().trim());
            if (existByUserId.isPresent()) {
                DiscordAccount exist = existByUserId.get();
                if (hasAuth) SecurityUtils.checkMerchantAccess(exist.getMerchantId());
                return handleExistingByUserId(exist, request, token, name);
            }
        }

        Optional<DiscordAccount> existByToken = accountRepository.findByToken(token);
        if (existByToken.isPresent()) {
            DiscordAccount exist = existByToken.get();
            if (hasAuth) SecurityUtils.checkMerchantAccess(exist.getMerchantId());
            return handleExistingByToken(exist, request, token);
        }

        return createNewAccount(request, token, name);
    }

    private ImportTokenResponse handleExistingByUserId(DiscordAccount exist, ImportTokenRequest request,
                                                       String token, String name) {
        if (exist.getStatus() == DiscordAccount.AccountStatus.INACTIVE) {
            exist.setStatus(DiscordAccount.AccountStatus.ACTIVE);
            exist.setToken(token);
            exist.setLastError(null);
            if (name != null && !name.isBlank()) exist.setName(name);
            if (request.avatar() != null && !request.avatar().isBlank()) {
                exist.setAvatarUrl(buildAvatarUrl(request.discordUserId(), request.avatar()));
            }
            exist = accountRepository.save(exist);
            try { syncService.syncOne(exist.getId()); } catch (Exception ignored) {}
            return new ImportTokenResponse(
                    AccountDto.from(exist, botManager.isConnected(exist.getId()), botManager.isConnecting(exist.getId())),
                    false, "账号已重新激活并更新 Token");
        }
        if (request.avatar() != null && !request.avatar().isBlank()
                && request.discordUserId() != null && !request.discordUserId().isBlank()) {
            String newAvatarUrl = buildAvatarUrl(request.discordUserId(), request.avatar());
            if (!newAvatarUrl.equals(exist.getAvatarUrl())) {
                exist.setAvatarUrl(newAvatarUrl);
            }
        }
        if (!token.equals(exist.getToken())) {
            exist.setToken(token);
            exist.setLastError(null);
        }
        exist = accountRepository.save(exist);
        try { syncService.syncOne(exist.getId()); } catch (Exception ignored) {}
        boolean infoUpdated = request.avatar() != null && !request.avatar().isBlank()
                && exist.getAvatarUrl() != null;
        return new ImportTokenResponse(
                AccountDto.from(exist, botManager.isConnected(exist.getId()), botManager.isConnecting(exist.getId())),
                true,
                infoUpdated
                        ? "该 Discord 账号已存在，后台名称：「" + exist.getName() + "」\n（头像信息已更新）"
                        : "该 Discord 账号已存在，后台名称：「" + exist.getName() + "」");
    }

    private ImportTokenResponse handleExistingByToken(DiscordAccount exist, ImportTokenRequest request,
                                                      String token) {
        if (exist.getStatus() == DiscordAccount.AccountStatus.INACTIVE) {
            exist.setStatus(DiscordAccount.AccountStatus.ACTIVE);
            exist.setLastError(null);
            if (request.discordUserId() != null && !request.discordUserId().isBlank()
                    && request.avatar() != null && !request.avatar().isBlank()) {
                exist.setAvatarUrl(buildAvatarUrl(request.discordUserId(), request.avatar()));
            }
            exist = accountRepository.save(exist);
            try { syncService.syncOne(exist.getId()); } catch (Exception ignored) {}
            return new ImportTokenResponse(
                    AccountDto.from(exist, botManager.isConnected(exist.getId()), botManager.isConnecting(exist.getId())),
                    false, "账号已重新激活");
        }
        boolean avatarUpdated = false;
        if (request.avatar() != null && !request.avatar().isBlank()
                && request.discordUserId() != null && !request.discordUserId().isBlank()) {
            String newAvatarUrl = buildAvatarUrl(request.discordUserId(), request.avatar());
            if (!newAvatarUrl.equals(exist.getAvatarUrl())) {
                exist.setAvatarUrl(newAvatarUrl);
                exist = accountRepository.save(exist);
                avatarUpdated = true;
            }
        }
        return new ImportTokenResponse(
                AccountDto.from(exist, botManager.isConnected(exist.getId()), botManager.isConnecting(exist.getId())),
                true,
                avatarUpdated
                        ? "该 Token 已导入过，后台名称：「" + exist.getName() + "」\n（头像信息已更新）"
                        : "该 Token 已导入过，后台名称：「" + exist.getName() + "」");
    }

    private ImportTokenResponse createNewAccount(ImportTokenRequest request, String token, String name) {
        DiscordAccount.AccountType type;
        if (request.accountType() != null && !request.accountType().isBlank()) {
            type = DiscordAccount.AccountType.valueOf(request.accountType().toUpperCase());
        } else {
            type = DiscordAccount.AccountType.BOT;
        }

        DiscordAccount account = new DiscordAccount();
        account.setName(name);
        account.setToken(token);
        account.setAccountType(type);
        account.setStatus(DiscordAccount.AccountStatus.ACTIVE);
        account.setMerchantId(SecurityUtils.currentMerchantId());
        if (type == DiscordAccount.AccountType.USER) {
            if (request.discordUserId() != null && !request.discordUserId().isBlank()) {
                account.setDiscordId(request.discordUserId().trim());
            }
            String displayName = request.globalName();
            if (displayName == null || displayName.isBlank()) displayName = request.username();
            if (displayName != null && !displayName.isBlank()) {
                account.setDiscordName(displayName);
            }
            if (request.avatar() != null && !request.avatar().isBlank()
                    && request.discordUserId() != null && !request.discordUserId().isBlank()) {
                account.setAvatarUrl(buildAvatarUrl(request.discordUserId(), request.avatar()));
            }
        }
        account = accountRepository.save(account);

        if (type == DiscordAccount.AccountType.USER) {
            return handleUserAccountPostCreate(account, token, request);
        } else {
            botManager.startAccount(account.getId());
            return new ImportTokenResponse(
                    AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId())),
                    false, "导入成功");
        }
    }

    private ImportTokenResponse handleUserAccountPostCreate(DiscordAccount account, String token,
                                                            ImportTokenRequest request) {
        boolean validationOk = false;
        try {
            JsonNode me = userClient.getMe(token);
            if (me.path("id").asText(null) != null) account.setDiscordId(me.path("id").asText());
            if (me.path("username").asText(null) != null) account.setDiscordName(me.path("username").asText());
            String avatarHash = me.path("avatar").asText(null);
            if (avatarHash != null && !avatarHash.isBlank() && account.getDiscordId() != null) {
                String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                account.setAvatarUrl("https://cdn.discordapp.com/avatars/"
                        + account.getDiscordId() + "/" + avatarHash + "." + ext);
            }
            account = accountRepository.save(account);
            validationOk = true;
            try {
                syncService.syncOne(account.getId());
            } catch (Exception syncErr) {
                account.setLastError("首次同步好友失败: " + syncErr.getMessage());
                account = accountRepository.save(account);
            }
        } catch (Exception e) {
            String errMsg = "Token 验证失败（但已保存）: " + e.getMessage();
            account.setLastError(errMsg);
            account = accountRepository.save(account);
        }

        String msg = validationOk ? "导入成功" : "导入成功（Token 验证失败，账号已保存，请确认 Token 有效性后重试同步）";
        return new ImportTokenResponse(
                AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId())),
                false, msg);
    }

    public AccountDto updateAccount(Long id, UpdateAccountRequest request) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());

        boolean tokenChanged = false;
        if (request.name() != null && !request.name().isBlank()) {
            account.setName(request.name().trim());
        }
        if (request.token() != null && !request.token().isBlank()
                && !request.token().trim().equals(account.getToken())) {
            account.setToken(request.token().trim());
            tokenChanged = true;
        }
        if (request.status() != null) {
            account.setStatus(DiscordAccount.AccountStatus.valueOf(request.status()));
        }
        if (request.remark() != null) {
            account.setRemark(request.remark().trim());
        }
        if (request.merchantId() != null) {
            account.setMerchantId(request.merchantId());
        }
        account = accountRepository.save(account);

        if (account.getAccountType() == DiscordAccount.AccountType.BOT) {
            manageBotConnection(account, id, tokenChanged);
        }
        return AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId()));
    }

    private void manageBotConnection(DiscordAccount account, Long id, boolean tokenChanged) {
        if (tokenChanged) {
            botManager.stopAccount(id);
            if (account.getStatus() == DiscordAccount.AccountStatus.ACTIVE) {
                botManager.startAccount(id);
            }
        } else if (account.getStatus() == DiscordAccount.AccountStatus.INACTIVE && botManager.isConnected(id)) {
            botManager.stopAccount(id);
        } else if (account.getStatus() == DiscordAccount.AccountStatus.ACTIVE
                && !botManager.isConnected(id) && !botManager.isConnecting(id)) {
            botManager.startAccount(id);
        }
    }

    public void deleteAccount(Long id) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());
        String accountName = account.getName();
        
        // 1. 先停掉机器人/同步
        botManager.stopAccount(id);
        
        // 2. 将账号标记为 INACTIVE，让轮询器停止处理该账号，防止死锁
        account.setStatus(DiscordAccount.AccountStatus.INACTIVE);
        accountRepository.save(account);
        accountRepository.flush(); // 强制刷新持久化，确保状态变更立即生效
        log.info("账号[id={}]已标记为INACTIVE，停止轮询和同步", id);
        
        // 等待轮询器完成当前周期（最多2秒）
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. 使用编程式事务 + 死锁重试执行删除
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        
        for (int attempt = 1; attempt <= MAX_DELETE_RETRIES; attempt++) {
            try {
                txTemplate.execute(status -> {
                    // 3a. 删除关联的服务器及其成员、抓取进度数据
                    List<GuildServer> guildServers = guildServerRepository.findByDiscordAccountId(id);
                    for (GuildServer guild : guildServers) {
                        Long guildServerId = guild.getId();
                        fetchProgressRepository.deleteByGuildServerId(guildServerId);
                        guildMemberRepository.deleteByGuildServerId(guildServerId);
                    }
                    guildServerRepository.deleteByDiscordAccountId(id);
                    log.info("已删除账号[id={}]关联的 {} 个服务器及其成员数据", id, guildServers.size());

                    // 3b. 移除 Agent 关联
                    DiscordAccount acc = accountRepository.findById(id).orElse(null);
                    if (acc != null) {
                        List<Agent> relatedAgents = agentRepository.findByDiscordAccountsContaining(acc);
                        for (Agent agent : relatedAgents) {
                            agent.getDiscordAccounts().remove(acc);
                            agentRepository.save(agent);
                        }

                        // 3c. 删除会话和消息
                        List<com.discordadmin.entity.Conversation> convs = conversationRepository.findByDiscordAccount(acc);
                        for (com.discordadmin.entity.Conversation conv : convs) {
                            messageRepository.deleteByConversation(conv);
                        }
                        conversationRepository.deleteByDiscordAccount(acc);

                        // 3d. 删除好友记录
                        friendRepository.deleteByDiscordAccount(acc);

                        // 3e. 删除账号
                        accountRepository.delete(acc);
                    }
                    return null;
                });
                
                log.info("账号[id={}, name={}]及其关联数据已硬删除", id, accountName);
                return; // 成功，退出重试循环
                
            } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                if (attempt >= MAX_DELETE_RETRIES) {
                    log.error("删除账号[id={}]死锁重试{}次后仍失败", id, MAX_DELETE_RETRIES, e);
                    throw e;
                }
                long waitMs = 500L * attempt;
                log.warn("删除账号[id={}]遇到死锁，第{}次重试（等待{}ms）", id, attempt, waitMs);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("删除账号被中断", ie);
                }
            }
        }
    }

    public AccountDto connect(Long id) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());
        if (account.getAccountType() == DiscordAccount.AccountType.BOT) {
            botManager.startAccount(id);
        } else {
            syncService.syncOne(id);
        }
        return AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId()));
    }

    public AccountDto disconnect(Long id) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());
        if (account.getAccountType() == DiscordAccount.AccountType.BOT) {
            botManager.stopAccount(id);
        }
        return AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId()));
    }

    /**
     * 手动刷新账号头像（调用 Discord API 获取最新头像）
     */
    public AccountDto refreshAvatar(Long id) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());

        if (account.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalStateException("仅 USER 类型账号支持刷新头像");
        }
        if (account.getToken() == null || account.getToken().isBlank()) {
            throw new IllegalStateException("账号 Token 为空，无法刷新头像");
        }

        try {
            JsonNode me = userClient.getMe(account.getToken());
            String avatarHash = me.path("avatar").asText(null);
            if (avatarHash != null && !avatarHash.isBlank() && account.getDiscordId() != null) {
                String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                String avatarUrl = "https://cdn.discordapp.com/avatars/"
                        + account.getDiscordId() + "/" + avatarHash + "." + ext;
                account.setAvatarUrl(avatarUrl);
                account = accountRepository.save(account);
                log.info("账号 [{}] 头像刷新成功", account.getName());
            }
            // 同步更新 discordName
            String username = me.path("username").asText(null);
            String globalName = me.path("global_name").asText(null);
            if (globalName != null && !globalName.isBlank()) {
                account.setDiscordName(globalName);
                if (account.getName() == null || account.getName().isBlank()) {
                    account.setName(globalName);
                }
            } else if (username != null && !username.isBlank()) {
                account.setDiscordName(username);
            }
            account = accountRepository.save(account);
        } catch (DiscordUserClient.DiscordUserApiException e) {
            throw new IllegalStateException("刷新头像失败：Discord API " + e.statusCode, e);
        } catch (Exception e) {
            throw new IllegalStateException("刷新头像失败：" + e.getMessage(), e);
        }

        return AccountDto.from(account, botManager.isConnected(id), botManager.isConnecting(id),
                account.getFriendCount() != null ? account.getFriendCount() : 0L,
                conversationRepository.countByDiscordAccount(account), 0L);
    }

    /**
     * 刷新 USER 账号的 Token（通过邮箱密码重新登录）
     */
    public RefreshTokenResponse refreshToken(Long id, RefreshTokenRequest request) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());

        if (account.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalArgumentException("仅 USER 类型账号支持刷新 Token");
        }

        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        try {
            JsonNode loginResp = userClient.login(request.email().trim(), request.password());
            String token = loginResp.path("token").asText(null);

            if (token == null || token.isBlank()) {
                boolean mfa = loginResp.path("mfa").asBoolean(false);
                if (mfa) {
                    throw new IllegalStateException("该账号开启了2FA验证，无法自动刷新Token，请手动重新登录");
                }
                throw new IllegalStateException("登录失败：未获取到Token");
            }

            // 验证新 Token 有效性
            JsonNode me = userClient.getMe(token);
            String userId = me.path("id").asText(null);

            // 更新账号
            account.setToken(token);
            account.setLastError(null);
            // 设置 Token 过期时间（Discord USER token 默认 24 小时有效期）
            account.setTokenExpiresAt(Instant.now().plusSeconds(24 * 60 * 60));
            account.setTokenCheckedAt(Instant.now());
            if (userId != null) {
                account.setDiscordId(userId);
            }
            String username = me.path("username").asText(null);
            String globalName = me.path("global_name").asText(null);
            if (globalName != null && !globalName.isBlank()) {
                account.setDiscordName(globalName);
                account.setName(globalName);
            } else if (username != null) {
                account.setDiscordName(username);
                account.setName(username);
            }

            // 更新头像
            String avatarHash = me.path("avatar").asText(null);
            if (avatarHash != null && !avatarHash.isBlank() && account.getDiscordId() != null) {
                String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                String avatarUrl = "https://cdn.discordapp.com/avatars/"
                        + account.getDiscordId() + "/" + avatarHash + "." + ext;
                account.setAvatarUrl(avatarUrl);
            }

            account = accountRepository.save(account);

            // 同步好友关系
            try {
                syncService.syncOne(id);
            } catch (Exception syncErr) {
                log.warn("刷新Token后同步好友失败: {}", syncErr.getMessage());
            }

            log.info("账号 [{}] Token 刷新成功", account.getName());
            return new RefreshTokenResponse(
                    AccountDto.from(account, botManager.isConnected(id), botManager.isConnecting(id),
                            true, friendRepository.countByDiscordAccount(account),
                            conversationRepository.countByDiscordAccount(account),
                            messageRepository.countByDiscordAccount(account),
                            null, null, null),
                    "Token 刷新成功");

        } catch (DiscordUserClient.DiscordUserApiException e) {
            log.error("账号 [{}] Token 刷新失败(Discord API错误): {}", account.getName(), e.getMessage());
            // 根据错误码和内容给出中文提示
            String errorMsg = resolveRefreshTokenError(e);
            throw new IllegalStateException(errorMsg, e);
        } catch (Exception e) {
            log.error("账号 [{}] Token 刷新失败: {}", account.getName(), e.getMessage());
            throw new IllegalStateException("Token 刷新失败: " + e.getMessage(), e);
        }
    }

    private String resolveRefreshTokenError(DiscordUserClient.DiscordUserApiException e) {
        int code = e.statusCode;
        String body = e.rawBody != null ? e.rawBody : "";

        if (code == 400) {
            if (body.contains("captcha")) {
                return "Token刷新失败：账号被Discord安全系统限制，需要人工完成验证码验证。请在Discord客户端手动登录该账号后重试。";
            }
            if (body.contains("email") || body.contains("password")) {
                return "Token刷新失败：邮箱或密码错误，请检查后重试。";
            }
            return "Token刷新失败：请求参数错误(400) - " + truncate(body, 100);
        } else if (code == 401) {
            if (body.contains("invalid")) {
                return "Token刷新失败：邮箱或密码错误。";
            }
            return "Token刷新失败：认证失败(401)，请检查账号信息。";
        } else if (code == 403) {
            return "Token刷新失败：访问被拒绝(403)，账号可能被限制或封禁。";
        } else if (code == 429) {
            return "Token刷新失败：请求过于频繁(429)，请稍后再试。";
        } else if (code >= 500) {
            return "Token刷新失败：Discord服务器错误(" + code + ")，请稍后重试。";
        }
        return "Token刷新失败：Discord API " + code + " - " + truncate(body, 100);
    }

    public BatchImportResponse batchImport(BatchImportRequest request) {
        if (request.accounts() == null || request.accounts().isEmpty()) {
            throw new IllegalArgumentException("账号列表不能为空");
        }

        List<BatchImportResultItem> results = new ArrayList<>();
        int successCount = 0;

        for (var item : request.accounts()) {
            String email = item.email();
            String password = item.password();
            if (email == null || email.isBlank() || password == null || password.isBlank()) {
                results.add(new BatchImportResultItem(email, password, false, "邮箱或密码为空", null));
                continue;
            }

            try {
                JsonNode loginResp = userClient.login(email.trim(), password);
                String token = loginResp.path("token").asText(null);

                if (token == null || token.isBlank()) {
                    boolean mfa = loginResp.path("mfa").asBoolean(false);
                    if (mfa) {
                        results.add(new BatchImportResultItem(email, password, false, "该账号开启了2FA，需用Chrome插件手动导入", null));
                    } else {
                        results.add(new BatchImportResultItem(email, password, false, "登录失败：未返回Token", null));
                    }
                    continue;
                }

                JsonNode me = userClient.getMe(token);
                String userId = me.path("id").asText(null);
                String username = me.path("username").asText("Unknown");
                String globalName = me.path("global_name").asText(username);
                String avatarHash = me.path("avatar").asText(null);
                String displayName = (globalName != null && !globalName.isBlank()) ? globalName : username;

                DiscordAccount account = findOrCreateAccount(userId, email, token, displayName);

                if (userId != null && avatarHash != null && !avatarHash.isBlank()) {
                    account.setAvatarUrl(buildAvatarUrl(userId, avatarHash));
                }

                account = accountRepository.save(account);

                try {
                    syncService.syncOne(account.getId());
                } catch (Exception syncErr) {
                    log.warn("批量导入：账号[{}]首次同步失败: {}", displayName, syncErr.getMessage());
                }

                results.add(new BatchImportResultItem(email, null, true, "导入成功", displayName));
                successCount++;
                log.info("批量导入成功: email={}, name={}", email, displayName);

                Thread.sleep(2000);

            } catch (DiscordUserClient.DiscordUserApiException e) {
                String errMsg = resolveBatchImportError(e);
                results.add(new BatchImportResultItem(email, password, false, errMsg, null));
                log.warn("批量导入失败: email={}, err={}", email, errMsg);
            } catch (Exception e) {
                results.add(new BatchImportResultItem(email, password, false, "异常: " + e.getMessage(), null));
                log.warn("批量导入异常: email={}, err={}", email, e.getMessage());
            }
        }

        return new BatchImportResponse(request.accounts().size(), successCount,
                request.accounts().size() - successCount, results);
    }

    private DiscordAccount findOrCreateAccount(String userId, String email, String token, String displayName) {
        DiscordAccount account = null;
        Long currentMerchantId = SecurityUtils.currentMerchantId();
        if (userId != null) {
            Optional<DiscordAccount> exist = accountRepository.findByDiscordId(userId);
            if (exist.isPresent()) {
                account = exist.get();
                account.setToken(token);
                account.setStatus(DiscordAccount.AccountStatus.ACTIVE);
                account.setLastError(null);
                account.setName(displayName);
                account.setEmail(email.trim());
                // 更新账号的商户归属为当前用户的商户
                account.setMerchantId(currentMerchantId);
            }
        }
        if (account == null) {
            account = new DiscordAccount();
            account.setName(displayName);
            account.setToken(token);
            account.setAccountType(DiscordAccount.AccountType.USER);
            account.setStatus(DiscordAccount.AccountStatus.ACTIVE);
            account.setDiscordId(userId);
            account.setDiscordName(displayName);
            account.setEmail(email.trim());
            account.setMerchantId(currentMerchantId);
        }
        return account;
    }

    private String resolveBatchImportError(DiscordUserClient.DiscordUserApiException e) {
        int code = e.statusCode;
        String body = e.rawBody != null ? e.rawBody : "";

        if (code == 400) {
            if (body.contains("captcha")) {
                return "需要验证码，需用Chrome插件手动导入";
            }
            if (body.contains("email") || body.contains("password")) {
                return "邮箱或密码格式错误";
            }
            return "请求参数错误(400): " + truncate(body, 100);
        } else if (code == 401) {
            if (body.contains("invalid")) {
                return "邮箱或密码错误";
            }
            return "认证失败(401): 邮箱或密码错误";
        } else if (code == 403) {
            if (body.contains("access") || body.contains("forbidden")) {
                return "访问被拒绝(403): 账号可能被限制";
            }
            return "无权限访问(403): " + truncate(body, 100);
        } else if (code == 429) {
            return "请求过于频繁(429): 触发速率限制，请稍后重试";
        } else if (code == 404) {
            return "接口不存在(404)";
        } else if (code >= 500) {
            return "服务器错误(" + code + "): " + truncate(body, 100);
        }
        return "Discord API " + code + ": " + truncate(body, 100);
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    public String buildAvatarUrl(String userId, String avatarHash) {
        if (userId == null || avatarHash == null) return null;
        String ext = avatarHash.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + "." + ext;
    }
}
