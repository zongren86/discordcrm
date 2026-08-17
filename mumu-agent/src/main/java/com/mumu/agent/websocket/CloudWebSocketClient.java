package com.mumu.agent.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mumu.agent.config.AgentConfig;
import com.mumu.agent.model.AgentMessage;
import com.mumu.agent.model.AgentStatus;
import com.mumu.agent.service.BatchOperationService;
import com.mumu.agent.service.EmulatorService;
import com.mumu.agent.service.DiscordAutomationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CloudWebSocketClient {
    
    private final AgentConfig agentConfig;
    private final EmulatorService emulatorService;
    private final DiscordAutomationService discordService;
    private final BatchOperationService batchService;
    private final ObjectMapper objectMapper;
    
    private volatile WebSocketSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    
    public CloudWebSocketClient(AgentConfig agentConfig,
                                 EmulatorService emulatorService,
                                 DiscordAutomationService discordService,
                                 BatchOperationService batchService,
                                 ObjectMapper objectMapper) {
        this.agentConfig = agentConfig;
        this.emulatorService = emulatorService;
        this.discordService = discordService;
        this.batchService = batchService;
        this.objectMapper = objectMapper;
    }
    
    @PostConstruct
    public void init() {
        connect();
    }
    
    @PreDestroy
    public void destroy() {
        disconnect();
    }
    
    private void connect() {
        try {
            String url = agentConfig.getCloudWebsocketUrl() 
                + "?deviceId=" + java.net.URLEncoder.encode(getDeviceId(), "UTF-8")
                + "&userId=" + java.net.URLEncoder.encode(getUserId(), "UTF-8");
            
            StandardWebSocketClient client = new StandardWebSocketClient();
            TextWebSocketHandler handler = new CloudTextWebSocketHandler();
            
            this.session = client.execute(handler, url).get(10, TimeUnit.SECONDS);
            connected.set(true);
            log.info("WebSocket 已连接到云端: {}", url);
            
            sendRegister();
        } catch (Exception e) {
            log.error("连接云端 WebSocket 失败: {}", e.getMessage());
            scheduleReconnect();
        }
    }
    
    private void disconnect() {
        connected.set(false);
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.debug("关闭 WebSocket: {}", e.getMessage());
            }
        }
    }
    
    private void scheduleReconnect() {
        try {
            TimeUnit.SECONDS.sleep(5);
            if (!connected.get()) {
                log.info("尝试重新连接云端...");
                connect();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void sendRegister() {
        AgentMessage msg = AgentMessage.status("REGISTER", "Agent 已注册");
        msg.setParams(Map.of(
            "deviceId", getDeviceId(),
            "userId", getUserId(),
            "os", System.getProperty("os.name"),
            "javaVersion", System.getProperty("java.version")
        ));
        sendMessage(msg);
    }
    
    @Scheduled(fixedDelayString = "${agent.heartbeat-interval:30}000")
    public void sendHeartbeat() {
        if (!connected.get()) return;
        
        try {
            AgentStatus status = collectStatus();
            AgentMessage msg = AgentMessage.of("HEARTBEAT", null, status);
            sendMessage(msg);
        } catch (Exception e) {
            log.error("发送心跳失败: {}", e.getMessage());
        }
    }
    
    private AgentStatus collectStatus() {
        AgentStatus status = new AgentStatus();
        status.setDeviceId(getDeviceId());
        status.setUserId(getUserId());
        status.setOs(System.getProperty("os.name"));
        status.setOsVersion(System.getProperty("os.version"));
        status.setMuMuPath(emulatorService.getMuMuPath());
        status.setMuMuAvailable(emulatorService.isMuMuAvailable());
        status.setEmulatorCount(emulatorService.getEmulatorCount());
        status.setRunningCount(emulatorService.getRunningCount());
        status.setLastHeartbeat(java.time.Instant.now());
        status.setEmulators(emulatorService.getEmulatorList());
        return status;
    }
    
    private class CloudTextWebSocketHandler extends TextWebSocketHandler {
        @Override
        public void afterConnectionEstablished(WebSocketSession webSocketSession) {
            log.info("WebSocket 连接已建立");
        }
        
        @Override
        protected void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) {
            onMessageReceived(message.getPayload());
        }
        
        @Override
        public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
            log.error("WebSocket 传输错误: {}", exception.getMessage());
            connected.set(false);
        }
        
        @Override
        public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus status) {
            connected.set(false);
            log.info("WebSocket 连接已关闭: {}", status);
            scheduleReconnect();
        }
    }
    
    public void onMessageReceived(String message) {
        try {
            AgentMessage msg = objectMapper.readValue(message, AgentMessage.class);
            handleMessage(msg);
        } catch (Exception e) {
            log.error("处理消息失败: {}", e.getMessage());
        }
    }
    
    private void handleMessage(AgentMessage msg) {
        log.info("收到云端指令: type={}, taskId={}", msg.getType(), msg.getTaskId());
        
        switch (msg.getType()) {
            case "CREATE_EMULATOR" -> handleCreateEmulator(msg);
            case "START_EMULATOR" -> handleStartEmulator(msg);
            case "STOP_EMULATOR" -> handleStopEmulator(msg);
            case "DELETE_EMULATOR" -> handleDeleteEmulator(msg);
            case "INSTALL_APK" -> handleInstallApk(msg);
            case "ADD_FRIEND" -> handleAddFriend(msg);
            case "CHECK_STATUS" -> handleCheckStatus(msg);
            case "BATCH_START" -> handleBatchStart(msg);
            case "BATCH_STOP" -> handleBatchStop(msg);
            case "BATCH_RESTART" -> handleBatchRestart(msg);
            case "BATCH_DELETE" -> handleBatchDelete(msg);
            case "BATCH_INSTALL_APK" -> handleBatchInstallApk(msg);
            case "BATCH_START_AUTO_ADD" -> handleBatchStartAutoAdd(msg);
            case "BATCH_STOP_AUTO_ADD" -> handleBatchStopAutoAdd(msg);
            default -> log.warn("未知消息类型: {}", msg.getType());
        }
    }
    
    private void handleCreateEmulator(AgentMessage msg) {
        try {
            int index = (Integer) msg.getParams().get("index");
            String result = emulatorService.createEmulator(index);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void handleStartEmulator(AgentMessage msg) {
        try {
            int index = (Integer) msg.getParams().get("index");
            String result = emulatorService.startEmulator(index);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void handleStopEmulator(AgentMessage msg) {
        try {
            int index = (Integer) msg.getParams().get("index");
            String result = emulatorService.stopEmulator(index);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void handleDeleteEmulator(AgentMessage msg) {
        try {
            int index = (Integer) msg.getParams().get("index");
            String result = emulatorService.deleteEmulator(index);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void handleInstallApk(AgentMessage msg) {
        try {
            int index = (Integer) msg.getParams().get("index");
            String apkUrl = (String) msg.getParams().get("apkUrl");
            String result = discordService.installDiscord(index, apkUrl);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void handleAddFriend(AgentMessage msg) {
        try {
            int index = (Integer) msg.getParams().get("index");
            String username = (String) msg.getParams().get("username");
            String result = discordService.addFriendByUsername(index, username);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void handleCheckStatus(AgentMessage msg) {
        AgentStatus status = collectStatus();
        sendResult(msg, "SUCCESS", status);
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchStart(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            Map<String, Object> result = batchService.batchStart(indices);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchStop(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            Map<String, Object> result = batchService.batchStop(indices);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchRestart(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            Map<String, Object> result = batchService.batchRestart(indices);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchDelete(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            Map<String, Object> result = batchService.batchDelete(indices);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchInstallApk(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            String apkUrl = (String) msg.getParams().get("apkUrl");
            Map<String, Object> result = batchService.batchInstallApk(indices, apkUrl);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchStartAutoAdd(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            List<String> usernames = (List<String>) msg.getParams().get("usernames");
            String taskId = (String) msg.getParams().get("taskId");
            Map<String, Object> result = batchService.batchStartAutoAdd(indices, usernames, taskId);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void handleBatchStopAutoAdd(AgentMessage msg) {
        try {
            List<Integer> indices = (List<Integer>) msg.getParams().get("indices");
            Map<String, Object> result = batchService.batchStopAutoAdd(indices);
            sendResult(msg, "SUCCESS", result);
        } catch (Exception e) {
            sendResult(msg, "FAILED", e.getMessage());
        }
    }
    
    private void sendResult(AgentMessage originalMsg, String status, Object result) {
        AgentMessage response = AgentMessage.of("TASK_RESULT", originalMsg.getTaskId(), result);
        response.setParams(Map.of("status", status));
        sendMessage(response);
    }
    
    public void sendMessage(AgentMessage msg) {
        if (!connected.get() || session == null || !session.isOpen()) {
            log.warn("WebSocket 未连接，无法发送消息");
            return;
        }
        
        try {
            String json = objectMapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }
    
    private String getDeviceId() {
        if (agentConfig.getDeviceId() != null && !agentConfig.getDeviceId().isEmpty()) {
            return agentConfig.getDeviceId();
        }
        String osName = System.getProperty("os.name").replaceAll("\\s", "_");
        String userName = System.getProperty("user.name");
        return userName + "_" + osName;
    }
    
    private String getUserId() {
        return agentConfig.getUserId();
    }
}
