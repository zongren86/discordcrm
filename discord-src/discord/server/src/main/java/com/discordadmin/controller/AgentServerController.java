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
import java.time.Instant;
import java.time.Duration;

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
            // 动态计算在线状态：心跳间隔 30s，超过 90s 没心跳 = OFFLINE
            String dynStatus = "OFFLINE";
            if (s.getLastSeenAt() != null) {
                long secSince = Duration.between(s.getLastSeenAt(), Instant.now()).getSeconds();
                if (secSince < 90) dynStatus = "ONLINE";
            }
            m.put("status", dynStatus);
            if (s.getLastSeenAt() != null) {
                long secSince = Duration.between(s.getLastSeenAt(), Instant.now()).getSeconds();
                m.put("secSinceLastHeartbeat", secSince);
            }
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
        result.put("envCheck", List.of(
                Map.of("name", "Node.js", "command", "node -v", "minVersion", "18", "installHelp", "https://nodejs.org/"),
                Map.of("name", "npm", "command", "npm -v", "minVersion", "8", "installHelp", "随 Node.js 一起安装"),
                Map.of("name", "Playwright", "command", "npx playwright --version", "minVersion", "latest", "installHelp", "由 npm install 自动安装")
        ));
        result.put("steps", List.of(
                Map.of("step", 1, "title", "解压安装包",
                        "desc", "将 crm_agent-v0.1.0.zip 解压到任意目录",
                        "code",                         "unzip crm_agent-v0.1.0.zip\n" +
                        "mv crm_agent ~/crm_agent\n" +
                        "cd ~/crm_agent"),
                Map.of("step", 2, "title", "安装依赖",
                        "desc", "国内用户建议先配置淘宝镜像加速",
                        "code",                         "npm config set registry https://registry.npmmirror.com\n" +
                        "npm install"),
                Map.of("step", 3, "title", "安装 Playwright Chromium",
                        "desc", "首次必做，下载约 180MB",
                        "code",                         "# 临时用镜像下载（推荐）\n" +
                        "PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright \\\n" +
                        "  npx playwright install chromium\n" +
                        "\n" +
                        "# 或直接下载（慢）\n" +
                        "npx playwright install chromium"),
                Map.of("step", 4, "title", "复制并编辑配置",
                        "desc", "复制模板为 config.json，修改 3 个核心字段",
                        "code",                         "# macOS/Linux\n" +
                        "cp config.example.json config.json\n" +
                        "\n" +
                        "# Windows\n" +
                        "copy config.example.json config.json\n" +
                        "\n" +
                        "# 然后编辑 config.json，修改：\n" +
                        "#   serverUrl:  后端地址 http://x.x.x.x:8090/api\n" +
                        "#   agentName:  节点名称（需与前端一致）\n" +
                        "#   token:      前端生成的 token"),
                Map.of("step", 5, "title", "启动 Agent",
                        "desc", "前台运行（调试）或后台守护（生产）",
                        "code",                         "# 前台运行（推荐先调试，看实时日志）\n" +
                        "node src/index.js\n" +
                        "\n" +
                        "# 后台守护（稳定后推荐）\n" +
                        "# macOS/Linux:\n" +
                        "nohup node src/index.js > agent.log 2>&1 &\n" +
                        "# Windows PowerShell:\n" +
                        "Start-Process node -ArgumentList 'src/index.js' -RedirectStandardOutput 'agent.log'\n" +
                        "\n" +
                        "# 停止\n" +
                        "kill $(pgrep -f 'node src/index.js')   # macOS/Linux\n" +
                        "Stop-Process -Name node -Force          # Windows"),
                Map.of("step", 6, "title", "验证在线",
                        "desc", "看到 [就绪] 等待任务... 且前端节点变绿即成功",
                        "code",                         "# 看实时日志\n" +
                        "tail -f agent.log              # macOS/Linux\n" +
                        "Get-Content agent.log -Wait    # Windows\n" +
                        "\n" +
                        "# 或前端代理管理页面直接看状态")
        ));
        result.put("configTemplate",                 "{\n" +
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
        result.put("notes", List.of(
                "serverUrl 必须含 /api 后缀，如 http://192.168.0.110:8090/api",
                "首次启动 Chromium 会弹浏览器窗口，请在其中完成 Discord 登录",
                "Windows 用户用 PowerShell/cmd 执行，不要用 Git Bash",
                "国内用户务必配置 npm 和 Playwright 镜像，否则下载会超时"
        ));
        return ResponseEntity.ok(result);
    }
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
        return                 "==============================================\n" +
                "  crm_agent v0.1.0 — 完整安装说明\n" +
                "==============================================\n" +
                "\n" +
                "【环境要求】\n" +
                "  Node.js >= 18   https://nodejs.org/\n" +
                "  npm >= 8        （随 Node.js 安装）\n" +
                "  macOS / Linux / Windows\n" +
                "\n" +
                "【环境检测】\n" +
                "  node -v                    # 应 >= 18.x\n" +
                "  npm -v                     # 应 >= 8.x\n" +
                "  npx playwright --version   # 应能正常输出版本\n" +
                "\n" +
                "【国内加速（强烈推荐）】\n" +
                "  # npm 淘宝镜像\n" +
                "  npm config set registry https://registry.npmmirror.com\n" +
                "\n" +
                "  # Playwright Chromium 镜像（临时用）\n" +
                "  PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright npx playwright install chromium\n" +
                "\n" +
                "  # 或永久生效，加到 ~/.zshrc / ~/.bashrc：\n" +
                "  export PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright\n" +
                "\n" +
                "【Step 1 — 解压】\n" +
                "  macOS/Linux:  unzip crm_agent-v0.1.0.zip && cd crm_agent\n" +
                "  Windows PS:   Expand-Archive crm_agent-v0.1.0.zip . ; cd crm_agent\n" +
                "\n" +
                "【Step 2 — npm install】\n" +
                "  npm install\n" +
                "\n" +
                "【Step 3 — npx playwright install chromium（首次必做，~180MB）】\n" +
                "  # 已配置镜像：\n" +
                "  npx playwright install chromium\n" +
                "  # 没配置镜像就用下面这个：\n" +
                "  PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright npx playwright install chromium\n" +
                "\n" +
                "【Step 4 — 配置 config.json】\n" +
                "  cp config.example.json config.json          # macOS/Linux\n" +
                "  copy config.example.json config.json        # Windows\n" +
                "\n" +
                "  然后编辑 config.json，关键字段：\n" +
                "  +------------------------------------------------------+\n" +
                "  | serverUrl  后端 API 地址，必须含 /api 后缀           |\n" +
                "  |            本机: http://127.0.0.1:8090/api           |\n" +
                "  |            局域网: http://192.168.0.110:8090/api     |\n" +
                "  | agentName  节点名称（需与前端代理管理创建时一致）    |\n" +
                "  | token      前端代理管理页面生成的 token               |\n" +
                "  | headless   false=显示浏览器窗口（推荐首次调试用）     |\n" +
                "  +------------------------------------------------------+\n" +
                "\n" +
                "  完整示例：\n" +
                "  {\n" +
                "    \"serverUrl\": \"http://192.168.0.110:8090/api\",\n" +
                "    \"agentName\": \"crm-agent-01\",\n" +
                "    \"token\": \"abc123def456...\",\n" +
                "    \"heartbeatIntervalMs\": 30000,\n" +
                "    \"pollIntervalMs\": 5000,\n" +
                "    \"browser\": {\n" +
                "      \"headless\": false,\n" +
                "      \"type\": \"chromium\",\n" +
                "      \"userDataDir\": \"./data/browser-profile\"\n" +
                "    }\n" +
                "  }\n" +
                "\n" +
                "【Step 5 — 启动】\n" +
                "  # 前台运行（推荐先用这个，能看到实时日志）\n" +
                "  node src/index.js\n" +
                "  # 成功标志：\n" +
                "  #   [就绪] 等待任务...\n" +
                "  #   前端代理管理页面节点变 在线\n" +
                "\n" +
                "  # 后台守护运行（稳定后推荐）\n" +
                "  # macOS/Linux:\n" +
                "    nohup node src/index.js > agent.log 2>&1 &\n" +
                "  # Windows PowerShell:\n" +
                "    Start-Process node -ArgumentList 'src/index.js' -RedirectStandardOutput 'agent.log'\n" +
                "\n" +
                "  # 查看日志 / 停止\n" +
                "    tail -f agent.log                 # macOS/Linux\n" +
                "    Get-Content agent.log -Wait       # Windows\n" +
                "    kill $(pgrep -f 'node src/index.js')   # macOS/Linux 停止\n" +
                "    Stop-Process -Name node -Force          # Windows 停止\n" +
                "\n" +
                "【常见问题 FAQ】\n" +
                "  Q: npm install 很慢？\n" +
                "  A: npm config set registry https://registry.npmmirror.com\n" +
                "\n" +
                "  Q: playwright download 超时？\n" +
                "  A: PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright npx playwright install chromium\n" +
                "\n" +
                "  Q: 前端节点一直离线？\n" +
                "  A: curl http://127.0.0.1:8090/api/agent-servers/heartbeat -X POST -H 'Content-Type: application/json' -d '{\\\"token\\\":\\\"你的token\\\"}'\n" +
                "     看是否返回成功。检查 token 是否正确、后端 8090 是否可达。\n" +
                "\n" +
                "  Q: macOS 提示 Chromium 无法打开？\n" +
                "  A: 系统设置 -> 隐私与安全 -> 允许 Chromium 运行\n" +
                "\n" +
                "  Q: Windows 上 Playwright 有兼容问题？\n" +
                "  A: 用 PowerShell 或 cmd 执行，不要用 Git Bash\n" +
                "\n" +
                "  Q: Windows 上后台运行后找不到进程？\n" +
                "  A: Get-Process node;  Stop-Process -Id <PID>\n" +
                "";
    }
}
