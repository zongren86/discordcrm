package com.discordadmin.controller;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.dto.FriendDtos.FriendDto;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccountNumber;
import com.discordadmin.entity.Friend;
import com.discordadmin.repository.AgentAccountNumberRelRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.DiscordAccountNumberRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.RelationshipSyncService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Transactional(readOnly = true)
@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final FriendRepository friendRepository;
    private final DiscordAccountRepository accountRepository;
    private final RelationshipSyncService syncService;
    private final DiscordUserClient userClient;
    private final AgentRepository agentRepository;
    private final AgentAccountNumberRelRepository relRepository;
    private final DiscordAccountNumberRepository accountNumberRepository;

    public FriendController(FriendRepository friendRepository,
                             DiscordAccountRepository accountRepository,
                             RelationshipSyncService syncService,
                             DiscordUserClient userClient,
                             AgentRepository agentRepository,
                             AgentAccountNumberRelRepository relRepository,
                             DiscordAccountNumberRepository accountNumberRepository) {
        this.friendRepository = friendRepository;
        this.accountRepository = accountRepository;
        this.syncService = syncService;
        this.userClient = userClient;
        this.agentRepository = agentRepository;
        this.relRepository = relRepository;
        this.accountNumberRepository = accountNumberRepository;
    }

    /** 列出全部好友（平台管理员看全部，商户管理员看商户下所有，普通用户只看分配给自己的账号下的好友） */
    @GetMapping
    public List<FriendDto> listAll() {
        if (SecurityUtils.isPlatformAdmin()) {
            return friendRepository.findPlatformFriends().stream().map(FriendDto::from).toList();
        }

        Long currentAgentId = SecurityUtils.currentAgentId();
        if (currentAgentId == null) {
            return List.of();
        }

        // 商户管理员：查看商户下所有好友
        if (SecurityUtils.isMerchantAdmin()) {
            Long merchantId = SecurityUtils.currentMerchantId();
            if (merchantId != null) {
                return friendRepository.findByMerchantIdOrderByGlobalNameAsc(merchantId).stream()
                        .map(FriendDto::from).toList();
            }
            return List.of();
        }

        // 普通用户：只能看到分配给自己的账号下的好友
        Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
        if (assignedAccountIds.isEmpty()) {
            return List.of();
        }

        List<Friend> allFriends = new ArrayList<>();
        for (Long accountId : assignedAccountIds) {
            accountRepository.findById(accountId).ifPresent(acc ->
                allFriends.addAll(friendRepository.findByDiscordAccountOrderByGlobalNameAsc(acc))
            );
        }
        return allFriends.stream().map(FriendDto::from).toList();
    }

    /** 列出某账号下的好友（校验账号是否分配给当前用户） */
    @GetMapping("/by-account/{accountId}")
    public List<FriendDto> listByAccount(@PathVariable Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));

        // 检查权限
        checkAccountAccess(accountId);

        return friendRepository.findByDiscordAccountOrderByGlobalNameAsc(acc).stream()
                .map(FriendDto::from).toList();
    }

    /** 列出待接收好友请求（平台管理员看全部，其他角色只看分配给自己的账号下的） */
    @GetMapping("/pending")
    public List<FriendDto> listPending() {
        if (SecurityUtils.isPlatformAdmin()) {
            return friendRepository.findPlatformFriendsByStatus(Friend.FriendStatus.PENDING_IN).stream()
                    .map(FriendDto::from).toList();
        }

        Long currentAgentId = SecurityUtils.currentAgentId();
        if (currentAgentId == null) {
            return List.of();
        }

        Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
        if (assignedAccountIds.isEmpty()) {
            return List.of();
        }

        List<Friend> pendingFriends = new ArrayList<>();
        for (Long accountId : assignedAccountIds) {
            accountRepository.findById(accountId).ifPresent(acc ->
                pendingFriends.addAll(
                    friendRepository.findByDiscordAccountAndStatusOrderByGlobalNameAsc(acc, Friend.FriendStatus.PENDING_IN))
            );
        }
        return pendingFriends.stream().map(FriendDto::from).toList();
    }

    /** 列出某账号下的待接收好友请求（校验账号是否分配给当前用户） */
    @GetMapping("/pending/{accountId}")
    public List<FriendDto> listPendingByAccount(@PathVariable Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));

        // 检查权限
        checkAccountAccess(accountId);

        return friendRepository
                .findByDiscordAccountAndStatusOrderByGlobalNameAsc(acc, Friend.FriendStatus.PENDING_IN)
                .stream().map(FriendDto::from).toList();
    }

    /** 接受好友请求：调 Discord PUT，成功后本地状态改为 ACCEPTED */
