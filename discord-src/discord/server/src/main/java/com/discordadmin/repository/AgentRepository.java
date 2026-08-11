package com.discordadmin.repository;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByUsername(String username);
    List<Agent> findByMerchantId(Long merchantId);
    List<Agent> findByMerchantIdAndEnabledTrue(Long merchantId);
    List<Agent> findByDiscordAccountsContaining(DiscordAccount account);
}