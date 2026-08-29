package com.discordadmin.controller;

import com.discordadmin.entity.AgentServer;
import com.discordadmin.service.AgentServerService;
import com.discordadmin.service.AgentTaskService;
import com.discordadmin.entity.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/agent-servers")
@RequiredArgsConstructor
@Slf4j
public class AgentServerController {

    private final AgentServerService agentServerService;
    private final AgentTaskService agentTaskService;
    private final ObjectMapper objectMapper;

    /** 节点列表（隐藏 token） */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<AgentServer> servers = agentServerService.list();
        List<Map<String, Object>> masked = servers.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("name", s.getName());
            m.put("token", "******");
            m.put("serverAddress", s.getServerAddress());
            m.put("merchantId", s.getMerchantId());
            m.put("status", s.getStatus());
            m.put("nodeVersion", s.getNodeVersion());
            m.put("browserType", s.getBrowserType());
            m.put("notes", s.getNotes());
            m.put("lastSeenAt", s.getLastSeenAt());
            m.put("createdAt", s.getCreatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(masked);
    }

    /** 新增节点 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String notes = body.get("notes");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "节点名称不能为空"));
        }
        AgentServer created = agentServerService.create(name.trim(), notes);
        Map<String, Object> result = new HashMap<>();
        result.put("id", created.getId());
        result.put("name", created.getName());
        result.put("token", created.getToken());  // 仅这一次返回明文
        result.put("message", "保存成功！token 仅显示一次，请妥善保管。");
        return ResponseEntity.ok(result);
    }

    /** 重置 token */
    @PostMapping("/{id}/reset-token")
    public ResponseEntity<Map<String, Object>> resetToken(@PathVariable Long id) {
        AgentServer updated = agentServerService.resetToken(id);
        Map<String, Object> result = new HashMap<>();
        result.put("id", updated.getId());
        result.put("name", updated.getName());
        result.put("token", updated.getToken());
        result.put("message", "token 已重置！请将新 token 更新到代理服务器配置中。");
        return ResponseEntity.ok(result);
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        agentServerService.delete(id);
        return ResponseEntity.ok(Map.of("message", "删除成功"));
    }

    /** 创建任务（前端调用，分配给指定 agent） */
    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> createTask(@RequestBody Map<String, Object> body) {
        Long agentServerId = Long.valueOf(body.get("agentServerId").toString());
        String type = (String) body.get("type");
        String paramsJson = body.get("params") != null ? safeJson(body.get("params")) : null;
        AgentTask task = agentTaskService.createTask(agentServerId, type, paramsJson);
        return ResponseEntity.ok(Map.of("id", task.getId(), "status", task.getStatus(), "type", task.getType()));
    }

    /** agent poll（免登录，用 token 认证） */
    @PostMapping("/tasks/poll")
    public ResponseEntity<?> pollTask(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null) return ResponseEntity.status(401).body(Map.of("error", "缺少 token"));
        try {
            Optional<AgentTask> task = agentTaskService.pollNext(token, body.get("agentName"));
            if (task.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "无任务"));
            AgentTask t = task.get();
            Map<String, Object> resp = new HashMap<>();
            resp.put("id", t.getId());
            resp.put("type", t.getType());
            resp.put("status", t.getStatus());
            resp.put("params", t.getParams() != null ? safeParse(t.getParams()) : null);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** agent report（免登录，用 token 认证） */
    @PostMapping("/tasks/report")
    public ResponseEntity<Map<String, Object>> reportTask(@RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        if (token == null) return ResponseEntity.status(401).body(Map.of("error", "缺少 token"));
        Long taskId = Long.valueOf(body.get("taskId").toString());
        String status = (String) body.get("status");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        try {
            AgentTask task = agentTaskService.reportTask(token, taskId, status, result);
            return ResponseEntity.ok(Map.of("id", task.getId(), "status", task.getStatus()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    /** 查询任务详情（前端轮询用） */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<?> getTask(@PathVariable Long id) {
        return agentTaskService.findById(id)
                .map(t -> ResponseEntity.ok(Map.of(
                        "id", t.getId(),
                        "type", t.getType(),
                        "status", t.getStatus(),
                        "result", t.getResult(),
                        "createdAt", t.getCreatedAt()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    private String safeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
    private Object safeParse(String json) {
        try { return objectMapper.readValue(json, Object.class); } catch (Exception e) { return json; }
    }

    /** agent 心跳（免登录，用 token 认证） */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "缺少 token"));
        }
        try {
            AgentServer server = agentServerService.heartbeat(
                    token,
                    body.get("serverAddress"),
                    body.get("nodeVersion"),
                    body.get("browserType")
            );
            Map<String, Object> result = new HashMap<>();
            result.put("id", server.getId());
            result.put("name", server.getName());
            result.put("status", server.getStatus());
            result.put("message", "heartbeat ok");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}