@Transactional
    @PostMapping("/accept/{accountId}/{friendUserId}")
    public Map<String, Object> accept(@PathVariable Long accountId,
                                       @PathVariable String friendUserId) throws Exception {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));

        // 检查权限
        checkAccountAccess(accountId);

        if (acc.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalStateException("仅 USER 类型账号可处理好友请求");
        }
        userClient.acceptFriendRequest(acc.getToken(), friendUserId);

        Optional<Friend> existOpt = friendRepository
                .findByDiscordAccountAndFriendDiscordUserId(acc, friendUserId);
        if (existOpt.isPresent()) {
            Friend f = existOpt.get();
            f.setStatus(Friend.FriendStatus.ACCEPTED);
            f.setSyncedAt(Instant.now());
            friendRepository.save(f);
        } else {
            // 远端已接受但本地无记录，先建一条 ACCEPTED（用户名等信息下次同步补全）
            Friend f = new Friend();
            f.setDiscordAccount(acc);
            f.setFriendDiscordUserId(friendUserId);
            f.setStatus(Friend.FriendStatus.ACCEPTED);
            f.setMerchantId(acc.getMerchantId());
            f.setSyncedAt(Instant.now());
            friendRepository.save(f);
        }
        return Map.of("ok", true);
    }

    /** 拒绝好友请求或删除好友：调 Discord DELETE，成功后删除本地记录 */
@Transactional
    @PostMapping("/reject/{accountId}/{friendUserId}")
    public Map<String, Object> reject(@PathVariable Long accountId,
                                       @PathVariable String friendUserId) throws Exception {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));

        // 检查权限
        checkAccountAccess(accountId);

        if (acc.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalStateException("仅 USER 类型账号可处理好友请求");
        }
        userClient.removeRelationship(acc.getToken(), friendUserId);

        friendRepository.findByDiscordAccountAndFriendDiscordUserId(acc, friendUserId)
                .ifPresent(friendRepository::delete);
        return Map.of("ok", true);
    }

    /** 手动触发某账号的好友同步（拉远端 -> upsert） */
@Transactional
    @PostMapping("/sync/{accountId}")
    public Map<String, Object> sync(@PathVariable Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));

        // 检查权限
        checkAccountAccess(accountId);

        int count = syncService.syncOne(accountId);
        return Map.of("syncedCount", count);
    }

    /** 获取当前用户有权限的账号ID列表 */
    private Set<Long> getAssignedAccountIds(Long currentAgentId) {
        Set<Long> assignedAccountIds = new HashSet<>();

        Optional<Agent> agentOpt = agentRepository.findById(currentAgentId);
        if (agentOpt.isEmpty()) {
            return assignedAccountIds;
        }

        Agent agent = agentOpt.get();

        // 1. 直接关联的账号（agent_discord_accounts）
        assignedAccountIds.addAll(
                agent.getDiscordAccounts().stream()
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

        return assignedAccountIds;
    }

    /** 检查账号是否分配给当前用户 */
    private void checkAccountAccess(Long accountId) {
        if (SecurityUtils.isPlatformAdmin()) {
            return; // 平台管理员可以访问所有账号
        }

        // 商户管理员可以访问同商户下的所有账号
        if (SecurityUtils.isMerchantAdmin()) {
            Long currentMerchantId = SecurityUtils.currentMerchantId();
            DiscordAccount acc = accountRepository.findById(accountId).orElse(null);
            if (acc != null && currentMerchantId != null && currentMerchantId.equals(acc.getMerchantId())) {
                return; // 同商户，允许访问
            }
        }

        Long currentAgentId = SecurityUtils.currentAgentId();
        if (currentAgentId == null) {
            throw new AccessDeniedException("无权访问该账号");
        }

        Set<Long> assignedAccountIds = getAssignedAccountIds(currentAgentId);
        if (!assignedAccountIds.contains(accountId)) {
            throw new AccessDeniedException("无权访问该账号");
        }
    }
}
