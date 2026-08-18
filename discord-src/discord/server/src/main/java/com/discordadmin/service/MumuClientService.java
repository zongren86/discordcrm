package com.discordadmin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class MumuClientService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${mumu.manager.url:http://localhost:8088}")
    private String mumuManagerUrl;

    /**
     * 将后台 1-based 索引转换为 MumuManager 0-based 索引
     */
    private int toMuMuIndex(int instanceIndex) {
        return instanceIndex - 1;
    }

    /**
     * 检查 MumuManager 是否可达
     */
    public boolean isReachable() {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<String>() {}
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("MumuManager 不可达: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定索引的模拟器实体是否存在
     * index 是 1-based（数据库索引，V001=1），Mumu REST API 使用 0-based
     */
    public boolean emulatorExists(int index) {
        try {
            List<Map<String, Object>> all = getAllEmulators();
            if (all == null || all.isEmpty()) return false;

            int muMuIndex = toMuMuIndex(index);
            for (Map<String, Object> emu : all) {
                Object idx = emu.get("index");
                Object name = emu.get("name");
                int emuIndex = -1;
                if (idx instanceof Number) {
                    emuIndex = ((Number) idx).intValue();
                } else if (idx instanceof String) {
                    try {
                        emuIndex = Integer.parseInt((String) idx);
                    } catch (NumberFormatException e) {
                        log.warn("无法解析模拟器 index: {}", idx);
                    }
                }

                if (emuIndex == muMuIndex) {
                    log.info("找到匹配的模拟器: 请求index(1-based)={}, MumuIndex(0-based)={}, name={}",
                            index, emuIndex, name);
                    return true;
                }
            }

            log.info("未找到匹配的模拟器: 请求index(1-based)={}, MumuIndex(0-based)={}, 总数={}",
                    index, muMuIndex, all.size());
            return false;
        } catch (Exception e) {
            log.warn("检查模拟器 #{} 是否存在失败: {}", index, e.getMessage());
            return false;
        }
    }

    /**
     * 获取所有模拟器列表（带错误信息）
     */
    public List<Map<String, Object>> getAllEmulatorsWithError() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            throw new RuntimeException("无法连接到 MumuManager: " + e.getMessage() + "。请确保 MumuManager 已启动并在 " + mumuManagerUrl + " 端口运行。");
        }
    }

    /**
     * 设置模拟器数量（逐个创建缺失的模拟器，过滤损坏实例）
     */
    public List<Map<String, Object>> setEmulatorCount(int count, int cpuCores, int memoryGb) {
        try {
            // 先获取当前模拟器列表
            List<Map<String, Object>> currentList = getAllEmulators();
            
            // 过滤掉损坏的实例，只计算健康实例
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
            log.info("当前物理模拟器: 总数={}, 健康={}, 损坏={}, 目标={}", 
                    currentList != null ? currentList.size() : 0, healthyCount, damagedCount, count);

            // 如果目标数量小于等于健康实例数量，直接返回当前列表
            if (count <= healthyCount) {
                log.info("目标数量 <= 健康实例数量，不需要创建新模拟器");
                return currentList;
            }

            // 需要创建新的模拟器（补足健康实例到目标数量）
            int needToCreate = count - healthyCount;
            log.info("需要创建 {} 个新模拟器", needToCreate);

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
                                        mumuManagerUrl + "/api/emulators/" + damagedIndex,
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

            // 逐个创建新的模拟器
            for (int i = 0; i < needToCreate; i++) {
                Map<String, Object> body = new HashMap<>();
                body.put("count", 1);
                body.put("cpuCount", cpuCores);
                body.put("memoryMB", memoryGb * 1024);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

                log.info("创建第 {} 个模拟器 (create API)", i + 1);
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        mumuManagerUrl + "/api/emulators/create",
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

            // 返回最终列表
            List<Map<String, Object>> finalList = getAllEmulators();
            log.info("模拟器创建完成，当前共 {} 个", finalList != null ? finalList.size() : 0);
            return finalList;

        } catch (Exception e) {
            log.error("调用 MumuManager 创建模拟器失败: {}", e.getMessage());
            throw new RuntimeException("调用 MumuManager 失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有模拟器列表
     */
    public List<Map<String, Object>> getAllEmulators() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 获取模拟器列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 启动指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> startEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators/" + muMuIndex + "/start",
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 启动模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 停止指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> stopEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators/" + muMuIndex + "/stop",
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 停止模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 重启指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> restartEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators/" + muMuIndex + "/restart",
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 重启模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 删除指定模拟器（传入 1-based 索引）
     */
    public Map<String, Object> deleteEmulator(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            log.info("调用 MumuManager 删除模拟器: 后台索引={}, Mumu索引={}", index, muMuIndex);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators/" + muMuIndex,
                    HttpMethod.DELETE,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 删除模拟器 #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 启动所有模拟器
     */
    public List<Map<String, Object>> startAllEmulators(Integer count) {
        String url = mumuManagerUrl + "/api/emulators/startAll";
        if (count != null && count > 0) {
            url += "?count=" + count;
        }
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 启动所有模拟器失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 停止所有模拟器
     */
    public List<Map<String, Object>> stopAllEmulators() {
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/emulators/stopAll",
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 停止所有模拟器失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 在指定模拟器安装 Discord（传入 1-based 索引）
     */
    public Map<String, Object> installDiscord(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/discord/install/" + muMuIndex,
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 安装 Discord #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 启动 Discord（传入 1-based 索引）
     */
    public Map<String, Object> launchDiscord(int index) {
        int muMuIndex = toMuMuIndex(index);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/discord/launch/" + muMuIndex,
                    HttpMethod.POST,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 启动 Discord #{} 失败: {}", index, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 上传 APK 文件
     */
    public Map<String, Object> uploadApk(byte[] fileData, String filename) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.ByteArrayResource(fileData) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });

            HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/discord/upload",
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 上传 APK 失败: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "上传失败: " + e.getMessage());
            return error;
        }
    }

    /**
     * 检查 APK 状态
     */
    public Map<String, Boolean> checkApkStatus() {
        try {
            ResponseEntity<Map<String, Boolean>> response = restTemplate.exchange(
                    mumuManagerUrl + "/api/discord/apk-status",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Boolean>>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("调用 MumuManager 检查 APK 状态失败: {}", e.getMessage());
            Map<String, Boolean> error = new HashMap<>();
            error.put("downloaded", false);
            return error;
        }
    }
}