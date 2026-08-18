package com.discordadmin.repository;

import com.discordadmin.entity.EmuFriendPool;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmuFriendPoolRepository extends JpaRepository<EmuFriendPool, Long> {

    List<EmuFriendPool> findByMerchantId(Long merchantId);

    List<EmuFriendPool> findByMerchantIdAndStatus(Long merchantId, EmuFriendPool.FriendStatus status);

    List<EmuFriendPool> findByServerId(Long serverId);

    List<EmuFriendPool> findByServerIdAndStatus(Long serverId, EmuFriendPool.FriendStatus status);

    Optional<EmuFriendPool> findByDiscordUserIdAndStatus(String discordUserId, EmuFriendPool.FriendStatus status);

    boolean existsByDiscordUserIdAndStatus(String discordUserId, EmuFriendPool.FriendStatus status);

    boolean existsByDiscordUserId(String discordUserId);

    long countByMerchantIdAndStatus(Long merchantId, EmuFriendPool.FriendStatus status);

    long countByServerId(Long serverId);

    /**
     * 锁定待分配的好友，用于原子性分配
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM EmuFriendPool f WHERE f.merchantId = :merchantId AND f.status = :status ORDER BY f.id ASC")
    List<EmuFriendPool> findLockedByMerchantIdAndStatus(
        @Param("merchantId") Long merchantId,
        @Param("status") EmuFriendPool.FriendStatus status,
        org.springframework.data.domain.Pageable pageable);

    /**
     * 批量更新状态
     */
    @Query("UPDATE EmuFriendPool f SET f.status = :newStatus, f.assignedTaskId = :taskId, f.updatedAt = CURRENT_TIMESTAMP WHERE f.id IN :ids AND f.status = :oldStatus")
    int updateStatusByIds(
        @Param("ids") List<Long> ids,
        @Param("oldStatus") EmuFriendPool.FriendStatus oldStatus,
        @Param("newStatus") EmuFriendPool.FriendStatus newStatus,
        @Param("taskId") Long taskId);
}
