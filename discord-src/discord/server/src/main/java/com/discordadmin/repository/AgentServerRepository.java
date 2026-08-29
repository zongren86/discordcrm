package com.discordadmin.repository;

import com.discordadmin.entity.AgentServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentServerRepository extends JpaRepository<AgentServer, Long> {
    Optional<AgentServer> findByName(String name);
    Optional<AgentServer> findByToken(String token);
    List<AgentServer> findByMerchantId(Long merchantId);
    List<AgentServer> findByMerchantIdOrMerchantIdIsNull(Long merchantId);
    boolean existsByName(String name);
}
