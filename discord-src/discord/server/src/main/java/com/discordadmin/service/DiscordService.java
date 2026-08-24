package com.discordadmin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DiscordService {

    @Value("${mumu.discord-apk-path}")
    private String discordApkPath;

    private final EmulatorService emulatorService;

    // 多个下载源按优先级排列
    private static final String[] DOWNLOAD_URLS = {
        // apkcombo CDN
        "https://apkcombo.com/discord/com.discord/download/phone-apk",
        // APKPure
        "https://apkpure.com/discord-chat-for-gamers/com.discord/download",
        // Uptodown
        "https://discord.en.uptodown.com/android/download",
        // Discord 官网 (通常重定向到 Play Store)
        "https://discord.com/download",
        // 通用 Google Play 直达
        "https://play.google.com/store/apps/details?id=com.discord"
    };

    private String currentUserId;

    public DiscordService(EmulatorService emulatorService) {
        this.emulatorService = emulatorService;
    }

    /** 设置当前操作用户 ID */
    public void setCurrentUserId(String userId) {
        this.currentUserId = userId;
        this.emulatorService.setCurrentUserId(userId);
    }

    /** 获取当前操作用户 ID */
    public String getCurrentUserId() {
        return this.currentUserId;
    }

    public boolean isApkDownloaded() {
        Path path = Paths.get(discordApkPath);
        return Files.exists(path) && path.toFile().length() > 1024 * 1024; // > 1MB
    }

    /**
     * 上传本地 APK 文件
     */
    public boolean uploadApk(MultipartFile file) {
        try {
            Path apkFile = Paths.get(discordApkPath);
            Files.createDirectories(apkFile.getParent());
            file.transferTo(apkFile);
            log.info("APK 上传成功: {} bytes", apkFile.toFile().length());
            return true;
        } catch (Exception e) {
            log.error("APK 上传失败", e);
            return false;
        }
    }

    /**
     * 下载 Discord APK
     */
    @Async
    public CompletableFuture<String> downloadApk() {
        return CompletableFuture.supplyAsync(() -> {
            // 先尝试用已知可用的直接下载链接
            // Discord 的 APK 通常通过 Google Play 分发，我们尝试几个已知源
            String[] directUrls = {
                "https://github.com/ImKKingshuk/Discord-APK/releases/latest/download/discord.apk",
                "https://www.softpedia.com/get/Mobile-Phone-Tools/Android-APK-Editor-for-PC/Discord.shtml",
            };

            // 尝试所有已知 URL
            for (String url : directUrls) {
                try {
                    log.info("尝试下载: {}", url);
                    if (downloadFromUrl(url)) return "SUCCESS";
                } catch (Exception e) {
                    log.warn("下载失败: {} -> {}", url, e.getMessage());
                }
            }

            // 放宽限制，尝试普遍镜像
            String[] mirrorUrls = {
                "https://github.com/niccokunzmann/simple-android-downloader/releases/download/0.1.0/discord.apk",
            };

            for (String url : mirrorUrls) {
                try {
                    if (downloadFromUrl(url)) return "SUCCESS";
                } catch (Exception ignored) {}
            }

            return "FAILED: 所有下载源均不可用，请手动上传 APK 文件";
        });
    }

    private boolean downloadFromUrl(String url) throws Exception {
        Path apkFile = Paths.get(discordApkPath);
        Files.createDirectories(apkFile.getParent());

        URI uri = URI.create(url);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        // 处理重定向
        int redirectCount = 0;
        while (redirectCount < 5) {
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 301 || status == 302 || status == 307 || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                if (newUrl == null) break;
                conn.disconnect();
                conn = (HttpURLConnection) URI.create(newUrl).toURL().openConnection();
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                redirectCount++;
                continue;
            }
            break;
        }

        int status = conn.getResponseCode();
        if (status >= 200 && status < 300) {
            String contentType = conn.getContentType();
            // 确保是 APK 文件（可能被判定为 application/vnd.android.package-archive 或 application/octet-stream）
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(apkFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                long total = 0;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    total += bytesRead;
                }
                log.info("APK下载完成, 大小: {} bytes, 类型: {}", total, contentType);
            }

            // 验证文件
            long fileSize = apkFile.toFile().length();
            if (fileSize > 1024 * 1024) {
                return true;
            } else {
                // 太小，可能是 HTML 页面
                log.warn("下载的文件太小 ({} bytes)，可能不是 APK", fileSize);
                Files.deleteIfExists(apkFile);
                return false;
            }
        } else {
            log.warn("下载失败, HTTP {}: {}", status, url);
            return false;
        }
    }

    public CompletableFuture<String> installDiscord(int index) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!isApkDownloaded()) {
                    return "ERROR: APK 未下载，请先下载或上传";
                }
                // 安装前确认 ADB 为 device 状态，避免 offline 导致安装失败
                int adbPort = emulatorService.getAdbPort(index);
                String device = "127.0.0.1:" + adbPort;
                emulatorService.execAdbRaw("connect", device);
                boolean ready = false;
                for (int i = 0; i < 30; i++) {
                    String state = emulatorService.execAdbRaw("-s", device, "get-state").trim();
                    if ("device".equals(state)) { ready = true; break; }
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
                if (!ready) {
                    return "ERROR: ADB 未就绪(device offline)，安装跳过 模拟器" + (index + 1);
                }
                log.info("在模拟器 {} 安装 Discord", index);
                String result = emulatorService.execAdb(index, "install", "-r", "-d", discordApkPath);
                log.info("模拟器 {} 安装结果: {}", index, result);
                return result.contains("Success") ? "SUCCESS" : result;
            } catch (Exception e) {
                log.error("模拟器 {} Discord 安装失败", index, e);
                return "ERROR: " + e.getMessage();
            }
        });
    }

    private static final String DISCORD_PKG = "com.discord";

    /**
     * 启动 Discord 客户端。
     * 使用 am start 显式启动 Discord 的 LAUNCHER Activity，避免 monkey 在未安装时
     * 回退启动其他应用（如 MuMu 游戏中心）导致坐标点击打在错误 App 上。
     */
    /**
     * 启动 Discord 客户端。
     * 根因修复：之前用 "cmd package resolve-activity --brief" 动态解析 launcher Activity，
     * 但它返回的是 com.discord/.main.MainDefault（非主界面引导页），启动后系统直接回到
     * launcher/桌面，从而误判为"打开游戏中心"。真正的 Discord 主界面 Activity 是
     * com.discord/.main.MainActivity（带 LAUNCHER 类别）。
     * 这里直接优先使用 MainActivity 作为启动组件，确保打开的是 Discord 客户端本身。
     */
    public String launchDiscord(int index) {
        log.info("启动 Discord: 模拟器 {}", index);

        if (!checkDiscordInstalled(index)) {
            log.warn("模拟器{} 未安装 Discord，放弃启动", index + 1);
            return "ERROR: 未安装 Discord";
        }

        // 优先用真正的 Discord 主界面 Activity；若解析失败再回退到通用启动
        String activity = resolveMainActivity(index);
        String comp = activity != null ? activity : DISCORD_PKG + "/.main.MainActivity";
        log.info("模拟器{} 启动 Discord Activity: {}", index + 1, comp);
        // -W 等待启动完成；-n 指定组件；--activity-clear-task 确保 Discord 到前台
        emulatorService.execAdb(index, "shell", "am", "start", "-W", "-n", comp,
                "--activity-clear-task", "--activity-clear-top");
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        // 校验：前台包名必须是 com.discord 且不能是桌面/启动器（如 app.lawnchair）
        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            String fg = getForegroundPackage(index);
            if (DISCORD_PKG.equals(fg)) {
                log.info("模拟器{} Discord 已在前台", index + 1);
                return "SUCCESS";
            }
            // 启动较慢时重试一次（仅前几秒）
            if (i == 3) {
                emulatorService.execAdb(index, "shell", "am", "start", "-W", "-n", comp,
                        "--activity-clear-task", "--activity-clear-top");
            }
        }
        String fg = getForegroundPackage(index);
        log.warn("模拟器{} 启动 Discord 后前台仍不是 Discord（当前: {}）", index + 1, fg);
        return "ERROR: Discord 未能进入前台 (当前: " + fg + ")";
    }

    /**
     * 从 dumpsys package 中确认 com.discord/.main.MainActivity 存在（带 LAUNCHER 的真正主界面）。
     * 不再使用 resolve-activity（会返回错误的 MainDefault 引导页）。
     */
    private String resolveMainActivity(int index) {
        try {
            String out = emulatorService.execAdb(index, "shell", "dumpsys", "package", DISCORD_PKG);
            if (out != null && out.contains(".main.MainActivity")) {
                return DISCORD_PKG + "/.main.MainActivity";
            }
            // 退化：从 dumpsys 里抓任意一个 com.discord/.<X>Activity 含 MainActivity 的
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(com\\.discord)/(\\.main\\.MainActivity)")
                    .matcher(out != null ? out : "");
            if (m.find()) {
                return m.group(1) + "/" + m.group(2);
            }
        } catch (Exception e) {
            log.debug("解析 Discord MainActivity 失败: 模拟器{}", index + 1, e);
        }
        return null;
    }

    /** 读取当前前台应用包名 */
    public String getForegroundPackage(int index) {
        try {
            // mCurrentFocus / mFocusedApp 里形如: com.discord/com.discord.main.MainActivity
            String out = emulatorService.execAdb(index, "shell", "dumpsys", "window");
            if (out != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?:mCurrentFocus|mFocusedApp)[^\\n]*?([A-Za-z0-9_.]+)/")
                        .matcher(out);
                String last = null;
                while (m.find()) last = m.group(1);
                if (last != null) return last;
            }
            // 回退方案：取最近的 resumed activity
            String act = emulatorService.execAdb(index, "shell", "dumpsys", "activity", "activities");
            if (act != null) {
                java.util.regex.Matcher m2 = java.util.regex.Pattern
                        .compile("mResumedActivity[^\\n]*?([A-Za-z0-9_.]+)/").matcher(act);
                if (m2.find()) return m2.group(1);
            }
        } catch (Exception e) {
            log.debug("读取前台包名失败: 模拟器{}", index + 1, e);
        }
        return "";
    }

    /** 当前前台是否为 Discord（用于「已打开就直接加好友」判断） */
    public boolean isDiscordForeground(int index) {
        return DISCORD_PKG.equals(getForegroundPackage(index));
    }

    /**
     * 是否停在 Discord 登录页（未登录）：登录页会同时出现账号框与密码框两个 EditText。
     * 用「输入框数量」判断比匹配「添加好友」文本更可靠（避免引导文案误判）。
     */
    public boolean isOnLoginPage(int index) {
        if (!isDiscordForeground(index)) return false;
        String xml = dumpUi(index);
        return findEditTexts(xml).length >= 2;
    }

    /**
     * 是否已登录 Discord：必须前台是 Discord，且「不是登录页」（没有账号/密码输入框），
     * 且能解析到当前登录用户名（底部个人资料栏）。三重条件避免误判。
     * 注意：登录页也可能含「添加好友」引导文案，故不以文本判定登录态。
     */
    public boolean isDiscordLoggedIn(int index) {
        if (!isDiscordForeground(index)) return false;
        if (isOnLoginPage(index)) return false; // 仍在登录页 = 未登录
        // 已登录主页：能抓到用户名（底部个人资料栏 content-desc）
        return getLoggedInUser(index) != null;
    }

    // ===================== UI 动态定位（替代硬编码坐标） =====================

    /** dump 当前页面 UI 层级，返回 uiautomator 输出的 XML */
    public String dumpUi(int index) {
        try {
            emulatorService.execAdb(index, "shell", "uiautomator", "dump", "/sdcard/ui_dump.xml");
            Thread.sleep(800);
            return emulatorService.execAdb(index, "shell", "cat", "/sdcard/ui_dump.xml");
        } catch (Exception e) {
            log.error("模拟器{} dump UI 失败", index + 1, e);
            return null;
        }
    }

    /**
     * 在 XML 中查找含任一关键词(匹配 text 或 content-desc)的节点，返回其中心坐标 [x,y]；未找到返回 null。
     * 关键词大小写不敏感；含中文关键词时做包含匹配。
     */
    /** 用 DOM 解析 uiautomator XML，避免正则误匹配导致坐标错乱 */
    public int[] findNodeCenter(String xml, String... keywords) {
        if (xml == null || xml.isBlank()) return null;
        try {
            javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
                    .newInstance().newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName("node");
            int[] fallback = null;
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) nodes.item(i);
                String text = e.getAttribute("text");
                String desc = e.getAttribute("content-desc");
                String bounds = e.getAttribute("bounds");
                if (bounds == null || bounds.isBlank()) continue;
                String label = (text + " " + desc).toLowerCase();
                boolean hit = false;
                for (String kw : keywords) {
                    if (kw != null && !kw.isBlank() && label.contains(kw.toLowerCase())) { hit = true; break; }
                }
                if (!hit) continue;
                int[] c = parseCenter(bounds);
                if (c == null) continue;
                if ("true".equals(e.getAttribute("clickable"))) return c; // 优先可点击节点
                if (fallback == null) fallback = c;
            }
            return fallback;
        } catch (Exception ex) {
            log.warn("模拟器 UI XML 解析失败: {}", ex.getMessage());
            return null;
        }
    }

    private static int[] parseCenter(String bounds) {
        // bounds="[left,top][right,bottom]"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
        java.util.regex.Matcher m = p.matcher(bounds);
        if (!m.find()) return null;
        int l = Integer.parseInt(m.group(1)), t = Integer.parseInt(m.group(2));
        int r = Integer.parseInt(m.group(3)), b = Integer.parseInt(m.group(4));
        return new int[]{ (l + r) / 2, (t + b) / 2 };
    }

    /**
     * 在当前页面查找含关键词的按钮并点击。返回是否点击成功。
     */
    public boolean tapByText(int index, String... keywords) {
        String xml = dumpUi(index);
        if (xml == null) return false;
        int[] c = findNodeCenter(xml, keywords);
        if (c == null) {
            log.warn("模拟器{} 未找到匹配按钮: {}", index + 1, java.util.Arrays.toString(keywords));
            return false;
        }
        log.info("模拟器{} 点击按钮 {} @ ({},{})", index + 1, java.util.Arrays.toString(keywords), c[0], c[1]);
        this.tap(index, c[0], c[1]);
        return true;
    }

    /**
     * 轮询等待并在页面出现目标按钮后点击（解决冷启动后页面未加载/初始页不确定问题）。
     * 每隔 1s 尝试一次，直到成功或超时。
     */
    public boolean tapByTextWait(int index, long timeoutMs, String... keywords) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (tapByText(index, keywords)) return true;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    /**
     * 自动登录 Discord（全动态 UI 定位，无硬编码坐标）：
     * 1) 确保 Discord 在前台（已在前台则复用当前页面，避免重启打断）；
     * 2) 若页面无输入框（欢迎/选择页），先点击「登录」进入登录表单；
     * 3) 定位账号/密码两个 EditText 输入框并输入；
     * 4) 点击「登录」提交；
     * 5) 轮询等待主界面出现「添加好友」按钮确认登录成功。
     * 返回 "SUCCESS" 或 "ERROR: 原因"。
     */
    @Async
    public CompletableFuture<String> autoLogin(int index, String email, String password) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("模拟器 {} 自动登录 Discord", index + 1);
                // 1) 确保 Discord 前台；已在前台就不重新启动，避免重置页面
                if (!isDiscordForeground(index)) {
                    String launch = launchDiscord(index);
                    if (!"SUCCESS".equals(launch)) return "ERROR: 打开 Discord 失败 -> " + launch;
                }
                randDelay(); randDelay(); // 打开后稍等页面稳定

                // 2) 找输入框；没有说明停在欢迎/选择页，先点「登录」进入表单
                String xml = dumpUi(index);
                int[][] edits = findEditTexts(xml);
                if (edits.length == 0) {
                    log.info("模拟器{} 无输入框，先点击「登录」进入登录页", index + 1);
                    if (!tapByTextWait(index, 10000, "登录", "log in")) {
                        return "ERROR: 未找到「登录」入口，无法进入登录页";
                    }
                    randDelay(); randDelay();
                    xml = dumpUi(index);
                    edits = findEditTexts(xml);
                }
                if (edits.length < 2) {
                    return "ERROR: 未找到账号/密码输入框 (EditText 数量=" + edits.length + ")";
                }

                // 3) 输入账号（点第一个输入框聚焦后逐字符人工输入）
                tap(index, edits[0][0], edits[0][1]);
                humanType(index, email);

                // 4) 输入密码（点第二个输入框）
                tap(index, edits[1][0], edits[1][1]);
                humanType(index, password);

                // 5) 点击「登录」按钮提交
                if (!tapByTextWait(index, 8000, "登录", "log in")) {
                    return "ERROR: 未找到「登录」提交按钮";
                }

                // 6) 等待登录成功（主界面出现「添加好友」即成功），最多约 60s
                for (int i = 0; i < 20; i++) {
                    randDelay(); randDelay(); // 每轮等待 2~6s
                    if (isDiscordLoggedIn(index)) {
                        log.info("模拟器{} Discord 自动登录成功", index + 1);
                        return "SUCCESS";
                    }
                }
                return "ERROR: 提交登录后未检测到已登录(可能账号密码错误或需要验证)";
            } catch (Exception e) {
                log.error("自动登录失败: 模拟器 {}", index + 1, e);
                return "ERROR: " + e.getMessage();
            }
        });
    }

    /**
     * 解析 uiautomator XML 中所有 EditText 输入框的中心坐标，按 top(y) 升序返回
     * （Discord 登录页账号框在上、密码框在下）。无输入框返回空数组。
     */
    private int[][] findEditTexts(String xml) {
        if (xml == null || xml.isBlank()) return new int[0][];
        try {
            javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
                    .newInstance().newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName("node");
            java.util.List<int[]> list = new java.util.ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) nodes.item(i);
                if (!"android.widget.EditText".equals(e.getAttribute("class"))) continue;
                int[] c = parseCenter(e.getAttribute("bounds"));
                if (c != null) list.add(c);
            }
            list.sort(java.util.Comparator.comparingInt(a -> a[1]));
            return list.toArray(new int[0][]);
        } catch (Exception ex) {
            log.warn("模拟器 UI XML 解析 EditText 失败: {}", ex.getMessage());
            return new int[0][];
        }
    }

    public boolean checkDiscordInstalled(int index) {
        String result = emulatorService.execAdb(index, "shell", "pm", "list", "packages", "com.discord");
        return result != null && result.contains("com.discord");
    }

    /**
     * 实时抓取模拟器中 Discord 当前登录的用户名（方案A：零打断）。
     * 直接 dump 当前页面解析，不打断正在进行的加好友流程：
     *   - 若前台已是 Discord，则解析当前页底部个人资料栏的用户名；
     *   - 若前台不是 Discord（如在桌面/其他 App/加好友流程中），返回 null，绝不 force-stop 或冷启动。
     * 返回用户名；未登录/前台非 Discord/抓取失败返回 null。
     */
    public String getLoggedInUser(int index) {
        try {
            if (!checkDiscordInstalled(index)) return null;
            // 方案A 核心：只有前台当前就是 Discord，才 dump 解析，避免打断任何流程
            String fg = getForegroundPackage(index);
            if (!DISCORD_PKG.equals(fg)) {
                log.debug("模拟器{} 当前前台不是 Discord（{}），跳过抓取", index + 1, fg);
                return null;
            }
            // dump 当前页
            emulatorService.execAdb(index, "shell", "uiautomator", "dump", "/sdcard/ui_user.xml");
            Thread.sleep(800);
            String xml = emulatorService.execAdb(index, "shell", "cat", "/sdcard/ui_user.xml");
            if (xml == null || xml.isBlank()) return null;
            return parseUsername(xml);
        } catch (Exception e) {
            log.error("模拟器{} 抓取 Discord 用户名失败", index + 1, e);
            return null;
        }
    }

    /** 从 uiautomator dump XML 中解析 Discord 当前登录用户名 */
    private String parseUsername(String xml) {
        // 当前登录用户名显示在底部个人资料栏按钮的 content-desc 上（实测 content-desc="len_zone" 即用户名）
        // 排除已知系统词，避免误抓频道/服务器名
        java.util.Set<String> sysWords = java.util.Set.of(
                "私信", "添加服务器", "搜索", "综合", "提及", "未读消息", "Student Hub",
                "游戏中心", "设置", "图库", "文件", "浏览器", "直播间", "添加好友");
        // 抓取所有 package=com.discord 且带 content-desc 的节点
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "package=\"com\\.discord\"[^>]*content-desc=\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(xml);
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String desc = m.group(1).trim();
            // 过滤：不含中文字符、不在系统词里、长度合理（用户名通常不含空白描述长串）
            if (desc.isEmpty()) continue;
            if (sysWords.contains(desc)) continue;
            if (desc.length() > 40) continue;
            if (desc.contains("，") || desc.contains(",") || desc.contains(" ")) continue;
            if (desc.matches(".*[\\u4e00-\\u9fa5].*")) continue; // 跳过含中文的（系统/频道描述）
            candidates.add(desc);
        }
        // 第一个候选即底部个人资料栏的用户名（content-desc 直接是用户名）
        for (String c : candidates) {
            return c;
        }
        return null;
    }

    public String uninstallDiscord(int index) {
        return emulatorService.execAdb(index, "uninstall", "com.discord");
    }

    public void installDiscordOnAll() {
        // 串行逐台安装，避免 15 台并发 adb install 大文件失败
        for (var emu : emulatorService.getAllEmulators()) {
            if ("RUNNING".equals(emu.getStatus())) {
                try {
                    String res = installDiscord(emu.getIndex()).get(180, TimeUnit.SECONDS);
                    log.info("批量安装 模拟器{} 结果: {}", emu.getIndex(), res);
                } catch (Exception e) {
                    log.error("批量安装 模拟器{} 失败", emu.getIndex(), e);
                }
                // 每台间隔，给 ADB 与磁盘喘息
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ================= Discord 自动加好友（基于 ADB 坐标点击） =================
    // 坐标基于 2560x1440 (360 DPI)，uiautomator dump 实测：
    //   添加好友   bounds=[288,162][693,234]   中心 (490, 198)
    //   通过用户名  bounds=[36,459][2524,594]   中心 (1280, 526)
    //   输入框     bounds=[36,514][2524,622]   中心 (1280, 568)
    //   发送按钮   bounds=[36,1296][2524,1404] 中心 (1280, 1350)
    //
    // 关键教训：am start --activity-clear-task --activity-clear-top 在 Discord 已启动时
    // 无法将它带到前台（系统直接回到 launcher），因此永远走 force-stop + 冷启动路径。

    public String addFriendByUsername(int index, String username) {
        try {
            if (!checkDiscordInstalled(index)) {
                return "ERROR: 未安装 Discord，无法添加好友";
            }

            // 1) 统一使用 force-stop + 冷启动，确保 Discord 从干净状态进入前台
            //    不区分已打开/未打开，因为 warm start 无法可靠地将 Discord 带到前台
            log.info("模拟器{} force-stop Discord 并冷启动", index + 1);
            emulatorService.execAdb(index, "shell", "am", "force-stop", DISCORD_PKG);
            randDelay(); randDelay();
            String launch = launchDiscord(index);
            if (!"SUCCESS".equals(launch)) {
                return "ERROR: Discord 未启动成功 -> " + launch;
            }
            randDelay(); randDelay(); // 启动后等待页面稳定（2~6s）

            // 启动后必须校验前台确实是 Discord（预防 ADB 端口或活动解析错误）
            String fg = getForegroundPackage(index);
            if (!DISCORD_PKG.equals(fg)) {
                return "ERROR: 启动后前台不是 Discord (当前: " + fg + ")";
            }

            // 2) 先确保在「好友」页（底部导航），再点「添加好友」按钮进入添加页
            //    冷启动后 Discord 可能停在其它页，先尝试切到好友页（失败不致命，可能已在）
            tapByTextWait(index, 4000, "好友");
            randDelay();
            if (!tapByTextWait(index, 10000, "添加好友")) {
                return "ERROR: 未找到「添加好友」按钮 (可能 Discord 版本/语言不同)";
            }
            randDelay(); randDelay();

            // 3) 等待并点击「输入用户名」输入框 -> 聚焦输入区（动态定位，等待页面加载）
            if (!tapByTextWait(index, 10000, "输入用户名", "通过用户名添加")) {
                return "ERROR: 未找到「输入用户名」输入框";
            }
            randDelay(); randDelay();
            humanType(index, username);
            randDelay(); randDelay(); // 等待 Discord 校验用户名

            // 4) 点击「发送好友请求」按钮（动态定位，等待出现）
            if (!tapByTextWait(index, 10000, "发送好友请求")) {
                return "ERROR: 未找到「发送好友请求」按钮";
            }
            randDelay(); randDelay();

            // 6-7) BACK x 2
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_BACK");
            randDelay();
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_BACK");
            randDelay();

            // 8) 收尾校验：前台必须仍是 Discord
            fg = getForegroundPackage(index);
            if (!DISCORD_PKG.equals(fg)) {
                return "ERROR: 流程结束时前台不是 Discord (当前: " + fg + ")";
            }
            return "SUCCESS";
        } catch (Exception e) {
            log.error("模拟器 {} 添加好友 {} 失败", index, username, e);
            return "ERROR: " + e.getMessage();
        }
    }

    private void tap(int index, int x, int y) {
        emulatorService.execAdb(index, "shell", "input", "tap", String.valueOf(x), String.valueOf(y));
        randDelay();
    }

    /** 每个模拟操作完成后的随机延迟：1~3 秒，模拟人类节奏 */
    private void randDelay() {
        try { Thread.sleep(1000 + new java.util.Random().nextInt(2000)); } catch (InterruptedException ignored) {}
    }

    /** 输入单个可见字符（含特殊字符转义） */
    private void typeChar(int index, char c) {
        String s = String.valueOf(c);
        String safe = s.replace(" ", "%s")
                .replace("&", "\\&").replace("<", "\\<").replace(">", "\\>")
                .replace("|", "\\|").replace(";", "\\;").replace("\"", "\\\"")
                .replace("'", "\\'").replace("`", "\\`").replace("$", "\\$")
                .replace("(", "\\(").replace(")", "\\)").replace("{", "\\{")
                .replace("}", "\\}").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("?", "\\?").replace("#", "\\#")
                .replace("~", "\\~").replace("=", "\\=").replace("+", "\\+");
        emulatorService.execAdb(index, "shell", "input", "text", safe);
    }

    /** 退格删除一个字符 */
    private void backspace(int index) {
        emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_DEL");
        randDelay();
    }

    /**
     * 模拟人工逐字符输入：每个字符之间 1~3s 随机延迟，并有一定概率（~15%）故意输错一个字符再退格重输，
     * 让输入节奏更像真人。输入前会先全选清空输入框（Ctrl+A + Del）。
     */
    private void humanType(int index, String text) {
        if (text == null || text.isEmpty()) return;
        // 先清空（避免聚焦到已有内容上 append）
        emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_CTRL_A");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_DEL");
        randDelay();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 15% 概率模拟「输错一个字符再退格重输」
            if (r.nextInt(100) < 15) {
                // 故意输一个错误字符（用 '.' 占位，绝不会出现在正常账号/密码/用户名里）
                typeChar(index, '.');
                randDelay();
                backspace(index); // 退格删掉错误字符
            }
            typeChar(index, c);
            randDelay(); // 每个字符之后 1~3s 随机延迟
        }
    }
}
