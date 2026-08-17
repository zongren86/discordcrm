package com.mumu.agent.service;

import com.mumu.agent.config.ApkConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class ApkCacheService {
    
    private final ApkConfig apkConfig;
    private static final String DISCORD_PKG = "com.discord";
    
    public ApkCacheService(ApkConfig apkConfig) {
        this.apkConfig = apkConfig;
    }
    
    public boolean isApkCached() {
        File cacheDir = new File(apkConfig.getCacheDir());
        File[] apkFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".apk"));
        return apkFiles != null && apkFiles.length > 0;
    }
    
    public String getLatestApkPath() {
        File cacheDir = new File(apkConfig.getCacheDir());
        File[] apkFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".apk"));
        if (apkFiles != null && apkFiles.length > 0) {
            // 返回最新修改的文件
            long maxTime = 0;
            File latestFile = null;
            for (File f : apkFiles) {
                if (f.lastModified() > maxTime) {
                    maxTime = f.lastModified();
                    latestFile = f;
                }
            }
            return latestFile != null ? latestFile.getAbsolutePath() : null;
        }
        return null;
    }
    
    public String getApkVersion() {
        String path = getLatestApkPath();
        if (path != null) {
            File file = new File(path);
            // 从文件名解析版本号，如 discord_145.8.apk
            String fileName = file.getName();
            if (fileName.matches("discord_\\d+\\.\\d+\\.apk")) {
                return fileName.replace("discord_", "").replace(".apk", "");
            }
        }
        return null;
    }
    
    public String downloadAndCache(String downloadUrl) {
        try {
            File cacheDir = new File(apkConfig.getCacheDir());
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            
            // 清理旧的 APK 文件
            File[] oldFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".apk"));
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    f.delete();
                }
            }
            
            // 生成新文件名
            String fileName = "discord_" + System.currentTimeMillis() + ".apk";
            File outputFile = new File(cacheDir, fileName);
            
            log.info("开始下载 APK: {} -> {}", downloadUrl, outputFile);
            
            URI uri = URI.create(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(apkConfig.getDownloadTimeout() * 1000);
            conn.setReadTimeout(apkConfig.getDownloadTimeout() * 1000);
            conn.setRequestProperty("User-Agent", "MuMu-Agent/1.0");
            
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(outputFile.toPath(), 
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long total = 0;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        total += bytesRead;
                    }
                    
                    log.info("APK 下载完成: {} bytes", total);
                    
                    // 验证文件大小
                    if (total > 1024 * 1024) { // > 1MB
                        return outputFile.getAbsolutePath();
                    } else {
                        log.warn("下载的 APK 文件太小，可能不是有效文件");
                        outputFile.delete();
                        return null;
                    }
                }
            } else {
                log.error("下载 APK 失败, HTTP {}", status);
                return null;
            }
        } catch (Exception e) {
            log.error("下载 APK 异常", e);
            return null;
        }
    }
    
    public boolean installApk(int adbPort, String apkPath) {
        try {
            String device = "127.0.0.1:" + adbPort;
            
            // 连接设备
            execAdb(adbPort, "connect", device);
            
            // 等待设备就绪
            boolean ready = false;
            for (int i = 0; i < 30; i++) {
                String state = execAdb(adbPort, "-s", device, "get-state").trim();
                if ("device".equals(state)) {
                    ready = true;
                    break;
                }
                Thread.sleep(1000);
            }
            
            if (!ready) {
                log.error("ADB 设备未就绪");
                return false;
            }
            
            // 安装 APK
            String result = execAdb(adbPort, "-s", device, "install", "-r", "-d", apkPath);
            boolean success = result.contains("Success");
            log.info("APK 安装结果: {}", result);
            return success;
        } catch (Exception e) {
            log.error("安装 APK 失败", e);
            return false;
        }
    }
    
    private String execAdb(int adbPort, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> command = new java.util.ArrayList<>();
        command.add("adb");
        command.addAll(java.util.Arrays.asList(args));
        pb.command(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        process.waitFor();
        return output.toString().trim();
    }
    
    public boolean checkDiscordInstalled(int adbPort) {
        try {
            String result = execAdb(adbPort, "shell", "pm", "list", "packages", DISCORD_PKG);
            return result.contains(DISCORD_PKG);
        } catch (Exception e) {
            return false;
        }
    }
}
