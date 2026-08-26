package com.discordadmin.service;

import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AutoAddTaskService {
    
    private final AutoAddTaskRepository taskRepository;
    private final AutoAddTaskItemRepository itemRepository;
    private final GuildServerRepository serverRepository;
    private final GuildMemberRepository memberRepository;
    private final OccupancyCheckService occupancyCheckService;
    private final CloudWebSocketService webSocketService;
    
    public AutoAddTaskService(AutoAddTaskRepository taskRepository,
                              AutoAddTaskItemRepository itemRepository,
                              GuildServerRepository serverRepository,
                              GuildMemberRepository memberRepository,
                              OccupancyCheckService occupancyCheckService,
                              CloudWebSocketService webSocketService) {
        this.taskRepository = taskRepository;
        this.itemRepository = itemRepository;
        this.serverRepository = serverRepository;
        this.memberRepository = memberRepository;
        this.occupancyCheckService = occupancyCheckService;
        this.webSocketService = webSocketService;
    }
    
    @Transactional
    public AutoAddTask createTask(Long merchantId, Long userId, Long serverId, 
                                   Long discordAccountId, Integer pauseDurationSeconds,
                                   Integer delayMinSeconds, Integer delayMaxSeconds) {
        
        // 检查占用情况
        if (!occupancyCheckService.canCreateTask(serverId, discordAccountId)) {
            throw new RuntimeException("该服务器或账号已被其他进行中的任务占用");
        }
        
        // 获取服务器成员列表作为好友号池
        List<GuildMember> members = memberRepository.findByGuildServerId(serverId);
        List<String> friendUserIds = members.stream()
            .map(GuildMember::getUserId)
            .collect(Collectors.toList());
        
        // 创建任务
        AutoAddTask task = new AutoAddTask();
        task.setMerchantId(merchantId);
        task.setUserId(String.valueOf(userId));
        task.setServerId(serverId);
        task.setDiscordAccountId(discordAccountId);
        task.setStatus(AutoAddTask.TaskStatus.PENDING);
        task.setTargetCount(friendUserIds.size());
        task.setPauseDurationSeconds(pauseDurationSeconds != null ? pauseDurationSeconds : 900);
        task.setDelayMinSeconds(delayMinSeconds != null ? delayMinSeconds : 60);
        task.setDelayMaxSeconds(delayMaxSeconds != null ? delayMaxSeconds : 300);
        
        task = taskRepository.save(task);
        
        // 创建任务明细
        List<AutoAddTaskItem> items = new ArrayList<>();
        for (String userIdStr : friendUserIds) {
            AutoAddTaskItem item = new AutoAddTaskItem();
            item.setTaskId(task.getId());
            item.setDiscordUserId(userIdStr);
            // 从 GuildMember 获取用户名
            members.stream()
                .filter(m -> userIdStr.equals(m.getUserId()))
                .findFirst()
                .ifPresent(m -> item.setUsername(m.getGlobalName() != null ? m.getGlobalName() : m.getUsername()));
            item.setStatus(AutoAddTaskItem.ItemStatus.PENDING);
            items.add(item);
        }
        itemRepository.saveAll(items);
        
        log.info("创建加好友任务: id={}, 服务器={}, 目标好友数={}", 
            task.getId(), serverId, friendUserIds.size());
        
        return task;
    }
    
    @Transactional
    public AutoAddTask startTask(Long taskId) {
        AutoAddTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() != AutoAddTask.TaskStatus.PENDING) {
            throw new RuntimeException("任务状态不允许启动");
        }
        
        // 检查是否被占用
        if (!occupancyCheckService.canCreateTask(task.getServerId(), task.getDiscordAccountId())) {
            throw new RuntimeException("该服务器或账号已被其他任务占用");
        }
        
        // 查找在线的 Agent
        List<AgentRegistration> agents = webSocketService.getOnlineAgentsByUserId(Long.parseLong(task.getUserId()));
        if (agents.isEmpty()) {
            throw new RuntimeException("未找到在线的本地 Agent，请先在本地启动 Agent 服务");
        }
        
        task.setStatus(AutoAddTask.TaskStatus.RUNNING);
        task.setAssignedAgentId(agents.get(0).getDeviceId());
        task.setStartedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        task = taskRepository.save(task);
        
        // 通知 Agent 开始执行
        webSocketService.notifyAgent(agents.get(0).getDeviceId(), Map.of(
            "type", "START_TASK",
            "taskId", task.getId(),
            "serverId", task.getServerId(),
            "discordAccountId", task.getDiscordAccountId()
        ));
        
        log.info("启动加好友任务: id={}, agent={}", taskId, agents.get(0).getDeviceId());
        
        return task;
    }
    
    @Transactional
    public AutoAddTask pauseTask(Long taskId) {
        AutoAddTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() != AutoAddTask.TaskStatus.RUNNING) {
            throw new RuntimeException("只有运行中的任务可以暂停");
        }
        
        task.setStatus(AutoAddTask.TaskStatus.PAUSED);
        task.setUpdatedAt(Instant.now());
        task = taskRepository.save(task);
        
        // 通知 Agent 暂停
        if (task.getAssignedAgentId() != null) {
            webSocketService.notifyAgent(task.getAssignedAgentId(), Map.of(
                "type", "PAUSE_TASK",
                "taskId", taskId
            ));
        }
        
        return task;
    }
    
    @Transactional
    public AutoAddTask stopTask(Long taskId) {
        AutoAddTask task = taskRepository.findById(taskId)
            .orElseThrow(() -> new RuntimeException("任务不存在"));
        
        if (task.getStatus() == AutoAddTask.TaskStatus.COMPLETED 
            || task.getStatus() == AutoAddTask.TaskStatus.FAILED
            || task.getStatus() == AutoAddTask.TaskStatus.CANCELLED) {
            return task;
        }
        
        task.setStatus(AutoAddTask.TaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        task = taskRepository.save(task);
        
        // 通知 Agent 停止
        if (task.getAssignedAgentId() != null) {
            webSocketService.notifyAgent(task.getAssignedAgentId(), Map.of(
                "type", "STOP_TASK",
                "taskId", taskId
            ));
        }
        
        return task;
    }
    
    @Transactional
    public void updateTaskItemResult(Long taskId, String discordUserId, 
                                      AutoAddTaskItem.ItemStatus status, String result) {
        AutoAddTaskItem item = itemRepository.findByTaskIdAndDiscordUserId(taskId, discordUserId)
            .orElseThrow(() -> new RuntimeException("任务明细不存在"));
        
        item.setStatus(status);
        item.setLastResult(result);
        item.setCompletedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        itemRepository.save(item);
        
        // 更新任务统计
        AutoAddTask task = taskRepository.findById(taskId).orElseThrow();
        long successCount = itemRepository.countByTaskIdAndStatus(taskId, AutoAddTaskItem.ItemStatus.SUCCESS);
        long failCount = itemRepository.countByTaskIdAndStatus(taskId, AutoAddTaskItem.ItemStatus.FAILED);
        task.setSuccessCount((int) successCount);
        task.setFailCount((int) failCount);
        task.setUpdatedAt(Instant.now());
        
        // 检查是否完成
        long totalProcessed = successCount + failCount;
        long totalItems = itemRepository.countByTaskId(taskId);
        if (totalProcessed >= totalItems) {
            task.setStatus(AutoAddTask.TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
        }
        
        taskRepository.save(task);
    }
    
    public List<AutoAddTask> getTasksByMerchant(Long merchantId) {
        return taskRepository.findByMerchantIdAndStatus(merchantId, null)
            .stream()
            .filter(t -> t.getStatus() != null)
            .collect(Collectors.toList());
    }
    
    public List<AutoAddTask> getTasksByUser(String userId) {
        return taskRepository.findByUserId(userId);
    }
    
    public Optional<AutoAddTask> getTaskById(Long taskId) {
        return taskRepository.findById(taskId);
    }
    
    public List<AutoAddTaskItem> getTaskItems(Long taskId) {
        return itemRepository.findByTaskId(taskId);
    }
    
    @Scheduled(fixedDelay = 60000) // 每分钟检查
    @Transactional
    public void processPendingTasks() {
        List<AutoAddTask> pendingTasks = taskRepository.findByStatus(AutoAddTask.TaskStatus.PENDING);
        
        for (AutoAddTask task : pendingTasks) {
            try {
                // 查找在线 Agent
                List<AgentRegistration> agents = webSocketService.getOnlineAgentsByUserId(Long.parseLong(task.getUserId()));
                if (!agents.isEmpty()) {
                    startTask(task.getId());
                }
            } catch (Exception e) {
                log.error("处理待执行任务失败: taskId={}", task.getId(), e);
            }
        }
    }
    
    @Scheduled(fixedDelay = 300000) // 每5分钟检查
    public void checkTaskTimeout() {
        List<AutoAddTask> runningTasks = taskRepository.findByStatus(AutoAddTask.TaskStatus.RUNNING);
        Instant timeoutThreshold = Instant.now().minusSeconds(3600); // 1小时超时
        
        for (AutoAddTask task : runningTasks) {
            if (task.getUpdatedAt() != null && task.getUpdatedAt().isBefore(timeoutThreshold)) {
                // 检查 Agent 是否在线
                boolean agentOnline = webSocketService.isAgentOnline(task.getAssignedAgentId());
                if (!agentOnline) {
                    log.warn("任务超时，Agent 离线: taskId={}", task.getId());
                    task.setStatus(AutoAddTask.TaskStatus.FAILED);
                    task.setCompletedAt(Instant.now());
                    task.setUpdatedAt(Instant.now());
                    taskRepository.save(task);
                }
            }
        }
    }
}
