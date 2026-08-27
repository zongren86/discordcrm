package com.discordadmin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.model.EmulatorInfo;
import com.discordadmin.repository.AgentRegistrationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmulatorService {

    @Value("${mumu.mumutool-path:}")
    private String mumutoolPath;

    @Value("${mumu.adb-path:}")
    private String adbPath;

    @Value("${mumu.vms-base-path:}")
    private String vmsBasePath;

    @Value("${mumu.default-emulator-count}")
    private int defaultCount;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Integer, EmulatorInfo> emulators = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile boolean daemonReady = false;
    /** 用户「应用」的模拟器数量，批量操作只作用于按序号升序的前 activeCount 台；<=0 表示全部 */
    private volatile int activeCount = 0;

    /** 配置持久化目录：~/.mumu-manager */
    private final String configDir = System.getProperty("user.home") + "/.mumu-manager";
    private final String configFile = configDir + "/config.json";
    /** 当前运行平台 */
    private final boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
    private final boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
    /** 运行期可被前端覆盖的路径（初始化时由配置/平台默认值填充） */
    private String resolvedMumutoolPath;
    private String resolvedAdbPath;
    private String resolvedVmsBasePath;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CloudWebSocketService cloudWebSocketService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentRegistrationRepository agentRegistrationRepository;

    private String currentUserId;

    /** 设置当前操作用户 ID（用于 Agent 模式） */
    public void setCurrentUserId(String userId) {
        this.currentUserId = userId;
    }

    /** 获取当前操作用户 ID */
    public String getCurrentUserId() {
        return this.currentUserId;
    }

    /** 写入模拟器实时抓取的 Discord 用户名（显示用） */
    public void setDiscordActualUser(int index, String user) {
        EmulatorInfo info = emulators.get(index);
        if (info == null) info = readVmConfig(index);
        info.setDiscordActualUser(user);
        emulators.put(index, info);
    }

    @PostConstruct
    public void init() {
        loadPathConfig();
        resolvePaths();
        ensureMumuDaemon();
    }

    /**
     * 从持久化配置文件（~/.mumu-manager/config.json）加载用户自定义路径。
     * 该文件中的路径优先级高于 application.yml 与平台默认值。
     */
    private void loadPathConfig() {
        try {
            File f = new File(configFile);
            if (!f.exists()) return;
            JsonNode node = objectMapper.readTree(f);
            if (node.has("mumutoolPath") && !node.get("mumutoolPath").asText().isBlank()) {
                mumutoolPath = node.get("mumutoolPath").asText();
            }
            if (node.has("adbPath") && !node.get("adbPath").asText().isBlank()) {
                adbPath = node.get("adbPath").asText();
            }
            if (node.has("vmsBasePath") && !node.get("vmsBasePath").asText().isBlank()) {
                vmsBasePath = node.get("vmsBasePath").asText();
            }
            log.info("已从配置文件加载路径设置");
        } catch (Exception e) {
            log.warn("读取路径配置文件失败，使用默认/配置项路径: {}", e.getMessage());
        }
    }

    /**
     * 将注入的路径配置按平台解析为最终可用的绝对路径。
     * 优先级：用户配置/环境变量 > application.yml > 平台探测默认。
     */
    private void resolvePaths() {
        resolvedMumutoolPath = resolveMumuToolPath();
        resolvedAdbPath = resolveAdbPath();
        resolvedVmsBasePath = resolveVmsBasePath();
        log.info("解析路径 => mumutool={}, adb={}, vms={}, windows={}",
                resolvedMumutoolPath, resolvedAdbPath, resolvedVmsBasePath, isWindows);
    }

    private String resolveMumuToolPath() {
        if (mumutoolPath != null && !mumutoolPath.isBlank()) return mumutoolPath;
        if (!isWindows && !isMac) return null;
        if (isWindows) {
            // Windows：MuMu 安装位置由用户选择，无法固定。探测常见位置，失败则提示配置。
            String[] candidates = {
                    "C:\\Program Files\\MuMu\\emulator\\nemux\\bin\\mumutool.exe",
                    "D:\\Program Files\\MuMu\\emulator\\nemux\\bin\\mumutool.exe",
                    System.getenv("ProgramFiles") + "\\MuMu\\emulator\\nemux\\bin\\mumutool.exe",
                    System.getenv("ProgramFiles") + "\\Netease\\MuMu\\emulator\\nemux\\bin\\mumutool.exe"
            };
            for (String c : candidates) {
                if (c != null && new File(c).exists()) return c;
            }
            return ""; // 交由调用方报错提示
        }
        // macOS 默认
        return "/Applications/MuMuPlayer.app/Contents/MacOS/mumutool";
    }

    private String resolveAdbPath() {
        if (adbPath != null && !adbPath.isBlank()) return adbPath;
        if (!isWindows && !isMac) return null;
        if (isWindows) {
            String androidHome = System.getenv("ANDROID_HOME");
            if (androidHome != null && !androidHome.isBlank()) {
                String p = androidHome + "\\platform-tools\\adb.exe";
                if (new File(p).exists()) return p;
            }
            // 尝试 MuMu 自带 adb
            String[] candidates = {
                    System.getenv("ProgramFiles") + "\\MuMu\\emulator\\nemux\\bin\\adb.exe",
                    "C:\\Program Files\\MuMu\\emulator\\nemux\\bin\\adb.exe"
            };
            for (String c : candidates) {
                if (c != null && new File(c).exists()) return c;
            }
            return "adb"; // 退回 PATH 中的 adb
        }
        String androidHome = System.getenv("ANDROID_HOME");
        if (androidHome != null && !androidHome.isBlank()) {
            return androidHome + "/platform-tools/adb";
        }
        return System.getProperty("user.home") + "/Library/Android/sdk/platform-tools/adb";
    }

    private String resolveVmsBasePath() {
        if (vmsBasePath != null && !vmsBasePath.isBlank()) return vmsBasePath;
        if (!isWindows && !isMac) return null;
        if (isWindows) {
            String localApp = System.getenv("LOCALAPPDATA");
            if (localApp != null && !localApp.isBlank()) {
                return localApp + "\\com.netease.mumu.nemux\\vms";
            }
            return System.getenv("USERPROFILE") + "\\AppData\\Local\\com.netease.mumu.nemux\\vms";
        }
        return System.getProperty("user.home") + "/Library/Application Support/com.netease.mumu.nemux/vms";
    }

    /**
     * 供前端调用：设置并持久化路径配置（Windows 用户安装位置不固定，需手动指定）。
     * 保存后立即重新解析路径。返回错误信息（若有）。
     */
    public synchronized String applyPathConfig(String toolPath, String adb, String vms) {
        try {
            new File(configDir).mkdirs();
            Map<String, String> cfg = new LinkedHashMap<>();
            cfg.put("mumutoolPath", toolPath == null ? "" : toolPath.trim());
            cfg.put("adbPath", adb == null ? "" : adb.trim());
            cfg.put("vmsBasePath", vms == null ? "" : vms.trim());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(configFile), cfg);
            mumutoolPath = cfg.get("mumutoolPath");
            adbPath = cfg.get("adbPath");
            vmsBasePath = cfg.get("vmsBasePath");
            resolvePaths();
            if (resolvedMumutoolPath.isBlank() || !new File(resolvedMumutoolPath).exists()) {
                return "mumutool 路径无效或不存在: " + resolvedMumutoolPath;
            }
            return "";
        } catch (Exception e) {
            return "保存路径配置失败: " + e.getMessage();
        }
    }

    /** 返回当前生效的路径配置（供前端展示） */
    public Map<String, String> getPathConfig() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mumutoolPath", resolvedMumutoolPath == null ? "" : resolvedMumutoolPath);
        m.put("adbPath", resolvedAdbPath == null ? "" : resolvedAdbPath);
        m.put("vmsBasePath", resolvedVmsBasePath == null ? "" : resolvedVmsBasePath);
        m.put("isWindows", String.valueOf(isWindows));
        return m;
    }

    /**
     * 弹出系统原生的目录选择对话框，返回用户选中的真实绝对路径。
     * macOS 用 osascript；Windows 用 Shell.Application 的 BrowseForFolder（无需 STA，最稳）。
     */
    public String selectDirectory(String prompt) {
        if (prompt == null || prompt.isBlank()) prompt = "选择目录";
        try {
            String raw;
            if (isWindows) {
                // 用临时 .ps1 文件 + -ExecutionPolicy Bypass 执行，避免 -Command 长字符串转义/
                // 特殊字符(&等)被吞；Shell.Application.BrowseForFolder 不依赖 STA 线程，最稳。
                String ps = "$sh = New-Object -ComObject Shell.Application\n"
                        + "$folder = $sh.BrowseForFolder(0, '" + escapePowerShell(prompt) + "', 0, 0)\n"
                        + "if ($folder) { $folder.Self.Path }";
                raw = runPowerShellFile(ps);
            } else {
                String sc = "POSIX path of (choose folder with prompt \"" + prompt.replace("\"", "'") + "\")";
                raw = runNativeCommand(new String[]{"osascript", "-e", sc});
            }
            return normalizePath(raw);
        } catch (Exception e) {
            log.warn("目录选择对话框失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 弹出系统原生的文件选择对话框，返回用户选中的真实绝对路径。
     * macOS 用 osascript；Windows 用 WinForms OpenFileDialog（需 -STA 线程才可正常显示）。
     */
    public String selectFile(String prompt) {
        if (prompt == null || prompt.isBlank()) prompt = "选择文件";
        try {
            String raw;
            if (isWindows) {
                // WinForms OpenFileDialog 需 -STA 线程才能正常弹出模态对话框；
                // 用临时 .ps1 文件 + -ExecutionPolicy Bypass 执行，规避 -Command 转义问题。
                String ps = "Add-Type -AssemblyName System.Windows.Forms\n"
                        + "$o = New-Object System.Windows.Forms.OpenFileDialog\n"
                        + "$o.Title = '" + escapePowerShell(prompt) + "'\n"
                        + "if ($o.ShowDialog() -eq 'OK') { $o.FileName }";
                raw = runPowerShellFile(ps);
            } else {
                String sc = "POSIX path of (choose file with prompt \"" + prompt.replace("\"", "'") + "\")";
                raw = runNativeCommand(new String[]{"osascript", "-e", sc});
            }
            return normalizePath(raw);
        } catch (Exception e) {
            log.warn("文件选择对话框失败: {}", e.getMessage());
            return null;
        }
    }

    /** PowerShell 单引号转义：把 ' 写成 ''（PowerShell 字符串内转义规则）。 */
    private String escapePowerShell(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /**
     * 将 PowerShell 脚本写入临时 .ps1 文件，再以 -ExecutionPolicy Bypass -File 方式执行。
     * 相比 -Command 长字符串，能避免 & 等特殊字符被 shell 吞掉，且对 WinForms 模态对话框更可靠。
     */
    private String runPowerShellFile(String script) throws Exception {
        File tmp = File.createTempFile("mumu-select-", ".ps1");
        try {
            Files.write(tmp.toPath(), script.getBytes(StandardCharsets.UTF_16LE));
            return runNativeCommand(new String[]{
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", tmp.getAbsolutePath()
            });
        } finally {
            try { tmp.delete(); } catch (Exception ignored) {}
        }
    }

    /** 执行本地命令并读取 stdout（去除首尾空白与引号）。带超时，避免对话框卡死时永久阻塞请求线程。
     *  同时捕获 stderr，便于 Windows 下 GUI 对话框无法弹出时排查真实错误。 */
    private String runNativeCommand(String[] cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        // 超时（毫秒）：用户选目录一般很快，超时视为取消/异常，销毁进程
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            log.warn("命令超时(120s)未结束，已终止: {}", String.join(" ", cmd));
            return null;
        }
        // 读取 stderr 用于排查（Windows 下对话框无法弹出的常见原因）
        StringBuilder err = new StringBuilder();
        try (BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = er.readLine()) != null) err.append(line).append(" | ");
        }
        if (p.exitValue() != 0) {
            if (!err.toString().isBlank()) {
                log.warn("命令非零退出({}): stderr={}", p.exitValue(), err);
            }
            // 非零退出视为用户取消选择（Windows 对话框取消也会返回非 0）
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private String normalizePath(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // macOS 可能偶发包裹引号
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 从 mumutool 路径推断 MuMuPlayer 主程序位置（Windows）。
     * 常见结构：.../nemux/bin/mumutool.exe 与 .../nemux/bin/MuMuPlayer.exe 同级。
     */
    private String inferMuMuPlayerExe() {
        if (resolvedMumutoolPath == null || resolvedMumutoolPath.isBlank()) return null;
        File tool = new File(resolvedMumutoolPath);
        File binDir = tool.getParentFile();
        if (binDir == null) return null;
        File exe = new File(binDir, "MuMuPlayer.exe");
        if (exe.exists()) return exe.getAbsolutePath();
        // 个别版本主程序在更上层
        File up = binDir.getParentFile();
        if (up != null) {
            File alt = new File(up, "MuMuPlayer.exe");
            if (alt.exists()) return alt.getAbsolutePath();
        }
        return null;
    }

    /**
     * 模拟器名称格式：从 v001 开始，按序号递增（index 0 => v001）。
     */
    private String formatEmuName(int index) {
        return "V" + String.format("%03d", index + 1);
    }

    /**
     * 确保 MuMuPlayer 守护进程运行中（每次都重新探测端口，自动拉起缺失的进程）
     */
    private synchronized void ensureMumuDaemon() {
        if (!isWindows && !isMac) {
            log.warn("MuMu 守护进程仅支持 Windows/macOS，当前平台不支持，跳过守护进程拉起");
            return;
        }

        // 先探测端口，若已就绪直接返回
        if (isDaemonReady()) {
            daemonReady = true;
            return;
        }

        // 守护进程未运行，尝试拉起 MuMuPlayer
        log.info("MuMu 守护进程未就绪，尝试启动 MuMuPlayer（platform={}）...", isWindows ? "windows" : (isMac ? "mac" : "linux"));
        try {
            if (isWindows) {
                // Windows：MuMu 主程序位于用户安装目录，由 mumutool 同级目录推断
                String exe = inferMuMuPlayerExe();
                if (exe != null && new File(exe).exists()) {
                    new ProcessBuilder(exe).start();
                } else {
                    log.warn("未找到 MuMuPlayer 主程序，请确认 mumutool 路径配置正确: {}", resolvedMumutoolPath);
                }
            } else if (isMac) {
                new ProcessBuilder("open", "-a", "MuMuPlayer").start();
            }
        } catch (Exception e) {
            log.error("无法启动 MuMuPlayer", e);
        }

        // 轮询等待守护进程就绪（最多 ~45 秒）
        for (int i = 0; i < 30; i++) {
            if (isDaemonReady()) {
                daemonReady = true;
                log.info("MuMu 守护进程已就绪");
                return;
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
        log.warn("MuMu 守护进程启动超时（端口探测失败），后续操作可能报错");
    }

    public boolean isDaemonReady() {
        if (resolvedMumutoolPath == null || resolvedMumutoolPath.isBlank()) return false;
        try {
            Process p = new ProcessBuilder(resolvedMumutoolPath, "port").start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            p.waitFor(5, TimeUnit.SECONDS);
            return out.contains("server-port") || err.contains("server-port");
        } catch (Exception e) {
            return false;
        }
    }

    public List<EmulatorInfo> getAllEmulators() {
        ensureMumuDaemon();
        synchronized (emulators) {
            refreshEmulatorList();
            return sortedEmulators();
        }
    }

    public EmulatorInfo getEmulator(int index) {
        ensureMumuDaemon();
        refreshEmulatorList();
        return emulators.get(index);
    }

    public synchronized List<EmulatorInfo> ensureEmulatorCount(int targetCount) {
        return ensureEmulatorCount(targetCount, null, null);
    }

    public synchronized List<EmulatorInfo> ensureEmulatorCount(int targetCount, Integer cpuCount, Integer memoryMB) {
        ensureMumuDaemon();
        log.info("确保模拟器数量: {} (cpu={}, memMB={})", targetCount, cpuCount, memoryMB);
        refreshEmulatorList();

        int currentCount = emulators.size();
        if (currentCount < targetCount) {
            for (int i = currentCount; i < targetCount; i++) {
                createEmulator(i, cpuCount, memoryMB);
            }
        }
        // 记录用户设定的目标数量：批量操作（全部启动等）只作用于前 targetCount 台
        this.activeCount = targetCount;
        refreshEmulatorList();
        return sortedEmulators();
    }

    /**
     * 批量操作的作用范围：前 activeCount 台（按 index 升序）。
     * 未设置（<=0）时表示对全部已存在的模拟器生效。
     */
    public int getActiveCount() {
        return activeCount;
    }

    public void setActiveCount(int count) {
        this.activeCount = Math.max(0, count);
    }

    /**
     * 取批量操作的目标 index 列表：按升序取前 activeCount 个
     */
    private List<Integer> targetIndexes() {
        List<Integer> all = sortedIndexes();
        if (activeCount > 0 && activeCount < all.size()) {
            return new ArrayList<>(all.subList(0, activeCount));
        }
        return all;
    }

    /**
     * 创建模拟器（使用默认 CPU/内存配置）
     */
    public EmulatorInfo createEmulator(int index) {
        return createEmulator(index, null, null);
    }

    /**
     * 创建模拟器，可指定 CPU 核心数(1-8)与内存(MB)。
     * 内存范围 1-15 GB。
     */
    public EmulatorInfo createEmulator(int index, Integer cpuCount, Integer memoryMB) {
        ensureMumuDaemon();
        log.info("创建模拟器 index={} cpu={} memMB={}", index, cpuCount, memoryMB);

        Path vmPath = Paths.get(resolvedVmsBasePath, String.valueOf(index));
        if (Files.exists(vmPath)) {
            log.info("模拟器 {} 已存在，跳过创建", index);
            // 确保 vmName 与管理后台一致
            syncVmName(index);
            EmulatorInfo info = readVmConfig(index);
            emulators.put(index, info);
            return info;
        }

        EmulatorInfo info = EmulatorInfo.builder()
                .index(index).name(formatEmuName(index)).status("CREATING").build();
        emulators.put(index, info);

        try {
            // mumutool create 必须携带 --type 或 --setting 之一，否则报
            // "Missing expected options '--type' or '--setting'"
            List<String> createArgs = new ArrayList<>();
            createArgs.add("create");
            String[] settingArgs = buildSettingArgs(cpuCount, memoryMB);
            if (settingArgs.length > 0) {
                createArgs.addAll(Arrays.asList(settingArgs));
            } else {
                createArgs.add("--type");
                createArgs.add("phone");
            }

            if (index > 0) {
                // clone 源动态选择：设备索引可能不连续（如 0 被删除），
                // 硬编码 clone 0 会因源设备不存在而失败（invalidParams）
                int source = findCloneSource();
                if (source >= 0) {
                    // clone 语法: mumutool clone <device>（不支持 --setting）
                    execMumuTool("clone", String.valueOf(source));
                    // 克隆完成后再通过 config 应用 CPU/内存
                    applyVmSetting(index, cpuCount, memoryMB);
                } else {
                    // 空库：无设备可克隆，改用 create（MuMu 自动分配最小可用 index）
                    log.info("空库无设备可克隆，改用 create 创建 index={}", index);
                    execMumuTool(createArgs.toArray(new String[0]));
                }
            } else {
                execMumuTool(createArgs.toArray(new String[0]));
            }
            // 同步 vmName 为管理后台格式
            syncVmName(index);
            EmulatorInfo updated = readVmConfig(index);
            updated.setStatus("STOPPED");
            emulators.put(index, updated);
            return updated;
        } catch (Exception e) {
            log.error("创建模拟器失败: index={}", index, e);
            info.setStatus("ERROR");
            info.setLastError(e.getMessage());
            return info;
        }
    }

    /**
     * 拼装 mumutool 的 --setting JSON 参数。未指定任何配置时返回空数组。
     */
    private String[] buildSettingArgs(Integer cpuCount, Integer memoryMB) {
        if (cpuCount == null && memoryMB == null) {
            return new String[0];
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (cpuCount != null) {
            sb.append("\"vmCpuCount\":").append(cpuCount);
            first = false;
        }
        if (memoryMB != null) {
            if (!first) sb.append(",");
            sb.append("\"vmMemoryOfMB\":").append(memoryMB);
        }
        sb.append("}");
        return new String[]{"--setting", sb.toString()};
    }

    /**
     * 选择可用的克隆源设备 index（取当前已存在的最小 index）。
     * 无任何设备时返回 -1（此时克隆不可行，应改用 create）。
     */
    private int findCloneSource() {
        refreshEmulatorList();
        return emulators.keySet().stream().mapToInt(Integer::intValue).min().orElse(-1);
    }

    /**
     * 对已存在的模拟器应用 CPU/内存配置（clone 后调用）。
     */
    private void applyVmSetting(int index, Integer cpuCount, Integer memoryMB) {
        String[] args = buildSettingArgs(cpuCount, memoryMB);
        if (args.length == 0) return;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("config");
            cmd.add(String.valueOf(index));
            cmd.add("--setting");
            cmd.add(args[1]); // args[1] 为 JSON 内容
            execMumuTool(cmd.toArray(new String[0]));
            log.info("模拟器{} 已应用配置 cpu={} memMB={}", index, cpuCount, memoryMB);
        } catch (Exception e) {
            log.warn("模拟器{} 应用配置失败(可忽略，后续仍可手动调整): {}", index, e.getMessage());
        }
    }

    /**
     * 同步模拟器的 vmName 为管理后台格式（V001、V002...）。
     * 通过 mumutool config 命令修改 vm.json 中的 vmName 字段。
     */
    private void syncVmName(int index) {
        String targetName = formatEmuName(index);
        try {
            // 先读取当前 vmName
            Path configPath = Paths.get(resolvedVmsBasePath, String.valueOf(index), "setting", "vm.json");
            if (!Files.exists(configPath)) {
                log.warn("模拟器{} vm.json 不存在，跳过名称同步", index);
                return;
            }

            // 读取当前 vmName
            JsonNode json = objectMapper.readTree(configPath.toFile());
            String currentName = json.path("vmName").asText("");

            // 如果当前名称已经是目标格式，跳过
            if (targetName.equals(currentName)) {
                log.info("模拟器{} vmName 已是 {}，跳过同步", index, targetName);
                return;
            }

            // 通过 mumutool config 命令更新 vmName
            String settingJson = "{\"vmName\":\"" + targetName + "\"}";
            List<String> cmd = new ArrayList<>();
            cmd.add("config");
            cmd.add(String.valueOf(index));
            cmd.add("--setting");
            cmd.add(settingJson);
            execMumuTool(cmd.toArray(new String[0]));
            log.info("模拟器{} vmName 已从 {} 更新为 {}", index, currentName, targetName);
        } catch (Exception e) {
            log.warn("模拟器{} vmName 同步失败(可忽略): {}", index, e.getMessage());
        }
    }


    public EmulatorInfo startEmulator(int index) {
        ensureMumuDaemon();
        log.info("启动模拟器 index={}", index);
        try {
            // 重试启动，避免守护进程瞬时繁忙导致的 invalidLaunch / 网络连接中断
            boolean started = false;
            for (int attempt = 1; attempt <= 3 && !started; attempt++) {
                String result = execMumuTool("open", String.valueOf(index));
                log.info("启动结果(第{}次): {}", attempt, result);
                if (result.contains("invalidPort") || result.contains("网络连接已中断")
                        || result.contains("invalidLaunch")) {
                    log.warn("模拟器{} 启动失败(第{}次)，重试", index, attempt);
                    try { Thread.sleep(3000L * attempt); } catch (InterruptedException ignored) {}
                    continue;
                }
                started = true;
            }

            EmulatorInfo info = readVmConfig(index);
            if (info.getAdbPort() == 0) {
                info.setAdbPort(info.getAdbPort() > 0 ? info.getAdbPort() : 16384 + index * 32);
            }
            // 先轮询等待模拟器真正 running，再等 ADB 就绪
            boolean running = waitForEmulatorRunning(index, 90);
            waitForAdbReady(info.getAdbPort(), 60);

            // 只有在确认模拟器真正运行时才设置 RUNNING 状态
            if (running) {
                info.setStatus("RUNNING");
            } else {
                log.warn("模拟器{} 启动超时，状态设为 STOPPED", index);
                info.setStatus("STOPPED");
                info.setLastError("启动超时：模拟器未能在规定时间内就绪");
            }
            // 启动完成后检查 Discord 安装状态
            int realAdbPort = getMumuRunningPort(index);
            if (realAdbPort > 0) {
                String device = "127.0.0.1:" + realAdbPort;
                execAdbRaw("connect", device);
                String pkgList = execAdbRaw("-s", device, "shell", "pm", "list", "packages", "com.discord");
                boolean installed = pkgList != null && pkgList.contains("com.discord");
                info.setDiscordInstalled(installed);
                log.info("模拟器{} Discord安装状态: {}", index, installed);
            }
            // 启动后做一次健康检查，确认模拟器配置/数据完整可用
            healthCheck(index);
            emulators.put(index, info);
            return info;
        } catch (Exception e) {
            log.error("启动模拟器失败: index={}", index, e);
            EmulatorInfo info = emulators.getOrDefault(index,
                    EmulatorInfo.builder().index(index).status("ERROR").lastError(e.getMessage()).build());
            info.setStatus("ERROR");
            info.setLastError(e.getMessage());
            emulators.put(index, info);
            return info;
        }
    }

    public List<EmulatorInfo> startAllEmulators() {
        ensureMumuDaemon();
        refreshEmulatorList();
        List<Integer> targets = targetIndexes();
        if (targets.isEmpty()) {
            return sortedEmulators();
        }
        log.info("全部启动（串行）：共 {} 台，目标 {}", targets.size(), targets);

        // 串行启动，每台随机间隔 1-5 秒，避免 Mumu 并发启动导致进程异常退出
        // 随机间隔可防止大量模拟器同时启动时资源竞争
        List<EmulatorInfo> results = new ArrayList<>();
        Random random = new Random();
        for (int i = 0; i < targets.size(); i++) {
            int idx = targets.get(i);
            log.info("启动第 {}/{} 台模拟器: index={}", i + 1, targets.size(), idx);

            // 启动前短暂等待守护进程就绪
            if (!waitForDaemonReady(10)) {
                log.warn("守护进程未就绪，跳过模拟器 {}", idx);
                EmulatorInfo failInfo = emulators.getOrDefault(idx,
                        EmulatorInfo.builder().index(idx).status("ERROR")
                                .lastError("守护进程未就绪，启动超时").build());
                failInfo.setStatus("ERROR");
                emulators.put(idx, failInfo);
                results.add(failInfo);
                continue;
            }

            try {
                EmulatorInfo started = startEmulator(idx);
                results.add(started);

                // 如果启动失败，快速重试一次
                if (!"RUNNING".equals(started.getStatus())) {
                    log.warn("模拟器{} 首次启动未成功，等待 3 秒后重试...", idx);
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

                    waitForDaemonReady(10);
                    started = startEmulator(idx);
                    results.set(results.size() - 1, started);

                    if (!"RUNNING".equals(started.getStatus())) {
                        log.error("模拟器{} 重试启动仍失败", idx);
                    }
                }

                // 启动成功后随机间隔 1-5 秒再启动下一台
                if (i < targets.size() - 1) {
                    int delay = 1000 + random.nextInt(4000); // 1000-5000ms
                    log.info("随机等待 {}ms 后启动下一台...", delay);
                    try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                log.error("启动模拟器{}失败", idx, e);
                EmulatorInfo failInfo = emulators.getOrDefault(idx,
                        EmulatorInfo.builder().index(idx).status("ERROR")
                                .lastError(e.getMessage()).build());
                failInfo.setStatus("ERROR");
                emulators.put(idx, failInfo);
                results.add(failInfo);
            }
        }
        refreshEmulatorList();
        return sortedEmulators();
    }

    /**
     * 等待 MuMuPlayer 守护进程就绪
     */
    private boolean waitForDaemonReady(int timeoutSeconds) {
        log.info("等待 MuMuPlayer 守护进程就绪 (超时={}s)...", timeoutSeconds);
        for (int i = 0; i < timeoutSeconds; i++) {
            if (isDaemonReady()) {
                daemonReady = true;
                return true;
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        log.warn("守护进程等待超时");
        return false;
    }

    public EmulatorInfo stopEmulator(int index) {
        ensureMumuDaemon();
        log.info("停止模拟器 index={}", index);
        int maxRetries = 3;
        boolean stopped = false;

        for (int attempt = 1; attempt <= maxRetries && !stopped; attempt++) {
            try {
                String result = execMumuTool("close", String.valueOf(index));
                log.info("停止结果(第{}次): {}", attempt, result);

                // 验证是否真正停止
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {}

                // 检查是否还在运行
                int realPort = getMumuRunningPort(index);
                if (realPort <= 0) {
                    stopped = true;
                    log.info("模拟器{} 已成功停止(第{}次)", index, attempt);
                } else {
                    log.warn("模拟器{} 停止后仍在运行，端口={}, 重试...", index, realPort);
                    // adb 强制断开
                    try {
                        EmulatorInfo info = emulators.get(index);
                        if (info != null && info.getAdbPort() > 0) {
                            execAdbRaw("-s", "127.0.0.1:" + info.getAdbPort(), "emu", "kill");
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.warn("停止警告(第{}次): {}", attempt, e.getMessage());
                // adb 强制断开
                try {
                    EmulatorInfo info = emulators.get(index);
                    if (info != null && info.getAdbPort() > 0) {
                        execAdbRaw("-s", "127.0.0.1:" + info.getAdbPort(), "emu", "kill");
                    }
                } catch (Exception ignored) {}

                if (attempt < maxRetries) {
                    try { Thread.sleep(3000L * attempt); } catch (InterruptedException ignored) {}
                }
            }
        }

        if (!stopped) {
            log.warn("模拟器{} 停止重试{}次后仍可能在运行，强制标记为停止", index, maxRetries);
        }

        EmulatorInfo info = emulators.getOrDefault(index, readVmConfig(index));
        info.setStatus("STOPPED");
        emulators.put(index, info);
        return info;
    }

    public List<EmulatorInfo> stopAllEmulators() {
        ensureMumuDaemon();
        refreshEmulatorList();
        // 停止对全部已存在的模拟器生效，同样按 index 升序执行
        return sortedIndexes().stream().map(this::stopEmulator).collect(Collectors.toList());
    }

    public EmulatorInfo restartEmulator(int index) {
        stopEmulator(index);
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        return startEmulator(index);
    }

    /**
     * 启动健康检查：检测模拟器配置与数据是否完整可用。
     * 检查项：vm 目录存在性、setting/vm.json 配置完整性、mumutool 能否识别该设备。
     * 任一检查不通过则标记为损坏（status=DAMAGED、damaged=true、damageReason 记录原因）。
     * @return 检查后的模拟器信息（含 damaged / damageReason / status）
     */
    public EmulatorInfo healthCheck(int index) {
        EmulatorInfo info = emulators.getOrDefault(index, readVmConfig(index));
        Path vmDir = Paths.get(resolvedVmsBasePath, String.valueOf(index));
        Path vmJson = Paths.get(resolvedVmsBasePath, String.valueOf(index), "setting", "vm.json");

        // 1) 基础目录与配置存在性
        if (!Files.exists(vmDir)) {
            markDamaged(info, "模拟器数据目录缺失: " + vmDir);
            emulators.put(index, info);
            return info;
        }
        if (!Files.exists(vmJson)) {
            markDamaged(info, "配置文件缺失: setting/vm.json");
            emulators.put(index, info);
            return info;
        }

        // 2) 配置内容完整性（关键字段、端口有效）
        // 注意：adbPort/androidPort 是 MuMu 在模拟器首次运行时才动态分配的，
        // 静态 vm.json 里新机/克隆机常为 0（非损坏），故端口为 0 不判损坏，
        // 真正可用性由下方 mumutool info 探测 + 运行时动态端口为准。
        try {
            JsonNode json = objectMapper.readTree(vmJson.toFile());
            int adbPort = json.path("adbPort").asInt(0);
            int androidPort = json.path("androidPort").asInt(0);
            // 仅当端口字段显式存在但为负值时，才视为配置损坏
            if ((json.has("adbPort") && adbPort < 0) || (json.has("androidPort") && androidPort < 0)) {
                markDamaged(info, "配置不完整: adbPort/androidPort 无效 (adbPort="
                        + adbPort + ", androidPort=" + androidPort + ")");
                emulators.put(index, info);
                return info;
            }
        } catch (Exception e) {
            markDamaged(info, "配置文件解析失败: " + e.getMessage());
            emulators.put(index, info);
            return info;
        }

        // 3) mumutool 能否识别该设备（running/stopped 都应能识别）
        try {
            String result = execMumuTool("info", String.valueOf(index));
            JsonNode json = objectMapper.readTree(result);
            JsonNode ret = json.path("return");
            // 能返回 state 字段即视为可识别；missing 表示设备不存在
            if (ret.has("missing") || ret.path("state").asText("").isBlank()) {
                markDamaged(info, "mumutool 无法识别该模拟器（设备已损坏或不存在）");
                emulators.put(index, info);
                return info;
            }
        } catch (Exception e) {
            // info 命令抛错（设备注册损坏）同样判定为损坏
            markDamaged(info, "mumutool info 失败: " + e.getMessage());
            emulators.put(index, info);
            return info;
        }

        // 健康检查通过：清除损坏标记
        info.setDamaged(false);
        info.setDamageReason(null);
        if (!"RUNNING".equals(info.getStatus())) {
            info.setStatus("STOPPED");
        }
        emulators.put(index, info);
        return info;
    }

    private void markDamaged(EmulatorInfo info, String reason) {
        info.setDamaged(true);
        info.setDamageReason(reason);
        info.setStatus("DAMAGED");
        log.warn("模拟器{} 健康检查未通过: {}", info.getIndex(), reason);
    }

    /**
     * 一键修复：重建损坏的模拟器。
     * 保留原模拟器的 CPU/内存配置，先删除（含数据目录），再用原配置重新创建，
     * 最后回写名称与状态。修复后模拟器处于 STOPPED 状态，可直接启动。
     * @return 修复后的 EmulatorInfo；修复失败返回 damaged=true 的对象
     */
    public EmulatorInfo repairEmulator(int index) {
        ensureMumuDaemon();
        log.info("一键修复模拟器 index={}", index);
        // 读取原配置（CPU/内存），修复后沿用
        EmulatorInfo before = emulators.getOrDefault(index, readVmConfig(index));
        Integer cpu = before.getCpuCount() > 0 ? before.getCpuCount() : null;
        Integer mem = before.getMemoryMB() > 0 ? before.getMemoryMB() : null;

        try {
            // 1) 删除现有（含数据目录）
            try { stopEmulator(index); } catch (Exception ignored) {}
            try { execMumuTool("delete", String.valueOf(index)); } catch (Exception ignored) {}
            // 强制清理残留目录
            Path vmDir = Paths.get(resolvedVmsBasePath, String.valueOf(index));
            if (Files.exists(vmDir)) {
                deleteRecursively(vmDir.toFile());
            }
            emulators.remove(index);

            // 2) 用原配置重建
            EmulatorInfo created = createEmulator(index, cpu, mem);
            created.setDamaged(false);
            created.setDamageReason(null);
            created.setStatus("STOPPED");
            emulators.put(index, created);
            log.info("模拟器{} 修复完成", index);
            return created;
        } catch (Exception e) {
            log.error("修复模拟器失败: index={}", index, e);
            EmulatorInfo failed = before;
            failed.setDamaged(true);
            failed.setDamageReason("修复失败: " + e.getMessage());
            failed.setStatus("DAMAGED");
            emulators.put(index, failed);
            return failed;
        }
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        file.delete();
    }

    public boolean deleteEmulator(int index) {
        ensureMumuDaemon();
        log.info("删除模拟器 index={}", index);
        try {
            stopEmulator(index);
            execMumuTool("delete", String.valueOf(index));
            emulators.remove(index);
            return true;
        } catch (Exception e) {
            log.error("删除失败: index={}", index, e);
            return false;
        }
    }

    /**
     * 批量删除模拟器。按 index 降序删除以降低索引变动带来的副作用。
     * @return 成功删除的 index 列表
     */
    public List<Integer> deleteEmulators(List<Integer> indexes) {
        ensureMumuDaemon();
        log.info("批量删除模拟器: {}", indexes);
        List<Integer> sorted = new ArrayList<>(indexes);
        sorted.sort(Collections.reverseOrder());
        List<Integer> deleted = new ArrayList<>();
        for (int idx : sorted) {
            if (deleteEmulator(idx)) {
                deleted.add(idx);
            }
        }
        refreshEmulatorList();
        return deleted;
    }

    public String takeScreenshot(int index) {
        try {
            EmulatorInfo info = getEmulator(index);
            if (info == null || !"RUNNING".equals(info.getStatus())) return null;
            String device = "127.0.0.1:" + info.getAdbPort();
            Process p = new ProcessBuilder(adbPath, "-s", device, "exec-out", "screencap", "-p").start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.getInputStream().transferTo(baos);
            p.waitFor(10, TimeUnit.SECONDS);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("截图失败: index={}", index, e);
            return null;
        }
    }

    /**
     * 执行 ADB 命令。优先使用 mumutool info 返回的真实 adb_port（动态分配），
     * vm.json 中的 adbPort 是静态值，与运行时的真实端口不匹配。
     */
    public String execAdb(int index, String... args) {
        // 如果有在线 Agent，优先通过 Agent 执行
        if (cloudWebSocketService != null && currentUserId != null) {
            List<AgentRegistration> agents = cloudWebSocketService.getOnlineAgentsByUserId(Long.parseLong(currentUserId));
            if (!agents.isEmpty()) {
                log.debug("Agent 模式: 通过 Agent 执行 ADB 命令, userId={}, index={}", currentUserId, index);
                return cloudWebSocketService.execAdb(Long.parseLong(currentUserId), index, args);
            }
        }
        
        // 本地模式执行
        try {
            // 优先使用真实动态端口
            int realPort = getMumuRunningPort(index);
            String device;
            if (realPort > 0) {
                device = "127.0.0.1:" + realPort;
            } else {
                // 模拟器未运行，降级使用 vm.json 静态端口（启动过程中可能有效）
                EmulatorInfo info = getEmulator(index);
                if (info == null || info.getAdbPort() == 0) {
                    return "ERROR: 模拟器未配置 ADB 端口";
                }
                device = "127.0.0.1:" + info.getAdbPort();
            }
            execAdbRaw("connect", device);
            List<String> cmd = new ArrayList<>();
            cmd.add(resolvedAdbPath); cmd.add("-s"); cmd.add(device);
            cmd.addAll(Arrays.asList(args));
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            p.waitFor(30, TimeUnit.SECONDS);
            return (out + err).trim();
        } catch (Exception e) {
            log.error("ADB执行失败: index={}, args={}", index, args, e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 计算MuMu模拟器的默认ADB端口
     * MuMu ADB端口公式: 16384 + index * 32
     * 但实际端口可能因 vm.json 配置而不同，此方法作为兜底计算
     */
    private int calculateAdbPort(int index) {
        return 16384 + index * 32;
    }

    public int getAdbPort(int index) {
        // 优先返回运行时真实动态端口（mumutool info 的 adb_port），
        // 静态 vm.json 推导值可能与实际不符，会导致 ADB connect/state 检测失败。
        // 注意：此处不调用 getMumuRunningPort，避免与 tryAdbFallback 形成递归
        EmulatorInfo info = emulators.get(index);
        if (info != null && info.getAdbPort() > 0) return info.getAdbPort();
        EmulatorInfo cfg = readVmConfig(index);
        int port = cfg.getAdbPort();
        if (port > 0) return port;
        // 兜底计算：MuMu ADB端口公式
        return calculateAdbPort(index);
    }

    public String execAdbRaw(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(resolvedAdbPath);
            cmd.addAll(Arrays.asList(args));
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            String err = new String(p.getErrorStream().readAllBytes()).trim();
            p.waitFor(10, TimeUnit.SECONDS);
            String result = !out.isEmpty() ? out : err;
            return result.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 通过 mumutool info 获取模拟器运行信息：返回 running/launching 状态下的真实 adb_port，
     * 未运行返回 -1。vm.json 中的 adbPort 是静态值，与实际端口不匹配。
     * 兼容 MuMu 的 "running" 和 "launching" 状态（部分版本模拟器启动过程中返回 launching）
     */
    private int getMumuRunningPort(int index) {
        try {
            String result = execMumuTool("info", String.valueOf(index));
            JsonNode json = objectMapper.readTree(result);
            String state = json.path("return").path("state").asText("");
            // 兼容 running / launching / booting 等运行中状态
            boolean isActive = "running".equals(state) || "launching".equals(state) || "booting".equals(state);
            if (isActive) {
                int port = json.path("return").path("adb_port").asInt(-1);
                if (port <= 0) {
                    // 虽然返回 running 但端口无效，尝试通过 ADB 兜底检测
                    port = tryAdbFallback(index);
                }
                return port;
            }
            // Mumu 返回 stopped/other 状态时，仍尝试 ADB 兜底检测
            // 防止 Mumu 守护进程未就绪或状态延迟导致误判
            log.debug("mumutool info {} 返回 state={}，尝试 ADB 兜底检测", index, state);
            return tryAdbFallback(index);
        } catch (Exception e) {
            log.warn("mumutool info {} 失败: {}，尝试 ADB 兜底", index, e.getMessage());
            return tryAdbFallback(index);
        }
    }

    /**
     * ADB 兜底检测：直接用 adb connect + get-state 判断模拟器是否在运行
     */
    private int tryAdbFallback(int index) {
        try {
            // 直接使用端口公式计算，避免与 getAdbPort/getMumuRunningPort 形成递归
            int adbPort = calculateAdbPort(index);
            if (adbPort <= 0) return -1;
            String device = "127.0.0.1:" + adbPort;
            // 确保 ADB server 已启动
            execAdbRaw("start-server");
            Thread.sleep(200);
            execAdbRaw("connect", device);
            Thread.sleep(500);
            String state = execAdbRaw("-s", device, "get-state").trim();
            if ("device".equals(state)) {
                log.info("模拟器{} ADB 兜底检测: 端口={}, 状态=device", index, adbPort);
                return adbPort;
            }
            // 尝试从已连接设备列表中搜索匹配端口
            String devices = execAdbRaw("devices").trim();
            if (devices.contains("127.0.0.1:" + adbPort) && devices.contains("device")) {
                log.info("模拟器{} ADB 兜底检测(设备列表): 端口={}, 状态=device", index, adbPort);
                return adbPort;
            }
            log.debug("模拟器{} ADB 兜底检测: 端口={}, 状态={} (未找到)", index, adbPort, state);
            return -1;
        } catch (Exception e) {
            log.debug("模拟器{} ADB 兜底检测异常: {}", index, e.getMessage());
            return -1;
        }
    }

    private boolean isMumuRunning(int index) {
        return getMumuRunningPort(index) > 0;
    }

    /**
     * 实时检测指定模拟器的 ADB 连接状态（device / offline / 无）。
     * 用于识别无论由本系统还是外部（MuMu 客户端）启动的模拟器是否在运行。
     */
    public boolean isAdbConnected(int index) {
        int adbPort = getAdbPort(index);
        if (adbPort <= 0) return false;
        String device = "127.0.0.1:" + adbPort;
        try {
            execAdbRaw("connect", device);
            String state = execAdbRaw("-s", device, "get-state").trim();
            return "device".equals(state);
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForAdbReady(int adbPort, int timeoutSeconds) {
        String device = "127.0.0.1:" + adbPort;
        log.info("等待 ADB 就绪: {} (最多{}秒)", device, timeoutSeconds);
        for (int i = 0; i < timeoutSeconds; i++) {
            try {
                execAdbRaw("connect", device);
                // 用 get-state 区分 offline / device，避免离线状态误判为就绪
                Process p = new ProcessBuilder(adbPath, "-s", device, "get-state").start();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor(5, TimeUnit.SECONDS);
                if ("device".equals(out)) {
                    // 再验证 shell 可用，确保真正可交互
                    Process shell = new ProcessBuilder(adbPath, "-s", device, "shell", "echo", "ready").start();
                    String sOut = new String(shell.getInputStream().readAllBytes()).trim();
                    shell.waitFor(5, TimeUnit.SECONDS);
                    if (sOut.contains("ready")) {
                        log.info("ADB 已就绪: {}", device);
                        return;
                    }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        log.warn("ADB 连接超时: {}", device);
    }

    /**
     * 轮询等待模拟器真正 running。
     * 注意：必须用 mumutool info 返回的真实 adb_port（动态分配），不能用 vm.json 的静态值。
     */
    private boolean waitForEmulatorRunning(int index, int timeoutSeconds) {
        for (int i = 0; i < timeoutSeconds; i++) {
            int realAdbPort = getMumuRunningPort(index);
            if (realAdbPort <= 0) {
                // 模拟器尚未启动或未就绪，继续等待
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                continue;
            }
            String device = "127.0.0.1:" + realAdbPort;
            try {
                execAdbRaw("connect", device);
                String state = execAdbRaw("-s", device, "get-state").trim();
                if ("device".equals(state)) {
                    log.info("模拟器{} 已进入 running 状态 (adb device, port={})", index, realAdbPort);
                    return true;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        log.warn("模拟器{} 等待 running 超时 (adb 未就绪)", index);
        return false;
    }

    private void refreshEmulatorList() {
        Path basePath = Paths.get(resolvedVmsBasePath);
        if (!Files.exists(basePath)) return;
        try {
            File[] vmDirs = basePath.toFile().listFiles(File::isDirectory);
            if (vmDirs == null) return;
            // 目录遍历顺序由文件系统决定，这里先按 index 数值升序排序，
            // 保证 LinkedHashMap 的插入顺序 = 模拟器1、模拟器2 ... 的自然顺序
            List<Integer> indexes = new ArrayList<>();
            for (File vmDir : vmDirs) {
                try {
                    indexes.add(Integer.parseInt(vmDir.getName()));
                } catch (NumberFormatException ignored) {}
            }
            Collections.sort(indexes);
            for (int index : indexes) {
                EmulatorInfo info = emulators.computeIfAbsent(index, k -> readVmConfig(index));
                // 使用 mumutool info 获取真实 adb_port 并判断运行状态
                int realAdbPort = getMumuRunningPort(index);
                boolean isMumuRunning = realAdbPort > 0;

                // 强制同步状态：基于 Mumu 实际运行状态
                if (isMumuRunning) {
                    if (!"RUNNING".equals(info.getStatus())) {
                        log.info("模拟器{} 状态同步: {} -> RUNNING", index, info.getStatus());
                        info.setStatus("RUNNING");
                        info.setLastError(null);
                    }
                } else {
                    // 模拟器未运行，所有非 STOPPED 状态都改为 STOPPED
                    if (!"STOPPED".equals(info.getStatus()) && !"DAMAGED".equals(info.getStatus())) {
                        log.info("模拟器{} 状态同步: {} -> STOPPED (Mumu未运行)", index, info.getStatus());
                        info.setStatus("STOPPED");
                        info.setLastError(null);
                    }
                }
                // 实时检测 Discord 安装状态（仅对 RUNNING 的模拟器），
                // 必须用 mumutool 返回的真实 adb_port（动态分配），不能用 vm.json 的静态值
                if (isMumuRunning) {
                    String device = "127.0.0.1:" + realAdbPort;
                    // 确保 ADB 已连接
                    execAdbRaw("connect", device);
                    String pkgList = execAdbRaw("-s", device, "shell", "pm", "list", "packages", "com.discord");
                    boolean installed = pkgList != null && pkgList.contains("com.discord");
                    info.setDiscordInstalled(installed);
                } else {
                    info.setDiscordInstalled(false);
                }

                // 同步 vmName，确保与管理后台一致
                syncVmName(index);

                // 启动健康检查：非 running 的模拟器检测是否损坏（RUNNING 视为健康）
                if (!"RUNNING".equals(info.getStatus())) {
                    healthCheck(index);
                }
            }
        } catch (Exception e) {
            log.warn("刷新列表出错", e);
        }
    }

    /**
     * 返回按 index 升序排列的模拟器列表（对应「模拟器1、模拟器2...」的顺序）
     */
    private List<EmulatorInfo> sortedEmulators() {
        synchronized (emulators) {
            return emulators.values().stream()
                    .sorted(Comparator.comparingInt(EmulatorInfo::getIndex))
                    .collect(Collectors.toList());
        }
    }

    /**
     * 返回按升序排列的 index 列表
     */
    private List<Integer> sortedIndexes() {
        synchronized (emulators) {
            return emulators.keySet().stream().sorted().collect(Collectors.toList());
        }
    }

    private EmulatorInfo readVmConfig(int index) {
        Path configPath = Paths.get(resolvedVmsBasePath, String.valueOf(index), "setting", "vm.json");
        var builder = EmulatorInfo.builder()
                .index(index).name(formatEmuName(index)).status("STOPPED");

        if (Files.exists(configPath)) {
            try {
                JsonNode json = objectMapper.readTree(configPath.toFile());
                int adbPort = json.path("adbPort").asInt(16384 + index * 32);
                builder.adbPort(adbPort);
                // androidPort 同样缺省推导，避免静态配置为 0 时内存态不一致
                int androidPort = json.path("androidPort").asInt(16385 + index * 32);
                builder.androidPort(androidPort);
                builder.frontendPort(json.path("frontendPort").asInt(0));
                // 名称统一按序号递增（V001/V002...），不采用 MuMu 内部 vmName，保证列表命名一致
                builder.name(formatEmuName(index));
                builder.cpuCount(json.path("vmCpuCount").asInt(0));
                builder.memoryMB(json.path("vmMemoryOfMB").asInt(0));
                int w = json.path("framebufferWidth").asInt(720);
                int h = json.path("framebufferHeight").asInt(1280);
                builder.resolution(w + "x" + h);
            } catch (Exception e) {
                log.warn("读取VM配置失败: index={}", index, e);
            }
        }
        return builder.build();
    }

    private String execMumuTool(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedMumutoolPath);
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).start();
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        p.waitFor(30, TimeUnit.SECONDS);

        // mumutool 返回 JSON 到 stdout 或 stderr 都可能
        String result = !out.trim().isEmpty() ? out : err;
        if (!err.isEmpty() && result.contains("Error")) {
            throw new IOException("mumutool error: " + err.trim());
        }
        log.debug("mumutool {}: {}", String.join(" ", args), result.trim());
        return result;
    }
}