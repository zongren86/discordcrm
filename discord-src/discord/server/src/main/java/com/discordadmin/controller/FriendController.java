package com.discordadmin.controller;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.dto.FriendDtos.FriendDto;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Friend;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.RelationshipSyncService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final FriendRepository friendRepository;
    private final DiscordAccountRepository accountRepository;
    private final RelationshipSyncService syncService;
    private final DiscordUserClient userClient;

    public FriendController(FriendRepository friendRepository,
                             DiscordAccountRepository accountRepository,
                             RelationshipSyncService syncService,
                             DiscordUserClient userClient) {
        this.friendRepository = friendRepository;
        this.accountRepository = accountRepository;
        this.syncService = syncService;
        this.userClient = userClient;
    }

    /** 列出全部好友（平台管理员只看平台级好友，其他角色只看本商户） */
    @GetMapping
    public List<FriendDto> listAll() {
        if (SecurityUtils.isPlatformAdmin()) {
            return friendRepository.findPlatformFriends().stream().map(FriendDto::from).toList();
        }
        Long merchantId = SecurityUtils.currentMerchantId();
        return friendRepository.findByMerchantId(merchantId).stream().map(FriendDto::from).toList();
    }

    /** 列出某账号下的好友（校验账号归属） */
    @GetMapping("/by-account/{accountId}")
    public List<FriendDto> listByAccount(@PathVariable Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(acc.getMerchantId());
        return friendRepository.findByDiscordAccountOrderByGlobalNameAsc(acc).stream()
                .map(FriendDto::from).toList();
    }

    /** 列出所有账号的待接收好友请求 */
    @GetMapping("/pending")
    public List<FriendDto> listPending() {
        if (SecurityUtils.isPlatformAdmin()) {
            return friendRepository.findPlatformFriendsByStatus(Friend.FriendStatus.PENDING_IN).stream()
                    .map(FriendDto::from).toList();
        }
        Long merchantId = SecurityUtils.currentMerchantId();
        return friendRepository.findByMerchantIdAndStatus(merchantId, Friend.FriendStatus.PENDING_IN).stream()
                .map(FriendDto::from).toList();
    }

    /** 列出某账号下的待接收好友请求（校验账号归属） */
    @GetMapping("/pending/{accountId}")
    public List<FriendDto> listPendingByAccount(@PathVariable Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(acc.getMerchantId());
        return friendRepository
                .findByDiscordAccountAndStatusOrderByGlobalNameAsc(acc, Friend.FriendStatus.PENDING_IN)
                .stream().map(FriendDto::from).toList();
    }

    /** 接受好友请求：调 Discord PUT，成功后本地状态改为 ACCEPTED */
    @PostMapping("/accept/{accountId}/{friendUserId}")
    public Map<String, Object> accept(@PathVariable Long accountId,
                                       @PathVariable String friendUserId) throws Exception {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(acc.getMerchantId());
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
    @PostMapping("/reject/{accountId}/{friendUserId}")
    public Map<String, Object> reject(@PathVariable Long accountId,
                                       @PathVariable String friendUserId) throws Exception {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(acc.getMerchantId());
        if (acc.getAccountType() != DiscordAccount.AccountType.USER) {
            throw new IllegalStateException("仅 USER 类型账号可处理好友请求");
        }
        userClient.removeRelationship(acc.getToken(), friendUserId);

        friendRepository.findByDiscordAccountAndFriendDiscordUserId(acc, friendUserId)
                .ifPresent(friendRepository::delete);
        return Map.of("ok", true);
    }

    /** 手动触发某账号的好友同步（拉远端 -> upsert） */
    @PostMapping("/sync/{accountId}")
    public Map<String, Object> sync(@PathVariable Long accountId) {
        DiscordAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在"));
        SecurityUtils.checkMerchantAccess(acc.getMerchantId());
        int count = syncService.syncOne(accountId);
        return Map.of("syncedCount", count);
    }
}
