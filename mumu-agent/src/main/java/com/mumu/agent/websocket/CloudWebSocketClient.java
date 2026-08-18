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
import java.util.*;
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
        AgentMessage msg = new AgentMessage();
        msg.setType("REGISTER");
        msg.setMessage("Agent 已注册");
        msg.setDeviceId(getDeviceId());
        msg.setUserId(getUserId());
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
    
    @SuppressWarnings("unchecked")
    private void handleMessage(AgentMessage msg) {
        String type = msg.getType();
        String taskId = msg.getTaskId();
        Map<String, Object> params = msg.getParams();
        
        log.info("收到云端指令: type={}, taskId={}", type, taskId);
        
        if ("REGISTER_ACK".equals(type) || "ERROR".equals(type)) {
            log.info("收到握手响应: type={}, params={}", type, params);
            return;
        }
        
        try {
            Map<String, Object> result = switch (type) {
                case "CREATE_EMULATOR" -> handleCreateEmulator(params);
                case "START_EMULATOR" -> handleStartEmulator(params);
                case "STOP_EMULATOR" -> handleStopEmulator(params);
                case "RESTART_EMULATOR" -> handleRestartEmulator(params);
                case "DELETE_EMULATOR" -> handleDeleteEmulator(params);
                case "INSTALL_APK" -> handleInstallApk(params);
                case "LAUNCH_DISCORD" -> handleLaunchDiscord(params);
                case "ADD_FRIEND" -> handleAddFriend(params);
                case "CHECK_STATUS" -> handleCheckStatus();
                case "BATCH_START" -> handleBatchStart(params);
                case "BATCH_STOP" -> handleBatchStop(params);
                case "BATCH_RESTART" -> handleBatchRestart(params);
                case "BATCH_DELETE" -> handleBatchDelete(params);
                case "BATCH_INSTALL_APK" -> handleBatchInstallApk(params);
                case "BATCH_START_AUTO_ADD" -> handleBatchStartAutoAdd(params);
                case "BATCH_STOP_AUTO_ADD" -> handleBatchStopAutoAdd(params);
                default -> {
                    log.warn("未知消息类型: {}", type);
                    Map<String, Object> error = new HashMap<>();
                    error.put("status", "FAILED");
                    error.put("message", "未知消息类型: " + type);
                    yield error;
                }
            };
            
            sendCommandResult(taskId, result);
        } catch (Exception e) {
            log.error("处理指令失败: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "FAILED");
            error.put("message", e.getMessage());
            sendCommandResult(taskId, error);
        }
    }
    
    private Map<String, Object> handleCreateEmulator(Map<String, Object> params) {
        // 支持两种模式：指定 index 创建，或指定 count 批量创建
        if (params.containsKey("count")) {
            int count = ((Number) params.get("count")).intValue();
            int cpuCores = params.containsKey("cpuCores") ? ((Number) params.get("cpuCores")).intValue() : 2;
            int memoryGb = params.containsKey("memoryGb") ? ((Number) params.get("memoryGb")).intValue() : 2;
            return emulatorService.setEmulatorCount(count, cpuCores, memoryGb);
        } else {
            // 云端使用 1-based index，MuMuManager 使用 0-based index
            int cloudIndex = ((Number) params.get("index")).intValue();
            int muMuIndex = cloudIndex - 1;
            return emulatorService.createEmulator(muMuIndex);
        }
    }

    private Map<String, Object> handleStartEmulator(Map<String, Object> params) {
        // 云端使用 1-based index，MuMuManager 使用 0-based index
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        return emulatorService.startEmulator(muMuIndex);
    }

    private Map<String, Object> handleStopEmulator(Map<String, Object> params) {
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        return emulatorService.stopEmulator(muMuIndex);
    }

    private Map<String, Object> handleRestartEmulator(Map<String, Object> params) {
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        return emulatorService.restartEmulator(muMuIndex);
    }

    private Map<String, Object> handleDeleteEmulator(Map<String, Object> params) {
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        return emulatorService.deleteEmulator(muMuIndex);
    }

    private Map<String, Object> handleInstallApk(Map<String, Object> params) {
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        String apkUrl = (String) params.get("apkUrl");
        String result = discordService.installDiscord(muMuIndex, apkUrl);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", result);
        return response;
    }

    private Map<String, Object> handleLaunchDiscord(Map<String, Object> params) {
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        String result = discordService.launchDiscord(muMuIndex);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", result);
        return response;
    }

    private Map<String, Object> handleAddFriend(Map<String, Object> params) {
        int cloudIndex = ((Number) params.get("index")).intValue();
        int muMuIndex = cloudIndex - 1;
        String username = (String) params.get("username");
        String result = discordService.addFriendByUsername(muMuIndex, username);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", result);
        return response;
    }
    
    private Map<String, Object> handleCheckStatus() {
        AgentStatus status = collectStatus();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("data", status);
        return response;
    }

    // 辅助方法：将云端的 1-based index 列表转换为 MuMuManager 的 0-based index 列表
    private List<Integer> convertToMuMuIndices(List<Integer> cloudIndices) {
        if (cloudIndices == null) return null;
        return cloudIndices.stream()
            .map(i -> i - 1)
            .collect(java.util.stream.Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchStart(Map<String, Object> params) {
        List<Integer> indices = params.containsKey("indices")
            ? convertToMuMuIndices((List<Integer>) params.get("indices"))
            : emulatorService.getEmulatorList().stream()
                .filter(e -> "STOPPED".equals(e.getStatus()))
                .map(e -> e.getIndex())
                .collect(java.util.stream.Collectors.toList());
        return emulatorService.batchStart(indices);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchStop(Map<String, Object> params) {
        List<Integer> indices = params.containsKey("indices")
            ? convertToMuMuIndices((List<Integer>) params.get("indices"))
            : emulatorService.getEmulatorList().stream()
                .filter(e -> "RUNNING".equals(e.getStatus()))
                .map(e -> e.getIndex())
                .collect(java.util.stream.Collectors.toList());
        return emulatorService.batchStop(indices);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchRestart(Map<String, Object> params) {
        List<Integer> indices = convertToMuMuIndices((List<Integer>) params.get("indices"));
        return emulatorService.batchRestart(indices);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchDelete(Map<String, Object> params) {
        List<Integer> indices = convertToMuMuIndices((List<Integer>) params.get("indices"));
        return emulatorService.batchDelete(indices);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchInstallApk(Map<String, Object> params) {
        List<Integer> indices = convertToMuMuIndices((List<Integer>) params.get("indices"));
        String apkUrl = (String) params.get("apkUrl");
        return batchService.batchInstallApk(indices, apkUrl);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchStartAutoAdd(Map<String, Object> params) {
        List<Integer> indices = convertToMuMuIndices((List<Integer>) params.get("indices"));
        List<String> usernames = (List<String>) params.get("usernames");
        String taskId = (String) params.get("taskId");
        return batchService.batchStartAutoAdd(indices, usernames, taskId);
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleBatchStopAutoAdd(Map<String, Object> params) {
        List<Integer> indices = convertToMuMuIndices((List<Integer>) params.get("indices"));
        return batchService.batchStopAutoAdd(indices);
    }
    
    private void sendCommandResult(String taskId, Map<String, Object> result) {
        log.info("发送指令结果: taskId={}, status={}", taskId, result.getOrDefault("status", "UNKNOWN"));
        AgentMessage response = AgentMessage.of("TASK_RESULT", taskId, result);
        Map<String, Object> params = new HashMap<>();
        params.put("status", result.getOrDefault("status", "UNKNOWN"));
        response.setParams(params);
        response.setData(result);
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
