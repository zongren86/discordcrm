package com.discordadmin.repository;

import com.discordadmin.entity.AgentServer;
import com.discordadmin.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    List<AgentTask> findByAgentServerAndStatusOrderByCreatedAtAsc(AgentServer agentServer, String status);
    Optional<AgentTask> findFirstByAgentServerAndStatusOrderByCreatedAtAsc(AgentServer agentServer, String status);

    long countByDiscordAccountId(Long accountId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE AgentTask t SET t.discordAccount = NULL WHERE t.discordAccount.id = :accountId")
    void detachAccountFromTasks(@org.springframework.data.repository.query.Param("accountId") Long accountId);
}
