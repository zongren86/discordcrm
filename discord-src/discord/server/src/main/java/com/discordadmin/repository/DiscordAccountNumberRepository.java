package com.discordadmin.repository;

import com.discordadmin.entity.DiscordAccountNumber;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DiscordAccountNumberRepository extends JpaRepository<DiscordAccountNumber, Long> {

    @Query("SELECT n FROM DiscordAccountNumber n WHERE " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%')))")
    Page<DiscordAccountNumber> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT n FROM DiscordAccountNumber n WHERE " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%'))) AND " +
           "n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> searchByKeywordAndTimeRange(@Param("keyword") String keyword,
                                                          @Param("startTime") Instant startTime,
                                                          @Param("endTime") Instant endTime,
                                                          Pageable pageable);

    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> findByTimeRange(@Param("startTime") Instant startTime,
                                               @Param("endTime") Instant endTime,
                                               Pageable pageable);

    /** 查询未绑定账号的编号 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.discordAccountId IS NULL")
    List<DiscordAccountNumber> findUnboundNumbers();

    /** 根据账号ID查询绑定的编号 */
    List<DiscordAccountNumber> findByDiscordAccountId(Long discordAccountId);

    /** 根据多个账号ID查询绑定的编号 */
    List<DiscordAccountNumber> findByDiscordAccountIdIn(java.util.Set<Long> discordAccountIds);

    /** 根据创建人ID查询 */
    List<DiscordAccountNumber> findByCreatorId(Long creatorId);

    /** 批量查询 */
    List<DiscordAccountNumber> findByIdIn(List<Long> ids);

    /** 仅在指定编号ID范围内分页查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.id IN :assignedIds")
    Page<DiscordAccountNumber> findByIdIn(@Param("assignedIds") List<Long> assignedIds, Pageable pageable);

    /** 仅在指定编号ID范围内+关键字分页查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.id IN :assignedIds AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%')))")
    Page<DiscordAccountNumber> searchByKeywordInIds(@Param("keyword") String keyword,
                                                    @Param("assignedIds") List<Long> assignedIds,
                                                    Pageable pageable);

    /** 仅在指定编号ID范围内+关键字+时间范围分页查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.id IN :assignedIds AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%'))) AND " +
           "n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> searchByKeywordAndTimeRangeInIds(@Param("keyword") String keyword,
                                                                 @Param("startTime") Instant startTime,
                                                                 @Param("endTime") Instant endTime,
                                                                 @Param("assignedIds") List<Long> assignedIds,
                                                                 Pageable pageable);

    /** 仅在指定编号ID范围内+时间范围分页查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.id IN :assignedIds AND n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> findByTimeRangeInIds(@Param("startTime") Instant startTime,
                                                     @Param("endTime") Instant endTime,
                                                     @Param("assignedIds") List<Long> assignedIds,
                                                     Pageable pageable);

    /** 按商户ID查询所有编号 */
    Page<DiscordAccountNumber> findByMerchantId(Long merchantId, Pageable pageable);

    /** 按商户ID+关键字查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%')))")
    Page<DiscordAccountNumber> searchByKeywordAndMerchantId(@Param("keyword") String keyword,
                                                              @Param("merchantId") Long merchantId,
                                                              Pageable pageable);

    /** 按商户ID+关键字+时间范围查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%'))) AND " +
           "n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> searchByKeywordAndTimeRangeAndMerchantId(@Param("keyword") String keyword,
                                                                         @Param("startTime") Instant startTime,
                                                                         @Param("endTime") Instant endTime,
                                                                         @Param("merchantId") Long merchantId,
                                                                         Pageable pageable);

    /** 按商户ID+时间范围查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> findByTimeRangeAndMerchantId(@Param("startTime") Instant startTime,
                                                               @Param("endTime") Instant endTime,
                                                               @Param("merchantId") Long merchantId,
                                                               Pageable pageable);

    /** 按商户ID+编号ID范围查询（普通用户使用） */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND n.id IN :assignedIds")
    Page<DiscordAccountNumber> findByMerchantIdAndIdIn(@Param("merchantId") Long merchantId,
                                                        @Param("assignedIds") List<Long> assignedIds,
                                                        Pageable pageable);

    /** 按商户ID+编号ID范围+关键字查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND n.id IN :assignedIds AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%')))")
    Page<DiscordAccountNumber> searchByKeywordAndMerchantIdAndIdIn(@Param("keyword") String keyword,
                                                                     @Param("merchantId") Long merchantId,
                                                                     @Param("assignedIds") List<Long> assignedIds,
                                                                     Pageable pageable);

    /** 按商户ID+编号ID范围+关键字+时间范围查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND n.id IN :assignedIds AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "(CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%') OR CAST(n.customNo AS string) LIKE CONCAT('%', :keyword, '%'))) AND " +
           "n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> searchByKeywordAndTimeRangeAndMerchantIdAndIdIn(@Param("keyword") String keyword,
                                                                                @Param("startTime") Instant startTime,
                                                                                @Param("endTime") Instant endTime,
                                                                                @Param("merchantId") Long merchantId,
                                                                                @Param("assignedIds") List<Long> assignedIds,
                                                                                Pageable pageable);

    /** 按商户ID+编号ID范围+时间范围查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId AND n.id IN :assignedIds AND n.createdAt BETWEEN :startTime AND :endTime")
    Page<DiscordAccountNumber> findByTimeRangeAndMerchantIdAndIdIn(@Param("startTime") Instant startTime,
                                                                      @Param("endTime") Instant endTime,
                                                                      @Param("merchantId") Long merchantId,
                                                                      @Param("assignedIds") List<Long> assignedIds,
                                                                      Pageable pageable);

    /** 查询指定商户最大的自定义编号 */
    @Query("SELECT MAX(n.customNo) FROM DiscordAccountNumber n WHERE n.merchantId = :merchantId")
    Integer findMaxCustomNoByMerchantId(@Param("merchantId") Long merchantId);

    /** 按商户和自定义编号查询 */
    DiscordAccountNumber findByMerchantIdAndCustomNo(Long merchantId, Integer customNo);

    /** 按商户和自定义编号查询（支持批量） */
    List<DiscordAccountNumber> findByMerchantIdAndCustomNoIn(Long merchantId, List<Integer> customNos);

        // 解绑：清除账号关联但保留编号数据
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "UPDATE DiscordAccountNumber n SET n.discordAccount = NULL WHERE n.discordAccount.id = :accountId")
    void detachFromDiscordAccount(@org.springframework.data.repository.query.Param("accountId") Long accountId);
}