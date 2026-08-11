package com.discordadmin.repository;

import com.discordadmin.entity.FetchProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FetchProgressRepository extends JpaRepository<FetchProgress, Long> {
    List<FetchProgress> findByGuildServerIdOrderByCreatedAtDesc(Long guildServerId);
    Optional<FetchProgress> findTopByGuildServerIdAndStatusOrderByCreatedAtDesc(Long guildServerId, String status);
    Optional<FetchProgress> findTopByGuildServerIdOrderByCreatedAtDesc(Long guildServerId);
    void deleteByGuildServerId(Long guildServerId);
}
