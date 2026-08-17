package com.mumu.agent.service;

import com.mumu.agent.config.MuMuConfig;
import com.mumu.agent.model.EmulatorInfo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmulatorService {
    
    private final MuMuConfig muMuConfig;
    private final Map<Integer, EmulatorInfo> emulators = new ConcurrentHashMap<>();
    private String detectedMuMuPath;
    
    public EmulatorService(MuMuConfig muMuConfig) {
        this.muMuConfig = muMuConfig;
    }
    
    @PostConstruct
    public void init() {
        this.detectedMuMuPath = detectMuMuPath();
        log.info("MuMu 安装路径: {}", detectedMuMuPath != null ? detectedMuMuPath : "未检测到");
    }
    
    private String detectMuMuPath() {
        // 优先使用配置文件指定的路径
        if (muMuConfig.getPath() != null && !muMuConfig.getPath().isEmpty()) {
            if (new File(muMuConfig.getPath()).exists()) {
                return muMuConfig.getPath();
            }
        }
        
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return detectMacOS();
        } else if (os.contains("win")) {
            return detectWindows();
        }
        return null;
    }
    
    private String detectMacOS() {
        String[] candidatePaths = {
            "/Applications/Netease/MuMu.app/Contents/MacOS/MuMu",
            "/Applications/MuMu.app/Contents/MacOS/MuMu",
            System.getProperty("user.home") + "/Applications/Netease/MuMu.app/Contents/MacOS/MuMu",
            System.getProperty("user.home") + "/Applications/MuMu.app/Contents/MacOS/MuMu"
        };
        
        for (String path : candidatePaths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        
        // 通过 spotlight 搜索
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"mdfind", "kMDItemCFBundleIdentifier == 'com.netease.mumu'"}
            );
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("MuMu.app")) {
                    File appDir = new File(line);
                    File exeFile = new File(appDir, "Contents/MacOS/MuMu");
                    if (exeFile.exists()) {
                        return exeFile.getAbsolutePath();
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("Spotlight 搜索失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    private String detectWindows() {
        String[] candidatePaths = {
            "C:\\Program Files\\Netease\\MuMu\\MuMuManager.exe",
            "C:\\Program Files (x86)\\Netease\\MuMu\\MuMuManager.exe",
            "D:\\Program Files\\Netease\\MuMu\\MuMuManager.exe",
            "D:\\Netease\\MuMu\\MuMuManager.exe"
        };
        
        for (String path : candidatePaths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        
        // 从注册表检测
        try {
            Process process = Runtime.getRuntime().exec(
                "reg query \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\MuMu\" /v InstallLocation"
            );
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("InstallLocation")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3) {
                        String installDir = parts[parts.length - 1];
                        File exeFile = new File(installDir, "MuMuManager.exe");
                        if (exeFile.exists()) {
                            return exeFile.getAbsolutePath();
                        }
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            log.debug("注册表检测失败: {}", e.getMessage());
        }
        
        return null;
    }
    
    public String getMuMuPath() {
        return detectedMuMuPath;
    }
    
    public boolean isMuMuAvailable() {
        return detectedMuMuPath != null && new File(detectedMuMuPath).exists();
    }
    
    public int getAdbPort(int index) {
        return muMuConfig.getAdbPortStart() + index * 2;
    }
    
    public int getEmulatorCount() {
        return emulators.size();
    }
    
    public int getRunningCount() {
        return (int) emulators.values().stream()
            .filter(e -> "RUNNING".equals(e.getStatus()))
            .count();
    }
    
    public List<EmulatorInfo> getEmulatorList() {
        return new ArrayList<>(emulators.values());
    }
    
    public String createEmulator(int index) {
        if (emulators.size() >= muMuConfig.getMaxInstances()) {
            return "ERROR: 已达到最大实例数限制（" + muMuConfig.getMaxInstances() + "个）";
        }
        
        if (emulators.containsKey(index)) {
            return "ERROR: 模拟器 index=" + index + " 已存在";
        }
        
        try {
            executeMuMuCommand("create", "-index", String.valueOf(index));
            
            EmulatorInfo info = new EmulatorInfo();
            info.setIndex(index);
            info.setAdbPort(getAdbPort(index));
            info.setStatus("CREATED");
            info.setCreatedAt(java.time.Instant.now());
            emulators.put(index, info);
            
            log.info("模拟器 index={} 创建成功，ADB端口={}", index, info.getAdbPort());
            return "SUCCESS";
        } catch (Exception e) {
            log.error("创建模拟器 index={} 失败", index, e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String startEmulator(int index) {
        EmulatorInfo info = emulators.get(index);
        if (info == null) {
            return "ERROR: 模拟器 index=" + index + " 不存在";
        }
        
        try {
            executeMuMuCommand("start", "-index", String.valueOf(index));
            
            info.setStatus("RUNNING");
            info.setLastStartedAt(java.time.Instant.now());
            
            // 等待模拟器启动
            Thread.sleep(3000);
            
            log.info("模拟器 index={} 启动成功", index);
            return "SUCCESS";
        } catch (Exception e) {
            info.setStatus("ERROR");
            info.setLastError(e.getMessage());
            log.error("启动模拟器 index={} 失败", index, e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String stopEmulator(int index) {
        EmulatorInfo info = emulators.get(index);
        if (info == null) {
            return "ERROR: 模拟器 index=" + index + " 不存在";
        }
        
        try {
            executeMuMuCommand("stop", "-index", String.valueOf(index));
            
            info.setStatus("STOPPED");
            log.info("模拟器 index={} 停止成功", index);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("停止模拟器 index={} 失败", index, e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String deleteEmulator(int index) {
        EmulatorInfo info = emulators.get(index);
        if (info == null) {
            return "ERROR: 模拟器 index=" + index + " 不存在";
        }
        
        try {
            if ("RUNNING".equals(info.getStatus())) {
                stopEmulator(index);
            }
            
            executeMuMuCommand("delete", "-index", String.valueOf(index));
            emulators.remove(index);
            
            log.info("模拟器 index={} 删除成功", index);
            return "SUCCESS";
        } catch (Exception e) {
            log.error("删除模拟器 index={} 失败", index, e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public EmulatorInfo getEmulator(int index) {
        return emulators.get(index);
    }
    
    public String execAdb(int index, String... args) throws Exception {
        int adbPort = getAdbPort(index);
        List<String> command = new ArrayList<>();
        command.add("adb");
        command.add("-s");
        command.add("127.0.0.1:" + adbPort);
        command.addAll(Arrays.asList(args));
        
        return executeCommand(command.toArray(new String[0]));
    }
    
    public String execAdbRaw(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("adb");
        command.addAll(Arrays.asList(args));
        return executeCommand(command.toArray(new String[0]));
    }
    
    private void executeMuMuCommand(String... args) throws Exception {
        if (!isMuMuAvailable()) {
            throw new RuntimeException("MuMu 未安装或路径未配置");
        }
        
        List<String> command = new ArrayList<>();
        command.add(detectedMuMuPath);
        command.addAll(Arrays.asList(args));
        
        String result = executeCommand(command.toArray(new String[0]));
        log.debug("MuMu 命令执行结果: {}", result);
    }
    
    private String executeCommand(String[] command) throws Exception {
        log.debug("执行命令: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("命令执行返回非零退出码: {}, 输出: {}", exitCode, output);
        }
        
        return output.toString().trim();
    }
}
