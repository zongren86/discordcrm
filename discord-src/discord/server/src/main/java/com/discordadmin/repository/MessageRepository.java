package com.discordadmin.repository;

import com.discordadmin.dto.UserStatsDtos.ActiveCustomerDto;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.conversation = :conversation AND (m.isDeleted IS NULL OR m.isDeleted = false) ORDER BY m.createdAt ASC")
    List<Message> findByConversationOrderByCreatedAtAsc(@Param("conversation") Conversation conversation);

    Optional<Message> findByDiscordMessageId(String discordMessageId);

    /** per-conversation去重：查找同一会话中是否已存在该Discord消息 */
    Optional<Message> findByConversationAndDiscordMessageId(Conversation conversation, String discordMessageId);

    void deleteByConversation(Conversation conversation);

    @Query("SELECT DISTINCT m.conversation.id FROM Message m WHERE m.senderDiscordUserId = :senderId")
    List<Long> findConversationIdsBySenderDiscordUserId(@Param("senderId") String senderDiscordUserId);

    @Query("""
        SELECT new com.discordadmin.dto.UserStatsDtos$ActiveCustomerDto(
            u.discordUserId,
            u.username,
            u.globalName,
            u.avatarUrl,
            COUNT(m.id),
            MAX(m.createdAt)
        ) FROM Message m
        JOIN m.conversation c
        JOIN c.discordUser u
        WHERE m.createdAt >= :since
        GROUP BY u.discordUserId, u.username, u.globalName, u.avatarUrl
        ORDER BY MAX(m.createdAt) DESC
        """)
    List<ActiveCustomerDto> findActiveCustomersSince(@Param("since") Instant since);

    @Query("""
        SELECT new com.discordadmin.dto.UserStatsDtos$ActiveCustomerDto(
            u.discordUserId,
            u.username,
            u.globalName,
            u.avatarUrl,
            COUNT(m.id),
            MAX(m.createdAt)
        ) FROM Message m
        JOIN m.conversation c
        JOIN c.discordUser u
        WHERE m.createdAt >= :since AND c.merchantId = :merchantId
        GROUP BY u.discordUserId, u.username, u.globalName, u.avatarUrl
        ORDER BY MAX(m.createdAt) DESC
        """)
    List<ActiveCustomerDto> findActiveCustomersSinceAndMerchant(@Param("since") Instant since,
                                                                  @Param("merchantId") Long merchantId);

    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE c.discordAccount = :account")
    long countByDiscordAccount(@Param("account") com.discordadmin.entity.DiscordAccount account);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.createdAt >= :start AND m.createdAt < :end")
    long countByCreatedAtBetween(@Param("start") Instant start,
                                 @Param("end") Instant end);

    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND m.createdAt < :end AND c.merchantId = :merchantId")
    long countByCreatedAtBetweenAndMerchant(@Param("start") Instant start,
                                            @Param("end") Instant end,
                                            @Param("merchantId") Long merchantId);

    /** 按客服统计消息数 */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.senderAgent.id = :agentId")
    long countByAgent(@Param("agentId") Long agentId);

    /** 按客服+日期范围统计消息数 */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.senderAgent.id = :agentId AND m.createdAt >= :start AND m.createdAt < :end")
    long countByAgentAndDateRange(@Param("agentId") Long agentId,
                                   @Param("start") Instant start,
                                   @Param("end") Instant end);

    /** 统计指定时间后的消息数 */
    long countByCreatedAtAfter(Instant start);

    /** 按商户统计指定时间后的消息数 */
    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND c.merchantId = :merchantId")
    long countByCreatedAtAfterAndMerchant(@Param("start") Instant start, @Param("merchantId") Long merchantId);

    /** 统计指定时间后活跃客户数 */
    @Query("SELECT COUNT(DISTINCT c.discordUser.id) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start")
    long countActiveCustomersSince(@Param("start") Instant start);

    /** 按商户统计指定时间后活跃客户数 */
    @Query("SELECT COUNT(DISTINCT c.discordUser.id) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND c.merchantId = :merchantId")
    long countActiveCustomersSinceAndMerchant(@Param("start") Instant start, @Param("merchantId") Long merchantId);

    /** 平台级：统计账号所属商户为null的会话中的消息数 */
    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE c.discordAccount.merchantId IS NULL")
    long countPlatformMessages();

    /** 按商户统计消息总数 */
    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE c.merchantId = :merchantId")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    /** 批量统计会话的未读消息数（基于lastReadAt或最后一条OUTBOUND消息时间） */
    @Query("""
        SELECT c.id, COUNT(m) FROM Message m JOIN m.conversation c
        WHERE c.id IN :convIds AND m.direction = 'INBOUND'
        AND m.createdAt > COALESCE(c.lastReadAt, (
            SELECT COALESCE(MAX(m2.createdAt), '1970-01-01T00:00:00Z') FROM Message m2
            WHERE m2.conversation = c AND m2.direction = 'OUTBOUND'
        ))
        GROUP BY c.id
        """)
    List<Object[]> countUnreadByConversationIds(@Param("convIds") List<Long> convIds);

    /** 统计单个会话的未读消息数 */
    @Query("""
        SELECT COUNT(m) FROM Message m JOIN m.conversation c
        WHERE c.id = :convId AND m.direction = 'INBOUND'
        AND m.createdAt > COALESCE(c.lastReadAt, (
            SELECT COALESCE(MAX(m2.createdAt), '1970-01-01T00:00:00Z') FROM Message m2
            WHERE m2.conversation = c AND m2.direction = 'OUTBOUND'
        ))
        """)
    int countUnreadByConversationId(@Param("convId") Long convId);

    /** 检查会话是否双方都有消息（既有INBOUND又有OUTBOUND） */
    @Query("""
        SELECT COUNT(m) > 0 FROM Message m
        WHERE m.conversation = :conversation AND m.direction = 'INBOUND'
        AND EXISTS (SELECT 1 FROM Message m2 WHERE m2.conversation = :conversation AND m2.direction = 'OUTBOUND')
        """)
    boolean hasBothDirectionsMessages(@Param("conversation") Conversation conversation);

    /** 统计会话中INBOUND消息数量 */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversation = :conversation AND m.direction = 'INBOUND'")
    long countInboundMessages(@Param("conversation") Conversation conversation);

    /** 统计会话中OUTBOUND消息数量 */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversation = :conversation AND m.direction = 'OUTBOUND'")
    long countOutboundMessages(@Param("conversation") Conversation conversation);

    /** 平台级：统计账号所属商户为null的会话中的活跃客户数 */
    @Query("SELECT COUNT(DISTINCT c.discordUser.id) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND c.merchantId IS NULL")
    long countPlatformActiveCustomersSince(@Param("start") Instant start);
}
