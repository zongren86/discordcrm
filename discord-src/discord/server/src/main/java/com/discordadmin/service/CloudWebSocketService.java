package com.discordadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.entity.EmuInstance;
import com.discordadmin.repository.AgentRegistrationRepository;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.EmuInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
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
            String userId = params.get("userId");
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
        String deviceId = (String) msg.get("deviceId");
        String userId = (String) msg.get("userId");
        
        Object paramsObj = msg.get("params");
        Map<String, Object> params = (paramsObj instanceof Map) ? (Map<String, Object>) paramsObj : null;
        if (params != null) {
            if (deviceId == null) deviceId = (String) params.get("deviceId");
            if (userId == null) userId = (String) params.get("userId");
        }
        
        final String finalDeviceId = deviceId;
        final String finalUserId = userId;
        
        log.info("处理 REGISTER: userId={}, deviceId={}", finalUserId, finalDeviceId);
        
        if (finalDeviceId == null || finalUserId == null) {
            sendError(session, "缺少 deviceId 或 userId");
            return;
        }
        
        try {
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
                Long merchantIdFromAgent = agentEntityRepository.findByUsername(finalUserId)
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
                finalUserId, finalDeviceId, agent.getMerchantId());
            
            sendMessage(session, Map.of(
                "type", "REGISTER_ACK",
                "status", "SUCCESS",
                "agentId", agent.getId()
            ));
        } catch (Exception e) {
            log.error("Agent 注册失败: userId={}, deviceId={}", finalUserId, finalDeviceId, e);
            sendError(session, "注册失败: " + e.getMessage());
        }
    }
    
    private void handleHeartbeat(WebSocketSession session, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        if (data != null) {
            String deviceId = (String) data.get("deviceId");
            if (deviceId != null) {
                AgentRegistration agent = onlineAgents.get(deviceId);
                if (agent == null) {
                    // 从数据库查找或创建
                    final String finalDeviceId = deviceId;
                    agent = agentRepository.findByUserIdAndDeviceId(
                        (String) data.get("userId"), deviceId)
                        .orElseGet(() -> {
                            AgentRegistration newAgent = new AgentRegistration();
                            newAgent.setUserId((String) data.get("userId"));
                            newAgent.setDeviceId(finalDeviceId);
                            return newAgent;
                        });
                    // 尝试设置 merchantId
                    if (agent.getMerchantId() == null && agent.getUserId() != null) {
                        agentEntityRepository.findByUsername(agent.getUserId())
                            .map(Agent::getMerchantId)
                            .ifPresent(agent::setMerchantId);
                    }
                    onlineAgents.put(deviceId, agent);
                    log.info("心跳恢复 Agent: deviceId={}, userId={}", deviceId, agent.getUserId());
                }
                agent.setLastHeartbeatAt(Instant.now());
                agent.setStatus("ONLINE");
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
            
            String userId = agent.getUserId();
            if (userId == null || userId.isEmpty()) {
                log.warn("心跳同步: userId 为空，跳过同步");
                return;
            }
            
            // 获取 merchantId，如果 AgentRegistration 中没有，则通过 AgentRepository 查找
            Long merchantId = agent.getMerchantId();
            if (merchantId == null) {
                merchantId = agentEntityRepository.findByUsername(userId)
                    .map(Agent::getMerchantId)
                    .orElse(null);
                // 回写 merchantId 到 AgentRegistration
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
            int createdCount = 0;
            for (Map<String, Object> emuData : emulators) {
                log.info("心跳同步: 模拟器数据={}", emuData);
                Integer muMuIndex = emuData.get("index") != null ? ((Number) emuData.get("index")).intValue() : null;
                if (muMuIndex == null) {
                    log.warn("心跳同步: 模拟器缺少 index 字段");
                    continue;
                }
                // MuMuManager 使用 0-based index，云端使用 1-based instanceIndex
                Integer cloudIndex = muMuIndex + 1;
                String status = (String) emuData.get("status");
                Integer adbPort = emuData.get("adbPort") != null ? ((Number) emuData.get("adbPort")).intValue() : null;
                String name = (String) emuData.get("name");
                Integer cpuCores = emuData.get("cpuCount") != null ? ((Number) emuData.get("cpuCount")).intValue() : 2;
                Integer memoryGb = emuData.get("memoryMB") != null ? ((Number) emuData.get("memoryMB")).intValue() / 1024 : 2;
                
                log.info("心跳同步: 处理模拟器 muMuIndex={}, cloudIndex={}, existingMap.containsKey={}", muMuIndex, cloudIndex, existingMap.containsKey(cloudIndex));
                
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
                    // 自动创建不存在的模拟器记录
                    instance = new EmuInstance();
                    instance.setMerchantId(merchantId);
                    instance.setUserId(userId);
                    instance.setInstanceIndex(cloudIndex);
                    instance.setName(name != null ? name : ("模拟器 #" + cloudIndex));
                    instance.setStatus(mapStatus(status));
                    instance.setCpuCores(cpuCores);
                    instance.setMemoryGb(memoryGb);
                    instance.setAdbPort(adbPort);
                    instance.setCreatedAt(Instant.now());
                    instance.setUpdatedAt(Instant.now());
                    instanceRepository.save(instance);
                    createdCount++;
                    log.info("心跳同步: 自动创建模拟器 #{}, name={}", cloudIndex, name);
                }
            }
            
            log.info("心跳同步完成: agent={}, 处理了 {} 个模拟器，更新了 {} 个，创建了 {} 个", agent.getDeviceId(), emulators.size(), updatedCount, createdCount);
        } catch (Exception e) {
            log.warn("同步模拟器状态失败: {}", e.getMessage());
        }
    }
    
    private EmuInstance.EmuStatus mapStatus(String status) {
        return switch (status) {
            case "RUNNING" -> EmuInstance.EmuStatus.RUNNING;
            case "STOPPED" -> EmuInstance.EmuStatus.STOPPED;
            case "CREATED" -> EmuInstance.EmuStatus.CREATED;
            default -> null;
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
    public List<CompletableFuture<Map<String, Object>>> broadcastCommandToUser(String userId, String commandType, Map<String, Object> params) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        
        for (AgentRegistration agent : onlineAgents.values()) {
            if (agent.getUserId().equals(userId) && "ONLINE".equals(agent.getStatus())) {
                futures.add(sendCommandAndWait(agent.getDeviceId(), commandType, params));
            }
        }
        
        return futures;
    }
    
    /**
     * 获取指定 userId 的在线 Agent；若 userId 为空则返回所有在线 Agent
     * 优先从内存 Map 获取，如果内存为空则从数据库回退查询
     */
    public List<AgentRegistration> getOnlineAgentsByUserId(String userId) {
        List<AgentRegistration> result = onlineAgents.values().stream()
            .filter(a -> "ONLINE".equals(a.getStatus()))
            .filter(a -> userId == null || userId.isEmpty() || userId.equals(a.getUserId()))
            .collect(java.util.stream.Collectors.toList());

        if (!result.isEmpty()) {
            return result;
        }

        // 回退：从数据库查询最近 2 分钟内心跳过的 Agent
        Instant threshold = Instant.now().minusSeconds(120);
        return agentRepository.findAll().stream()
            .filter(a -> "ONLINE".equals(a.getStatus()))
            .filter(a -> a.getLastHeartbeatAt() != null && a.getLastHeartbeatAt().isAfter(threshold))
            .filter(a -> userId == null || userId.isEmpty() || userId.equals(a.getUserId()))
            .peek(a -> {
                if (!onlineAgents.containsKey(a.getDeviceId())) {
                    onlineAgents.put(a.getDeviceId(), a);
                    log.info("从数据库恢复在线 Agent: deviceId={}, userId={}", a.getDeviceId(), a.getUserId());
                }
            })
            .collect(java.util.stream.Collectors.toList());
    }
    
    public boolean isAgentOnline(String deviceId) {
        AgentRegistration agent = onlineAgents.get(deviceId);
        return agent != null && "ONLINE".equals(agent.getStatus());
    }
    
    public AgentRegistration getAgentByDeviceId(String deviceId) {
        return onlineAgents.get(deviceId);
    }
    
    public List<AgentRegistration> getAllOnlineAgents() {
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
}
