package com.discordadmin.repository;

import com.discordadmin.entity.FetchProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FetchProgressRepository extends JpaRepository<FetchProgress, Long> {
    List<FetchProgress> findByGuildServerIdOrderByCreatedAtDesc(Long guildServerId);
    Optional<FetchProgress> findTopByGuildServerIdAndStatusOrderByCreatedAtDesc(Long guildServerId, String status);
    Optional<FetchProgress> findTopByGuildServerIdOrderByCreatedAtDesc(Long guildServerId);

    /**
     * 按 guildServerId 删除抓取进度。
     * 使用 @Modifying + @Query 直接执行批量 DELETE，避免 Spring Data 默认派生 deleteBy*
     * 先走 findAll 再逐条 delete(id) 时，因记录已被先删除（或不存在）抛出
     * "Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1"。
     */
    @Modifying
    @Query("DELETE FROM FetchProgress fp WHERE fp.guildServerId = :guildServerId")
    void deleteByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 按 discordAccountId 删除抓取进度（当删除 Discord 账号时使用）。
     * 同样使用批量 DELETE，避免孤儿/已删除记录导致的 row count mismatch 异常。
     */
    @Modifying
    @Query("DELETE FROM FetchProgress fp WHERE fp.discordAccountId = :discordAccountId")
    void deleteByDiscordAccountId(@Param("discordAccountId") Long discordAccountId);
}
