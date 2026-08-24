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
import java.util.zip.*;

@Slf4j
@Component
public class AgentDownloadHelper {
    
    public static ResponseEntity<Resource> downloadAgentPackage(String userId, Long merchantId, String serverName) {
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
            
            String configContent = "{\n" +
                "  \"userId\": \"" + userId + "\",\n" +
                "  \"merchantId\": " + (merchantId != null ? merchantId : 0) + ",\n" +
                "  \"serverUrl\": \"wss://" + serverName + "/ws/agent\",\n" +
                "  \"heartbeatInterval\": 30000,\n" +
                "  \"autoStart\": true\n" +
                "}";
            
            String readmeContent = "# MuMu Agent 安装说明\n\n" +
                "## 安装步骤\n\n" +
                "1. 安装 Node.js (>= 18)\n" +
                "   下载地址: https://nodejs.org/\n\n" +
                "2. 解压本压缩包\n\n" +
                "3. 进入 mumu-agent 目录，安装依赖:\n" +
                "   npm install\n\n" +
                "4. 启动 Agent:\n" +
                "   node agent.js\n\n\n" +
                "5. 刷新管理后台页面，检查连接状态\n\n" +
                "## 当前配置\n\n" +
                "- 商户账号: " + userId + "\n" +
                "- 商户ID: " + (merchantId != null ? merchantId : 0) + "\n" +
                "- 服务器地址: wss://" + serverName + "/ws/agent\n\n" +
                "## 注意事项\n\n" +
                "- 请确保服务器上已安装 MuMu 模拟器\n" +
                "- 同一账号只能在一台服务器上运行\n" +
                "- 如需更换服务器，请先停止旧服务器上的 Agent\n";
            
            Set<String> addedEntries = new HashSet<>();
            
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                addFolderToZip(agentFolder, "mumu-agent", zos, addedEntries);
                
                String configEntryName = "mumu-agent/config.json";
                if (!addedEntries.contains(configEntryName)) {
                    ZipEntry configEntry = new ZipEntry(configEntryName);
                    zos.putNextEntry(configEntry);
                    zos.write(configContent.getBytes("UTF-8"));
                    zos.closeEntry();
                    addedEntries.add(configEntryName);
                }
                
                String readmeEntryName = "mumu-agent/README.txt";
                if (!addedEntries.contains(readmeEntryName)) {
                    ZipEntry readmeEntry = new ZipEntry(readmeEntryName);
                    zos.putNextEntry(readmeEntry);
                    zos.write(readmeContent.getBytes("UTF-8"));
                    zos.closeEntry();
                    addedEntries.add(readmeEntryName);
                }
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
    
    private static void addFolderToZip(File folder, String parentFolder, ZipOutputStream zos, Set<String> addedEntries) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.getName().equals("node_modules") || file.getName().startsWith(".")) {
                continue;
            }
            
            String entryName = parentFolder + "/" + file.getName();
            
            if (file.isDirectory()) {
                addFolderToZip(file, entryName, zos, addedEntries);
            } else {
                if (addedEntries.contains(entryName)) {
                    log.debug("Skipping duplicate entry: {}", entryName);
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
