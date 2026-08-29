package com.discordadmin.controller;

import com.discordadmin.entity.AgentServer;
import com.discordadmin.service.AgentServerService;
import com.discordadmin.service.AgentTaskService;
import com.discordadmin.entity.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import java.io.*;
import java.nio.file.*;
import java.util.stream.*;
import java.util.zip.*;
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

    @Value("${app.agent-source-dir:}")
    private String agentSourceDir;
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
    // ============ crm_agent 包下载 ============

    /** 下载 crm_agent 完整包（动态打包源码） */
    @GetMapping("/package")
    public ResponseEntity<Resource> downloadAgentPackage() {
        try {
            Path sourceDir = resolveAgentSourceDir();
            if (sourceDir == null || !Files.isDirectory(sourceDir)) {
                return ResponseEntity.status(500).body(null);
            }
            byte[] zipBytes = buildZip(sourceDir);
            ByteArrayResource resource = new ByteArrayResource(zipBytes);
            String filename = "crm_agent-v0.1.0.zip";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                    .contentLength(zipBytes.length)
                    .body(resource);
        } catch (Exception e) {
            log.error("打包 agent 包失败", e);
            return ResponseEntity.status(500).body(null);
        }
    }

    /** 获取包信息 + 安装说明 */
    @GetMapping("/package-info")
    public ResponseEntity<Map<String, Object>> packageInfo(HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + ((request.getServerPort() == 80 || request.getServerPort() == 443) ? "" : ":" + request.getServerPort())
                + request.getContextPath();
        Map<String, Object> result = new HashMap<>();
        result.put("version", "0.1.0");
        result.put("downloadUrl", baseUrl + "/api/agent-servers/package");
        result.put("filename", "crm_agent-v0.1.0.zip");
        result.put("requiresNode", ">=18");
        result.put("requiresPlaywright", "chromium");
        result.put("steps", List.of(
                Map.of("step", 1, "title", "解压安装包", "desc", "将 crm_agent-v0.1.0.zip 解压到任意目录，如 ~/crm_agent"),
                Map.of("step", 2, "title", "安装依赖", "desc", "进入目录执行: npm install"),
                Map.of("step", 3, "title", "安装浏览器", "desc", "执行: npx playwright install chromium（首次必做，~180MB）"),
                Map.of("step", 4, "title", "配置节点", "desc", "复制 config.example.json 为 config.json，填入后端地址 + 节点 token"),
                Map.of("step", 5, "title", "启动 Agent", "desc", "执行: node src/index.js，看到 [就绪] 等待任务... 即启动成功"),
                Map.of("step", 6, "title", "验证在线", "desc", "回到后台「代理管理」页面，节点状态应为在线")
        ));
        result.put("configTemplate", "{\n" +
                "  \"serverUrl\": \"http://127.0.0.1:8090/api\",\n" +
                "  \"agentName\": \"crm-agent-01\",\n" +
                "  \"token\": \"在此粘贴前端生成的token\",\n" +
                "  \"heartbeatIntervalMs\": 30000,\n" +
                "  \"pollIntervalMs\": 5000,\n" +
                "  \"browser\": {\n" +
                "    \"headless\": false,\n" +
                "    \"type\": \"chromium\",\n" +
                "    \"userDataDir\": \"./data/browser-profile\"\n" +
                "  }\n" +
                "}");
        return ResponseEntity.ok(result);
    }

    /** 定位 crm_agent 源码目录 */
    private Path resolveAgentSourceDir() {
        // 1. 用显式配置
        if (agentSourceDir != null && !agentSourceDir.isBlank()) {
            Path p = Paths.get(agentSourceDir);
            if (Files.isDirectory(p)) return p;
        }
        // 2. 自动推断：从 user.dir 向上找 crm_agent
        try {
            Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            for (Path p = cwd; p != null; p = p.getParent()) {
                Path candidate = p.resolve("crm_agent");
                if (Files.isDirectory(candidate) && Files.exists(candidate.resolve("package.json"))) {
                    return candidate;
                }
            }
            // 同级别兄弟目录
            if (cwd.getParent() != null) {
                Path sibling = cwd.getParent().resolve("crm_agent");
                if (Files.isDirectory(sibling)) return sibling;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 把 crm_agent 目录打包成 zip（排除 node_modules / data / .git 等） */
    private byte[] buildZip(Path sourceDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            String baseName = "crm_agent";
            try (Stream<Path> walk = Files.walk(sourceDir)) {
                walk.forEach(p -> {
                    try {
                        Path rel = sourceDir.relativize(p);
                        String relStr = rel.toString().replace('\\', '/');
                        // 排除目录
                        String[] skipDirs = {"node_modules", "data", ".git", ".idea", ".vscode", "__pycache__"};
                        for (String skip : skipDirs) {
                            if (relStr.equals(skip) || relStr.startsWith(skip + "/")) return;
                        }
                        // 排除文件
                        String[] skipFiles = {".DS_Store", "package-lock.json", "config.json"};
                        for (String skip : skipFiles) {
                            if (relStr.equals(skip)) return;
                        }
                        // 排除隐藏文件
                        if (relStr.startsWith(".")) return;
                        // 空目录跳过
                        if (Files.isDirectory(p)) return;

                        String entryName = baseName + "/" + relStr;
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (Exception ignored) {}
                });
            }
            // 追加安装说明 README_INSTALL.txt
            String readme = buildInstallReadme();
            zos.putNextEntry(new ZipEntry(baseName + "/README_INSTALL.txt"));
            zos.write(readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private String buildInstallReadme() {
        return "==============================================\n" +
                "  crm_agent v0.1.0 — 安装说明\n" +
                "==============================================\n" +
                "\n" +
                "【1. 环境要求】\n" +
                "  - Node.js >= 18  (https://nodejs.org/)\n" +
                "  - macOS / Linux / Windows\n" +
                "\n" +
                "【2. 安装依赖】\n" +
                "  cd crm_agent\n" +
                "  npm install\n" +
                "\n" +
                "【3. 安装 Playwright Chromium】\n" +
                "  npx playwright install chromium\n" +
                "  （首次必做，~180MB 下载）\n" +
                "\n" +
                "【4. 配置节点】\n" +
                "  cp config.example.json config.json\n" +
                "  然后编辑 config.json：\n" +
                "    serverUrl  = 后端 API 地址，如 http://127.0.0.1:8090/api\n" +
                "    agentName  = 节点名称（需与前端创建时一致）\n" +
                "    token      = 前端「代理管理」页面生成的 token\n" +
                "\n" +
                "【5. 启动】\n" +
                "  node src/index.js\n" +
                "  看到 [就绪] 等待任务... 即启动成功\n" +
                "\n" +
                "【6. 验证】\n" +
                "  回到后台「代理管理」页面，节点状态应为在线\n" +
                "\n" +
                "【7. 后台启动（可选）】\n" +
                "  nohup node src/index.js > agent.log 2>&1 &\n" +
                "\n";
    }

}
