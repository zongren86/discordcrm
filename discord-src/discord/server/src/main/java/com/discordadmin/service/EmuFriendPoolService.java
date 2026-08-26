package com.discordadmin.service;

import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EmuFriendPoolService {

    private final GuildMemberRepository memberRepository;
    private final EmuServerBindingRepository serverBindingRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final GuildServerRepository guildServerRepository;

    // 好友状态常量
    public static final int STATUS_PENDING = 0;    // 待添加
    public static final int STATUS_ASSIGNED = 1;   // 已分配
    public static final int STATUS_SUCCESS = 2;    // 添加成功
    public static final int STATUS_FAILED = 3;     // 添加失败

    public EmuFriendPoolService(GuildMemberRepository memberRepository,
                                 EmuServerBindingRepository serverBindingRepository,
                                 DiscordAccountRepository discordAccountRepository,
                                 GuildServerRepository guildServerRepository) {
        this.memberRepository = memberRepository;
        this.serverBindingRepository = serverBindingRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.guildServerRepository = guildServerRepository;
    }

    /**
     * 初始化好友池：将服务器成员设置为待添加状态
     * 用于将抓取到的成员标记为可分配的状态
     */
    @Transactional
    public int initFriendPool(Long serverId) {
        List<GuildMember> members = memberRepository.findByGuildServerId(serverId);
        
        int count = 0;
        for (GuildMember member : members) {
            if (member.getFriendStatus() == null || member.getFriendStatus() != STATUS_PENDING) {
                // 如果成员没有状态或不是待添加状态，保持原有状态
                // 只初始化那些还没有被处理的成员
                continue;
            }
        }
        
        // 统计已经是待添加状态的成员数量
        return (int) memberRepository.countPendingByGuildServerId(serverId);
    }

    /**
     * 获取服务器的好友池列表
     */
    public List<Map<String, Object>> getFriendPool(Long serverId, String status) {
        List<GuildMember> members;
        
        if (status != null && !status.isEmpty()) {
            int statusValue = parseStatus(status);
            members = memberRepository.findByGuildServerIdAndFriendStatus(serverId, statusValue);
        } else {
            // 获取所有有好友状态的成员
            Page<GuildMember> page = memberRepository.findFriendPoolByGuildServerId(serverId, Pageable.unpaged());
            members = page.getContent();
        }

        return members.stream()
            .map(member -> convertToMap(member))
            .collect(Collectors.toList());
    }

    /**
     * 分页获取好友池列表
     */
    public Page<Map<String, Object>> getFriendPoolPage(Long serverId, String status, Pageable pageable) {
        Page<GuildMember> memberPage;
        
        if (status != null && !status.isEmpty()) {
            int statusValue = parseStatus(status);
            memberPage = memberRepository.findByGuildServerIdAndFriendStatus(serverId, statusValue, pageable);
        } else {
            memberPage = memberRepository.findFriendPoolByGuildServerId(serverId, pageable);
        }

        return memberPage.map(this::convertToMap);
    }

    /**
     * 获取服务器的好友池统计
     */
    public Map<String, Object> getFriendPoolStats(Long serverId) {
        Map<String, Object> stats = new HashMap<>();
        
        long total = memberRepository.countWithFriendStatusByGuildServerId(serverId);
        long pending = memberRepository.countPendingByGuildServerId(serverId);
        long assigned = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_ASSIGNED)
                + memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_SUCCESS)
                + memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_FAILED);
        long success = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_SUCCESS);
        long failed = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_FAILED);
        
        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("assigned", assigned);
        stats.put("success", success);
        stats.put("failed", failed);
        
        return stats;
    }

    /**
     * 获取商户所有服务器的好友池统计（包括未绑定的服务器）
     */
    public Map<String, Object> getFriendPoolStatsByMerchant(Long merchantId) {
        Map<String, Object> stats = new HashMap<>();
        long total = 0, pending = 0, assigned = 0, success = 0, failed = 0;
        
        List<EmuServerBinding> bindings = serverBindingRepository.findByMerchantId(merchantId);
        for (EmuServerBinding binding : bindings) {
            Long serverId = binding.getServerId();
            total += memberRepository.countWithFriendStatusByGuildServerId(serverId);
            pending += memberRepository.countPendingByGuildServerId(serverId);
            
            long serverSuccess = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_SUCCESS);
            long serverFailed = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_FAILED);
            long serverAssigned = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_ASSIGNED);
            assigned += serverAssigned + serverSuccess + serverFailed;
            success += serverSuccess;
            failed += serverFailed;
        }
        
        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("assigned", assigned);
        stats.put("success", success);
        stats.put("failed", failed);
        
        return stats;
    }

    /**
     * 获取商户下每个服务器的好友池统计（按服务器分组）
     */
    public List<Map<String, Object>> getFriendPoolStatsByServer(Long merchantId) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        List<EmuServerBinding> bindings = serverBindingRepository.findByMerchantId(merchantId);
        for (EmuServerBinding binding : bindings) {
            Long serverId = binding.getServerId();
            Map<String, Object> serverStats = new HashMap<>();
            
            long total = memberRepository.countWithFriendStatusByGuildServerId(serverId);
            long pending = memberRepository.countPendingByGuildServerId(serverId);
            long success = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_SUCCESS);
            long failed = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_FAILED);
            long assigned = memberRepository.countByGuildServerIdAndFriendStatus(serverId, STATUS_ASSIGNED) + success + failed;
            
            serverStats.put("serverId", serverId);
            serverStats.put("serverName", binding.getServerName() != null ? binding.getServerName() : "服务器 #" + serverId);
            serverStats.put("total", total);
            serverStats.put("pending", pending);
            serverStats.put("assigned", assigned);
            serverStats.put("success", success);
            serverStats.put("failed", failed);
            
            result.add(serverStats);
        }
        
        return result;
    }

    /**
     * 分配一个待添加的成员给任务
     */
    @Transactional
    public GuildMember assignOneFriend(Long serverId, Long discordAccountId, Long taskId) {
        Optional<GuildMember> memberOpt = memberRepository.findOnePendingByGuildServerId(serverId);
        
        if (memberOpt.isPresent()) {
            GuildMember member = memberOpt.get();
            member.setFriendStatus(STATUS_ASSIGNED);
            member.setDiscordAccountId(discordAccountId);
            member.setAssignedTaskId(taskId);
            member.setUpdatedAt(Instant.now());
            return memberRepository.save(member);
        }
        
        return null;
    }

    /**
     * 分配多个好友给任务
     */
    @Transactional
    public List<GuildMember> assignFriendsToTask(Long serverId, Long taskId, Long discordAccountId, int count) {
        List<GuildMember> pendingMembers = memberRepository.findPendingByGuildServerId(serverId);
        
        List<GuildMember> assigned = new ArrayList<>();
        for (int i = 0; i < Math.min(count, pendingMembers.size()); i++) {
            GuildMember member = pendingMembers.get(i);
            member.setFriendStatus(STATUS_ASSIGNED);
            member.setDiscordAccountId(discordAccountId);
            member.setAssignedTaskId(taskId);
            member.setUpdatedAt(Instant.now());
            assigned.add(memberRepository.save(member));
        }
        return assigned;
    }

    /**
     * 更新好友添加结果
     */
    @Transactional
    public void updateFriendResult(Long memberId, int status, String error) {
        memberRepository.findById(memberId).ifPresent(member -> {
            member.setFriendStatus(status);
            member.setLastError(error);
            member.setFinishedAt(Instant.now());
            member.setUpdatedAt(Instant.now());
            memberRepository.save(member);
        });
    }

    /**
     * 更新好友处理信息
     */
    @Transactional
    public void updateFriendProcessing(Long memberId, Integer emulatorIndex) {
        memberRepository.findById(memberId).ifPresent(member -> {
            member.setEmulatorIndex(emulatorIndex);
            member.setStartedAt(Instant.now());
            member.setUpdatedAt(Instant.now());
            memberRepository.save(member);
        });
    }

    /**
     * 增加重试次数
     */
    @Transactional
    public void incrementRetryCount(Long memberId) {
        memberRepository.findById(memberId).ifPresent(member -> {
            member.setRetryCount(member.getRetryCount() != null ? member.getRetryCount() + 1 : 1);
            member.setUpdatedAt(Instant.now());
            memberRepository.save(member);
        });
    }

    /**
     * 重置好友状态为待添加
     */
    @Transactional
    public void resetFriendStatus(Long memberId) {
        memberRepository.findById(memberId).ifPresent(member -> {
            member.setFriendStatus(STATUS_PENDING);
            member.setDiscordAccountId(null);
            member.setAssignedTaskId(null);
            member.setLastError(null);
            member.setUpdatedAt(Instant.now());
            memberRepository.save(member);
        });
    }

    /**
     * 获取待添加的好友列表
     */
    public List<GuildMember> getPendingFriends(Long serverId) {
        return memberRepository.findPendingByGuildServerId(serverId);
    }

    /**
     * 获取已分配的好友列表
     */
    public List<GuildMember> getAssignedFriends(Long serverId) {
        return memberRepository.findAssignedByGuildServerId(serverId);
    }

    /**
     * 转换为Map用于前端展示
     */
    private Map<String, Object> convertToMap(GuildMember member) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", member.getId());
        item.put("serverId", member.getGuildServerId());
        item.put("discordUserId", member.getUserId());
        item.put("username", member.getUsername());
        item.put("globalName", member.getGlobalName());
        item.put("displayName", member.getDisplayName());
        item.put("avatarUrl", member.getAvatarUrl());
        item.put("friendStatus", member.getFriendStatus());
        item.put("statusText", getStatusText(member.getFriendStatus()));
        item.put("discordAccountId", member.getDiscordAccountId());
        item.put("assignedTaskId", member.getAssignedTaskId());
        item.put("emulatorIndex", member.getEmulatorIndex());
        item.put("lastError", member.getLastError());
        item.put("startedAt", member.getStartedAt());
        item.put("finishedAt", member.getFinishedAt());
        item.put("retryCount", member.getRetryCount());
        item.put("updatedAt", member.getUpdatedAt());
        item.put("createdAt", member.getCreatedAt());
        
        // 查询 Discord 账号名称
        if (member.getDiscordAccountId() != null) {
            DiscordAccount account = discordAccountRepository.findById(member.getDiscordAccountId()).orElse(null);
            item.put("discordAccountName", account != null ? account.getName() : "-");
        } else {
            item.put("discordAccountName", "-");
        }
        
        return item;
    }

    /**
     * 解析状态字符串
     */
    private int parseStatus(String status) {
        return switch (status.toUpperCase()) {
            case "PENDING" -> STATUS_PENDING;
            case "ASSIGNED" -> STATUS_ASSIGNED;
            case "SUCCESS" -> STATUS_SUCCESS;
            case "FAILED" -> STATUS_FAILED;
            default -> STATUS_PENDING;
        };
    }

    /**
     * 获取状态文本
     */
    private String getStatusText(Integer status) {
        if (status == null || status == STATUS_PENDING) return "待添加";
        return switch (status) {
            case STATUS_ASSIGNED -> "已分配";
            case STATUS_SUCCESS -> "添加成功";
            case STATUS_FAILED -> "添加失败";
            default -> "未知";
        };
    }
}
