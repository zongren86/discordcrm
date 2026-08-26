package com.discordadmin.controller;

import com.discordadmin.dto.FriendDtos;
import com.discordadmin.entity.AutoAddTask;
import com.discordadmin.entity.AutoAddTaskItem;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.GuildMemberRepository;
import com.discordadmin.repository.GuildServerRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.AutoAddTaskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auto-add-tasks")
public class AutoAddTaskController {

    private final AutoAddTaskService taskService;
    private final GuildServerRepository serverRepository;
    private final GuildMemberRepository memberRepository;

    public AutoAddTaskController(AutoAddTaskService taskService,
                                 GuildServerRepository serverRepository,
                                 GuildMemberRepository memberRepository) {
        this.taskService = taskService;
        this.serverRepository = serverRepository;
        this.memberRepository = memberRepository;
    }

    /** 创建自动添加好友任务 */
    @PostMapping
    public Map<String, Object> createTask(@RequestBody CreateTaskRequest request) {
        Long merchantId = SecurityUtils.currentMerchantId();
        Long userId = SecurityUtils.currentUserId();
        
        AutoAddTask task = taskService.createTask(
            merchantId,
            userId,
            request.serverId(),
            request.discordAccountId(),
            request.pauseDurationSeconds(),
            request.delayMinSeconds(),
            request.delayMaxSeconds()
        );
        
        return Map.of("taskId", task.getId(), "status", task.getStatus().name());
    }

    /** 获取商户的所有任务 */
    @GetMapping
    public List<AutoAddTask> listTasks() {
        Long merchantId = SecurityUtils.currentMerchantId();
        return taskService.getTasksByMerchant(merchantId);
    }

    /** 获取指定任务详情 */
    @GetMapping("/{taskId}")
    public AutoAddTask getTask(@PathVariable Long taskId) {
        return taskService.getTaskById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }

    /** 获取任务明细 */
    @GetMapping("/{taskId}/items")
    public List<AutoAddTaskItem> getTaskItems(@PathVariable Long taskId) {
        return taskService.getTaskItems(taskId);
    }

    /** 启动任务 */
    @PostMapping("/{taskId}/start")
    public AutoAddTask startTask(@PathVariable Long taskId) {
        return taskService.startTask(taskId);
    }

    /** 暂停任务 */
    @PostMapping("/{taskId}/pause")
    public AutoAddTask pauseTask(@PathVariable Long taskId) {
        return taskService.pauseTask(taskId);
    }

    /** 停止任务 */
    @PostMapping("/{taskId}/stop")
    public AutoAddTask stopTask(@PathVariable Long taskId) {
        return taskService.stopTask(taskId);
    }

    /** 获取服务器成员作为好友号池 */
    @GetMapping("/server/{serverId}/members")
    public List<Map<String, String>> getServerMembers(@PathVariable Long serverId) {
        GuildServer server = serverRepository.findById(serverId)
            .orElseThrow(() -> new IllegalArgumentException("服务器不存在"));
        SecurityUtils.checkMerchantAccess(server.getMerchantId());
        
        List<GuildMember> members = memberRepository.findByGuildServerId(serverId);
        return members.stream()
            .map(m -> Map.of(
                "userId", m.getUserId(),
                "username", m.getUsername() != null ? m.getUsername() : "",
                "globalName", m.getGlobalName() != null ? m.getGlobalName() : ""
            ))
            .collect(Collectors.toList());
    }

    /** 批量创建任务（多选模拟器） */
    @PostMapping("/batch")
    public Map<String, Object> batchCreateTasks(@RequestBody BatchCreateRequest request) {
        Long merchantId = SecurityUtils.currentMerchantId();
        Long userId = SecurityUtils.currentUserId();
        
        int successCount = 0;
        int failCount = 0;
        
        for (Long accountId : request.discordAccountIds()) {
            try {
                taskService.createTask(
                    merchantId,
                    userId,
                    request.serverId(),
                    accountId,
                    request.pauseDurationSeconds(),
                    request.delayMinSeconds(),
                    request.delayMaxSeconds()
                );
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }
        
        return Map.of("success", successCount, "fail", failCount);
    }

    /** 批量启动任务 */
    @PostMapping("/batch/start")
    public Map<String, Object> batchStartTasks(@RequestBody BatchActionRequest request) {
        int successCount = 0;
        int failCount = 0;
        StringBuilder failMsg = new StringBuilder();
        
        for (Long taskId : request.taskIds()) {
            try {
                taskService.startTask(taskId);
                successCount++;
            } catch (Exception e) {
                failCount++;
                if (failMsg.length() > 0) failMsg.append("; ");
                failMsg.append(taskId).append(": ").append(e.getMessage());
            }
        }
        
        return Map.of(
            "success", successCount, 
            "fail", failCount,
            "failDetails", failMsg.toString()
        );
    }

    /** 批量停止任务 */
    @PostMapping("/batch/stop")
    public Map<String, Object> batchStopTasks(@RequestBody BatchActionRequest request) {
        int successCount = 0;
        int failCount = 0;
        
        for (Long taskId : request.taskIds()) {
            try {
                taskService.stopTask(taskId);
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }
        
        return Map.of("success", successCount, "fail", failCount);
    }

    /** 批量暂停任务 */
    @PostMapping("/batch/pause")
    public Map<String, Object> batchPauseTasks(@RequestBody BatchActionRequest request) {
        int successCount = 0;
        int failCount = 0;
        
        for (Long taskId : request.taskIds()) {
            try {
                taskService.pauseTask(taskId);
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }
        
        return Map.of("success", successCount, "fail", failCount);
    }

    /** 数据请求体类 */
    public record CreateTaskRequest(
        Long serverId,
        Long discordAccountId,
        Integer pauseDurationSeconds,
        Integer delayMinSeconds,
        Integer delayMaxSeconds
    ) {}

    public record BatchCreateRequest(
        Long serverId,
        List<Long> discordAccountIds,
        Integer pauseDurationSeconds,
        Integer delayMinSeconds,
        Integer delayMaxSeconds
    ) {}

    public record BatchActionRequest(
        List<Long> taskIds
    ) {}
}
