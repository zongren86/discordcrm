package com.discordadmin.repository;

import com.discordadmin.entity.AutoAddTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoAddTaskRepository extends JpaRepository<AutoAddTask, Long> {
    
    List<AutoAddTask> findByMerchantId(Long merchantId);
    
    List<AutoAddTask> findByUserId(String userId);
    
    List<AutoAddTask> findByStatus(AutoAddTask.TaskStatus status);
    
    List<AutoAddTask> findByMerchantIdAndUserId(Long merchantId, String userId);
    
    List<AutoAddTask> findByMerchantIdAndStatus(Long merchantId, AutoAddTask.TaskStatus status);
    
    List<AutoAddTask> findByUserIdAndStatus(String userId, AutoAddTask.TaskStatus status);
    
    Optional<AutoAddTask> findByServerIdAndDiscordAccountIdAndStatusIn(
        Long serverId, Long discordAccountId, List<AutoAddTask.TaskStatus> statuses);
    
    boolean existsByServerIdAndStatusIn(Long serverId, List<AutoAddTask.TaskStatus> statuses);
    
    boolean existsByDiscordAccountIdAndStatusIn(Long discordAccountId, List<AutoAddTask.TaskStatus> statuses);
    
    List<AutoAddTask> findByServerIdAndStatusIn(Long serverId, List<AutoAddTask.TaskStatus> statuses);
    
    List<AutoAddTask> findByDiscordAccountIdAndStatusIn(Long discordAccountId, List<AutoAddTask.TaskStatus> statuses);
    
    boolean existsByServerIdAndDiscordAccountIdAndStatusInAndIdNot(
        Long serverId, Long discordAccountId, List<AutoAddTask.TaskStatus> statuses, Long excludeId);
    
    long countByMerchantId(Long merchantId);
    
    long countByMerchantIdAndStatus(Long merchantId, AutoAddTask.TaskStatus status);
}
