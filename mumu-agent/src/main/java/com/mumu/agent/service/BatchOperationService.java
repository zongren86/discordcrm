package com.mumu.agent.service;

import com.mumu.agent.config.AgentConfig;
import com.mumu.agent.model.AgentMessage;
import com.mumu.agent.model.EmulatorInfo;
import com.mumu.agent.websocket.CloudWebSocketClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class BatchOperationService {
    
    private final EmulatorService emulatorService;
    private final DiscordAutomationService discordService;
    private final ApkCacheService apkCacheService;
    private final CloudWebSocketClient webSocketClient;
    private final AgentConfig agentConfig;
    
    // 每个模拟器的自动加好友状态
    private final Map<Integer, AutoAddState> autoAddStates = new ConcurrentHashMap<>();
    // 正在执行操作的模拟器（防止并发冲突）
    private final Set<Integer> busyEmulators = ConcurrentHashMap.newKeySet();
    // 批量操作线程池
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(10, r -> {
        Thread t = new Thread(r, "batch-operation-worker");
        t.setDaemon(true);
        return t;
    });
    
    public BatchOperationService(EmulatorService emulatorService,
                                  DiscordAutomationService discordService,
                                  ApkCacheService apkCacheService,
                                  @Lazy CloudWebSocketClient webSocketClient,
                                  AgentConfig agentConfig) {
        this.emulatorService = emulatorService;
        this.discordService = discordService;
        this.apkCacheService = apkCacheService;
        this.webSocketClient = webSocketClient;
        this.agentConfig = agentConfig;
    }
    
    public Map<String, Object> batchStart(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = Collections.synchronizedList(new ArrayList<>());
        List<String> failList = Collections.synchronizedList(new ArrayList<>());
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                Map<String, Object> res = emulatorService.startEmulator(index);
                if ("SUCCESS".equals(res.get("status"))) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res.getOrDefault("message", ""));
                }
            });
        }
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    public Map<String, Object> batchStop(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = Collections.synchronizedList(new ArrayList<>());
        List<String> failList = Collections.synchronizedList(new ArrayList<>());
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                stopAutoAdd(index);
                Map<String, Object> res = emulatorService.stopEmulator(index);
                if ("SUCCESS".equals(res.get("status"))) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res.getOrDefault("message", ""));
                }
            });
        }
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    public Map<String, Object> batchRestart(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = Collections.synchronizedList(new ArrayList<>());
        List<String> failList = Collections.synchronizedList(new ArrayList<>());
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                stopAutoAdd(index);
                emulatorService.stopEmulator(index);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                Map<String, Object> res = emulatorService.startEmulator(index);
                if ("SUCCESS".equals(res.get("status"))) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res.getOrDefault("message", ""));
                }
            });
        }
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    public Map<String, Object> batchDelete(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = Collections.synchronizedList(new ArrayList<>());
        List<String> failList = Collections.synchronizedList(new ArrayList<>());
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                stopAutoAdd(index);
                Map<String, Object> res = emulatorService.deleteEmulator(index);
                if ("SUCCESS".equals(res.get("status"))) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res.getOrDefault("message", ""));
                }
            });
        }
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    public Map<String, Object> batchInstallApk(List<Integer> indices, String apkUrl) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                String res = discordService.installDiscord(index, apkUrl);
                if (res.startsWith("SUCCESS")) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res);
                }
            });
        }
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    public String startAutoAdd(int index, List<String> friendUsernames, String taskId) {
        EmulatorInfo info = emulatorService.getEmulator(index);
        if (info == null) return "ERROR: 模拟器不存在";
        if (!"RUNNING".equals(info.getStatus())) return "ERROR: 模拟器未运行";
        
        // 检查是否已登录 Discord
        if (!discordService.isDiscordLoggedIn(index)) {
            return "ERROR: Discord 未登录，请先手动登录";
        }
        
        // 获取当前登录的 Discord 账号（用于回传）
        String discordUsername = discordService.getLoggedInUser(index);
        
        AutoAddState state = new AutoAddState();
        state.setRunning(true);
        state.setTaskId(taskId);
        state.setDiscordUsername(discordUsername);
        state.setRemainingFriends(new ArrayList<>(friendUsernames));
        state.setNextAddAt(System.currentTimeMillis());
        state.setAddedCount(0);
        autoAddStates.put(index, state);
        
        log.info("启动自动加好友: index={}, taskId={}, discordUser={}, friendCount={}", 
            index, taskId, discordUsername, friendUsernames.size());
        
        return "SUCCESS";
    }
    
    public String stopAutoAdd(int index) {
        AutoAddState state = autoAddStates.get(index);
        if (state == null) return "ERROR: 未启动自动加好友";
        
        state.setRunning(false);
        autoAddStates.remove(index);
        
        log.info("停止自动加好友: index={}, addedCount={}", index, state.getAddedCount());
        
        return "SUCCESS";
    }
    
    /**
     * 批量启动自动加好友（异步执行）
     */
    public Map<String, Object> batchStartAutoAdd(List<Integer> indices, List<String> friendUsernames, String taskId) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = Collections.synchronizedList(new ArrayList<>());
        List<String> failList = Collections.synchronizedList(new ArrayList<>());
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                String res = startAutoAdd(index, friendUsernames, taskId + "_" + index);
                if ("SUCCESS".equals(res)) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res);
                }
            });
        }
        
        // 等待所有任务完成（最多等待30秒）
        try {
            Thread.sleep(30000);
        } catch (InterruptedException ignored) {}
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    /**
     * 批量停止自动加好友（异步执行）
     */
    public Map<String, Object> batchStopAutoAdd(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = Collections.synchronizedList(new ArrayList<>());
        List<String> failList = Collections.synchronizedList(new ArrayList<>());
        
        for (int index : indices) {
            batchExecutor.submit(() -> {
                String res = stopAutoAdd(index);
                if ("SUCCESS".equals(res)) {
                    successList.add("模拟器" + index);
                } else {
                    failList.add("模拟器" + index + ": " + res);
                }
            });
        }
        
        // 等待所有任务完成（最多等待5秒）
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ignored) {}
        
        result.put("success", successList);
        result.put("fail", failList);
        return result;
    }
    
    public boolean isAutoAddRunning(int index) {
        AutoAddState state = autoAddStates.get(index);
        return state != null && state.isRunning();
    }
    
    public AutoAddState getAutoAddState(int index) {
        return autoAddStates.get(index);
    }
    
    @Scheduled(fixedDelay = 1000) // 每秒检查
    public void tick() {
        long now = System.currentTimeMillis();
        
        for (Map.Entry<Integer, AutoAddState> entry : autoAddStates.entrySet()) {
            int index = entry.getKey();
            AutoAddState state = entry.getValue();
            
            if (!state.isRunning()) continue;
            if (state.getNextAddAt() > now) continue;
            if (state.getRemainingFriends().isEmpty()) {
                // 没有待添加的好友了，自动停止
                stopAutoAdd(index);
                // 通知云端任务完成
                notifyTaskCompleted(state);
                continue;
            }
            
            if (busyEmulators.add(index)) {
                batchExecutor.submit(() -> {
                    try {
                        doAddOne(index, state);
                    } catch (Exception e) {
                        log.error("模拟器{} 加好友异常", index, e);
                    } finally {
                        busyEmulators.remove(index);
                    }
                });
            }
        }
    }
    
    private void doAddOne(int index, AutoAddState state) {
        EmulatorInfo info = emulatorService.getEmulator(index);
        if (info == null || !"RUNNING".equals(info.getStatus())) {
            stopAutoAdd(index);
            return;
        }
        
        // 再次检查是否已登录
        if (!discordService.isDiscordLoggedIn(index)) {
            stopAutoAdd(index);
            notifyTaskError(state, "Discord 未登录");
            return;
        }
        
        // 每次添加好友前重新检测当前登录的 Discord 账号
        String currentDiscordUsername = discordService.getLoggedInUser(index);
        if (currentDiscordUsername != null && !currentDiscordUsername.equals(state.getDiscordUsername())) {
            log.info("模拟器{} Discord账号变更: {} -> {}", index, state.getDiscordUsername(), currentDiscordUsername);
            state.setDiscordUsername(currentDiscordUsername);
        }
        
        // 获取待添加的好友
        List<String> friends = state.getRemainingFriends();
        if (friends.isEmpty()) return;
        
        String username = friends.remove(0);
        
        // 执行添加
        String result = discordService.addFriendByUsername(index, username);
        
        // 回传结果到云端（包含当前Discord账号，无论成功或失败）
        notifyAddResult(state, username, result);
        
        if (result.startsWith("SUCCESS")) {
            state.setAddedCount(state.getAddedCount() + 1);
        }
        
        // 排程下一次（保留间隔策略）
        long interval = state.getIntervalSeconds() > 0 ? 
            state.getIntervalSeconds() * 1000L : 15 * 60 * 1000L; // 默认15分钟
        long randomDelay = state.getDelayMaxSeconds() > state.getDelayMinSeconds() ?
            (long)(state.getDelayMinSeconds() + new Random().nextInt(state.getDelayMaxSeconds() - state.getDelayMinSeconds())) * 1000L :
            60 * 1000L;
        
        state.setNextAddAt(System.currentTimeMillis() + interval + randomDelay);
    }
    
    private void notifyAddResult(AutoAddState state, String username, String result) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "ADD_FRIEND_RESULT");
        msg.put("taskId", state.getTaskId());
        msg.put("username", username);
        msg.put("result", result);
        msg.put("discordUsername", state.getDiscordUsername());
        msg.put("addedCount", state.getAddedCount());
        webSocketClient.sendMessage(AgentMessage.of("ADD_FRIEND_RESULT", state.getTaskId(), msg));
    }
    
    private void notifyTaskCompleted(AutoAddState state) {
        webSocketClient.sendMessage(AgentMessage.of("TASK_COMPLETED", state.getTaskId(), null));
    }
    
    private void notifyTaskError(AutoAddState state, String error) {
        webSocketClient.sendMessage(AgentMessage.status("TASK_ERROR", error));
    }
    
    @lombok.Data
    public static class AutoAddState {
        private boolean running = false;
        private String taskId;
        private String discordUsername; // 当前登录的 Discord 账号
        private List<String> remainingFriends = new ArrayList<>();
        private long nextAddAt;
        private int addedCount;
        private int intervalSeconds = 900; // 默认15分钟
        private int delayMinSeconds = 60;
        private int delayMaxSeconds = 300;
        private Instant startedAt = Instant.now();
    }
}
