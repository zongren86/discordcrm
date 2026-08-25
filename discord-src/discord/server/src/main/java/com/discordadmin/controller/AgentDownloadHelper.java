package com.discordadmin.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.zip.*;

@Slf4j
@Component
public class AgentDownloadHelper {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public static ResponseEntity<Resource> downloadAgentPackage(String userId, Long merchantId, String serverUrl) {
        try {
            String userDir = System.getProperty("user.dir");
            log.info("downloadAgentPackage user.dir: {}", userDir);
            
            String agentDir = userDir + "/../mumu-agent";
            File agentFolder = new File(agentDir);
            
            log.info("Trying agentDir: {}, exists: {}, isDir: {}", agentDir, agentFolder.exists(), agentFolder.isDirectory());
            
            if (!agentFolder.exists() || !agentFolder.isDirectory()) {
                agentDir = userDir + "/mumu-agent";
                agentFolder = new File(agentDir);
                log.info("Fallback agentDir: {}, exists: {}, isDir: {}", agentDir, agentFolder.exists(), agentFolder.isDirectory());
            }
            
            if (!agentFolder.exists() || !agentFolder.isDirectory()) {
                log.error("mumu-agent directory not found");
                return ResponseEntity.notFound().build();
            }
            
            Path zipPath = Files.createTempFile("mumu-agent-", ".zip");
            
            // 生成统一的 config.json（包含所有平台配置）
            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("userId", userId);
            configMap.put("merchantId", merchantId != null ? merchantId : 0);
            configMap.put("serverUrl", "ws://" + serverUrl + "/ws/agent");
            configMap.put("heartbeatInterval", 30000);
            configMap.put("autoStart", true);
            
            // macOS 平台配置
            Map<String, Object> darwinConfig = new LinkedHashMap<>();
            darwinConfig.put("mumuPath", "/Applications/MuMuPlayer.app");
            darwinConfig.put("adbPath", "/Users/worktools/platform-tools/adb");
            
            // Windows 平台配置
            Map<String, Object> win32Config = new LinkedHashMap<>();
            win32Config.put("mumuPath", "C:\\Program Files\\Netease\\MuMu\\nx_main\\MuMuNxMain.exe");
            win32Config.put("adbPath", "C:\\Program Files\\Netease\\MuMu\\nx_main\\adb.exe");
            
            // Linux 平台配置
            Map<String, Object> linuxConfig = new LinkedHashMap<>();
            linuxConfig.put("mumuPath", "/opt/MuMuPlayer");
            linuxConfig.put("adbPath", "");
            
            Map<String, Object> platformsMap = new LinkedHashMap<>();
            platformsMap.put("darwin", darwinConfig);
            platformsMap.put("win32", win32Config);
            platformsMap.put("linux", linuxConfig);
            configMap.put("platforms", platformsMap);
            
            String configContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(configMap);
            
            // 从文件读取启动脚本
            String startMacContent = readFileContent(agentFolder, "start_mac.command");
            String startWinContent = readFileContent(agentFolder, "start_win.bat");
            
            // 生成 README
            String readmeContent = 
                "MuMu Agent v2.0.0 使用说明\n" +
                "========================================\n\n" +
                "## 快速开始\n\n" +
                "### macOS 用户:\n" +
                "    双击 start_mac.command\n\n" +
                "### Windows 用户:\n" +
                "    双击 start_win.bat\n\n" +
                "## 前置条件\n" +
                "- Node.js 18+ (https://nodejs.org/)\n" +
                "- MuMu 模拟器已安装\n\n" +
                "## 首次使用\n" +
                "1. 解压下载的 zip 包\n" +
                "2. 双击启动脚本:\n" +
                "   - macOS: start_mac.command\n" +
                "   - Windows: start_win.bat\n" +
                "3. 脚本会自动:\n" +
                "   - 检查 Node.js 环境\n" +
                "   - 安装依赖 (npm install)\n" +
                "   - 加载配置文件\n" +
                "   - 启动 Agent\n\n" +
                "## 配置说明\n" +
                "- config.json: 唯一配置文件\n" +
                "  - 通用配置: userId, serverUrl 等\n" +
                "  - 平台配置: platforms.darwin/win32/linux\n" +
                "- 启动时自动根据系统选择平台配置\n\n" +
                "## 注意事项\n" +
                "- 一个账号只能在一台服务器上运行\n" +
                "- 请确保 MuMu 模拟器版本兼容\n" +
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
    
    private static String readFileContent(File folder, String fileName) {
        File file = new File(folder, fileName);
        if (file.exists() && file.isFile()) {
            try {
                return new String(Files.readAllBytes(file.toPath()));
            } catch (IOException e) {
                log.warn("读取文件 {} 失败: {}", fileName, e.getMessage());
            }
        }
        // 返回默认内容
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
            if (file.getName().equals("node_modules") || file.getName().startsWith(".") ||
                file.getName().equals("config.json") ||
                file.getName().equals("README.txt") ||
                file.getName().equals("start_mac.command") ||
                file.getName().equals("start.sh") || file.getName().equals("start.bat") ||
                file.getName().equals("start_win.bat")) {
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
