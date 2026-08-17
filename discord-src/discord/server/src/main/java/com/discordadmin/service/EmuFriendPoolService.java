package com.discordadmin.service;

import com.discordadmin.entity.EmuFriendPool;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EmuFriendPoolService {

    private final EmuFriendPoolRepository friendPoolRepository;
    private final EmuServerBindingRepository serverBindingRepository;
    private final GuildMemberRepository memberRepository;

    public EmuFriendPoolService(EmuFriendPoolRepository friendPoolRepository,
                                 EmuServerBindingRepository serverBindingRepository,
                                 GuildMemberRepository memberRepository) {
        this.friendPoolRepository = friendPoolRepository;
        this.serverBindingRepository = serverBindingRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * 从服务器成员同步好友到好友号池
     */
    @Transactional
    public int syncFriendsFromServer(Long merchantId, Long serverId) {
        // 获取服务器成员
        List<GuildMember> members = memberRepository.findByGuildServerId(serverId);
        
        int addedCount = 0;
        for (GuildMember member : members) {
            // 检查是否已存在
            boolean exists = friendPoolRepository.findByDiscordUserIdAndStatus(
                member.getUserId(), EmuFriendPool.FriendStatus.PENDING).isPresent();
            
            if (!exists) {
                EmuFriendPool friend = new EmuFriendPool();
                friend.setMerchantId(merchantId);
                friend.setServerId(serverId);
                friend.setDiscordUserId(member.getUserId());
                friend.setUsername(member.getUsername());
                friend.setGlobalName(member.getGlobalName());
                friend.setStatus(EmuFriendPool.FriendStatus.PENDING);
                friend.setCreatedAt(Instant.now());
                
                friendPoolRepository.save(friend);
                addedCount++;
            }
        }
        return addedCount;
    }

    /**
     * 获取商户的好友号池
     */
    public List<Map<String, Object>> getFriendPool(Long merchantId, String status) {
        List<EmuFriendPool> pool;
        if (status != null && !status.isEmpty()) {
            EmuFriendPool.FriendStatus friendStatus = EmuFriendPool.FriendStatus.valueOf(status);
            pool = friendPoolRepository.findByMerchantIdAndStatus(merchantId, friendStatus);
        } else {
            pool = friendPoolRepository.findByMerchantId(merchantId);
        }

        return pool.stream()
            .map(friend -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", friend.getId());
                item.put("serverId", friend.getServerId());
                item.put("discordUserId", friend.getDiscordUserId());
                item.put("username", friend.getUsername());
                item.put("globalName", friend.getGlobalName());
                item.put("status", friend.getStatus().name());
                item.put("statusText", getStatusText(friend.getStatus()));
                item.put("assignedTaskId", friend.getAssignedTaskId());
                item.put("lastError", friend.getLastError());
                item.put("createdAt", friend.getCreatedAt());
                return item;
            })
            .collect(Collectors.toList());
    }

    /**
     * 获取好友池统计
     */
    public Map<String, Object> getFriendPoolStats(Long merchantId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", friendPoolRepository.findByMerchantId(merchantId).size());
        stats.put("pending", friendPoolRepository.countByMerchantIdAndStatus(merchantId, EmuFriendPool.FriendStatus.PENDING));
        stats.put("assigned", friendPoolRepository.countByMerchantIdAndStatus(merchantId, EmuFriendPool.FriendStatus.ASSIGNED));
        stats.put("success", friendPoolRepository.countByMerchantIdAndStatus(merchantId, EmuFriendPool.FriendStatus.SUCCESS));
        stats.put("failed", friendPoolRepository.countByMerchantIdAndStatus(merchantId, EmuFriendPool.FriendStatus.FAILED));
        return stats;
    }

    /**
     * 分配好友给任务
     */
    @Transactional
    public List<EmuFriendPool> assignFriendsToTask(Long merchantId, Long taskId, int count) {
        List<EmuFriendPool> pendingFriends = friendPoolRepository.findByMerchantIdAndStatus(
            merchantId, EmuFriendPool.FriendStatus.PENDING);

        List<EmuFriendPool> assigned = new ArrayList<>();
        for (int i = 0; i < Math.min(count, pendingFriends.size()); i++) {
            EmuFriendPool friend = pendingFriends.get(i);
            friend.setStatus(EmuFriendPool.FriendStatus.ASSIGNED);
            friend.setAssignedTaskId(taskId);
            friend.setUpdatedAt(Instant.now());
            assigned.add(friendPoolRepository.save(friend));
        }
        return assigned;
    }

    /**
     * 更新好友添加结果
     */
    @Transactional
    public void updateFriendResult(Long friendId, EmuFriendPool.FriendStatus status, String error) {
        friendPoolRepository.findById(friendId).ifPresent(friend -> {
            friend.setStatus(status);
            friend.setLastError(error);
            friend.setUpdatedAt(Instant.now());
            friendPoolRepository.save(friend);
        });
    }

    /**
     * 获取状态文本
     */
    private String getStatusText(EmuFriendPool.FriendStatus status) {
        return switch (status) {
            case PENDING -> "待添加";
            case ASSIGNED -> "已分配";
            case SUCCESS -> "添加成功";
            case FAILED -> "添加失败";
        };
    }
}
