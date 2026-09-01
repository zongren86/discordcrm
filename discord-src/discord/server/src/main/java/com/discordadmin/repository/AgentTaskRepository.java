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

    /** 带 scheduledAt 过滤：只取 scheduledAt <= now 或 scheduledAt IS NULL 的 */
    @org.springframework.data.jpa.repository.Query(
      "SELECT t FROM AgentTask t WHERE t.agentServer = :server AND t.status = :status " +
      "AND (t.scheduledAt IS NULL OR t.scheduledAt <= :now) " +
      "ORDER BY t.createdAt ASC LIMIT 1"
    )
    Optional<AgentTask> findFirstReady(@org.springframework.data.repository.query.Param("server") AgentServer server,
                                        @org.springframework.data.repository.query.Param("status") String status,
                                        @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    long countByDiscordAccountId(Long accountId);

    // 直接删子表记录（账号都删了，关联 task 也没用了）
    void deleteByDiscordAccountId(Long accountId);

    // 或者置 NULL（保留 task 记录，只解除关联）
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("UPDATE AgentTask t SET t.discordAccount = NULL WHERE t.discordAccount.id = :accountId")
    void detachAccountFromTasks(@org.springframework.data.repository.query.Param("accountId") Long accountId);
}
