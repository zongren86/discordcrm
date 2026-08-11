package com.discordadmin.repository;

import com.discordadmin.entity.DiscordUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DiscordUserRepository extends JpaRepository<DiscordUser, Long> {
    Optional<DiscordUser> findByDiscordUserId(String discordUserId);

    /** 按商户统计客户数（通过会话关联） */
    @Query("SELECT COUNT(DISTINCT u) FROM DiscordUser u WHERE u.id IN " +
           "(SELECT c.discordUser.id FROM Conversation c WHERE c.merchantId = :merchantId)")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    /** 平台级统计（merchantId为null的会话关联的客户） */
    @Query("SELECT COUNT(DISTINCT u) FROM DiscordUser u WHERE u.id IN " +
           "(SELECT c.discordUser.id FROM Conversation c WHERE c.merchantId IS NULL)")
    long countPlatformCustomers();
}
