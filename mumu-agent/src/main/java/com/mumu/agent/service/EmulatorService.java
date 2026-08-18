package com.mumu.agent.service;

import com.mumu.agent.config.MuMuConfig;
import com.mumu.agent.model.EmulatorInfo;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmulatorService {
    
    private final MuMuConfig muMuConfig;
    private final Map<Integer, EmulatorInfo> emulators = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();
    
    public EmulatorService(MuMuConfig muMuConfig) {
        this.muMuConfig = muMuConfig;
    }
    
    @PostConstruct
    public void init() {
        log.info("MuMu Agent 初始化，MuMuManager URL: {}", muMuConfig.getManagerUrl());
        syncEmulatorsFromManager();
    }
    
    /**
     * 从 MuMuManager 同步模拟器列表
     */
    public void syncEmulatorsFromManager() {
        try {
            List<Map<String, Object>> managers = getAllEmulatorsFromManager();
            emulators.clear();
            for (Map<String, Object> m : managers) {
                EmulatorInfo info = new EmulatorInfo();
                info.setIndex((Integer) m.get("index"));
                info.setName((String) m.get("name"));
                info.setStatus((String) m.get("status"));
                info.setAdbPort(m.get("adbPort") != null ? ((Number) m.get("adbPort")).intValue() : null);
                if (m.get("createdAt") != null) {
                    info.setCreatedAt(java.time.Instant.parse((String) m.get("createdAt")));
                }
                emulators.put(info.getIndex(), info);
            }
            log.info("从 MuMuManager 同步了 {} 个模拟器", emulators.size());
        } catch (Exception e) {
            log.warn("从 MuMuManager 同步模拟器失败: {}", e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAllEmulatorsFromManager() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            return response.getBody() != null ? response.getBody() : new ArrayList<>();
        } catch (Exception e) {
            log.warn("获取 MuMuManager 模拟器列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    public String getMuMuPath() {
        String path = muMuConfig.getPath();
        return path != null ? path : muMuConfig.getManagerUrl();
    }
    
    public boolean isMuMuAvailable() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                muMuConfig.getManagerUrl() + "/api/emulators", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
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
    
    /**
     * 创建模拟器（通过 MuMuManager API）
     */
    public Map<String, Object> createEmulator(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("index", index);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators/" + index,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            log.info("创建模拟器 #{} 成功: {}", index, response.getBody());
            
            syncEmulatorsFromManager();
            result.put("status", "SUCCESS");
            result.put("message", "模拟器 #" + index + " 创建成功");
        } catch (Exception e) {
            log.error("创建模拟器 #{} 失败: {}", index, e.getMessage());
            result.put("status", "FAILED");
            result.put("message", "创建失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 批量设置模拟器数量 — 逐个调用 /api/emulators/create 创建
     */
    public Map<String, Object> setEmulatorCount(int count, int cpuCores, int memoryGb) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 先获取当前模拟器列表（过滤损坏实例）
            List<Map<String, Object>> currentList = getAllEmulatorsFromManager();
            int healthyCount = 0;
            int damagedCount = 0;
            if (currentList != null) {
                for (Map<String, Object> emu : currentList) {
                    String status = (String) emu.get("status");
                    Boolean damaged = (Boolean) emu.get("damaged");
                    if ("DAMAGED".equals(status) || Boolean.TRUE.equals(damaged)) {
                        damagedCount++;
                    } else {
                        healthyCount++;
                    }
                }
            }
            log.info("当前模拟器: 总数={}, 健康={}, 损坏={}, 目标={}", 
                    currentList != null ? currentList.size() : 0, healthyCount, damagedCount, count);

            // 先删除损坏的实例
            if (damagedCount > 0 && currentList != null) {
                log.info("发现 {} 个损坏实例，尝试清理...", damagedCount);
                for (Map<String, Object> emu : currentList) {
                    String status = (String) emu.get("status");
                    Boolean damaged = (Boolean) emu.get("damaged");
                    if ("DAMAGED".equals(status) || Boolean.TRUE.equals(damaged)) {
                        Object idx = emu.get("index");
                        if (idx instanceof Number) {
                            int damagedIndex = ((Number) idx).intValue();
                            try {
                                log.info("删除损坏模拟器: index={}", damagedIndex);
                                restTemplate.exchange(
                                        muMuConfig.getManagerUrl() + "/api/emulators/" + damagedIndex,
                                        HttpMethod.DELETE,
                                        null,
                                        new ParameterizedTypeReference<Map<String, Object>>() {}
                                );
                            } catch (Exception e) {
                                log.warn("删除损坏模拟器 #{} 失败: {}", damagedIndex, e.getMessage());
                            }
                        }
                    }
                }
            }

            // 如果目标数量小于等于健康实例数量，直接返回
            if (count <= healthyCount) {
                log.info("目标数量 <= 健康实例数量，不需要创建新模拟器");
                syncEmulatorsFromManager();
                result.put("status", "SUCCESS");
                result.put("message", "目标数量 " + count + " 已满足，无需创建");
                result.put("count", healthyCount);
                return result;
            }

            // 需要创建新的模拟器（补足健康实例到目标数量）
            int needToCreate = count - healthyCount;
            log.info("需要创建 {} 个新模拟器", needToCreate);

            // 逐个创建新的模拟器
            for (int i = 0; i < needToCreate; i++) {
                Map<String, Object> body = new HashMap<>();
                body.put("count", 1);
                body.put("cpuCount", cpuCores);
                body.put("memoryMB", memoryGb * 1024);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

                log.info("Agent创建第 {} 个模拟器 (create API)", i + 1);
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        muMuConfig.getManagerUrl() + "/api/emulators/create",
                        HttpMethod.POST,
                        entity,
                        new ParameterizedTypeReference<Map<String, Object>>() {}
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    log.info("模拟器创建成功: {}", response.getBody().get("name"));
                } else {
                    log.error("模拟器创建失败");
                }
            }

            // 同步最新列表
            syncEmulatorsFromManager();
            List<Map<String, Object>> finalList = getAllEmulatorsFromManager();
            int finalCount = finalList != null ? finalList.size() : 0;
            log.info("模拟器创建完成，当前共 {} 个", finalCount);

            result.put("status", "SUCCESS");
            result.put("message", "已创建 " + needToCreate + " 个模拟器，当前共 " + finalCount + " 个");
            result.put("count", finalCount);
            result.put("data", finalList);
        } catch (Exception e) {
            log.error("设置模拟器数量失败: {}", e.getMessage());
            result.put("status", "FAILED");
            result.put("message", "设置失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 启动模拟器
     */
    public Map<String, Object> startEmulator(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators/" + index + "/start",
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            log.info("启动模拟器 #{} 成功", index);
            syncEmulatorsFromManager();
            
            result.put("status", "SUCCESS");
            result.put("message", "模拟器 #" + index + " 启动成功");
        } catch (Exception e) {
            log.error("启动模拟器 #{} 失败: {}", index, e.getMessage());
            result.put("status", "FAILED");
            result.put("message", "启动失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 停止模拟器
     */
    public Map<String, Object> stopEmulator(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators/" + index + "/stop",
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            log.info("停止模拟器 #{} 成功", index);
            syncEmulatorsFromManager();
            
            result.put("status", "SUCCESS");
            result.put("message", "模拟器 #" + index + " 停止成功");
        } catch (Exception e) {
            log.error("停止模拟器 #{} 失败: {}", index, e.getMessage());
            result.put("status", "FAILED");
            result.put("message", "停止失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 重启模拟器
     */
    public Map<String, Object> restartEmulator(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators/" + index + "/restart",
                HttpMethod.POST,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            log.info("重启模拟器 #{} 成功", index);
            syncEmulatorsFromManager();
            
            result.put("status", "SUCCESS");
            result.put("message", "模拟器 #" + index + " 重启成功");
        } catch (Exception e) {
            log.error("重启模拟器 #{} 失败: {}", index, e.getMessage());
            result.put("status", "FAILED");
            result.put("message", "重启失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 删除模拟器
     */
    public Map<String, Object> deleteEmulator(int index) {
        Map<String, Object> result = new HashMap<>();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators/" + index,
                HttpMethod.DELETE,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            log.info("删除模拟器 #{} 成功", index);
            emulators.remove(index);
            
            result.put("status", "SUCCESS");
            result.put("message", "模拟器 #" + index + " 删除成功");
        } catch (Exception e) {
            log.error("删除模拟器 #{} 失败: {}", index, e.getMessage());
            result.put("status", "FAILED");
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * 批量启动
     */
    public Map<String, Object> batchStart(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        
        for (Integer index : indices) {
            try {
                startEmulator(index);
                successList.add("#" + index);
            } catch (Exception e) {
                failList.add("#" + index + ": " + e.getMessage());
            }
        }
        
        result.put("status", failList.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
        result.put("successList", successList);
        result.put("failList", failList);
        return result;
    }
    
    /**
     * 批量停止
     */
    public Map<String, Object> batchStop(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        
        for (Integer index : indices) {
            try {
                stopEmulator(index);
                successList.add("#" + index);
            } catch (Exception e) {
                failList.add("#" + index + ": " + e.getMessage());
            }
        }
        
        result.put("status", failList.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
        result.put("successList", successList);
        result.put("failList", failList);
        return result;
    }
    
    /**
     * 批量重启
     */
    public Map<String, Object> batchRestart(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        
        for (Integer index : indices) {
            try {
                restartEmulator(index);
                successList.add("#" + index);
            } catch (Exception e) {
                failList.add("#" + index + ": " + e.getMessage());
            }
        }
        
        result.put("status", failList.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
        result.put("successList", successList);
        result.put("failList", failList);
        return result;
    }
    
    /**
     * 批量删除
     */
    public Map<String, Object> batchDelete(List<Integer> indices) {
        Map<String, Object> result = new HashMap<>();
        List<String> successList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        
        for (Integer index : indices) {
            try {
                deleteEmulator(index);
                successList.add("#" + index);
            } catch (Exception e) {
                failList.add("#" + index + ": " + e.getMessage());
            }
        }
        
        result.put("status", failList.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
        result.put("successList", successList);
        result.put("failList", failList);
        return result;
    }
    
    public EmulatorInfo getEmulator(int index) {
        return emulators.get(index);
    }
    
    /**
     * 执行 ADB 命令（通过 MuMuManager API）
     */
    public String execAdb(int index, String... args) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("index", index);
            body.put("args", Arrays.asList(args));
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                muMuConfig.getManagerUrl() + "/api/emulators/" + index + "/adb",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            Map<String, Object> result = response.getBody();
            if (result != null && result.get("output") != null) {
                return result.get("output").toString();
            }
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            log.warn("ADB 命令执行失败: {}", e.getMessage());
            return "";
        }
    }
}
