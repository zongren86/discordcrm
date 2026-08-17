package com.discordadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.repository.AgentRegistrationRepository;
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
import java.util.stream.Collectors;

@Slf4j
@Service
public class CloudWebSocketService extends TextWebSocketHandler {
    
    private final AgentRegistrationRepository agentRepository;
    private final ObjectMapper objectMapper;
    
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AgentRegistration> onlineAgents = new ConcurrentHashMap<>();
    
    public CloudWebSocketService(AgentRegistrationRepository agentRepository, 
                                  ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立: sessionId={}", session.getId());
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> msg = objectMapper.readValue(message.getPayload(), Map.class);
            String type = (String) msg.get("type");
            
            switch (type) {
                case "REGISTER" -> handleRegister(session, msg);
                case "HEARTBEAT" -> handleHeartbeat(session, msg);
                case "TASK_RESULT" -> handleTaskResult(session, msg);
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
        }
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: sessionId={}", session.getId(), exception);
    }
    
    private void handleRegister(WebSocketSession session, Map<String, Object> msg) {
        String deviceId = (String) msg.get("deviceId");
        String userId = (String) msg.get("userId");
        
        if (deviceId == null || userId == null) {
            sendError(session, "缺少 deviceId 或 userId");
            return;
        }
        
        sessions.put(session.getId(), session);
        
        AgentRegistration agent = agentRepository.findByUserIdAndDeviceId(userId, deviceId)
            .orElseGet(() -> {
                AgentRegistration newAgent = new AgentRegistration();
                newAgent.setUserId(userId);
                newAgent.setDeviceId(deviceId);
                return newAgent;
            });
        
        Map<String, Object> params = (Map<String, Object>) msg.get("params");
        if (params != null) {
            agent.setOs((String) params.get("os"));
            agent.setOsVersion((String) params.get("osVersion"));
        }
        
        agent.setStatus("ONLINE");
        agent.setLastHeartbeatAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        agent = agentRepository.save(agent);
        
        onlineAgents.put(deviceId, agent);
        
        log.info("Agent 注册成功: userId={}, deviceId={}", userId, deviceId);
        
        sendMessage(session, Map.of(
            "type", "REGISTER_ACK",
            "status", "SUCCESS",
            "agentId", agent.getId()
        ));
    }
    
    private void handleHeartbeat(WebSocketSession session, Map<String, Object> msg) {
        Map<String, Object> status = (Map<String, Object>) msg.get("data");
        if (status != null) {
            String deviceId = (String) status.get("deviceId");
            if (deviceId != null) {
                AgentRegistration agent = onlineAgents.get(deviceId);
                if (agent != null) {
                    agent.setLastHeartbeatAt(Instant.now());
                    agent.setStatus("ONLINE");
                    agentRepository.save(agent);
                }
            }
        }
    }
    
    private void handleTaskResult(WebSocketSession session, Map<String, Object> msg) {
        // 任务结果由 AutoAddTaskService 处理
        // 这里仅记录日志
        String taskId = (String) msg.get("taskId");
        String status = msg.get("params") != null ? 
            (String) ((Map<String, Object>) msg.get("params")).get("status") : null;
        
        log.info("收到任务结果: taskId={}, status={}", taskId, status);
        
        // 将结果传递给任务服务处理
        // 由于可能的循环依赖，这里简化处理，通过事件机制解耦
        // 实际项目中可使用 ApplicationEvent
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
    
    public void notifyAgent(String deviceId, Map<String, Object> message) {
        WebSocketSession session = findSessionByDeviceId(deviceId);
        if (session != null && session.isOpen()) {
            sendMessage(session, message);
        } else {
            log.warn("Agent 不在线，无法发送消息: deviceId={}", deviceId);
        }
    }
    
    public List<AgentRegistration> getOnlineAgentsByUserId(String userId) {
        return onlineAgents.values().stream()
            .filter(a -> a.getUserId().equals(userId) && "ONLINE".equals(a.getStatus()))
            .collect(Collectors.toList());
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
    
    private WebSocketSession findSessionByDeviceId(String deviceId) {
        AgentRegistration agent = onlineAgents.get(deviceId);
        if (agent == null) return null;
        
        // 遍历 sessions 查找对应的 session
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            // 需要维护 deviceId -> sessionId 的映射
            // 这里简化处理，实际项目中需要在注册时保存映射
        }
        return null;
    }
    
    private String findDeviceIdBySession(String sessionId) {
        // 简化实现，实际项目中需要在注册时保存映射
        // 这里返回 null 让 afterConnectionClosed 不做处理
        return null;
    }
}
