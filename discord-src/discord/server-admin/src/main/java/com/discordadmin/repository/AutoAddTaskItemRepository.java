package com.discordadmin.repository;

import com.discordadmin.entity.AutoAddTaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutoAddTaskItemRepository extends JpaRepository<AutoAddTaskItem, Long> {
    
    List<AutoAddTaskItem> findByTaskId(Long taskId);
    
    List<AutoAddTaskItem> findByTaskIdAndStatus(Long taskId, AutoAddTaskItem.ItemStatus status);
    
    Optional<AutoAddTaskItem> findByTaskIdAndDiscordUserId(Long taskId, String discordUserId);
    
    long countByTaskId(Long taskId);
    
    long countByTaskIdAndStatus(Long taskId, AutoAddTaskItem.ItemStatus status);
    
    void deleteByTaskId(Long taskId);
}
