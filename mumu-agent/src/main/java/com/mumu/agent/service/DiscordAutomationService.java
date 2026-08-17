package com.mumu.agent.service;

import com.mumu.agent.model.EmulatorInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DiscordAutomationService {
    
    private final EmulatorService emulatorService;
    private final ApkCacheService apkCacheService;
    
    private static final String DISCORD_PKG = "com.discord";
    private static final long RANDOM_DELAY_MIN = 1000;
    private static final long RANDOM_DELAY_MAX = 3000;
    
    public DiscordAutomationService(EmulatorService emulatorService, ApkCacheService apkCacheService) {
        this.emulatorService = emulatorService;
        this.apkCacheService = apkCacheService;
    }
    
    public String installDiscord(int index, String apkUrl) {
        try {
            EmulatorInfo info = emulatorService.getEmulator(index);
            if (info == null) {
                return "ERROR: 模拟器不存在";
            }
            
            // 检查 APK 缓存
            String apkPath = apkCacheService.getLatestApkPath();
            if (apkPath == null) {
                // 下载 APK
                apkPath = apkCacheService.downloadAndCache(apkUrl);
                if (apkPath == null) {
                    return "ERROR: APK 下载失败";
                }
            }
            
            // 安装 APK
            boolean success = apkCacheService.installApk(info.getAdbPort(), apkPath);
            if (success) {
                info.setDiscordInstalled("true");
                return "SUCCESS: Discord 安装成功";
            } else {
                return "ERROR: Discord 安装失败";
            }
        } catch (Exception e) {
            log.error("安装 Discord 失败", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String launchDiscord(int index) {
        try {
            EmulatorInfo info = emulatorService.getEmulator(index);
            if (info == null) {
                return "ERROR: 模拟器不存在";
            }
            
            if (!apkCacheService.checkDiscordInstalled(info.getAdbPort())) {
                return "ERROR: Discord 未安装";
            }
            
            // 强制停止 Discord
            emulatorService.execAdb(index, "shell", "am", "force-stop", DISCORD_PKG);
            randDelay(); randDelay();
            
            // 启动 Discord
            String result = emulatorService.execAdb(index, "shell", "am", "start", 
                "-W", "-n", DISCORD_PKG + "/.main.MainActivity",
                "--activity-clear-task", "--activity-clear-top");
            
            randDelay(); randDelay(); // 等待页面加载
            
            // 验证是否在前台
            String fg = getForegroundPackage(index);
            if (!DISCORD_PKG.equals(fg)) {
                return "ERROR: Discord 未能进入前台 (当前: " + fg + ")";
            }
            
            log.info("模拟器{} Discord 启动成功", index + 1);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("启动 Discord 失败", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String getForegroundPackage(int index) {
        try {
            String out = emulatorService.execAdb(index, "shell", "dumpsys", "window");
            if (out != null) {
                Pattern p = Pattern.compile("(?:mCurrentFocus|mFocusedApp)[^\\n]*?([A-Za-z0-9_.]+)/");
                Matcher m = p.matcher(out);
                String last = null;
                while (m.find()) last = m.group(1);
                if (last != null) return last;
            }
            
            // 回退方案
            String act = emulatorService.execAdb(index, "shell", "dumpsys", "activity", "activities");
            if (act != null) {
                Pattern p2 = Pattern.compile("mResumedActivity[^\\n]*?([A-Za-z0-9_.]+)/");
                Matcher m2 = p2.matcher(act);
                if (m2.find()) return m2.group(1);
            }
        } catch (Exception e) {
            log.debug("读取前台包名失败", e);
        }
        return "";
    }
    
    public boolean isDiscordForeground(int index) {
        return DISCORD_PKG.equals(getForegroundPackage(index));
    }
    
    public boolean isOnLoginPage(int index) {
        if (!isDiscordForeground(index)) return false;
        String xml = dumpUi(index);
        if (xml == null) return false;
        int[][] edits = findEditTexts(xml);
        return edits.length >= 2;
    }
    
    public boolean isDiscordLoggedIn(int index) {
        if (!isDiscordForeground(index)) return false;
        if (isOnLoginPage(index)) return false;
        return getLoggedInUser(index) != null;
    }
    
    public String getLoggedInUser(int index) {
        try {
            if (!isDiscordForeground(index)) return null;
            
            String xml = dumpUi(index);
            if (xml == null || xml.isBlank()) return null;
            return parseUsername(xml);
        } catch (Exception e) {
            log.error("获取登录用户失败", e);
            return null;
        }
    }
    
    public String dumpUi(int index) {
        try {
            emulatorService.execAdb(index, "shell", "uiautomator", "dump", "/sdcard/ui_dump.xml");
            Thread.sleep(800);
            return emulatorService.execAdb(index, "shell", "cat", "/sdcard/ui_dump.xml");
        } catch (Exception e) {
            log.error("dump UI 失败", e);
            return null;
        }
    }
    
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
                if ("true".equals(e.getAttribute("clickable"))) return c;
                if (fallback == null) fallback = c;
            }
            return fallback;
        } catch (Exception ex) {
            log.warn("UI XML 解析失败: {}", ex.getMessage());
            return null;
        }
    }
    
    private static int[] parseCenter(String bounds) {
        Pattern p = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
        Matcher m = p.matcher(bounds);
        if (!m.find()) return null;
        int l = Integer.parseInt(m.group(1)), t = Integer.parseInt(m.group(2));
        int r = Integer.parseInt(m.group(3)), b = Integer.parseInt(m.group(4));
        return new int[]{ (l + r) / 2, (t + b) / 2 };
    }
    
    public boolean tapByText(int index, String... keywords) {
        String xml = dumpUi(index);
        if (xml == null) return false;
        int[] c = findNodeCenter(xml, keywords);
        if (c == null) {
            log.warn("未找到匹配按钮: {}", Arrays.toString(keywords));
            return false;
        }
        log.info("点击按钮 {} @ ({},{})", Arrays.toString(keywords), c[0], c[1]);
        tap(index, c[0], c[1]);
        return true;
    }
    
    public boolean tapByTextWait(int index, long timeoutMs, String... keywords) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (tapByText(index, keywords)) return true;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        return false;
    }
    
    public String addFriendByUsername(int index, String username) {
        try {
            EmulatorInfo info = emulatorService.getEmulator(index);
            if (info == null) {
                return "ERROR: 模拟器不存在";
            }
            
            // 检查 Discord 是否已安装
            if (!apkCacheService.checkDiscordInstalled(info.getAdbPort())) {
                return "ERROR: Discord 未安装";
            }
            
            // 检查是否已登录
            if (!isDiscordLoggedIn(index)) {
                return "ERROR: Discord 未登录，请先在模拟器上手动登录";
            }
            
            // 检查是否在首页
            if (!isOnHomePage(index)) {
                // 跳转到首页
                goToHomePage(index);
            }
            
            // 开始加好友流程
            log.info("模拟器{} 开始添加好友: {}", index + 1, username);
            
            // 1. 点击「添加好友」按钮
            if (!tapByTextWait(index, 10000, "添加好友", "Add Friend")) {
                return "ERROR: 未找到「添加好友」按钮";
            }
            randDelay(); randDelay();
            
            // 2. 点击「输入用户名」输入框
            if (!tapByTextWait(index, 10000, "输入用户名", "通过用户名添加", "Search")) {
                return "ERROR: 未找到「输入用户名」输入框";
            }
            randDelay(); randDelay();
            
            // 3. 输入用户名
            humanType(index, username);
            randDelay(); randDelay();
            
            // 4. 点击「发送好友请求」按钮
            if (!tapByTextWait(index, 10000, "发送好友请求", "Send Friend Request")) {
                return "ERROR: 未找到「发送好友请求」按钮";
            }
            randDelay(); randDelay();
            
            // 5. 返回两次
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_BACK");
            randDelay();
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_BACK");
            randDelay();
            
            // 6. 验证仍在 Discord
            String fg = getForegroundPackage(index);
            if (!DISCORD_PKG.equals(fg)) {
                return "ERROR: 流程结束时前台不是 Discord (当前: " + fg + ")";
            }
            
            log.info("模拟器{} 好友添加成功: {}", index + 1, username);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("添加好友失败", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    private boolean isOnHomePage(int index) {
        if (!isDiscordForeground(index)) return false;
        String xml = dumpUi(index);
        if (xml == null) return false;
        return findNodeCenter(xml, "添加好友", "Add Friend") != null;
    }
    
    private void goToHomePage(int index) {
        // 点击底部导航的"好友"或"Home"按钮
        if (!tapByTextWait(index, 5000, "好友", "Friends", "Home")) {
            // 如果找不到，尝试按 HOME 键
            try {
                emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_HOME");
                randDelay();
                // 重新启动 Discord
                launchDiscord(index);
            } catch (Exception ignored) {}
        }
        randDelay();
    }
    
    private void tap(int index, int x, int y) {
        try {
            emulatorService.execAdb(index, "shell", "input", "tap", 
                String.valueOf(x), String.valueOf(y));
            randDelay();
        } catch (Exception e) {
            log.error("点击失败", e);
        }
    }
    
    private int[][] findEditTexts(String xml) {
        if (xml == null || xml.isBlank()) return new int[0][];
        try {
            javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
                .newInstance().newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(
                new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName("node");
            List<int[]> list = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) nodes.item(i);
                if (!"android.widget.EditText".equals(e.getAttribute("class"))) continue;
                int[] c = parseCenter(e.getAttribute("bounds"));
                if (c != null) list.add(c);
            }
            list.sort(java.util.Comparator.comparingInt(a -> a[1]));
            return list.toArray(new int[0][]);
        } catch (Exception ex) {
            log.warn("解析 EditText 失败: {}", ex.getMessage());
            return new int[0][];
        }
    }
    
    private String parseUsername(String xml) {
        java.util.Set<String> sysWords = java.util.Set.of(
            "私信", "添加服务器", "搜索", "综合", "提及", "未读消息",
            "游戏中心", "设置", "图库", "文件", "浏览器", "直播间", "添加好友",
            "Home", "Friends", "Search", "Settings");
        
        Pattern p = Pattern.compile(
            "package=\"com\\.discord\"[^>]*content-desc=\"([^\"]+)\"");
        Matcher m = p.matcher(xml);
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String desc = m.group(1).trim();
            if (desc.isEmpty()) continue;
            if (sysWords.contains(desc)) continue;
            if (desc.length() > 40) continue;
            if (desc.contains("，") || desc.contains(",") || desc.contains(" ")) continue;
            if (desc.matches(".*[\\u4e00-\\u9fa5].*")) continue;
            candidates.add(desc);
        }
        
        for (String c : candidates) {
            return c;
        }
        return null;
    }
    
    private void humanType(int index, String text) {
        if (text == null || text.isEmpty()) return;
        
        // 先清空输入框
        try {
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_CTRL_A");
            Thread.sleep(300);
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_DEL");
            randDelay();
        } catch (Exception ignored) {}
        
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 15% 概率模拟输错一个字符再退格重输
            if (r.nextInt(100) < 15) {
                typeChar(index, '.');
                randDelay();
                backspace(index);
            }
            typeChar(index, c);
            randDelay();
        }
    }
    
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
        
        try {
            emulatorService.execAdb(index, "shell", "input", "text", safe);
        } catch (Exception e) {
            log.error("输入字符失败: {}", e.getMessage());
        }
    }
    
    private void backspace(int index) {
        try {
            emulatorService.execAdb(index, "shell", "input", "keyevent", "KEYCODE_DEL");
            randDelay();
        } catch (Exception e) {
            log.error("退格失败: {}", e.getMessage());
        }
    }
    
    private void randDelay() {
        try {
            long delay = RANDOM_DELAY_MIN + new java.util.Random().nextInt((int)(RANDOM_DELAY_MAX - RANDOM_DELAY_MIN));
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {}
    }
}
