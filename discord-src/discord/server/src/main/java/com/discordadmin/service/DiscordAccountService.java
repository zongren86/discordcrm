package com.discordadmin.service;

import com.discordadmin.discord.DiscordBotManager;
import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.dto.DiscordAccountDtos.*;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.AgentAccountNumberRel;
import com.discordadmin.entity.DiscordAccount;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DiscordAccountService {

    private static final Logger log = LoggerFactory.getLogger(DiscordAccountService.class);

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
                                 AgentAccountNumberRelRepository relRepository) {
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
    }

    public List<AccountDto> listAccounts(String keyword, String status) {
        Long merchantId = SecurityUtils.currentMerchantId();
        boolean isPlatformAdmin = SecurityUtils.isPlatformAdmin();

        List<DiscordAccount> accounts = queryAccounts(keyword, status, merchantId, isPlatformAdmin);

        boolean changed = false;
        Map<Long, Boolean> tokenValidMap = new HashMap<>();
        
        for (DiscordAccount a : accounts) {
            boolean tokenValid = true;
            
            // 检测 USER 账号的 token 有效性
            if (a.getAccountType() == DiscordAccount.AccountType.USER
                    && a.getToken() != null && !a.getToken().isBlank()) {
                try {
                    userClient.getMe(a.getToken());
                    tokenValid = true;
                } catch (Exception e) {
                    tokenValid = false;
                    log.warn("账号 [{}] token 已失效", a.getName());
                }
            }
            tokenValidMap.put(a.getId(), tokenValid);
            
            // 获取头像
            if (a.getAvatarUrl() == null
                    && a.getAccountType() == DiscordAccount.AccountType.USER
                    && a.getToken() != null
                    && a.getDiscordId() != null) {
                try {
                    JsonNode me = userClient.getMe(a.getToken());
                    String avatarHash = me.path("avatar").asText(null);
                    if (avatarHash != null && !avatarHash.isBlank()) {
                        String ext = avatarHash.startsWith("a_") ? "gif" : "png";
                        String avatarUrl = "https://cdn.discordapp.com/avatars/"
                                + a.getDiscordId() + "/" + avatarHash + "." + ext;
                        a.setAvatarUrl(avatarUrl);
                        changed = true;
                    }
                } catch (Exception ignored) {}
            }
        }
        if (changed) {
            accountRepository.saveAll(accounts);
        }

        // 批量查询 counts，避免 N+1 问题
        Map<Long, Long> friendCountMap = batchCountFriends(accounts);
        Map<Long, Long> conversationCountMap = batchCountConversations(accounts);
        Map<Long, Long> messageCountMap = batchCountMessages(accounts);

        Map<Long, Boolean> finalTokenValidMap = tokenValidMap;
        return accounts.stream()
                .map(a -> buildAccountDto(a,
                        finalTokenValidMap.getOrDefault(a.getId(), true),
                        friendCountMap.getOrDefault(a.getId(), 0L),
                        conversationCountMap.getOrDefault(a.getId(), 0L),
                        messageCountMap.getOrDefault(a.getId(), 0L)))
                .toList();
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
                                                Long merchantId, boolean isPlatformAdmin) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasStatus = status != null && !status.isBlank();

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

        // Non-platform admin: see own merchant's accounts + null merchantId accounts
        // Split OR queries into two separate queries to enable index usage
        List<DiscordAccount> result = new ArrayList<>();
        DiscordAccount.AccountStatus statusEnum = hasStatus
                ? DiscordAccount.AccountStatus.valueOf(status.toUpperCase()) : null;
        String kw = hasKeyword ? keyword.trim() : null;

        // Query 1: accounts with matching merchantId
        if (hasKeyword && hasStatus) {
            result.addAll(accountRepository.searchWithAgentsByMerchantIdAndKeywordAndStatus(
                    merchantId, kw, statusEnum));
        } else if (hasKeyword) {
            result.addAll(accountRepository.searchWithAgentsByMerchantId(merchantId, kw));
        } else if (hasStatus) {
            result.addAll(accountRepository.findWithAgentsByMerchantIdAndStatus(merchantId, statusEnum));
        } else {
            result.addAll(accountRepository.findWithAgentsByMerchantId(merchantId));
        }

        // Query 2: accounts with null merchantId
        if (hasKeyword && hasStatus) {
            result.addAll(accountRepository.searchWithAgentsByNullMerchantIdAndKeywordAndStatus(kw, statusEnum));
        } else if (hasKeyword) {
            result.addAll(accountRepository.searchWithAgentsByNullMerchantId(kw));
        } else if (hasStatus) {
            result.addAll(accountRepository.findWithAgentsByNullMerchantIdAndStatus(statusEnum));
        } else {
            result.addAll(accountRepository.findWithAgentsByNullMerchantId());
        }

        return result;
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
        String accType = request.accountType() != null ? request.accountType().toUpperCase() : "BOT";
        account.setAccountType(DiscordAccount.AccountType.valueOf(accType));
        account.setStatus(DiscordAccount.AccountStatus.ACTIVE);
        if (request.merchantId() != null) {
            account.setMerchantId(request.merchantId());
        } else {
            account.setMerchantId(SecurityUtils.currentMerchantId());
        }
        if (request.email() != null) account.setEmail(request.email().trim());
        if (request.remark() != null) account.setRemark(request.remark().trim());
        account = accountRepository.save(account);
        botManager.startAccount(account.getId());
        return AccountDto.from(account, botManager.isConnected(account.getId()), botManager.isConnecting(account.getId()));
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

    @Transactional
    public void deleteAccount(Long id) {
        DiscordAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(account.getMerchantId());
        botManager.stopAccount(id);

        // 1. 删除关联的服务器及其成员、抓取进度数据
        List<GuildServer> guildServers = guildServerRepository.findByDiscordAccountId(id);
        for (GuildServer guild : guildServers) {
            Long guildServerId = guild.getId();
            fetchProgressRepository.deleteByGuildServerId(guildServerId);
            guildMemberRepository.deleteByGuildServerId(guildServerId);
        }
        guildServerRepository.deleteByDiscordAccountId(id);
        log.info("已删除账号[id={}]关联的 {} 个服务器及其成员数据", id, guildServers.size());

        // 2. 移除 Agent 关联
        List<Agent> relatedAgents = agentRepository.findByDiscordAccountsContaining(account);
        for (Agent agent : relatedAgents) {
            agent.getDiscordAccounts().remove(account);
            agentRepository.save(agent);
        }

        // 3. 删除会话和消息
        List<com.discordadmin.entity.Conversation> convs = conversationRepository.findByDiscordAccount(account);
        for (com.discordadmin.entity.Conversation conv : convs) {
            messageRepository.deleteByConversation(conv);
        }
        conversationRepository.deleteByDiscordAccount(account);

        // 4. 删除好友记录
        friendRepository.deleteByDiscordAccount(account);

        // 5. 删除账号
        accountRepository.delete(account);
        log.info("账号[id={}, name={}]及其关联数据已硬删除", id, account.getName());
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

        } catch (Exception e) {
            log.error("账号 [{}] Token 刷新失败: {}", account.getName(), e.getMessage());
            throw new IllegalStateException("Token 刷新失败: " + e.getMessage(), e);
        }
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
