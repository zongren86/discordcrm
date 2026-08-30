package com.discordadmin.repository;

import com.discordadmin.entity.DiscordAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DiscordAccountRepository extends JpaRepository<DiscordAccount, Long> {
    Optional<DiscordAccount> findByToken(String token);
    Optional<DiscordAccount> findByDiscordId(String discordId);
    Optional<DiscordAccount> findByName(String name);
    List<DiscordAccount> findByStatus(DiscordAccount.AccountStatus status);
    List<DiscordAccount> findByMerchantId(Long merchantId);
    List<DiscordAccount> findByAccountType(DiscordAccount.AccountType accountType);

    List<DiscordAccount> findByMerchantIdIsNull();

    @Query("SELECT COUNT(a) FROM DiscordAccount a WHERE a.merchantId IS NULL")
    long countByMerchantIdIsNull();

    /** 按商户统计账号数 */
    @Query("SELECT COUNT(a) FROM DiscordAccount a WHERE a.merchantId = :merchantId")
    long countByMerchantId(@Param("merchantId") Long merchantId);

    List<DiscordAccount> findByMerchantIdIsNullAndStatus(DiscordAccount.AccountStatus status);

    /** 带 JOIN FETCH agents 的查询 - 用于列表展示 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE " +
           "(a.merchantId = :merchantId OR a.merchantId IS NULL) AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsByMerchantOrNullAndKeyword(@Param("merchantId") Long merchantId,
                                                                   @Param("keyword") String keyword);

    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE " +
           "(a.merchantId = :merchantId OR a.merchantId IS NULL) AND a.status = :status AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsByMerchantOrNullAndKeywordAndStatus(@Param("merchantId") Long merchantId,
                                                                             @Param("keyword") String keyword,
                                                                             @Param("status") DiscordAccount.AccountStatus status);

    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId = :merchantId OR a.merchantId IS NULL")
    List<DiscordAccount> findWithAgentsByMerchantIdOrNull(@Param("merchantId") Long merchantId);

    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE " +
           "(a.merchantId = :merchantId OR a.merchantId IS NULL) AND a.status = :status")
    List<DiscordAccount> findWithAgentsByMerchantIdOrNullAndStatus(@Param("merchantId") Long merchantId,
                                                                    @Param("status") DiscordAccount.AccountStatus status);

    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsAllByKeyword(@Param("keyword") String keyword);

    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.status = :status AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsAllByKeywordAndStatus(@Param("keyword") String keyword,
                                                               @Param("status") DiscordAccount.AccountStatus status);

    @Query("SELECT a FROM DiscordAccount a WHERE " +
           "(a.merchantId = :merchantId OR a.merchantId IS NULL) AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchByMerchantOrNullAndKeyword(@Param("merchantId") Long merchantId,
                                                           @Param("keyword") String keyword);

    @Query("SELECT a FROM DiscordAccount a WHERE " +
           "(a.merchantId = :merchantId OR a.merchantId IS NULL) AND a.status = :status AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchByMerchantOrNullAndKeywordAndStatus(@Param("merchantId") Long merchantId,
                                                                    @Param("keyword") String keyword,
                                                                    @Param("status") DiscordAccount.AccountStatus status);

    @Query("SELECT a FROM DiscordAccount a WHERE a.merchantId = :merchantId OR a.merchantId IS NULL")
    List<DiscordAccount> findByMerchantIdOrNull(@Param("merchantId") Long merchantId);

    @Query("SELECT a FROM DiscordAccount a WHERE " +
           "(a.merchantId = :merchantId OR a.merchantId IS NULL) AND a.status = :status")
    List<DiscordAccount> findByMerchantIdOrNullAndStatus(@Param("merchantId") Long merchantId,
                                                          @Param("status") DiscordAccount.AccountStatus status);

    @Query("SELECT a FROM DiscordAccount a WHERE " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchAllByKeyword(@Param("keyword") String keyword);

    @Query("SELECT a FROM DiscordAccount a WHERE a.status = :status AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchAllByKeywordAndStatus(@Param("keyword") String keyword,
                                                     @Param("status") DiscordAccount.AccountStatus status);

    @Query("SELECT a FROM DiscordAccount a WHERE a.status = :status")
    List<DiscordAccount> findAllByStatus(@Param("status") DiscordAccount.AccountStatus status);

    /** 带 JOIN FETCH agents 的查询 - 平台管理员无筛选查询 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents")
    List<DiscordAccount> findAllWithAgents();

    /** 带 JOIN FETCH agents 的查询 - 按ID批量查询 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.id IN :ids")
    List<DiscordAccount> findAllWithAgentsByIdIn(@Param("ids") Set<Long> ids);

    /** 带 JOIN FETCH agents 的查询 - 平台管理员按状态查询 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.status = :status")
    List<DiscordAccount> findWithAgentsByStatus(@Param("status") DiscordAccount.AccountStatus status);

    /** 批量获取账号，避免 N+1 查询 */
    List<DiscordAccount> findByIdIn(List<Long> ids);

    /** 按商户ID查询（不包含 null merchantId）- 替代 OR 条件 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId = :merchantId")
    List<DiscordAccount> findWithAgentsByMerchantId(@Param("merchantId") Long merchantId);

    /** 查询 null merchantId 的账号 - 替代 OR 条件 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId IS NULL")
    List<DiscordAccount> findWithAgentsByNullMerchantId();

    /** 按商户ID+状态查询（不包含 null merchantId） */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId = :merchantId AND a.status = :status")
    List<DiscordAccount> findWithAgentsByMerchantIdAndStatus(@Param("merchantId") Long merchantId,
                                                              @Param("status") DiscordAccount.AccountStatus status);

    /** null merchantId + 状态查询 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId IS NULL AND a.status = :status")
    List<DiscordAccount> findWithAgentsByNullMerchantIdAndStatus(@Param("status") DiscordAccount.AccountStatus status);

    /** 按商户ID+关键词搜索（不包含 null merchantId） */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId = :merchantId AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsByMerchantId(@Param("merchantId") Long merchantId,
                                                       @Param("keyword") String keyword);

    /** null merchantId + 关键词搜索 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId IS NULL AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsByNullMerchantId(@Param("keyword") String keyword);

    /** 按商户ID+关键词+状态搜索（不包含 null merchantId） */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId = :merchantId AND a.status = :status AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsByMerchantIdAndKeywordAndStatus(@Param("merchantId") Long merchantId,
                                                                          @Param("keyword") String keyword,
                                                                          @Param("status") DiscordAccount.AccountStatus status);

    /** null merchantId + 关键词 + 状态搜索 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.merchantId IS NULL AND a.status = :status AND " +
           "(LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(a.remark) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    List<DiscordAccount> searchWithAgentsByNullMerchantIdAndKeywordAndStatus(@Param("keyword") String keyword,
                                                                             @Param("status") DiscordAccount.AccountStatus status);

    /** 按 ID 列表批量统计好友数 */
    @Query("SELECT a.id, COUNT(f) FROM DiscordAccount a LEFT JOIN Friend f ON f.discordAccount = a WHERE a.id IN :ids GROUP BY a.id")
    List<Object[]> countFriendsByAccountIds(@Param("ids") List<Long> ids);

    /** 按 ID 列表批量统计会话数 */
    @Query("SELECT a.id, COUNT(c) FROM DiscordAccount a LEFT JOIN Conversation c ON c.discordAccount = a WHERE a.id IN :ids GROUP BY a.id")
    List<Object[]> countConversationsByAccountIds(@Param("ids") List<Long> ids);

    /** 按 ID 列表批量统计消息数 */
    @Query("SELECT a.id, COUNT(m) FROM DiscordAccount a LEFT JOIN Conversation c ON c.discordAccount = a LEFT JOIN Message m ON m.conversation = c WHERE a.id IN :ids GROUP BY a.id")
    List<Object[]> countMessagesByAccountIds(@Param("ids") List<Long> ids);

    /** Token 定时体检专用：查 ACTIVE + USER + token_valid=true 的账号 */
    @Query("SELECT a FROM DiscordAccount a WHERE a.status = 'ACTIVE' " +
           "AND a.accountType = 'USER' AND a.token IS NOT NULL AND a.token <> '' " +
           "AND (a.tokenValid = true OR a.tokenValid IS NULL)")
    /** agent 拉自己负责的 AGENT 采集账号 */
    List<DiscordAccount> findByAgentServerIdAndSourceAndStatus(Long agentServerId, String source, DiscordAccount.AccountStatus status);

    List<DiscordAccount> findForTokenHealthCheck();
}
