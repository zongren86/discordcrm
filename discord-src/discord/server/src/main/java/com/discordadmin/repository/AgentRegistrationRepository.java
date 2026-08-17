package com.discordadmin.repository;

import com.discordadmin.entity.AgentRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRegistrationRepository extends JpaRepository<AgentRegistration, Long> {
    
    Optional<AgentRegistration> findByUserIdAndDeviceId(String userId, String deviceId);
    
    List<AgentRegistration> findByUserId(String userId);
    
    List<AgentRegistration> findByStatus(String status);
    
    List<AgentRegistration> findByUserIdAndStatus(String userId, String status);
    
    long countByUserId(String userId);
    
    void deleteByUserIdAndDeviceId(String userId, String deviceId);
    
    List<AgentRegistration> findByLastHeartbeatAtBefore(Instant threshold);
}
