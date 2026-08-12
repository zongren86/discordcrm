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

    /** 根据创建人ID查询 */
    List<DiscordAccountNumber> findByCreatorId(Long creatorId);

    /** 批量查询 */
    List<DiscordAccountNumber> findByIdIn(List<Long> ids);
}
