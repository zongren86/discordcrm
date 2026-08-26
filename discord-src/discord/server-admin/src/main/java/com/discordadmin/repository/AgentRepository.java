package com.discordadmin.repository;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByUsername(String username);
    List<Agent> findAllByUsername(String username);
    List<Agent> findByMerchantId(Long merchantId);
    List<Agent> findByMerchantIdAndEnabledTrue(Long merchantId);
    List<Agent> findByDiscordAccountsContaining(DiscordAccount account);
    List<Agent> findByAccountType(Integer accountType);

    /**
     * 批量查找与指定Discord账号关联的所有Agent
     * 通过 agent_discord_accounts 中间表直接查询
     */
    @Query("SELECT a FROM Agent a JOIN a.discordAccounts da WHERE da.id IN :accountIds AND a.accountType = 1")
    List<Agent> findAgentsByDiscordAccountIds(@Param("accountIds") Set<Long> accountIds);

    /**
     * 查找与指定Discord账号关联的所有Agent（包含管理员）
     */
    @Query("SELECT a FROM Agent a JOIN a.discordAccounts da WHERE da.id IN :accountIds")
    List<Agent> findAllAgentsByDiscordAccountIds(@Param("accountIds") Set<Long> accountIds);
}