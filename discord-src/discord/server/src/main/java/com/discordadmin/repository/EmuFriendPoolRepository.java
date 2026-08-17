package com.discordadmin.repository;

import com.discordadmin.entity.EmuFriendPool;
import org.springframework.data.jpa.repository.JpaRepository;
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

    long countByMerchantIdAndStatus(Long merchantId, EmuFriendPool.FriendStatus status);

    long countByServerId(Long serverId);
}
