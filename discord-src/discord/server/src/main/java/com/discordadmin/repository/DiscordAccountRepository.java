package com.discordadmin.repository;

import com.discordadmin.entity.DiscordAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiscordAccountRepository extends JpaRepository<DiscordAccount, Long> {
    Optional<DiscordAccount> findByBotToken(String botToken);
    Optional<DiscordAccount> findByDiscordBotId(String discordBotId);
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

    /** 带 JOIN FETCH agents 的查询 - 平台管理员按状态查询 */
    @Query("SELECT DISTINCT a FROM DiscordAccount a LEFT JOIN FETCH a.agents WHERE a.status = :status")
    List<DiscordAccount> findWithAgentsByStatus(@Param("status") DiscordAccount.AccountStatus status);

    /** 批量获取账号，避免 N+1 查询 */
    List<DiscordAccount> findByIdIn(List<Long> ids);
}
