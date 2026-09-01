package com.discordadmin.controller;

import com.discordadmin.entity.AgentServer;
import com.discordadmin.service.AgentServerService;
import com.discordadmin.service.AgentTaskService;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.AgentServerRepository;
import com.discordadmin.service.ConversationService;
import com.discordadmin.discord.InboundMessage;
import com.discordadmin.service.MessageService;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.AgentTask;
import com.discordadmin.entity.Friend;
import com.discordadmin.entity.DiscordUser;
import com.discordadmin.entity.Conversation;
import com.discordadmin.repository.FriendRepository;
import com.discordadmin.repository.DiscordUserRepository;
import com.discordadmin.repository.ConversationRepository;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 安全地从 JSON Map 取字符串，兼容 String / Long / Integer / Double / null */
    private static String sstr(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        return v.toString();
    }

    /** 安全地从 JSON Map 取字符串，带默认值 */
    private static String sstr(Object v, String def) {
        if (v == null) return def;
        if (v instanceof String s) return s.isEmpty() ? def : s;
        return v.toString();
    }

    private final AgentServerService agentServerService;
    private final AgentTaskService agentTaskService;
    private final DiscordAccountRepository discordAccountRepository;
    private final AgentServerRepository agentServerRepository;
    private final MessageService messageService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private DiscordUserRepository discordUserRepository;
    @Autowired
    private ConversationRepository conversationRepository;

    @Value("${app.agent-source-dir:}")
    private String agentSourceDir;
    private final ObjectMapper objectMapper;
    private final FriendRepository friendRepository;

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
            // 动态计算在线状态：心跳间隔 5s，超过 15s 没心跳 = OFFLINE
            String dynStatus = "OFFLINE";
            if (s.getLastSeenAt() != null) {
                long secSince = Duration.between(s.getLastSeenAt(), Instant.now()).getSeconds();
                if (secSince < 15) dynStatus = "ONLINE";
            }
            m.put("status", dynStatus);
            if (s.getLastSeenAt() != null) {
                long secSince = Duration.between(s.getLastSeenAt(), Instant.now()).getSeconds();
                m.put("secSinceLastHeartbeat", secSince);
            }
            m.put("nodeVersion", s.getNodeVersion());
            m.put("browserType", s.getBrowserType());
            m.put("notes", s.getNotes());
            m.put("accountCount", discordAccountRepository.countByAgentServerId(s.getId()));
            m.put("maxAccounts", s.getMaxAccounts() == null ? 500 : s.getMaxAccounts());
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
        String paramsJson;
        if (body.get("params") != null) {
            paramsJson = safeJson(body.get("params"));
        } else {
            // 前端传平铺字段（browserProfilePath, accountId, token, channelId...）→ 自动收集
            java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : body.entrySet()) {
                if (!"agentServerId".equals(e.getKey()) && !"type".equals(e.getKey())) {
                    extras.put(e.getKey(), e.getValue());
                }
            }
            paramsJson = extras.isEmpty() ? null : safeJson(extras);
        }
        log.info("创建 agent task type={}, params={}", type, paramsJson);
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

    /** agent 取消自己的任务（免登录，token 认证） */
    @PostMapping("/tasks/{id}/cancel-by-agent")
    public ResponseEntity<Map<String, Object>> cancelByAgent(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null) return ResponseEntity.status(401).body(Map.of("error", "缺少 token"));
        try {
            AgentTask task = agentTaskService.cancelByAgent(token, id);
            return ResponseEntity.ok(Map.of("id", task.getId(), "status", task.getStatus()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    /** 前端用户取消任务（需要登录 JWT） */
    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelByUser(@PathVariable Long id) {
        try {
            AgentTask task = agentTaskService.cancelByUser(id);
            return ResponseEntity.ok(Map.of("id", task.getId(), "status", task.getStatus()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 查询任务详情 */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<?> getTask(@PathVariable Long id) {
        Map<String, Object> detail = agentTaskService.findTaskDetail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detail);
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
            String name = body.get("name");
            AgentServer server = agentServerService.heartbeat(
                    token,
                    name,
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
            byte[] zipBytes;
            // 优先: 源码目录存在 (开发模式), 动态打包
            Path sourceDir = resolveAgentSourceDir();
            if (sourceDir != null && Files.isDirectory(sourceDir)) {
                zipBytes = buildZip(sourceDir);
            } else {
                // 兜底: jar 内嵌 zip (生产模式)
                String zipName = "crm_agent-v" + readAgentVersion() + ".zip";
                try (var is = getClass().getClassLoader().getResourceAsStream(zipName)) {
                    if (is == null) {
                        // 再试旧名字
                        try (var is2 = getClass().getClassLoader().getResourceAsStream("agent-package.zip")) {
                            if (is2 == null) {
                                log.error("未找到内嵌 agent zip, classpath 资源列表:");
                                return ResponseEntity.status(500).body(null);
                            }
                            zipBytes = is2.readAllBytes();
                        }
                    } else {
                        zipBytes = is.readAllBytes();
                    }
                }
            }
            String filename = "crm_agent-v" + readAgentVersion() + ".zip";
            ByteArrayResource resource = new ByteArrayResource(zipBytes);
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
        String agentVer = readAgentVersion();
        String zipName = "crm_agent-v" + agentVer + ".zip";
        Map<String, Object> result = new HashMap<>();
        result.put("version", agentVer);
        result.put("downloadUrl", baseUrl + "/api/agent-servers/package");
        result.put("filename", zipName);
        result.put("requiresNode", ">=18");
        result.put("requiresPlaywright", "chromium");
        result.put("envCheck", List.of(
                Map.of("name", "Node.js", "command", "node -v", "minVersion", "18", "installHelp", "https://nodejs.org/"),
                Map.of("name", "npm", "command", "npm -v", "minVersion", "8", "installHelp", "随 Node.js 一起安装"),
                Map.of("name", "Playwright", "command", "npx playwright --version", "minVersion", "latest", "installHelp", "由 npm install 自动安装")
        ));
        result.put("steps", List.of(
                Map.of("step", 1, "title", "解压安装包",
                        "desc", "将 crm_agent-v{VER}.zip 解压到任意目录",
                        "code",                         "unzip crm_agent-v{VER}.zip\n" +
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
                        "直接编辑 config.json（zip 里已包含模板）\n" +
                        "\n" +
                        "# Windows\n" +
                        "直接编辑 config.json（zip 里已包含模板）\n" +
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


    private String readAgentVersion() {
        try {
            Path sourceDir = resolveAgentSourceDir();
            if (sourceDir != null) {
                // 优先从 package.json 读（npm 标准），兜底 config.json
                Path pkgFile = sourceDir.resolve("package.json");
                if (Files.exists(pkgFile)) {
                    String pkgJson = Files.readString(pkgFile);
                    int pIdx = pkgJson.indexOf("\"version\"");
                    if (pIdx >= 0) {
                        int pColon = pkgJson.indexOf(':', pIdx + 1);
                        int pQ1 = pkgJson.indexOf('"', pColon + 1);
                        int pQ2 = pkgJson.indexOf('"', pQ1 + 1);
                        if (pQ2 > pQ1) return pkgJson.substring(pQ1 + 1, pQ2);
                    }
                }
                // 兜底 config.json
                Path cfgFile = sourceDir.resolve("config.json");
                if (Files.exists(cfgFile)) {
                    String json = Files.readString(cfgFile);
                    // 找 "version": "xxx" —— 简单字符串解析
                    int idx = json.indexOf("\"version\"");
                    if (idx >= 0) {
                        int colon = json.indexOf(':', idx + 1);
                        if (colon > 0) {
                            int q1 = json.indexOf('"', colon + 1);
                            if (q1 > 0) {
                                int q2 = json.indexOf('"', q1 + 1);
                                if (q2 > q1) {
                                    return json.substring(q1 + 1, q2);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "1.15.6";
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
                        String[] skipFiles = {".DS_Store", "package-lock.json"};
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
            String readme = buildInstallReadme(readAgentVersion());
            zos.putNextEntry(new ZipEntry(baseName + "/README_INSTALL.txt"));
            zos.write(readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private String buildInstallReadme(String ver) {
        String content =                 "==============================================\n" +
                "  crm_agent v{VER} — 完整安装说明\n" +
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
                "  macOS/Linux:  unzip crm_agent-v{VER}.zip && cd crm_agent\n" +
                "  Windows PS:   Expand-Archive crm_agent-v{VER}.zip . ; cd crm_agent\n" +
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
    "  zip 里已包含 config.json 模板，直接修改即可：\n" +
                "  直接编辑 config.json（zip 里已包含模板）          # macOS/Linux\n" +
                "  直接编辑 config.json（zip 里已包含模板）        # Windows\n" +
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
        return content.replace("{VER}", ver);
    }

    /**
     * agent 拉自己负责的 AGENT 采集账号列表（用于 HTTP 轮询收消息）
     * body: { token }
     * 返回: [{ id, name, discordId, token, status }] — 只返回必要字段，不含敏感信息（browserProfilePath 等在 agent 本地已有）
     */
    @PostMapping("/accounts")
    public ResponseEntity<List<Map<String, Object>>> getAgentAccounts(@RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效的 agent token"));
        List<DiscordAccount> accounts = discordAccountRepository
                .findByAgentServerIdAndSourceAndStatus(server.getId(), "AGENT", DiscordAccount.AccountStatus.ACTIVE);
        List<Map<String, Object>> result = accounts.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("discordId", a.getDiscordId());
            m.put("token", a.getToken());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /**
     * agent 上报新消息（agent 机器 HTTP 轮询 Discord API 拿到的）
     * body: { token, messages: [{ accountId, channelId, channelType, discordMessageId, authorId, authorName, content, timestamp, isFromMe }] }
     */
    @PostMapping("/messages/report")
    public ResponseEntity<Map<String, Object>> reportMessages(@RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效的 agent token"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.ok(Map.of("received", 0));
        }

        int saved = 0;
        for (Map<String, Object> m : messages) {
            try {
                Long accountId = Long.valueOf(m.get("accountId").toString());
                String channelId = sstr(m.get("channelId"));
                String discordMsgId = sstr(m.get("discordMessageId"));
                String content = sstr(m.get("content"), "");
                // ==== 统一入口解码 HTML 实体（emoji 等）====
                content = com.discordadmin.translation.TranslationServiceFactory.decodeHtmlEntities(content);
                boolean isFromMe = Boolean.TRUE.equals(m.get("isFromMe"));

                // 解析完整字段
                String authorId = sstr(m.get("authorId"), "");
                String authorName = sstr(m.get("authorName"), "");
                String authorGlobalName = sstr(m.get("authorGlobalName"), authorName);
                String authorAvatar = sstr(m.get("authorAvatar"));
                String channelType = sstr(m.get("channelType"), "dm");
                String messageType = sstr(m.get("messageType"), "text");
                String gifUrl = sstr(m.get("gifUrl"));

                // 构建 attachmentsJson
                String attachmentsJson = null;
                Object attsObj = m.get("attachments");
                if (attsObj != null) {
                    try { attachmentsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(attsObj); }
                    catch (Exception ignored) {}
                }
                String stickerItemsJson = null;
                Object stickersObj = m.get("stickers");
                if (stickersObj != null) {
                    try { stickerItemsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(stickersObj); }
                    catch (Exception ignored) {}
                }

                if (isFromMe) {
                    // 自己发的消息 → OUTBOUND，走 MessageService 的 OUTBOUND 保存
                    messageService.saveAgentOutboundMessage(accountId, channelId, discordMsgId,
                            authorId, authorName, content, messageType, gifUrl, attachmentsJson, stickerItemsJson);
                } else {
                    // 别人发的消息 → INBOUND，走完整的 handleInbound 链路
                    InboundMessage inbound = new InboundMessage(
                            accountId, discordMsgId, authorId, authorName, authorGlobalName, authorAvatar,
                            true, null, null, // isDirectMessage=true（agent目前只拉DM）
                            channelId, "DM/" + channelId.substring(0, Math.min(8, channelId.length())),
                            content, attachmentsJson, messageType,
                            null, null, null, null, // 无语音
                            stickerItemsJson, gifUrl
                    );
                    conversationService.handleInbound(inbound);
                }
                saved++;
            } catch (Exception e) {
                log.warn("Agent 上报消息入库失败 discordMsgId={}: {}", m.get("discordMessageId"), e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("received", saved));
    }

    @PostMapping("/friends/report")
    public ResponseEntity<Map<String, Object>> reportFriends(@RequestBody Map<String, Object> body) {
        String token = (String) body.get("token");
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效的 agent token"));

        Long accountId = Long.valueOf(body.get("accountId").toString());
        DiscordAccount acc = discordAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在: " + accountId));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> friends = (java.util.List<Map<String, Object>>) body.get("friends");
        if (friends == null || friends.isEmpty()) {
            // 没好友也要更新 synced_at（标记为已同步过）
            log.info("[好友同步] accountId={} 无好友数据", accountId);
            return ResponseEntity.ok(Map.of("saved", 0, "note", "no friends"));
        }

        int saved = 0, removed = 0;
        java.util.Set<String> incomingIds = new java.util.HashSet<>();

        for (Map<String, Object> f : friends) {
            try {
                String friendId = sstr(f.get("friendDiscordUserId"));
                if (friendId == null || friendId.isEmpty()) continue;
                incomingIds.add(friendId);

                // relationshipType: 1=好友 2=入站待请求 3=出站待请求 4=阻止
                Object rtObj = f.get("relationshipType");
                int rt = (rtObj instanceof Number) ? ((Number) rtObj).intValue() : 1;

                Friend.FriendStatus status;
                switch (rt) {
                    case 2: status = Friend.FriendStatus.PENDING_IN; break;
                    case 1:
                    case 3:
                    case 4:
                    default: status = Friend.FriendStatus.ACCEPTED; break;
                }

                java.util.Optional<Friend> exist = friendRepository
                        .findByDiscordAccountAndFriendDiscordUserId(acc, friendId);
                Friend friend = exist.orElseGet(Friend::new);
                friend.setDiscordAccount(acc);
                friend.setMerchantId(acc.getMerchantId());
                friend.setFriendDiscordUserId(friendId);

                // 字段兜底逻辑：
                // 1) agent 上报的 username/globalName/avatar 非空才覆盖（防止旧 agent 代码传 undefined 把已有值抹空）
                // 2) 数据库里如果也没有，就去同 friendId 的其他账号好友记录里抄一份
                String u = sstr(f.get("username"));
                String g = sstr(f.get("globalName"));
                String a = sstr(f.get("avatar"));

                // 当前记录已有值且 agent 没上报新值 → 保留原值
                if ((u == null || u.isEmpty()) && friend.getUsername() != null && !friend.getUsername().isEmpty()) {
                    u = friend.getUsername();
                }
                if ((g == null || g.isEmpty()) && friend.getGlobalName() != null && !friend.getGlobalName().isEmpty()) {
                    g = friend.getGlobalName();
                }
                if ((a == null || a.isEmpty()) && friend.getAvatar() != null && !friend.getAvatar().isEmpty()) {
                    a = friend.getAvatar();
                }

                // 额外兜底：从同 friendId 的其他账号完整记录抄
                if (u == null || u.isEmpty() || g == null || g.isEmpty() || a == null || a.isEmpty()) {
                    for (Friend src : friendRepository.findByFriendDiscordUserId(friendId)) {
                        if (src.getUsername() != null && !src.getUsername().isEmpty()) {
                            if (u == null || u.isEmpty()) u = src.getUsername();
                            if (g == null || g.isEmpty()) g = src.getGlobalName();
                            if (a == null || a.isEmpty()) a = src.getAvatar();
                            break;
                        }
                    }
                }

                friend.setUsername(u != null ? u : "");
                friend.setGlobalName(g != null ? g : (u != null ? u : ""));
                friend.setAvatar(a);
                friend.setStatus(status);
                friend.setSyncedAt(Instant.now());
                if (friend.getId() == null) friend.setCreatedAt(Instant.now());
                friendRepository.save(friend);
                saved++;
            } catch (Exception e) {
                log.warn("[好友同步] 处理好友失败 friendId={}: {}", f.get("friendDiscordUserId"), e.getMessage());
            }
        }

        // 标记不再是好友的记录为 PENDING_IN 之外的状态？暂不删除
        // （Discord API 拉的是完整关系列表，agent 不应该有太多误报）
        log.info("[好友同步] accountId={} 上报 {} 条, 保存 {} 条, 跳过 {} 条",
            accountId, friends.size(), saved, friends.size() - saved);

        return ResponseEntity.ok(Map.of("saved", saved, "total", friends.size()));
    }

    /**
     * Agent 上报 DM 频道列表 —— 为每个 1:1 DM 创建/更新 Conversation
     * 这样 Chat.vue 会话列表才能显示好友
     */
    @PostMapping("/dm-channels/report")
    public ResponseEntity<Map<String, Object>> reportDmChannels(@RequestBody Map<String, Object> body) {
        String agentToken = (String) body.get("token");
        AgentServer server = agentServerRepository.findByToken(agentToken)
                .orElseThrow(() -> new IllegalArgumentException("无效的 agent token"));

        Long accountId = Long.valueOf(body.get("accountId").toString());
        DiscordAccount acc = discordAccountRepository.findById(accountId).orElse(null);
        if (acc == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "账号不存在: " + accountId));
        }

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> dms = (java.util.List<Map<String, Object>>) body.get("dms");
        if (dms == null) dms = java.util.Collections.emptyList();

        int saved = 0;
        for (Map<String, Object> dm : dms) {
            int channelType = dm.get("channelType") != null ? ((Number) dm.get("channelType")).intValue() : -1;
            if (channelType != 1) continue; // 只处理 1:1 DM

            String channelId = sstr(dm.get("channelId"), null);
            if (channelId == null) continue;

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> recipients = (java.util.List<Map<String, Object>>) dm.get("recipients");
            if (recipients == null || recipients.isEmpty()) continue;

            Map<String, Object> recipient = recipients.get(0);
            String userId = sstr(recipient.get("id"), null);
            if (userId == null) continue;

            // upsert DiscordUser
            DiscordUser user = discordUserRepository.findByDiscordUserId(userId).orElse(null);
            if (user == null) {
                user = new DiscordUser();
                user.setDiscordUserId(userId);
                user.setFirstSeenAt(java.time.Instant.now());
            }
            user.setUsername(sstr(recipient.get("username"), null));
            user.setGlobalName(sstr(recipient.get("globalName"), null));
            String avatar = sstr(recipient.get("avatar"), null);
            if (avatar != null) {
                user.setAvatarUrl("https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + ".png");
            }
            user = discordUserRepository.save(user);

            // upsert Conversation
            Conversation conv = conversationRepository
                    .findByChannelIdAndDiscordAccount_Id(channelId, acc.getId())
                    .orElse(null);
            if (conv == null) {
                conv = new Conversation();
                conv.setChannelId(channelId);
                conv.setType(Conversation.ConversationType.DM);
                conv.setStatus(Conversation.ConversationStatus.OPEN);
                conv.setCreatedAt(java.time.Instant.now());
            }
            conv.setDiscordUser(user);
            conv.setDiscordAccount(acc);
            conv.setMerchantId(acc.getMerchantId());
            String displayName = user.getGlobalName() != null ? user.getGlobalName() : user.getUsername();
            conv.setChannelName(displayName);
            conversationRepository.saveAndFlush(conv);
            saved++;
        }

        log.info("[DM频道上报] agent={} accountId={} 上报={}, 保存={}", server.getName(), accountId, dms.size(), saved);
        return ResponseEntity.ok(Map.of("success", true, "saved", saved, "total", dms.size()));
    }

    /**
     * Agent 上报账号 token 状态（失效/恢复）
     * agent 在消息轮询、发消息、拉好友等场景检测到 401 时调用
     */
    @PostMapping("/accounts/token-status")
    public ResponseEntity<Map<String, Object>> reportTokenStatus(@RequestBody Map<String, Object> body) {
        String agentToken = (String) body.get("token");
        AgentServer server = agentServerRepository.findByToken(agentToken)
                .orElseThrow(() -> new IllegalArgumentException("无效的 agent token"));

        Long accountId = Long.valueOf(body.get("accountId").toString());
        boolean valid = !Boolean.FALSE.equals(body.get("valid"));  // 默认 true
        String reason = sstr(body.get("reason"), valid ? "ok" : "401 Unauthorized");

        DiscordAccount acc = discordAccountRepository.findById(accountId).orElse(null);
        if (acc == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "账号不存在: " + accountId));
        }

        boolean changed = !Boolean.valueOf(valid).equals(acc.getTokenValid());
        acc.setTokenValid(valid);
        acc.setTokenCheckedAt(Instant.now());
        if (!valid) {
            acc.setLastError(reason);
        } else {
            acc.setLastError(null);
        }
        discordAccountRepository.save(acc);

        if (changed) {
            log.warn("[Token状态] agent={} accountId={} name={} valid={} reason={}",
                server.getName(), accountId, acc.getName(), valid, reason);
        } else {
            log.debug("[Token状态] agent={} accountId={} valid={} (未变化)",
                server.getName(), accountId, valid);
        }

        return ResponseEntity.ok(Map.of("success", true, "accountId", accountId, "tokenValid", valid));
    }
}
