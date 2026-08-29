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
}
