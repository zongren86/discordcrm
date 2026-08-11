package com.discordadmin.repository;

import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Friend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendRepository extends JpaRepository<Friend, Long> {

    List<Friend> findByDiscordAccount(DiscordAccount account);

    List<Friend> findByDiscordAccountOrderByGlobalNameAsc(DiscordAccount account);

    Optional<Friend> findByDiscordAccountAndFriendDiscordUserId(DiscordAccount account, String friendDiscordUserId);

    /** 按状态查所有账号的好友/请求（用于列出全部待接收请求） */
    List<Friend> findByStatus(Friend.FriendStatus status);

    /** 按商户查所有好友 */
    List<Friend> findByMerchantId(Long merchantId);

    /** 按商户+状态查好友/请求 */
    List<Friend> findByMerchantIdAndStatus(Long merchantId, Friend.FriendStatus status);

    /** 平台级：查账号所属商户为null的好友 */
    @Query("SELECT f FROM Friend f WHERE f.discordAccount.merchantId IS NULL")
    List<Friend> findPlatformFriends();

    /** 平台级：统计账号所属商户为null的好友数量 */
    @Query("SELECT COUNT(f) FROM Friend f WHERE f.discordAccount.merchantId IS NULL")
    long countPlatformFriends();

    /** 按商户统计好友数量 */
    @Query("SELECT COUNT(f) FROM Friend f WHERE f.discordAccount.merchantId = :merchantId")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    /** 平台级：按状态查账号所属商户为null的好友/请求 */
    @Query("SELECT f FROM Friend f WHERE f.discordAccount.merchantId IS NULL AND f.status = :status")
    List<Friend> findPlatformFriendsByStatus(Friend.FriendStatus status);

    /** 按账号+状态查好友/请求 */
    List<Friend> findByDiscordAccountAndStatusOrderByGlobalNameAsc(DiscordAccount account, Friend.FriendStatus status);

    void deleteByDiscordAccount(DiscordAccount account);

    long countByDiscordAccount(DiscordAccount account);

    /** 通过好友的Discord用户ID批量查询好友记录 */
    List<Friend> findByFriendDiscordUserIdIn(List<String> friendDiscordUserIds);
}
