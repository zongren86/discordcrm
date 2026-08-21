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
           "CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    Page<DiscordAccountNumber> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT n FROM DiscordAccountNumber n WHERE " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%')) AND " +
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
           "CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%'))")
    Page<DiscordAccountNumber> searchByKeywordInIds(@Param("keyword") String keyword,
                                                    @Param("assignedIds") List<Long> assignedIds,
                                                    Pageable pageable);

    /** 仅在指定编号ID范围内+关键字+时间范围分页查询 */
    @Query("SELECT n FROM DiscordAccountNumber n WHERE n.id IN :assignedIds AND " +
           "(LOWER(n.boundAccount) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(n.id AS string) LIKE CONCAT('%', :keyword, '%')) AND " +
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
}
