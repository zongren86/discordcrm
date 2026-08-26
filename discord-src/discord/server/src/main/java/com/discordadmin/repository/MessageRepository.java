package com.discordadmin.repository;

import com.discordadmin.dto.UserStatsDtos.ActiveCustomerDto;
import com.discordadmin.entity.Conversation;
import com.discordadmin.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.conversation = :conversation AND (m.isDeleted IS NULL OR m.isDeleted = false) ORDER BY m.createdAt ASC")
    List<Message> findByConversationOrderByCreatedAtAsc(@Param("conversation") Conversation conversation);

    @Query("SELECT m FROM Message m WHERE m.conversation = :conversation AND (m.isDeleted IS NULL OR m.isDeleted = false) ORDER BY m.createdAt DESC, m.id DESC")
    Slice<Message> findLatestByConversation(@Param("conversation") Conversation conversation, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversation = :conversation AND (m.isDeleted IS NULL OR m.isDeleted = false) " +
            "AND (m.createdAt < :before OR (m.createdAt = :before AND m.id < :beforeId)) " +
            "ORDER BY m.createdAt DESC, m.id DESC")
    Slice<Message> findOlderByConversation(@Param("conversation") Conversation conversation,
                                            @Param("before") Instant before,
                                            @Param("beforeId") Long beforeId,
                                            Pageable pageable);

    Optional<Message> findByDiscordMessageId(String discordMessageId);

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

    @Query("SELECT COUNT(m) FROM Message m WHERE m.senderAgent.id = :agentId")
    long countByAgent(@Param("agentId") Long agentId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.senderAgent.id = :agentId AND m.createdAt >= :start AND m.createdAt < :end")
    long countByAgentAndDateRange(@Param("agentId") Long agentId,
                                   @Param("start") Instant start,
                                   @Param("end") Instant end);

    long countByCreatedAtAfter(Instant start);

    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND c.merchantId = :merchantId")
    long countByCreatedAtAfterAndMerchant(@Param("start") Instant start, @Param("merchantId") Long merchantId);

    @Query("SELECT COUNT(DISTINCT c.discordUser.id) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start")
    long countActiveCustomersSince(@Param("start") Instant start);

    @Query("SELECT COUNT(DISTINCT c.discordUser.id) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND c.merchantId = :merchantId")
    long countActiveCustomersSinceAndMerchant(@Param("start") Instant start, @Param("merchantId") Long merchantId);

    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE c.discordAccount.merchantId IS NULL")
    long countPlatformMessages();

    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE c.merchantId = :merchantId")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    @Query("""
        SELECT c.id, COUNT(m) FROM Message m JOIN m.conversation c
        WHERE c.id IN :convIds AND m.direction = 'INBOUND'
        AND m.createdAt > CASE
            WHEN c.lastReadAt IS NULL AND (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND') IS NULL
                THEN '1970-01-01T00:00:00Z'
            WHEN c.lastReadAt IS NULL
                THEN (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND')
            WHEN (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND') IS NULL
                THEN c.lastReadAt
            WHEN c.lastReadAt > (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND')
                THEN c.lastReadAt
            ELSE (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND')
        END
        GROUP BY c.id
        """)
    List<Object[]> countUnreadByConversationIds(@Param("convIds") List<Long> convIds);

    @Query("""
        SELECT COUNT(m) FROM Message m JOIN m.conversation c
        WHERE c.id = :convId AND m.direction = 'INBOUND'
        AND m.createdAt > CASE
            WHEN c.lastReadAt IS NULL AND (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND') IS NULL
                THEN '1970-01-01T00:00:00Z'
            WHEN c.lastReadAt IS NULL
                THEN (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND')
            WHEN (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND') IS NULL
                THEN c.lastReadAt
            WHEN c.lastReadAt > (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND')
                THEN c.lastReadAt
            ELSE (SELECT MAX(m2.createdAt) FROM Message m2 WHERE m2.conversation = c AND m2.direction = 'OUTBOUND')
        END
        """)
    int countUnreadByConversationId(@Param("convId") Long convId);

    @Query("""
        SELECT COUNT(m) > 0 FROM Message m
        WHERE m.conversation = :conversation AND m.direction = 'INBOUND'
        AND EXISTS (SELECT 1 FROM Message m2 WHERE m2.conversation = :conversation AND m2.direction = 'OUTBOUND')
        """)
    boolean hasBothDirectionsMessages(@Param("conversation") Conversation conversation);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversation = :conversation AND m.direction = 'INBOUND'")
    long countInboundMessages(@Param("conversation") Conversation conversation);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversation = :conversation AND m.direction = 'OUTBOUND'")
    long countOutboundMessages(@Param("conversation") Conversation conversation);

    @Query("SELECT COUNT(DISTINCT c.discordUser.id) FROM Message m JOIN m.conversation c WHERE m.createdAt >= :start AND c.discordAccount.merchantId IS NULL")
    long countPlatformActiveCustomersSince(@Param("start") Instant start);

    @Query("SELECT MIN(m.discordMessageId) FROM Message m WHERE m.conversation = :conversation AND m.discordMessageId IS NOT NULL")
    Optional<String> findMinDiscordMessageIdByConversation(@Param("conversation") Conversation conversation);

    @Query("SELECT MAX(m.discordMessageId) FROM Message m WHERE m.conversation = :conversation AND m.discordMessageId IS NOT NULL")
    Optional<String> findMaxDiscordMessageIdByConversation(@Param("conversation") Conversation conversation);

    @Query("SELECT m.discordMessageId FROM Message m WHERE m.conversation.id = :convId AND m.discordMessageId IS NOT NULL")
    List<String> findDiscordMessageIdsByConversation(@Param("convId") Long convId);

    @Query("SELECT m FROM Message m WHERE m.direction = 'INBOUND' " +
            "AND (m.translatedContent IS NULL OR m.translatedContent = m.content) " +
            "AND m.messageType = 'text' AND m.content IS NOT NULL AND m.content != '' " +
            "AND m.createdAt > :since ORDER BY m.createdAt DESC")
    List<Message> findUntranslatedInboundMessages(@Param("since") Instant since, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :convId AND m.direction = 'INBOUND' " +
            "AND (m.translatedContent IS NULL OR m.translatedContent = m.content) " +
            "AND m.messageType = 'text' AND m.content IS NOT NULL AND m.content != '' " +
            "ORDER BY m.createdAt DESC")
    List<Message> findUntranslatedMessagesByConversation(@Param("convId") Long convId);

    /** 按商户+账号列表+客服列表+时间范围统计消息总数 */
    @Query("SELECT COUNT(m) FROM Message m JOIN m.conversation c WHERE c.merchantId = :merchantId " +
            "AND (:accountIds IS NULL OR c.discordAccount.id IN :accountIds) " +
            "AND (:agentIds IS NULL OR c.ownerAgentId IN :agentIds) " +
            "AND (:start IS NULL OR m.createdAt >= :start) " +
            "AND (:end IS NULL OR m.createdAt < :end)")
    long countByFilters(@Param("merchantId") Long merchantId,
                        @Param("accountIds") List<Long> accountIds,
                        @Param("agentIds") List<Long> agentIds,
                        @Param("start") Instant start,
                        @Param("end") Instant end);

    /** 按商户+账号列表+客服列表+时间范围统计每日各活跃客户数（当天发送消息>=3的客户数） */
    @Query("SELECT DATE(m.createdAt) as d, COUNT(DISTINCT c.discordUser.id) FROM Message m " +
            "JOIN m.conversation c WHERE c.merchantId = :merchantId " +
            "AND (:accountIds IS NULL OR c.discordAccount.id IN :accountIds) " +
            "AND (:agentIds IS NULL OR c.ownerAgentId IN :agentIds) " +
            "AND m.createdAt >= :start AND m.createdAt < :end " +
            "GROUP BY DATE(m.createdAt), c.discordUser.id " +
            "HAVING COUNT(m.id) >= 3 " +
            "ORDER BY d")
    List<Object[]> countDailyActiveCustomersRaw(@Param("merchantId") Long merchantId,
                                                  @Param("accountIds") List<Long> accountIds,
                                                  @Param("agentIds") List<Long> agentIds,
                                                  @Param("start") Instant start,
                                                  @Param("end") Instant end);

    /** 按商户+账号列表+客服列表+时间范围统计每日发送消息总数 */
    @Query("SELECT DATE(m.createdAt) as d, COUNT(m) FROM Message m JOIN m.conversation c " +
            "WHERE c.merchantId = :merchantId " +
            "AND (:accountIds IS NULL OR c.discordAccount.id IN :accountIds) " +
            "AND (:agentIds IS NULL OR c.ownerAgentId IN :agentIds) " +
            "AND m.createdAt >= :start AND m.createdAt < :end " +
            "GROUP BY DATE(m.createdAt) ORDER BY d")
    List<Object[]> countDailyMessagesByFilters(@Param("merchantId") Long merchantId,
                                                  @Param("accountIds") List<Long> accountIds,
                                                  @Param("agentIds") List<Long> agentIds,
                                                  @Param("start") Instant start,
                                                  @Param("end") Instant end);
}

