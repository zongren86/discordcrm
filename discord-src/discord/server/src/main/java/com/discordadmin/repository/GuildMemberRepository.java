package com.discordadmin.repository;

import com.discordadmin.entity.GuildMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
