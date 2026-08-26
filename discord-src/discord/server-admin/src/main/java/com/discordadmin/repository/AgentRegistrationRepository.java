package com.discordadmin.repository;

import com.discordadmin.entity.AgentRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface AgentRegistrationRepository extends JpaRepository<AgentRegistration, Long> {
    
    Optional<AgentRegistration> findByUserIdAndDeviceId(Long userId, String deviceId);
    
    List<AgentRegistration> findByUserId(Long userId);
    
    List<AgentRegistration> findByStatus(String status);
    
    List<AgentRegistration> findByUserIdAndStatus(Long userId, String status);
    
    long countByUserId(Long userId);
    
    void deleteByUserIdAndDeviceId(Long userId, String deviceId);
    
    List<AgentRegistration> findByLastHeartbeatAtBefore(Instant threshold);
    
    // 查找同一 userId+deviceId 的多条记录（用于清理重复数据）
    @Query("SELECT ar FROM AgentRegistration ar WHERE ar.userId = :userId AND ar.deviceId = :deviceId ORDER BY ar.id")
    List<AgentRegistration> findAllByUserIdAndDeviceIdOrdered(@Param("userId") Long userId, @Param("deviceId") String deviceId);
    
    // 统计同一 userId+deviceId 的记录数
    long countByUserIdAndDeviceId(Long userId, String deviceId);
}
