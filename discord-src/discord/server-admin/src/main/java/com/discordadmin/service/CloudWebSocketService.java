package com.discordadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.entity.EmuInstance;
import com.discordadmin.repository.AgentRegistrationRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.EmuInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CloudWebSocketService extends TextWebSocketHandler {
    
    private final AgentRegistrationRepository agentRepository;
    private final AgentRepository agentEntityRepository;
    private final EmuInstanceRepository instanceRepository;
    private final ObjectMapper objectMapper;
    
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AgentRegistration> onlineAgents = new ConcurrentHashMap<>();
    private final Map<String, String> deviceSessionMap = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingRequests = new ConcurrentHashMap<>();
    
    private static final int REQUEST_TIMEOUT_SECONDS = 180;
    private static final int HEARTBEAT_TIMEOUT_SECONDS = 90; // 心跳超时时间（秒）
    
    public CloudWebSocketService(AgentRegistrationRepository agentRepository, 
                                  AgentRepository agentEntityRepository,
                                  EmuInstanceRepository instanceRepository,
                                  ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.agentEntityRepository = agentEntityRepository;
        this.instanceRepository = instanceRepository;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立: sessionId={}", session.getId());
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            Map<String, String> params = parseQueryParams(query);
            String deviceId = params.get("deviceId");
            String userIdStr = params.get("userId");
            Long userId = resolveUserId(userIdStr);
            if (deviceId != null && userId != null) {
                sessions.put(session.getId(), session);
                deviceSessionMap.put(deviceId, session.getId());
                log.info("Agent 连接: deviceId={}, userId={}, sessionId={}", deviceId, userId, session.getId());
            }
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String value = eq < pair.length() - 1 ? pair.substring(eq + 1) : "";
                try {
                    params.put(java.net.URLDecoder.decode(key, "UTF-8"),
                               java.net.URLDecoder.decode(value, "UTF-8"));
                } catch (Exception e) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    /**
     * 将 userId 字符串转换为 Long 类型的用户 ID
     * 支持两种格式：数字 ID（如 "1"）或用户名（如 "merchantadmin"）
     */
    private Long resolveUserId(String userIdStr) {
        if (userIdStr == null || userIdStr.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            return agentEntityRepository.findAllByUsername(userIdStr).stream().findFirst()
                .map(Agent::getId)
                .orElse(null);
        }
    }
    

    /**
     * 安全地将对象转换为字符串，处理 Integer/Long/String 等类型
     */
    private String safeString(Object obj) {
        if (obj == null) return null;
        return obj.toString();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
            String type = (String) msg.get("type");
            
            log.info("收到消息: type={}, sessionId={}", type, session.getId());
            
            switch (type) {
                case "REGISTER" -> handleRegister(session, msg);
                case "HEARTBEAT" -> handleHeartbeat(session, msg);
                case "TASK_RESULT" -> handleTaskResult(session, msg);
                case "REGISTER_ACK" -> {} // Agent 收到云端注册确认
                case "AGENT_COMMAND_RESULT" -> handleCommandResult(msg);
                default -> log.warn("未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
        }
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String deviceId = findDeviceIdBySession(session.getId());
        if (deviceId != null) {
            AgentRegistration agent = onlineAgents.remove(deviceId);
            if (agent != null) {
                agent.setStatus("OFFLINE");
                agent.setUpdatedAt(Instant.now());
                agentRepository.save(agent);
                log.info("Agent 离线: deviceId={}", deviceId);
            }
            sessions.remove(session.getId());
            deviceSessionMap.remove(deviceId);
        }
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: sessionId={}", session.getId(), exception);
    }
    
    private void handleRegister(WebSocketSession session, Map<String, Object> msg) {
        String deviceId = safeString(msg.get("deviceId"));
        String userIdStr = safeString(msg.get("userId"));
        Long userId = resolveUserId(userIdStr);
        
        Object paramsObj = msg.get("params");
        Map<String, Object> params = (paramsObj instanceof Map) ? (Map<String, Object>) paramsObj : null;
        if (params != null) {
            if (deviceId == null) deviceId = (String) params.get("deviceId");
            if (userId == null && params.get("userId") != null) userId = resolveUserId(safeString(params.get("userId")));
        }
        
        final String finalDeviceId = deviceId;
        final Long finalUserId = userId;
        
        log.info("处理 REGISTER: userId={}, deviceId={}", finalUserId, finalDeviceId);
        
        if (finalDeviceId == null || finalUserId == null) {
            sendError(session, "缺少 deviceId 或 userId");
            return;
        }
        
        try {
            // 关闭同一 deviceId 的旧连接，防止重复连接
            String oldSessionId = deviceSessionMap.get(finalDeviceId);
            if (oldSessionId != null && !oldSessionId.equals(session.getId())) {
                WebSocketSession oldSession = sessions.get(oldSessionId);
                if (oldSession != null && oldSession.isOpen()) {
                    try {
                        oldSession.close(CloseStatus.NORMAL);
                        log.info("关闭旧 Agent 连接: deviceId={}, oldSessionId={}", finalDeviceId, oldSessionId);
                    } catch (IOException e) {
                        log.warn("关闭旧 Agent 连接失败: deviceId={}", finalDeviceId, e);
                    }
                }
                sessions.remove(oldSessionId);
            }
            
            sessions.put(session.getId(), session);
            deviceSessionMap.put(finalDeviceId, session.getId());
            
            AgentRegistration agent = agentRepository.findByUserIdAndDeviceId(finalUserId, finalDeviceId)
                .orElseGet(() -> {
                    AgentRegistration newAgent = new AgentRegistration();
                    newAgent.setUserId(finalUserId);
                    newAgent.setDeviceId(finalDeviceId);
                    return newAgent;
                });
            
            if (params != null) {
                agent.setOs((String) params.get("os"));
                agent.setOsVersion((String) params.get("osVersion"));
            }
            
            // 尝试通过 AgentRepository 查找 merchantId
            if (agent.getMerchantId() == null) {
                Long merchantIdFromAgent = agentEntityRepository.findById(finalUserId)
                    .map(Agent::getMerchantId)
                    .orElse(null);
                if (merchantIdFromAgent != null) {
                    agent.setMerchantId(merchantIdFromAgent);
                }
            }
            
            agent.setStatus("ONLINE");
            agent.setLastHeartbeatAt(Instant.now());
            agent.setUpdatedAt(Instant.now());
            agent = agentRepository.save(agent);
            
            onlineAgents.put(finalDeviceId, agent);
            
            log.info("Agent 注册成功: userId={}, deviceId={}, merchantId={}", 
                String.valueOf(finalUserId), finalDeviceId, agent.getMerchantId());
            
            sendMessage(session, Map.of(
                "type", "REGISTER_ACK",
                "status", "SUCCESS",
                "agentId", agent.getId()
            ));
        } catch (Exception e) {
            log.error("Agent 注册失败: userId={}, deviceId={}", String.valueOf(finalUserId), finalDeviceId, e);
            sendError(session, "注册失败: " + e.getMessage());
        }
    }
    
    private void handleHeartbeat(WebSocketSession session, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        if (data != null) {

            String deviceId = safeString(data.get("deviceId"));
            String userIdStr = safeString(data.get("userId"));
            Long userId = userIdStr != null ? Long.parseLong(userIdStr) : null;
            if (deviceId != null) {
                AgentRegistration agent = onlineAgents.get(deviceId);
                if (agent == null) {
                    // 从数据库查找或创建（带去重逻辑）
                    final String finalDeviceId = deviceId;
                    final Long finalUserId = userId;
                    
                    // 先检查是否有重复记录，进行清理
                    List<AgentRegistration> duplicates = agentRepository.findAllByUserIdAndDeviceIdOrdered(userId, deviceId);
                    if (duplicates.size() > 1) {
                        log.warn("发现重复 Agent 记录: userId={}, deviceId={}, count={}", userId, deviceId, duplicates.size());
                        // 保留最早的记录，删除其他重复记录
                        for (int i = 1; i < duplicates.size(); i++) {
                            agentRepository.delete(duplicates.get(i));
                            log.info("删除重复 Agent 记录: id={}", duplicates.get(i).getId());
                        }
                    }
                    
                    // 使用 findByUserIdAndDeviceId 查找（现在应该只有一条记录）
                    agent = agentRepository.findByUserIdAndDeviceId(userId, deviceId)
                        .orElseGet(() -> {
                            AgentRegistration newAgent = new AgentRegistration();
                            newAgent.setUserId(finalUserId);
                            newAgent.setDeviceId(finalDeviceId);
                            return newAgent;
                        });
                    // 尝试设置 merchantId
                    if (agent.getMerchantId() == null && agent.getUserId() != null) {
                        agentEntityRepository.findById(agent.getUserId())
                            .map(Agent::getMerchantId)
                            .ifPresent(agent::setMerchantId);
                    }
                    onlineAgents.put(deviceId, agent);
                    log.info("心跳恢复 Agent: deviceId={}, userId={}", deviceId, agent.getUserId());
                }
                agent.setLastHeartbeatAt(Instant.now());
                agent.setStatus("ONLINE");
                
                // 保存 MuMuPlayer 运行状态
                Object mumuPlayerRunning = data.get("mumuPlayerRunning");
                if (mumuPlayerRunning != null) {
                    agent.setMumuPlayerRunning(Boolean.TRUE.equals(mumuPlayerRunning));
                }
                Object emulatorCount = data.get("emulatorCount");
                if (emulatorCount != null) {
                    agent.setEmulatorCount(((Number) emulatorCount).intValue());
                }
                Object runningEmulatorCount = data.get("runningEmulatorCount");
                if (runningEmulatorCount != null) {
                    agent.setRunningEmulatorCount(((Number) runningEmulatorCount).intValue());
                }
                agentRepository.save(agent);
                
                syncEmulatorStatusFromHeartbeat(agent, data);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private void syncEmulatorStatusFromHeartbeat(AgentRegistration agent, Map<String, Object> data) {
        try {
            List<Map<String, Object>> emulators = (List<Map<String, Object>>) data.get("emulators");
            log.info("心跳同步: agent={}, emulators={}", agent.getDeviceId(), emulators != null ? emulators.size() : 0);
            
            if (emulators == null || emulators.isEmpty()) {
                return;
            }
            
            Long userId = agent.getUserId();
            if (userId == null) {
                log.warn("心跳同步: userId 为空，跳过同步");
                return;
            }
            
            // 获取 merchantId，如果 AgentRegistration 中没有，则通过 AgentRepository 查找
            Long merchantId = agent.getMerchantId();
            if (merchantId == null) {
                merchantId = agentEntityRepository.findById(userId)
                    .map(Agent::getMerchantId)
                    .orElse(null);
                if (merchantId != null) {
                    agent.setMerchantId(merchantId);
                    agentRepository.save(agent);
                }
            }
            
            if (merchantId == null) {
                log.warn("心跳同步: merchantId 为空，userId={}", userId);
                return;
            }
            
            List<EmuInstance> existingInstances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
            Map<Integer, EmuInstance> existingMap = new HashMap<>();
            for (EmuInstance inst : existingInstances) {
                existingMap.put(inst.getInstanceIndex(), inst);
            }
            
            int updatedCount = 0;
            int skippedCount = 0;
            int cleanedCount = 0;
            // 收集物理模拟器的 cloudIndex 集合，用于检测需要清理的记录
            Set<Integer> physicalCloudIndices = new HashSet<>();
            for (Map<String, Object> emuData : emulators) {
                Integer muMuIndex = emuData.get("index") != null ? ((Number) emuData.get("index")).intValue() : null;
                if (muMuIndex == null) continue;
                physicalCloudIndices.add(muMuIndex + 1);
            }
            
            for (Map<String, Object> emuData : emulators) {
                log.info("心跳同步: 模拟器数据={}", emuData);
                Integer muMuIndex = emuData.get("index") != null ? ((Number) emuData.get("index")).intValue() : null;
                if (muMuIndex == null) {
                    log.warn("心跳同步: 模拟器缺少 index 字段");
                    continue;
                }
                Integer cloudIndex = muMuIndex + 1;
                String status = (String) emuData.get("status");
                Integer adbPort = emuData.get("adbPort") != null ? ((Number) emuData.get("adbPort")).intValue() : null;
                
                EmuInstance instance = existingMap.get(cloudIndex);
                if (instance != null) {
                    boolean changed = false;
                    
                    if (status != null) {
                        EmuInstance.EmuStatus newStatus = mapStatus(status);
                        if (newStatus != null && newStatus != instance.getStatus()) {
                            instance.setStatus(newStatus);
                            changed = true;
                        }
                    }
                    
                    if (adbPort != null && !adbPort.equals(instance.getAdbPort())) {
                        instance.setAdbPort(adbPort);
                        changed = true;
                    }
                    
                    if (changed) {
                        instance.setUpdatedAt(Instant.now());
                        instanceRepository.save(instance);
                        updatedCount++;
                    }
                } else {
                    // 物理存在但数据库不存在 -> 跳过，不自动创建（必须由用户手动创建）
                    skippedCount++;
                    log.info("心跳同步: 跳过模拟器 #{}, name={} (数据库无记录，不自动创建)", cloudIndex, emuData.get("name"));
                }
            }
            
            // 只提示物理已不存在的记录，不自动删除
            for (EmuInstance dbInst : existingInstances) {
                if (!physicalCloudIndices.contains(dbInst.getInstanceIndex())) {
                    log.warn("【心跳提示】数据库记录 #{} ({}) 在物理中不存在 - 如确认已删除可手动清理", 
                        dbInst.getInstanceIndex(), dbInst.getName());
                    cleanedCount++;
                }
            }
            
            log.info("心跳同步完成: agent={}, 处理了 {} 个模拟器，更新了 {} 个，跳过 {} 个，{} 个孤立记录(仅提示)", 
                agent.getDeviceId(), emulators.size(), updatedCount, skippedCount, cleanedCount);
        } catch (Exception e) {
            log.warn("同步模拟器状态失败: {}", e.getMessage());
        }
    }
    
    private EmuInstance.EmuStatus mapStatus(String status) {
        if (status == null || status.isEmpty()) {
            return EmuInstance.EmuStatus.STOPPED;
        }
        return switch (status) {
            case "RUNNING" -> EmuInstance.EmuStatus.RUNNING;
            case "STOPPED" -> EmuInstance.EmuStatus.STOPPED;
            case "CREATED" -> EmuInstance.EmuStatus.CREATED;

            default -> EmuInstance.EmuStatus.STOPPED;
        };
    }
    
    private void handleTaskResult(WebSocketSession session, Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        Map<String, Object> params = (Map<String, Object>) msg.get("params");
        Object data = msg.get("data");
        
        log.info("收到任务结果: taskId={}, params={}", taskId, params);
        
        if (taskId != null) {
            CompletableFuture<Map<String, Object>> future = pendingRequests.remove(taskId);
            if (future != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("taskId", taskId);
                result.put("status", params != null ? params.get("status") : "UNKNOWN");
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataMap = (Map<String, Object>) data;
                    result.putAll(dataMap);
                } else if (data != null) {
                    result.put("data", data);
                }
                future.complete(result);
            }
        }
    }
    
    private void handleCommandResult(Map<String, Object> msg) {
        String taskId = (String) msg.get("taskId");
        if (taskId != null) {
            CompletableFuture<Map<String, Object>> future = pendingRequests.remove(taskId);
            if (future != null) {
                future.complete(msg);
            }
        }
    }
    
    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, Map.of(
            "type", "ERROR",
            "message", error
        ));
    }
    
    public void sendMessage(WebSocketSession session, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("发送消息失败", e);
        }
    }
    
    /**
     * 向指定 deviceId 的 Agent 发送通知（无需等待响应）
     */
    public void notifyAgent(String deviceId, Map<String, Object> message) {
        WebSocketSession session = findSessionByDeviceId(deviceId);
        if (session == null || !session.isOpen()) {
            log.warn("Agent 不在线，无法发送通知: deviceId={}", deviceId);
            return;
        }
        
        sendMessage(session, message);
        log.debug("已向 Agent 发送通知: deviceId={}, type={}", deviceId, message.get("type"));
    }
    
    /**
     * 向指定 deviceId 的 Agent 发送指令并等待结果
     */
    public CompletableFuture<Map<String, Object>> sendCommandAndWait(String deviceId, String commandType, Map<String, Object> params) {
        WebSocketSession session = findSessionByDeviceId(deviceId);
        if (session == null || !session.isOpen()) {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("Agent 不在线: deviceId=" + deviceId));
            return future;
        }
        
        String taskId = UUID.randomUUID().toString();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingRequests.put(taskId, future);
        
        Map<String, Object> message = new HashMap<>();
        message.put("type", commandType);
        message.put("taskId", taskId);
        message.put("params", params != null ? params : new HashMap<>());
        
        sendMessage(session, message);
        
        // 返回带有超时的 future，而不是原始 future
        return future.orTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                pendingRequests.remove(taskId);
                log.warn("指令超时: taskId={}", taskId);
                Map<String, Object> timeoutResult = new HashMap<>();
                timeoutResult.put("status", "TIMEOUT");
                timeoutResult.put("message", "指令执行超时");
                return timeoutResult;
            });
    }
    
    /**
     * 向指定 userId 的所有在线 Agent 广播指令
     */
    public List<CompletableFuture<Map<String, Object>>> broadcastCommandToUser(Long userId, String commandType, Map<String, Object> params) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        
        for (AgentRegistration agent : onlineAgents.values()) {
            if (userId != null && userId.equals(agent.getUserId()) && "ONLINE".equals(agent.getStatus())) {
                futures.add(sendCommandAndWait(agent.getDeviceId(), commandType, params));
            }
        }
        
        return futures;
    }
    
    /**
     * 通过 Agent 执行 ADB 命令
     * @param userId 用户 ID
     * @param index 模拟器索引 (0-based)
     * @param args ADB 命令参数
     * @return ADB 命令输出
     */
    public String execAdb(Long userId, int index, String... args) {
        List<AgentRegistration> agents = getOnlineAgentsByUserId(userId);
        if (agents.isEmpty()) {
            log.warn("Agent 模式: 无在线 Agent, userId={}", userId);
            return "ERROR: 无在线 Agent";
        }

        AgentRegistration agent = agents.get(0);
        Map<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("args", java.util.Arrays.asList(args));

        try {
            Map<String, Object> result = sendCommandAndWait(agent.getDeviceId(), "EXEC_ADB", params)
                .get(30, TimeUnit.SECONDS);

            if ("SUCCESS".equals(result.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null && Boolean.TRUE.equals(data.get("success"))) {
                    return (String) data.getOrDefault("output", "");
                } else {
                    return "ERROR: " + (data != null ? data.get("message") : "未知错误");
                }
            } else {
                return "ERROR: " + result.getOrDefault("message", "命令执行失败");
            }
        } catch (Exception e) {
            log.error("通过 Agent 执行 ADB 命令失败", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 获取指定 userId 的在线 Agent；若 userId 为空则返回所有在线 Agent
     * 优先从内存 Map 获取，如果内存为空则从数据库回退查询
     */
    public List<AgentRegistration> getOnlineAgentsByUserId(Long userId) {
        // 1) 精确按 userId 匹配
        List<AgentRegistration> result = onlineAgents.values().stream()
            .filter(a -> "ONLINE".equals(a.getStatus()))
            .filter(a -> userId == null || userId.equals(a.getUserId()))
            .collect(java.util.stream.Collectors.toList());

        // 2) 兜底：有任何在线 agent 就返回（默认选中第一个，兼容 agent 配的 userId 和登录账号不一致的场景）
        if (result.isEmpty()) {
            result = onlineAgents.values().stream()
                .filter(a -> "ONLINE".equals(a.getStatus()))
                .collect(java.util.stream.Collectors.toList());
            if (!result.isEmpty()) {
                log.info("userId={} 无精确匹配, 兜底返回全部在线 agent ({} 个)", userId, result.size());
            }
        }

        if (!result.isEmpty()) {
            return result;
        }

        // 3) 最后回退：从数据库查询最近 2 分钟内心跳过的 Agent
        Instant threshold = Instant.now().minusSeconds(120);
        List<AgentRegistration> dbResult = agentRepository.findAll().stream()
            .filter(a -> "ONLINE".equals(a.getStatus()))
            .filter(a -> a.getLastHeartbeatAt() != null && a.getLastHeartbeatAt().isAfter(threshold))
            .peek(a -> {
                if (!onlineAgents.containsKey(a.getDeviceId())) {
                    onlineAgents.put(a.getDeviceId(), a);
                    log.info("从数据库恢复在线 Agent: deviceId={}, userId={}", a.getDeviceId(), a.getUserId());
                }
            })
            .collect(java.util.stream.Collectors.toList());
        return dbResult;
    }
    
    public boolean isAgentOnline(String deviceId) {
        AgentRegistration agent = onlineAgents.get(deviceId);
        return agent != null && "ONLINE".equals(agent.getStatus());
    }
    
    public AgentRegistration getAgentByDeviceId(String deviceId) {
        return onlineAgents.get(deviceId);
    }
    
    /**
     * 定时检查心跳超时的 Agent
     * 每隔 30 秒检查一次，如果 Agent 超过 90 秒没有发送心跳，则标记为离线
     */
    @Scheduled(fixedRate = 30000)
    public void checkHeartbeatTimeout() {
        Instant now = Instant.now();
        List<String> timeoutAgents = new ArrayList<>();
        
        for (Map.Entry<String, AgentRegistration> entry : onlineAgents.entrySet()) {
            AgentRegistration agent = entry.getValue();
            Instant lastHeartbeat = agent.getLastHeartbeatAt();
            
            // 如果从未收到过心跳，但连接已超过 HEARTBEAT_TIMEOUT_SECONDS，则标记为离线
            if (lastHeartbeat == null) {
                // 检查连接时间
                continue;
            }
            
            long secondsSinceLastHeartbeat = java.time.Duration.between(lastHeartbeat, now).getSeconds();
            if (secondsSinceLastHeartbeat > HEARTBEAT_TIMEOUT_SECONDS) {
                timeoutAgents.add(entry.getKey());
                log.warn("心跳超时: deviceId={}, 上次心跳={}秒前", entry.getKey(), secondsSinceLastHeartbeat);
            }
        }
        
        // 将超时的 Agent 标记为离线
        for (String deviceId : timeoutAgents) {
            AgentRegistration agent = onlineAgents.remove(deviceId);
            if (agent != null) {
                agent.setStatus("OFFLINE");
                agent.setUpdatedAt(now);
                agentRepository.save(agent);
                log.info("Agent 心跳超时离线: deviceId={}", deviceId);
                
                // 关闭对应的 WebSocket 会话
                String sessionId = deviceSessionMap.get(deviceId);
                if (sessionId != null) {
                    WebSocketSession session = sessions.get(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.close(CloseStatus.SERVER_ERROR);
                        } catch (IOException e) {
                            log.warn("关闭超时 Agent 会话失败: deviceId={}", deviceId);
                        }
                    }
                    sessions.remove(sessionId);
                    deviceSessionMap.remove(deviceId);
                }
            }
        }
    }
    
    /**
     * 获取所有在线的 Agent（过滤掉已超时的）
     */
    public List<AgentRegistration> getAllOnlineAgents() {
        // 先执行一次超时检查
        checkHeartbeatTimeout();
        return new ArrayList<>(onlineAgents.values());
    }
    
    public WebSocketSession findSessionByDeviceId(String deviceId) {
        String sessionId = deviceSessionMap.get(deviceId);
        if (sessionId != null) {
            return sessions.get(sessionId);
        }
        return null;
    }
    
    private String findDeviceIdBySession(String sessionId) {
        for (Map.Entry<String, String> entry : deviceSessionMap.entrySet()) {
            if (entry.getValue().equals(sessionId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 通过 Agent 获取物理模拟器列表
     * @param userId 用户 ID
     * @return 物理模拟器列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEmulatorsFromAgent(Long userId) {
        List<AgentRegistration> agents = getOnlineAgentsByUserId(userId);
        if (agents.isEmpty()) {
            log.warn("获取模拟器列表失败: 无在线 Agent, userId={}", userId);
            return null;
        }

        AgentRegistration agent = agents.get(0);
        try {
            Map<String, Object> result = sendCommandAndWait(agent.getDeviceId(), "GET_EMULATORS", null)
                .get(30, TimeUnit.SECONDS);

            if ("SUCCESS".equals(result.get("status"))) {
                // handleTaskResult 会把 data 展开到 result 顶层（putAll）
                // 所以需要同时兼容两种格式: result.emulators 或 result.data.emulators
                List<Map<String, Object>> emuList = null;
                
                // 方式1: emulators 已在顶层（handleTaskResult putAll 展开后）
                Object emulatorsTop = result.get("emulators");
                if (emulatorsTop instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> list = (List<Map<String, Object>>) emulatorsTop;
                    emuList = list;
                }
                
                // 方式2: emulators 在 data 子对象中
                if (emuList == null) {
                    Object dataObj = result.get("data");
                    if (dataObj instanceof Map) {
                        Map<String, Object> data = (Map<String, Object>) dataObj;
                        Object emulatorsNested = data.get("emulators");
                        if (emulatorsNested instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> list = (List<Map<String, Object>>) emulatorsNested;
                            emuList = list;
                        }
                    }
                }
                
                if (emuList != null) {
                    log.info("从 Agent 获取到 {} 个模拟器", emuList.size());
                    return emuList;
                }
                log.warn("获取模拟器列表: emulators 列表为空或不存在, result keys={}", result.keySet());
                return new ArrayList<>();
            } else {
                log.warn("获取模拟器列表失败: {}", result.getOrDefault("message", "未知错误"));
                return null;
            }
        } catch (Exception e) {
            log.error("通过 Agent 获取模拟器列表失败", e);
            return null;
        }
    }

    /**
     * 通过 Agent 删除模拟器
     * @param userId 用户 ID
     * @param index 模拟器索引 (0-based)
     * @return 操作结果
     */
    public Map<String, Object> deleteEmulatorOnAgent(Long userId, int index) {
        List<AgentRegistration> agents = getOnlineAgentsByUserId(userId);
        if (agents.isEmpty()) {
            log.warn("通过 Agent 删除模拟器失败: 无在线 Agent, userId={}", userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "无在线 Agent");
            return result;
        }

        AgentRegistration agent = agents.get(0);
        Map<String, Object> params = new HashMap<>();
        params.put("index", index);

        try {
            Map<String, Object> result = sendCommandAndWait(agent.getDeviceId(), "DELETE_EMULATOR", params)
                .get(30, TimeUnit.SECONDS);

            if ("SUCCESS".equals(result.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null) {
                    return data;
                }
                Map<String, Object> r = new HashMap<>();
                r.put("success", true);
                return r;
            } else {
                log.warn("通过 Agent 删除模拟器失败: {}", result.getOrDefault("message", "未知错误"));
                Map<String, Object> r = new HashMap<>();
                r.put("success", false);
                r.put("message", result.getOrDefault("message", "删除失败"));
                return r;
            }
        } catch (Exception e) {
            log.error("通过 Agent 删除模拟器失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 通过 Agent 停止模拟器
     * @param userId 用户 ID
     * @param index 模拟器索引 (0-based)
     * @return 操作结果
     */
    public Map<String, Object> stopEmulatorOnAgent(Long userId, int index) {
        List<AgentRegistration> agents = getOnlineAgentsByUserId(userId);
        if (agents.isEmpty()) {
            log.warn("通过 Agent 停止模拟器失败: 无在线 Agent, userId={}", userId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "无在线 Agent");
            return result;
        }

        AgentRegistration agent = agents.get(0);
        Map<String, Object> params = new HashMap<>();
        params.put("index", index);

        try {
            Map<String, Object> result = sendCommandAndWait(agent.getDeviceId(), "STOP_EMULATOR", params)
                .get(30, TimeUnit.SECONDS);

            if ("SUCCESS".equals(result.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null) {
                    return data;
                }
                Map<String, Object> r = new HashMap<>();
                r.put("success", true);
                return r;
            } else {
                log.warn("通过 Agent 停止模拟器失败: {}", result.getOrDefault("message", "未知错误"));
                Map<String, Object> r = new HashMap<>();
                r.put("success", false);
                r.put("message", result.getOrDefault("message", "停止失败"));
                return r;
            }
        } catch (Exception e) {
            log.error("通过 Agent 停止模拟器失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 通过 Agent 检查模拟器是否存在
     * @param userId 用户 ID
     * @param index 模拟器索引 (0-based)
     * @return 是否存在
     */
    /**
     * 通过 deviceId 从指定 Agent 获取物理模拟器列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEmulatorsFromAgentByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            log.warn("getEmulatorsFromAgentByDeviceId: deviceId 为空");
            return null;
        }
        AgentRegistration agent = onlineAgents.get(deviceId);
        if (agent == null || !"ONLINE".equals(agent.getStatus())) {
            log.warn("getEmulatorsFromAgentByDeviceId: Agent 不在线, deviceId={}", deviceId);
            return null;
        }
        try {
            Map<String, Object> result = sendCommandAndWait(deviceId, "GET_EMULATORS", null)
                .get(30, TimeUnit.SECONDS);
            if ("SUCCESS".equals(result.get("status"))) {
                List<Map<String, Object>> emuList = null;
                Object emulatorsTop = result.get("emulators");
                if (emulatorsTop instanceof List) {
                    emuList = (List<Map<String, Object>>) emulatorsTop;
                }
                if (emuList == null) {
                    Object dataObj = result.get("data");
                    if (dataObj instanceof Map) {
                        Object emulatorsNested = ((Map<String, Object>) dataObj).get("emulators");
                        if (emulatorsNested instanceof List) {
                            emuList = (List<Map<String, Object>>) emulatorsNested;
                        }
                    }
                }
                if (emuList != null) {
                    log.info("从 Agent(deviceId={}) 获取到 {} 个模拟器", deviceId, emuList.size());
                    return emuList;
                }
                return new ArrayList<>();
            }
            log.warn("getEmulatorsFromAgentByDeviceId: 命令失败, deviceId={}", deviceId);
            return null;
        } catch (Exception e) {
            log.error("getEmulatorsFromAgentByDeviceId 异常, deviceId={}", deviceId, e);
            return null;
        }
    }

    public boolean emulatorExistsOnAgent(Long userId, int index) {
        List<Map<String, Object>> emulators = getEmulatorsFromAgent(userId);
        if (emulators == null) return false;
        
        for (Map<String, Object> emu : emulators) {
            Object idx = emu.get("index");
            if (idx instanceof Number && ((Number) idx).intValue() == index) {
                return true;
            }
        }
        return false;
    }

    /**
     * 断开指定 Agent 的连接
     */
    public Map<String, Object> disconnectAgent(String deviceId) {
        Map<String, Object> result = new HashMap<>();
        try {
            String sessionId = deviceSessionMap.get(deviceId);
            if (sessionId != null) {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    session.close(CloseStatus.NORMAL);
                    log.info("Agent 已主动断开: deviceId={}", deviceId);
                }
                sessions.remove(sessionId);
                deviceSessionMap.remove(deviceId);
            }
            AgentRegistration agent = onlineAgents.remove(deviceId);
            if (agent != null) {
                agent.setStatus("OFFLINE");
                agent.setUpdatedAt(Instant.now());
                agentRepository.save(agent);
            }
            result.put("success", true);
            result.put("message", "Agent 已断开");
        } catch (Exception e) {
            log.error("断开 Agent 失败: deviceId={}", deviceId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 删除指定 Agent 注册记录
     */
    public Map<String, Object> deleteAgent(Long userId, String deviceId) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 先断开连接
            disconnectAgent(deviceId);
            
            // 删除数据库记录
            agentRepository.findByUserIdAndDeviceId(userId, deviceId)
                .ifPresent(agentRepository::delete);
            
            log.info("Agent 注册记录已删除: userId={}, deviceId={}", userId, deviceId);
            result.put("success", true);
            result.put("message", "Agent 已删除");
        } catch (Exception e) {
            log.error("删除 Agent 失败: userId={}, deviceId={}", userId, deviceId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

}
