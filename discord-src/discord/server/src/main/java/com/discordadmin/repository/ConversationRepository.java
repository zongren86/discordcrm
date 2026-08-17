package com.discordadmin.repository;

import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByChannelId(String channelId);

    /** 查找指定频道ID下的所有会话（用于跨账号去重） */
    List<Conversation> findAllByChannelId(String channelId);

    /** 按频道ID+账号ID查找会话（per-account去重） */
    Optional<Conversation> findByChannelIdAndDiscordAccount_Id(String channelId, Long accountId);

    List<Conversation> findByDiscordUser(DiscordUser discordUser);

    List<Conversation> findAllByOrderByLastMessageAtDesc();

    Optional<Conversation> findByDiscordUserAndDiscordAccountAndType(DiscordUser discordUser, DiscordAccount discordAccount, Conversation.ConversationType type);

    List<Conversation> findByDiscordAccountAndType(DiscordAccount discordAccount, Conversation.ConversationType type);

    /** 删除该账号下的所有会话（删账号前调用以避免 FK 违规） */
    List<Conversation> findByDiscordAccount(DiscordAccount discordAccount);

    void deleteByDiscordAccount(DiscordAccount discordAccount);

    long countByDiscordAccount(DiscordAccount discordAccount);

    /** 按商户ID查找会话 */
    List<Conversation> findByMerchantIdOrderByLastMessageAtDesc(Long merchantId);

    /** 按商户ID+账号ID查找会话 */
    List<Conversation> findByMerchantIdAndDiscordAccount_IdOrderByLastMessageAtDesc(Long merchantId, Long accountId);

    /** 按商户ID+阶段查找会话 */
    List<Conversation> findByMerchantIdAndStageOrderByLastMessageAtDesc(Long merchantId, Conversation.Stage stage);

    /** 按商户ID+账号ID+阶段查找会话 */
    List<Conversation> findByMerchantIdAndDiscordAccount_IdAndStageOrderByLastMessageAtDesc(Long merchantId, Long accountId, Conversation.Stage stage);

    /** 关键词搜索（匹配昵称/用户名/备注/最后消息内容） */
    @Query("SELECT c FROM Conversation c LEFT JOIN c.discordUser u " +
            "WHERE (c.merchantId = :merchantId OR (c.merchantId IS NULL AND :merchantId IS NULL)) " +
            "AND (LOWER(COALESCE(u.globalName,'')) LIKE LOWER(CONCAT('%',:kw,'%')) " +
            "   OR LOWER(COALESCE(u.username,'')) LIKE LOWER(CONCAT('%',:kw,'%')) " +
            "   OR LOWER(COALESCE(c.remark,'')) LIKE LOWER(CONCAT('%',:kw,'%')) " +
            "   OR LOWER(COALESCE(c.lastMessagePreview,'')) LIKE LOWER(CONCAT('%',:kw,'%'))) " +
            "ORDER BY c.lastMessageAt DESC")
    List<Conversation> searchByMerchantAndKeyword(@Param("merchantId") Long merchantId,
                                                    @Param("kw") String keyword);

    /** 按商户+账号+阶段+关键词组合搜索 */
    @Query("SELECT c FROM Conversation c LEFT JOIN c.discordUser u " +
            "WHERE (c.merchantId = :merchantId OR (c.merchantId IS NULL AND :merchantId IS NULL)) " +
            "AND (:accountId IS NULL OR c.discordAccount.id = :accountId) " +
            "AND (:stage IS NULL OR c.stage = :stage) " +
            "AND (LOWER(COALESCE(u.globalName,'')) LIKE LOWER(CONCAT('%',:kw,'%')) " +
            "   OR LOWER(COALESCE(u.username,'')) LIKE LOWER(CONCAT('%',:kw,'%')) " +
            "   OR LOWER(COALESCE(c.remark,'')) LIKE LOWER(CONCAT('%',:kw,'%')) " +
            "   OR LOWER(COALESCE(c.lastMessagePreview,'')) LIKE LOWER(CONCAT('%',:kw,'%'))) " +
            "ORDER BY c.lastMessageAt DESC")
    List<Conversation> searchByMerchantAndFilters(@Param("merchantId") Long merchantId,
                                                   @Param("accountId") Long accountId,
                                                   @Param("stage") Conversation.Stage stage,
                                                   @Param("kw") String keyword);

    /** 置顶会话排在前面，其他按最后消息时间倒序 */
    @Query("SELECT c FROM Conversation c WHERE (c.merchantId = :merchantId OR (c.merchantId IS NULL AND :merchantId IS NULL)) ORDER BY c.pinned DESC, c.lastMessageAt DESC")
    List<Conversation> findByMerchantIdOrderByPinnedAndLastMessageAtDesc(@Param("merchantId") Long merchantId);

    /** 统计指定商户下的会话总数 */
    @Query("SELECT COUNT(c) FROM Conversation c WHERE (c.merchantId = :merchantId OR (c.merchantId IS NULL AND :merchantId IS NULL))")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    /** 按阶段分组统计会话数量 */
    @Query("SELECT c.stage, COUNT(c) FROM Conversation c WHERE (c.merchantId = :merchantId OR (c.merchantId IS NULL AND :merchantId IS NULL)) GROUP BY c.stage")
    List<Object[]> countByStageAndMerchantId(@Param("merchantId") Long merchantId);

    long countByLastMessageAtBetween(java.time.Instant start, java.time.Instant end);

    @Query("SELECT COUNT(c) FROM Conversation c WHERE c.lastMessageAt >= :start AND c.lastMessageAt < :end AND c.merchantId = :merchantId")
    long countByLastMessageAtBetweenAndMerchant(@Param("start") java.time.Instant start,
                                                 @Param("end") java.time.Instant end,
                                                 @Param("merchantId") Long merchantId);

    /** 按分配客服统计会话数量 */
    @Query("SELECT c.assignedAgent, COUNT(c) FROM Conversation c WHERE (c.merchantId = :merchantId OR (c.merchantId IS NULL AND :merchantId IS NULL)) GROUP BY c.assignedAgent")
    List<Object[]> countByAssignedAgentAndMerchant(@Param("merchantId") Long merchantId);

    /** 统计指定时间后创建的会话数 */
    long countByCreatedAtAfter(java.time.Instant start);

    /** 按商户统计指定时间后创建的会话数 */
    @Query("SELECT COUNT(c) FROM Conversation c WHERE c.createdAt >= :start AND c.merchantId = :merchantId")
    long countByCreatedAtAfterAndMerchant(@Param("start") java.time.Instant start, @Param("merchantId") Long merchantId);

    /** 按好友Discord用户ID+账号ID查找会话 */
    @Query("SELECT c FROM Conversation c JOIN c.discordUser u WHERE u.discordUserId = :friendDiscordUserId AND c.discordAccount.id = :accountId")
    List<Conversation> findByDiscordUserAndDiscordAccount(@Param("friendDiscordUserId") String friendDiscordUserId, @Param("accountId") Long accountId);

    /** 按ownerAgentId查询会话（普通用户权限控制） */
    List<Conversation> findByOwnerAgentId(Long ownerAgentId);

    /** 按商户ID和ownerAgentId查询会话 */
    List<Conversation> findByMerchantIdAndOwnerAgentId(Long merchantId, Long ownerAgentId);
}
