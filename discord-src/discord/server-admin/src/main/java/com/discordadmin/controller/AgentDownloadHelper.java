package com.discordadmin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class AgentDownloadHelper {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.mumu-agent-path:}")
    private String configuredAgentPath;

    private volatile Path cachedClasspathDir;

    public ResponseEntity<Resource> downloadAgentPackage(Long userId, Long merchantId, String serverUrl) {
        try {
            File agentFolder = resolveAgentDirectory();
            if (agentFolder == null) {
                log.error("mumu-agent directory not found (tried: configured path, user.dir siblings, classpath)");
                return ResponseEntity.notFound().build();
            }

            Path zipPath = Files.createTempFile("mumu-agent-", ".zip");

            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("version", "v2.13.0");
            configMap.put("userId", userId);
            configMap.put("merchantId", merchantId != null ? merchantId : 0);
            configMap.put("serverUrl", "ws://" + serverUrl + "/ws/agent");
            configMap.put("heartbeatInterval", 30000);
            configMap.put("autoStart", true);

            Map<String, Object> darwinConfig = new LinkedHashMap<>();
            darwinConfig.put("mumuPath", "/Applications/MuMuPlayer.app");
            darwinConfig.put("adbPath", "/Users/worktools/platform-tools/adb");

            Map<String, Object> win32Config = new LinkedHashMap<>();
            win32Config.put("mumuPath", "C:\\Program Files\\Netease\\MuMuPlayer-12.0");
            win32Config.put("adbPath", "C:\\Program Files\\Netease\\MuMuPlayer-12.0\\shell\\adb.exe");

            Map<String, Object> linuxConfig = new LinkedHashMap<>();
            linuxConfig.put("mumuPath", "/opt/MuMuPlayer");
            linuxConfig.put("adbPath", "");

            Map<String, Object> platformsMap = new LinkedHashMap<>();
            platformsMap.put("darwin", darwinConfig);
            platformsMap.put("win32", win32Config);
            platformsMap.put("linux", linuxConfig);
            configMap.put("platforms", platformsMap);

            String configContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(configMap);

            String startMacContent = readFileContent(agentFolder, "start_mac.command");
            String startWinContent = readFileContent(agentFolder, "start_win.bat");

            String readmeContent =
                "MuMu Agent v2.13.0 使用说明\n" +
                "========================================\n\n" +
                "## 快速开始\n\n" +
                "### macOS 用户:\n" +
                "    双击 start_mac.command\n\n" +
                "### Windows 用户:\n" +
                "    双击 start_win.bat\n\n" +
                "## 前置条件\n" +
                "- Node.js 18+ (https://nodejs.org/)\n" +
                "- MuMu 模拟器已安装\n\n" +
                "## 配置 config.json (重要!)\n\n" +
                "config.json 是唯一配置文件，必须正确配置才能使用。\n\n" +
                "### mumuPath 配置\n" +
                "必须设置 MuMu 可执行文件的完整路径:\n\n" +
                "- Windows: C:\\Program Files\\Netease\\MuMuPlayer-12.0\n" +
                "- macOS: /Applications/MuMuPlayer.app\n\n" +
                "### adbPath 配置\n" +
                "必须设置 ADB 可执行文件的完整路径:\n" +
                "- Windows: C:\\Program Files\\Netease\\MuMuPlayer-12.0\\shell\\adb.exe\n" +
                "- macOS: /Users/xxx/Library/Android/sdk/platform-tools/adb\n\n" +
                "### 配置示例 (Windows):\n" +
                "{\n" +
                "  \"version\": \"v2.13.0\",\n" +
                "  \"userId\": \"merchantadmin2\",\n" +
                "  \"merchantId\": 5,\n" +
                "  \"serverUrl\": \"ws://服务器IP:8090/ws/agent\",\n" +
                "  \"platforms\": {\n" +
                "    \"win32\": {\n" +
                "      \"mumuPath\": \"C:\\\\Program Files\\\\Netease\\\\MuMu\\\\nx_main\\\\MuMuNxMain.exe\",\n" +
                "      \"adbPath\": \"C:\\\\Program Files\\\\Netease\\\\MuMu\\\\nx_main\\\\adb.exe\"\n" +
                "    }\n" +
                "  }\n" +
                "}\n\n" +
                "## 首次使用\n" +
                "1. 解压下载的 zip 包\n" +
                "2. 修改 config.json 配置\n" +
                "3. 双击启动脚本:\n" +
                "   - macOS: start_mac.command\n" +
                "   - Windows: start_win.bat\n\n" +
                "## 注意事项\n" +
                "- mumuPath 必须是可执行文件的完整路径，不是目录\n" +
                "- 一个账号只能在一台服务器上运行\n" +
                "- 如遇问题请查看命令行日志\n";

            Set<String> addedEntries = new HashSet<>();

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                addFolderToZip(agentFolder, "mumu-agent", zos, addedEntries);

                writeZipEntry(zos, addedEntries, "mumu-agent/config.json", configContent);
                writeZipEntry(zos, addedEntries, "mumu-agent/start_mac.command", startMacContent);
                writeZipEntry(zos, addedEntries, "mumu-agent/start_win.bat", startWinContent);
                writeZipEntry(zos, addedEntries, "mumu-agent/README.txt", readmeContent);
            }

            Resource resource = new FileSystemResource(zipPath.toFile());

            log.info("Created zip file: {}, size: {}", zipPath, Files.size(zipPath));

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mumu-agent.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(resource);

        } catch (Exception e) {
            log.error("downloadAgentPackage error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private File resolveAgentDirectory() {
        if (configuredAgentPath != null && !configuredAgentPath.isBlank()) {
            File configured = new File(configuredAgentPath);
            log.info("Checking configured app.mumu-agent-path: {}, exists: {}", configuredAgentPath, configured.isDirectory());
            if (configured.isDirectory()) {
                return configured;
            }
        }

        String userDir = System.getProperty("user.dir");
        log.info("downloadAgentPackage user.dir: {}", userDir);

        // 优先找 ./mumu-agent（server-admin 子项目自带）
        File localDir = new File(userDir, "mumu-agent");
        log.info("Trying ./mumu-agent (优先): {}, exists: {}, isDir: {}", localDir, localDir.exists(), localDir.isDirectory());
        if (localDir.isDirectory()) {
            return localDir;
        }

        // 再找 ../mumu-agent（根目录兄弟）
        File siblingDir = new File(userDir, "../mumu-agent");
        log.info("Trying ../mumu-agent (fallback): {}, exists: {}, isDir: {}", siblingDir, siblingDir.exists(), siblingDir.isDirectory());
        if (siblingDir.isDirectory()) {
            return siblingDir;
        }

        Path classpathDir = copyClasspathResourceToTempDir("classpath:/mumu-agent/");
        if (classpathDir != null) {
            return classpathDir.toFile();
        }

        return null;
    }

    private Path copyClasspathResourceToTempDir(String resourcePattern) {
        if (cachedClasspathDir != null && Files.isDirectory(cachedClasspathDir)) {
            return cachedClasspathDir;
        }
        try {
            Path tempDir = Files.createTempDirectory("mumu-agent-cp-");
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(resourcePattern + "**");

            int copied = 0;
            for (Resource res : resources) {
                if (!res.exists() || !res.isReadable()) {
                    continue;
                }
                String uriPath = res.getURI().toString();
                int idx = uriPath.indexOf("/mumu-agent/");
                if (idx < 0) {
                    continue;
                }
                String relativePath = uriPath.substring(idx + "/mumu-agent/".length());
                if (relativePath.isEmpty() || relativePath.endsWith("/")) {
                    continue;
                }
                Path target = tempDir.resolve(relativePath);
                Files.createDirectories(target.getParent());
                try (InputStream is = res.getInputStream()) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            }

            if (copied == 0) {
                log.warn("classpath:/mumu-agent/ not found or empty");
                Files.deleteIfExists(tempDir);
                return null;
            }

            log.info("Extracted {} classpath resources to temp dir: {}", copied, tempDir);
            cachedClasspathDir = tempDir;
            return tempDir;
        } catch (Exception e) {
            log.warn("Failed to copy classpath mumu-agent resources: {}", e.getMessage());
            return null;
        }
    }

    private static String readFileContent(File folder, String fileName) {
        File file = new File(folder, fileName);
        if (file.exists() && file.isFile()) {
            try {
                return new String(Files.readAllBytes(file.toPath()));
            } catch (IOException e) {
                log.warn("读取文件 {} 失败: {}", fileName, e.getMessage());
            }
        }
        if (fileName.equals("start_mac.command")) {
            return "#!/bin/bash\necho '请将 start_mac.command 放到 mumu-agent 目录下'\n";
        } else if (fileName.equals("start_win.bat")) {
            return "@echo off\necho 请将 start_win.bat 放到 mumu-agent 目录下\npause\n";
        }
        return "";
    }

    private static void writeZipEntry(ZipOutputStream zos, Set<String> addedEntries, String entryName, String content) throws IOException {
        addedEntries.remove(entryName);
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(content.getBytes("UTF-8"));
        zos.closeEntry();
        addedEntries.add(entryName);
    }

    private static void addFolderToZip(File folder, String parentFolder, ZipOutputStream zos, Set<String> addedEntries) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            String fileName = file.getName();
            if (fileName.equals("node_modules") || fileName.startsWith(".") ||
                fileName.equals("config.json") ||
                fileName.startsWith("config_") || fileName.startsWith("config.") ||
                fileName.equals("README.txt") ||
                fileName.equals("start_mac.command") ||
                fileName.equals("start.sh") || fileName.equals("start.bat") ||
                fileName.equals("start_win.bat")) {
                continue;
            }

            String entryName = parentFolder + "/" + file.getName();

            if (file.isDirectory()) {
                addFolderToZip(file, entryName, zos, addedEntries);
            } else {
                if (addedEntries.contains(entryName)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(entryName));
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                }
                zos.closeEntry();
                addedEntries.add(entryName);
            }
        }
    }
}
