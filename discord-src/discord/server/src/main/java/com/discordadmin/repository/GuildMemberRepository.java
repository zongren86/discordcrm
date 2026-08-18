package com.discordadmin.repository;

import com.discordadmin.entity.GuildMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuildMemberRepository extends JpaRepository<GuildMember, Long> {
    List<GuildMember> findByGuildServerId(Long guildServerId);

    long countByGuildServerId(Long guildServerId);

    Optional<GuildMember> findByGuildServerIdAndUserId(Long guildServerId, String userId);

    void deleteByGuildServerId(Long guildServerId);

    List<GuildMember> findByGuildServerIdAndUserIdIn(Long guildServerId, List<String> userIds);

    Page<GuildMember> findByGuildServerId(Long guildServerId, Pageable pageable);

    // ========== 好友池相关查询 ==========

    /**
     * 按服务器ID和好友状态查询
     */
    List<GuildMember> findByGuildServerIdAndFriendStatus(Long guildServerId, Integer friendStatus);

    /**
     * 按服务器ID和好友状态分页查询
     */
    Page<GuildMember> findByGuildServerIdAndFriendStatus(Long guildServerId, Integer friendStatus, Pageable pageable);

    /**
     * 按服务器ID统计各好友状态的数量
     */
    long countByGuildServerIdAndFriendStatus(Long guildServerId, Integer friendStatus);

    /**
     * 按服务器ID统计总数（包括null的视为待添加）
     */
    @Query("SELECT COUNT(m) FROM GuildMember m WHERE m.guildServerId = :guildServerId")
    long countWithFriendStatusByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 分页查询好友池中的所有成员
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId")
    Page<GuildMember> findFriendPoolByGuildServerId(@Param("guildServerId") Long guildServerId, Pageable pageable);

    /**
     * 查询待添加的成员（friendStatus=0或null）
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND (m.friendStatus = 0 OR m.friendStatus IS NULL)")
    List<GuildMember> findPendingByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 统计待添加的成员数量（friendStatus=0或null）
     */
    @Query("SELECT COUNT(m) FROM GuildMember m WHERE m.guildServerId = :guildServerId AND (m.friendStatus = 0 OR m.friendStatus IS NULL)")
    long countPendingByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 查询好友池中待添加的成员（分页，包括null状态的）
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND (m.friendStatus = 0 OR m.friendStatus IS NULL)")
    Page<GuildMember> findPendingFriendPoolByGuildServerId(@Param("guildServerId") Long guildServerId, Pageable pageable);

    /**
     * 查询好友池中已分配的成员
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 1")
    List<GuildMember> findAssignedByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 查询好友池中已分配的成员（分页）
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 1")
    Page<GuildMember> findAssignedFriendPoolByGuildServerId(@Param("guildServerId") Long guildServerId, Pageable pageable);

    /**
     * 查询好友池中添加成功的成员
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 2")
    List<GuildMember> findSuccessByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 查询好友池中添加成功的成员（分页）
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 2")
    Page<GuildMember> findSuccessFriendPoolByGuildServerId(@Param("guildServerId") Long guildServerId, Pageable pageable);

    /**
     * 查询好友池中添加失败的成员
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 3")
    List<GuildMember> findFailedByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 查询好友池中添加失败的成员（分页）
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 3")
    Page<GuildMember> findFailedFriendPoolByGuildServerId(@Param("guildServerId") Long guildServerId, Pageable pageable);

    /**
     * 获取一个待添加的成员用于分配
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND m.friendStatus = 0 ORDER BY m.id ASC LIMIT 1")
    Optional<GuildMember> findOnePendingByGuildServerId(@Param("guildServerId") Long guildServerId);

    /**
     * 更新成员的好友状态
     */
    @Modifying
    @Query("UPDATE GuildMember m SET m.friendStatus = :friendStatus, m.updatedAt = CURRENT_TIMESTAMP WHERE m.id = :id")
    int updateFriendStatus(@Param("id") Long id, @Param("friendStatus") Integer friendStatus);

    /**
     * 更新成员的分配信息
     */
    @Modifying
    @Query("UPDATE GuildMember m SET m.friendStatus = 1, m.discordAccountId = :discordAccountId, m.assignedTaskId = :assignedTaskId, m.updatedAt = CURRENT_TIMESTAMP WHERE m.id = :id")
    int assignMember(@Param("id") Long id, @Param("discordAccountId") Long discordAccountId, @Param("assignedTaskId") Long assignedTaskId);

    /**
     * 更新成员的添加结果
     */
    @Modifying
    @Query("UPDATE GuildMember m SET m.friendStatus = :friendStatus, m.lastError = :lastError, m.updatedAt = CURRENT_TIMESTAMP WHERE m.id = :id")
    int updateAddResult(@Param("id") Long id, @Param("friendStatus") Integer friendStatus, @Param("lastError") String lastError);

    /**
     * 搜索功能
     */
    @Query("SELECT m FROM GuildMember m WHERE m.guildServerId = :guildServerId AND " +
           "(LOWER(m.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.globalName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.nick) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.userId) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<GuildMember> searchByGuildServerId(@Param("guildServerId") Long guildServerId,
                                            @Param("keyword") String keyword,
                                            Pageable pageable);

    @Query("SELECT COUNT(m) FROM GuildMember m WHERE m.guildServerId = :guildServerId AND " +
           "(LOWER(m.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.globalName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.nick) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.userId) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    long countByGuildServerIdAndKeyword(@Param("guildServerId") Long guildServerId,
                                        @Param("keyword") String keyword);
}
