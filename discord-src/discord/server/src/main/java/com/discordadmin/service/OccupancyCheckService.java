package com.discordadmin.service;

import com.discordadmin.entity.AutoAddTask;
import com.discordadmin.repository.AutoAddTaskRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OccupancyCheckService {
    
    private final AutoAddTaskRepository taskRepository;
    
    private static final List<AutoAddTask.TaskStatus> ACTIVE_STATUSES = Arrays.asList(
        AutoAddTask.TaskStatus.PENDING,
        AutoAddTask.TaskStatus.RUNNING,
        AutoAddTask.TaskStatus.PAUSED
    );
    
    public OccupancyCheckService(AutoAddTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }
    
    public boolean isServerOccupied(Long serverId) {
        return taskRepository.existsByServerIdAndStatusIn(serverId, ACTIVE_STATUSES);
    }
    
    public boolean isServerOccupied(Long serverId, Long excludeTaskId) {
        return taskRepository.existsByServerIdAndDiscordAccountIdAndStatusInAndIdNot(
            serverId, null, ACTIVE_STATUSES, excludeTaskId);
    }
    
    public boolean isDiscordAccountOccupied(Long discordAccountId) {
        return taskRepository.existsByDiscordAccountIdAndStatusIn(discordAccountId, ACTIVE_STATUSES);
    }
    
    public boolean canCreateTask(Long serverId, Long discordAccountId) {
        // 检查服务器是否被占用
        if (taskRepository.existsByServerIdAndStatusIn(serverId, ACTIVE_STATUSES)) {
            return false;
        }
        // 检查账号是否被占用
        if (taskRepository.existsByDiscordAccountIdAndStatusIn(discordAccountId, ACTIVE_STATUSES)) {
            return false;
        }
        return true;
    }
    
    public List<AutoAddTask> getActiveTasksByServer(Long serverId) {
        return taskRepository.findByServerIdAndStatusIn(serverId, ACTIVE_STATUSES);
    }
    
    public List<AutoAddTask> getActiveTasksByAccount(Long discordAccountId) {
        return taskRepository.findByDiscordAccountIdAndStatusIn(discordAccountId, ACTIVE_STATUSES);
    }
    
    /**
     * 获取所有被占用的Discord账号ID集合
     */
    public Set<Long> getOccupiedDiscordAccountIds() {
        List<AutoAddTask> activeTasks = taskRepository.findByStatusIn(ACTIVE_STATUSES);
        return activeTasks.stream()
            .map(AutoAddTask::getDiscordAccountId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }
    
    /**
     * 获取所有被占用的服务器ID集合
     */
    public Set<Long> getOccupiedServerIds() {
        List<AutoAddTask> activeTasks = taskRepository.findByStatusIn(ACTIVE_STATUSES);
        return activeTasks.stream()
            .map(AutoAddTask::getServerId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }
}
