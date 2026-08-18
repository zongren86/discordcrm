package com.discordadmin.service;

import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.EmuInstance;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.EmuInstanceRepository;
import com.discordadmin.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmuInstanceService {

    private final EmuInstanceRepository instanceRepository;
    private final MumuClientService mumuClientService;
    private final CloudWebSocketService webSocketService;
    private final ApkManagementService apkManagementService;
    private final DiscordAccountRepository discordAccountRepository;

    @Value("${emulator.local-mode:false}")
    private boolean localMode;

    public EmuInstanceService(EmuInstanceRepository instanceRepository, 
                               MumuClientService mumuClientService,
                               CloudWebSocketService webSocketService,
                               ApkManagementService apkManagementService,
                               DiscordAccountRepository discordAccountRepository) {
        this.instanceRepository = instanceRepository;
        this.mumuClientService = mumuClientService;
        this.webSocketService = webSocketService;
        this.apkManagementService = apkManagementService;
        this.discordAccountRepository = discordAccountRepository;
    }

    private Long resolveMerchantId() {
        Long id = SecurityUtils.currentMerchantId();
        return id != null ? id : 1L;
    }

    private String resolveUserId() {
        String id = SecurityUtils.currentUserId();
        return id != null ? id : "default";
    }

    /**
     * 获取当前用户的所有模拟器实例
     */
    public List<Map<String, Object>> getCurrentUserInstances() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        log.info("获取模拟器列表: merchantId={}, userId={}", merchantId, userId);
        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        log.info("查询结果: 找到 {} 个模拟器", instances.size());
        
        // 从 MumuManager 获取最新物理状态并合并
        List<Map<String, Object>> physicalList = null;
        try {
            physicalList = mumuClientService.getAllEmulatorsWithError();
        } catch (Exception e) {
            log.warn("无法获取物理模拟器列表: {}", e.getMessage());
        }
        
        List<Map<String, Object>> result = instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
        
        // 合并物理状态到返回结果
        if (physicalList != null) {
            for (Map<String, Object> emu : result) {
                int dbIndex = ((Number) emu.get("index")).intValue();
                int mumuIndex = dbIndex - 1; // 数据库是1-based，Mumu是0-based
                
                // 查找对应的物理模拟器
                for (Map<String, Object> phys : physicalList) {
                    int physIdx = -1;
                    Object idx = phys.get("index");
                    if (idx instanceof Number) {
                        physIdx = ((Number) idx).intValue();
                    }
                    
                    if (physIdx == mumuIndex) {
                        // 合并物理状态
                        Object status = phys.get("status");
                        if (status != null) {
                            emu.put("status", status.toString());
                        }
                        // 使用物理模拟器的真实名称（V001、V002...）
                        Object physName = phys.get("name");
                        if (physName != null) {
                            emu.put("name", physName.toString());
                        }
                        Object discordInstalled = phys.get("discordInstalled");
                        if (discordInstalled != null) {
                            emu.put("discordInstalled", discordInstalled);
                        }
                        Object discordLoggedIn = phys.get("discordLoggedIn");
                        if (discordLoggedIn != null) {
                            emu.put("discordLoggedIn", discordLoggedIn);
                        }
                        // 合并模拟器检测到的Discord账号信息（覆盖数据库中的discordAccountId）
                        Object physDiscordAccount = phys.get("discordAccount");
                        if (physDiscordAccount != null && !physDiscordAccount.toString().isEmpty()) {
                            emu.put("discordAccount", physDiscordAccount.toString());
                        }
                        Object physDiscordActualUser = phys.get("discordActualUser");
                        if (physDiscordActualUser != null) {
                            emu.put("discordActualUser", physDiscordActualUser.toString());
                        }
                        Object discordLoginFailed = phys.get("discordLoginFailed");
                        if (discordLoginFailed != null) {
                            emu.put("discordLoginFailed", discordLoginFailed);
                        }
                        Object discordLoginError = phys.get("discordLoginError");
                        if (discordLoginError != null) {
                            emu.put("lastError", discordLoginError.toString());
                        }
                        Object adbPort = phys.get("adbPort");
                        if (adbPort != null) {
                            emu.put("adbPort", adbPort);
                        }
                        Object resolution = phys.get("resolution");
                        if (resolution != null) {
                            emu.put("resolution", resolution);
                        }
                        Object cpuCount = phys.get("cpuCount");
                        if (cpuCount != null) {
                            emu.put("cpuCores", cpuCount);
                        }
                        Object memoryMB = phys.get("memoryMB");
                        if (memoryMB != null) {
                            emu.put("memoryGb", memoryMB);
                        }
                        // 合并自动加好友相关字段
                        Object autoRunning = phys.get("autoRunning");
                        if (autoRunning != null) {
                            emu.put("autoRunning", autoRunning);
                        }
                        Object addedCount = phys.get("addedCount");
                        if (addedCount != null) {
                            emu.put("addedCount", addedCount);
                        }
                        Object nextAddAt = phys.get("nextAddAt");
                        if (nextAddAt != null) {
                            emu.put("nextAddAt", nextAddAt);
                        }
                        Object autoLastResult = phys.get("autoLastResult");
                        if (autoLastResult != null) {
                            emu.put("autoLastResult", autoLastResult.toString());
                        }
                        break;
                    }
                }
            }
        }
        
        return result;
    }

    /**
     * 获取当前用户的在线 Agent
     */
    public List<AgentRegistration> getOnlineAgents() {
        String userId = resolveUserId();
        return webSocketService.getOnlineAgentsByUserId(userId);
    }

    /**
     * 确保物理模拟器连接可用
     */
    private void ensurePhysicalReady() {
        boolean agentOnline = !webSocketService.getAllOnlineAgents().isEmpty();
        boolean localReachable = mumuClientService.isReachable();

        if (!agentOnline && !localReachable) {
            StringBuilder sb = new StringBuilder();
            sb.append("未检测到本地模拟器，无法执行此操作。\n\n");
            sb.append("请按以下步骤操作：\n");
            sb.append("1. 启动本地 MumuManager 服务（端口 8088）\n");
            sb.append("2. 如果使用云端模式，请启动 mumu-agent\n");
            sb.append("   cd mumu-agent && mvn spring-boot:run\n");
            sb.append("3. 如果是本地开发模式，设置 emulator.local-mode=true\n");
            throw new RuntimeException(sb.toString());
        }
    }

    /**
     * 设置模拟器数量 — 实体优先：先创建物理实体，成功后再写数据库记录
     * APK 检查仅作提示，不阻塞创建；创建后异步自动安装 Discord
     */
    @Transactional
    public List<Map<String, Object>> setInstanceCount(int count, int cpuCores, int memoryGb) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        if (merchantId == null || userId == null) {
            throw new RuntimeException("用户未登录，无法管理模拟器");
        }

        // 1. 检查物理连接
        boolean agentOnline = !webSocketService.getAllOnlineAgents().isEmpty();
        boolean localReachable = mumuClientService.isReachable();
        log.info("物理连接状态: agentOnline={}, localReachable={}, localMode={}", agentOnline, localReachable, localMode);

        if (!agentOnline && !localReachable) {
            throw new RuntimeException("未检测到 MumuManager，请启动 MuMu 模拟器后台服务（端口 8088）");
        }

        // 2. 记录当前数据库记录数量（用于日志）
        List<EmuInstance> existingInstances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        log.info("当前数据库已有 {} 条模拟器记录", existingInstances.size());

        // 3. 获取当前物理模拟器数量（用于日志）
        int currentHealthyCount = 0;
        try {
            List<Map<String, Object>> currentList = mumuClientService.getAllEmulators();
            if (currentList != null) {
                for (Map<String, Object> emu : currentList) {
                    String status = (String) emu.get("status");
                    Boolean damaged = (Boolean) emu.get("damaged");
                    if (!"DAMAGED".equals(status) && !Boolean.TRUE.equals(damaged)) {
                        currentHealthyCount++;
                    }
                }
            }
            log.info("当前物理模拟器健康数量: {}", currentHealthyCount);
        } catch (Exception e) {
            log.warn("获取当前物理模拟器数量失败: {}", e.getMessage());
        }

        // 4. 创建物理实例（优先本地模式，回退到 Agent 模式）
        boolean physicalSuccess = false;
        List<Map<String, Object>> physicalList = new ArrayList<>();
        String createMethod = "none";

        // 优先使用本地模式（更可靠，直接操作 MumuManager）
        if (localMode && localReachable) {
            log.info("使用本地模式创建模拟器（localMode=true, localReachable=true）");
            try {
                physicalList = mumuClientService.setEmulatorCount(count, cpuCores, memoryGb);
                log.info("MumuManager 返回: {} 个模拟器", physicalList != null ? physicalList.size() : 0);
                
                int healthyInResult = countHealthy(physicalList);
                log.info("健康模拟器数量: 需要 {}, 实际 {}", count, healthyInResult);
                
                if (healthyInResult >= count) {
                    physicalSuccess = true;
                    createMethod = "local";
                } else {
                    log.error("MumuManager 返回的健康模拟器数量不足: 需要 {}, 实际健康 {}", count, healthyInResult);
                    throw new RuntimeException("MumuManager 创建模拟器失败：健康实例数量不足 (需要 " + count + ", 实际 " + healthyInResult + ")");
                }
            } catch (Exception e) {
                log.error("本地模式创建模拟器失败: {}", e.getMessage());
                throw new RuntimeException("创建物理模拟器失败: " + e.getMessage());
            }
        } else if (agentOnline) {
            // Agent 模式：通过 WebSocket 创建
            log.info("使用 Agent 模式创建模拟器");
            List<AgentRegistration> onlineAgents = webSocketService.getAllOnlineAgents();
            for (AgentRegistration agent : onlineAgents) {
                try {
                    Map<String, Object> params = new HashMap<>();
                    params.put("count", count);
                    params.put("cpuCores", cpuCores);
                    params.put("memoryGb", memoryGb);

                    CompletableFuture<Map<String, Object>> future =
                        webSocketService.sendCommandAndWait(agent.getDeviceId(), "CREATE_EMULATOR", params);
                    Map<String, Object> result = future.get(180, TimeUnit.SECONDS);
                    log.info("Agent {} 创建模拟器结果: status={}", agent.getDeviceId(), result.get("status"));
                    
                    if ("SUCCESS".equals(result.get("status"))) {
                        physicalSuccess = true;
                        createMethod = "agent";
                        // 尝试从结果中获取模拟器列表
                        Object data = result.get("data");
                        if (data instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> agentList = (List<Map<String, Object>>) data;
                            if (agentList != null && !agentList.isEmpty()) {
                                physicalList = agentList;
                                log.info("Agent 返回 {} 个模拟器", physicalList.size());
                            }
                        }
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Agent {} 创建模拟器失败: {}", agent.getDeviceId(), e.getMessage());
                }
            }
            
            if (!physicalSuccess) {
                throw new RuntimeException("Agent 模式创建模拟器失败，请检查本地 Agent 是否正常运行");
            }
        } else {
            throw new RuntimeException("无法创建物理模拟器：既没有可用的本地 MumuManager，也没有在线 Agent。请确保 MumuManager 已启动（端口 8088）");
        }

        // 5. 验证物理实体（使用 MumuManager API 获取最新列表，确保物理实例真正存在）
        int healthyAfterCreate = 0;
        try {
            List<Map<String, Object>> finalList = mumuClientService.getAllEmulatorsWithError();
            if (finalList == null || finalList.isEmpty()) {
                throw new RuntimeException("物理模拟器创建失败：MumuManager 返回空列表，物理实例可能未真正创建");
            }
            
            healthyAfterCreate = countHealthy(finalList);
            log.info("物理模拟器验证: 创建方式={}, 总数={}, 健康={}, 需要={}", 
                    createMethod, finalList.size(), healthyAfterCreate, count);
            
            if (healthyAfterCreate < count) {
                throw new RuntimeException(String.format("物理模拟器数量验证失败: 需要 %d 个健康实例，实际只有 %d 个。物理创建可能未成功，请检查 MumuManager 日志", count, healthyAfterCreate));
            }
            
            physicalList = finalList;
            log.info("物理模拟器验证通过，实际存在 {} 个健康实体", healthyAfterCreate);
        } catch (RuntimeException e) {
            log.error("物理模拟器验证失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("物理模拟器验证异常", e);
            throw new RuntimeException("物理模拟器创建后验证失败: " + e.getMessage());
        }

        log.info("模拟器创建完成: 方式={}, 健康实例={}", createMethod, healthyAfterCreate);

        // 6. APK 检查（仅作日志提示）
        Map<String, Object> apkStatus = apkManagementService.checkApkStatus();
        boolean apkDownloaded = Boolean.TRUE.equals(apkStatus.get("downloaded"));
        if (!apkDownloaded) {
            log.warn("Discord APK 未上传，模拟器创建成功后将跳过自动安装。请稍后上传 APK 并手动安装");
        } else {
            log.info("Discord APK 检查通过，将在模拟器创建后自动安装");
        }

        // 7. 写数据库记录（使用 Mumu 实际名称建立对应关系）
        List<Map<String, Object>> instances = syncInstanceDatabase(merchantId, userId, count, cpuCores, memoryGb, physicalList);

        // 8. 如果 APK 存在，异步启动模拟器并自动安装 Discord
        if (apkDownloaded) {
            CompletableFuture.runAsync(() -> {
                try {
                    log.info("开始自动启动新创建的模拟器并安装 Discord...");
                    for (Map<String, Object> inst : instances) {
                        int index = ((Number) inst.get("index")).intValue();
                        try {
                            // 启动模拟器
                            mumuClientService.startEmulator(index);
                            log.info("模拟器 #{} 启动成功", index);
                            
                            // 等待模拟器完全启动
                            Thread.sleep(8000);
                            
                            // 安装 Discord
                            mumuClientService.installDiscord(index);
                            log.info("模拟器 #{} Discord 安装成功", index);
                            
                            // 启动 Discord
                            Thread.sleep(3000);
                            mumuClientService.launchDiscord(index);
                            log.info("模拟器 #{} Discord 已启动", index);
                            
                            // 更新数据库状态
                            updateInstanceAfterInstall(index, true);
                        } catch (Exception e) {
                            log.warn("模拟器 #{} 自动安装/启动 Discord 失败: {}", index, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.error("自动安装 Discord 任务异常", e);
                }
            });
        } else {
            log.info("Discord APK 未上传，跳过自动安装。请先上传 APK 后手动安装 Discord");
        }

        return instances;
    }

    /**
     * 同步模拟器数据库记录 — 使用 Mumu 实际名称建立对应关系
     * Mumu 使用 0-based index (V001=0, V002=1...)，数据库使用 1-based instanceIndex
     */
    private List<Map<String, Object>> syncInstanceDatabase(Long merchantId, String userId, int count, int cpuCores, int memoryGb, List<Map<String, Object>> physicalList) {
        // 获取 Mumu 的实际模拟器列表（按 index 排序）
        List<Map<String, Object>> mumuEmulators = new ArrayList<>();
        
        // 优先使用传入的 physicalList
        if (physicalList != null && !physicalList.isEmpty()) {
            mumuEmulators = physicalList.stream()
                .filter(e -> !"DAMAGED".equals(e.get("status")) && !Boolean.TRUE.equals(e.get("damaged")))
                .sorted(Comparator.comparingInt(e -> ((Number) e.get("index")).intValue()))
                .collect(Collectors.toList());
        }
        
        // 如果 physicalList 为空或没有健康实例，从 MumuManager API 获取
        if (mumuEmulators.isEmpty()) {
            log.info("传入的模拟器列表为空，从 MumuManager API 获取...");
            try {
                List<Map<String, Object>> freshList = mumuClientService.getAllEmulators();
                if (freshList != null && !freshList.isEmpty()) {
                    mumuEmulators = freshList.stream()
                        .filter(e -> !"DAMAGED".equals(e.get("status")) && !Boolean.TRUE.equals(e.get("damaged")))
                        .sorted(Comparator.comparingInt(e -> ((Number) e.get("index")).intValue()))
                        .collect(Collectors.toList());
                    log.info("从 MumuManager 获取到 {} 个健康模拟器", mumuEmulators.size());
                }
            } catch (Exception e) {
                log.warn("从 MumuManager 获取模拟器列表失败: {}", e.getMessage());
            }
        }
        
        log.info("同步数据库: Mumu 健康模拟器={}, 目标数量={}", mumuEmulators.size(), count);
        
        if (mumuEmulators.isEmpty()) {
            throw new RuntimeException("Mumu 中没有任何健康的物理模拟器，无法创建数据库记录。请确保物理实例创建成功");
        }
        
        // 如果物理实例数量少于目标数量，报错而不是创建虚拟记录
        if (mumuEmulators.size() < count) {
            log.error("物理模拟器数量不足: 需要 {}, 实际健康 {}", count, mumuEmulators.size());
            throw new RuntimeException(String.format("物理模拟器数量不足：需要 %d 个健康实例，实际只有 %d 个。物理创建可能失败，请检查 MumuManager 日志", count, mumuEmulators.size()));
        }
        
        // 清理现有记录，重新建立对应关系
        List<EmuInstance> existing = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        if (!existing.isEmpty()) {
            instanceRepository.deleteAll(existing);
        }
        
        // 为每个 Mumu 模拟器创建数据库记录，使用 Mumu 的实际名称
        int createdCount = 0;
        for (int i = 0; i < mumuEmulators.size() && createdCount < count; i++) {
            Map<String, Object> mumuEmu = mumuEmulators.get(i);
            int mumuIndex = ((Number) mumuEmu.get("index")).intValue(); // 0-based
            String mumuName = (String) mumuEmu.get("name"); // V001, V002...
            
            EmuInstance instance = new EmuInstance();
            instance.setMerchantId(merchantId);
            instance.setUserId(userId);
            instance.setName(mumuName != null ? mumuName : "V" + String.format("%03d", mumuIndex + 1));
            instance.setInstanceIndex(mumuIndex + 1);
            instance.setStatus(mapMumuStatus((String) mumuEmu.get("status")));
            instance.setCpuCores(cpuCores);
            instance.setMemoryGb(memoryGb);
            instance.setResolution("720x1280");
            instance.setAdbPort(mumuEmu.get("adbPort") != null ? ((Number) mumuEmu.get("adbPort")).intValue() : null);
            instance.setDiscordInstalled(false);
            instance.setDiscordLoggedIn(false);
            instance.setAutoRunning(false);
            instance.setAddedCount(0);
            instance.setCreatedAt(Instant.now());
            instance.setUpdatedAt(Instant.now());
            
            instanceRepository.save(instance);
            createdCount++;
            log.info("创建数据库记录: {} (Mumu index={}, DB index={})", mumuName, mumuIndex, mumuIndex + 1);
        }

        return instanceRepository.findByMerchantIdAndUserId(merchantId, userId).stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }
    
    /**
     * 映射 Mumu 状态到数据库状态
     */
    private EmuInstance.EmuStatus mapMumuStatus(String mumuStatus) {
        if (mumuStatus == null) return EmuInstance.EmuStatus.CREATED;
        return switch (mumuStatus) {
            case "RUNNING" -> EmuInstance.EmuStatus.RUNNING;
            case "STOPPED" -> EmuInstance.EmuStatus.STOPPED;
            case "DAMAGED" -> EmuInstance.EmuStatus.ERROR;
            default -> EmuInstance.EmuStatus.CREATED;
        };
    }

    /**
     * 启动模拟器 — 异步执行，立即返回
     * 启动后自动打开 Discord（如果已安装）
     */
    @Transactional
    public Map<String, Object> startInstance(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        if (!mumuClientService.emulatorExists(index)) {
            throw new RuntimeException(String.format("物理模拟器 #%d 不存在，无法启动。请先创建模拟器。", index));
        }

        // 异步启动，不等待完成
        CompletableFuture.runAsync(() -> {
            try {
                mumuClientService.startEmulator(index);
                log.info("模拟器 #{} 启动指令执行完成", index);

                // 如果已安装 Discord，自动打开
                if (instance.getDiscordInstalled()) {
                    log.info("模拟器 #{} Discord 已安装，自动打开...", index);
                    try {
                        Thread.sleep(3000); // 等待模拟器完全启动
                        mumuClientService.launchDiscord(index);
                        log.info("模拟器 #{} Discord 已自动打开", index);
                        // 更新 Discord 首页状态
                        updateDiscordOnHome(index, true);
                    } catch (Exception e) {
                        log.warn("模拟器 #{} 自动打开 Discord 失败: {}", index, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("模拟器 #{} 启动指令执行失败: {}", index, e.getMessage());
            }
        });

        // 先更新状态为运行中
        instance.setStatus(EmuInstance.EmuStatus.RUNNING);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 停止模拟器 — 异步执行，立即返回
     */
    @Transactional
    public Map<String, Object> stopInstance(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        if (!mumuClientService.emulatorExists(index)) {
            throw new RuntimeException(String.format("物理模拟器 #%d 不存在，无法停止", index));
        }

        // 异步停止，不等待完成
        CompletableFuture.runAsync(() -> {
            try {
                mumuClientService.stopEmulator(index);
                log.info("模拟器 #{} 停止指令执行完成", index);
            } catch (Exception e) {
                log.warn("模拟器 #{} 停止指令执行失败: {}", index, e.getMessage());
            }
        });

        // 先更新状态为已停止
        instance.setStatus(EmuInstance.EmuStatus.STOPPED);
        instance.setAutoRunning(false);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 重启模拟器 — 异步执行，立即返回
     */
    @Transactional
    public Map<String, Object> restartInstance(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        if (!mumuClientService.emulatorExists(index)) {
            throw new RuntimeException(String.format("物理模拟器 #%d 不存在，无法重启", index));
        }

        // 异步重启，不等待完成
        CompletableFuture.runAsync(() -> {
            try {
                mumuClientService.restartEmulator(index);
                log.info("模拟器 #{} 重启指令执行完成", index);
            } catch (Exception e) {
                log.warn("模拟器 #{} 重启指令执行失败: {}", index, e.getMessage());
            }
        });

        // 先更新状态为运行中
        instance.setStatus(EmuInstance.EmuStatus.RUNNING);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 启动所有模拟器
     */
    @Transactional
    public List<Map<String, Object>> startAllInstances() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        if (!onlineAgents.isEmpty()) {
            for (AgentRegistration agent : onlineAgents) {
                try {
                    webSocketService.sendCommandAndWait(agent.getDeviceId(), "BATCH_START", new HashMap<>())
                        .get(180, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Agent {} 启动失败: {}", agent.getDeviceId(), e.getMessage());
                }
            }
        } else if (localMode) {
            mumuClientService.startAllEmulators(null);
        } else {
            throw new RuntimeException("本地 Agent 未上线，无法启动模拟器。请启动 mumu-agent。");
        }

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        for (EmuInstance inst : instances) {
            inst.setStatus(EmuInstance.EmuStatus.RUNNING);
            inst.setLastError(null);
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 停止所有模拟器
     */
    @Transactional
    public List<Map<String, Object>> stopAllInstances() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        if (!onlineAgents.isEmpty()) {
            for (AgentRegistration agent : onlineAgents) {
                try {
                    webSocketService.sendCommandAndWait(agent.getDeviceId(), "BATCH_STOP", new HashMap<>())
                        .get(180, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Agent {} 停止失败: {}", agent.getDeviceId(), e.getMessage());
                }
            }
        } else if (localMode) {
            mumuClientService.stopAllEmulators();
        } else {
            throw new RuntimeException("本地 Agent 未上线，无法停止模拟器。请启动 mumu-agent。");
        }

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        for (EmuInstance inst : instances) {
            inst.setStatus(EmuInstance.EmuStatus.STOPPED);
            inst.setAutoRunning(false);
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 安装Discord到模拟器
     */
    @Transactional
    public Map<String, Object> installDiscord(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        if (instance.getStatus() != EmuInstance.EmuStatus.RUNNING) {
            throw new RuntimeException("模拟器未运行");
        }

        mumuClientService.installDiscord(index);

        instance.setDiscordInstalled(true);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 启动Discord应用
     */
    @Transactional
    public Map<String, Object> launchDiscord(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        if (!instance.getDiscordInstalled()) {
            throw new RuntimeException("Discord未安装");
        }

        mumuClientService.launchDiscord(index);

        instance.setDiscordOnHome(false);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        Map<String, Object> result = new HashMap<>();
        result.put("result", "Discord已启动，请确保进入首页");
        return result;
    }

    /**
     * 更新Discord首页状态
     */
    @Transactional
    public Map<String, Object> updateDiscordHomeStatus(int index, boolean onHome) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setDiscordOnHome(onHome);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        Map<String, Object> result = new HashMap<>();
        result.put("onHome", onHome);
        result.put("success", true);
        return result;
    }

    /**
     * 标记Discord登录状态
     */
    @Transactional
    public Map<String, Object> updateDiscordLoginStatus(int index, boolean loggedIn) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setDiscordLoggedIn(loggedIn);
        if (!loggedIn) {
            instance.setDiscordOnHome(false);
        }
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        Map<String, Object> result = new HashMap<>();
        result.put("loggedIn", loggedIn);
        result.put("success", true);
        return result;
    }

    /**
     * 启动自动加好友
     */
    @Transactional
    public Map<String, Object> startAutoAdd(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        if (instance.getStatus() != EmuInstance.EmuStatus.RUNNING) {
            throw new RuntimeException("模拟器未运行");
        }

        if (!instance.getDiscordInstalled()) {
            throw new RuntimeException("Discord未安装");
        }

        if (!instance.getDiscordLoggedIn()) {
            instance.setLastError("Discord未登录");
            instanceRepository.save(instance);
            throw new RuntimeException("Discord未登录，请先在模拟器中登录Discord");
        }

        if (!instance.getDiscordOnHome()) {
            instance.setLastError("Discord未在首页");
            instanceRepository.save(instance);
            throw new RuntimeException("Discord未在首页，请先跳转到首页");
        }

        instance.setAutoRunning(true);
        instance.setLastError(null);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 停止自动加好友
     */
    @Transactional
    public Map<String, Object> stopAutoAdd(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));

        instance.setAutoRunning(false);
        instance.setUpdatedAt(Instant.now());
        instanceRepository.save(instance);

        return convertToMap(instance);
    }

    /**
     * 全部启动自动加好友
     */
    @Transactional
    public List<Map<String, Object>> startAllAutoAdd() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        List<String> errors = new ArrayList<>();

        for (EmuInstance inst : instances) {
            if (inst.getStatus() == EmuInstance.EmuStatus.RUNNING 
                && inst.getDiscordInstalled() 
                && inst.getDiscordLoggedIn()
                && !inst.getAutoRunning()) {
                inst.setAutoRunning(true);
                inst.setLastError(null);
                inst.setUpdatedAt(Instant.now());
                instanceRepository.save(inst);
            } else if (inst.getStatus() == EmuInstance.EmuStatus.RUNNING && !inst.getDiscordLoggedIn()) {
                errors.add("#" + inst.getInstanceIndex() + " Discord未登录");
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("部分模拟器无法启动: " + String.join("; ", errors));
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 全部停止自动加好友
     */
    @Transactional
    public List<Map<String, Object>> stopAllAutoAdd() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        for (EmuInstance inst : instances) {
            inst.setAutoRunning(false);
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
        }

        return instances.stream()
            .map(this::convertToMap)
            .collect(Collectors.toList());
    }

    /**
     * 删除模拟器 — 同步执行：先停物理 → 删物理 → 清DB → 重建剩余索引
     * index 是 1-based（数据库 instanceIndex，V001=1）
     */
    @Transactional
    public Map<String, Object> deleteInstance(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        log.info("开始同步删除模拟器 #{} (name={})", index, instance.getName());
        Map<String, Object> result = new HashMap<>();
        List<String> actions = new ArrayList<>();

        try {
            // 1. 通过名称精确定位 Mumu 模拟器的 0-based index（作为校验用）
            int mumuTargetIndex = findMumuIndexByNameOrIndex(instance.getName(), index);
            boolean physicalExists = mumuTargetIndex >= 0;
            log.info("模拟器 #{} 物理状态: {}, mumuTargetIndex(0-based)={}", index, physicalExists ? "存在" : "不存在", mumuTargetIndex);

            if (physicalExists) {
                // 2. 先停止（传 1-based，MumuClientService 内部 toMuMuIndex 会 -1）
                try {
                    mumuClientService.stopEmulator(index);
                    log.info("模拟器 #{} 物理实例已停止", index);
                    actions.add("已停止物理实例");
                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.warn("停止物理模拟器 #{} 失败: {}，继续删除", index, e.getMessage());
                    actions.add("停止失败: " + e.getMessage());
                }

                // 3. 删除物理模拟器（传 1-based）
                try {
                    mumuClientService.deleteEmulator(index);
                    log.info("物理模拟器 #{} 已删除 (mumuIndex={})", index, mumuTargetIndex);
                    actions.add("已删除物理实例");
                    Thread.sleep(1500);
                } catch (Exception e) {
                    log.error("删除物理模拟器 #{} 失败: {}", index, e.getMessage());
                    actions.add("物理删除失败: " + e.getMessage());
                }

                // 4. 验证物理删除（传 1-based）
                try {
                    boolean stillExists = mumuClientService.emulatorExists(index);
                    if (stillExists) {
                        log.warn("物理模拟器 #{} 删除后仍存在", index);
                        actions.add("警告: 物理实例可能未完全删除");
                    } else {
                        log.info("物理模拟器 #{} 已确认删除", index);
                    }
                } catch (Exception e) {
                    log.warn("验证物理删除状态失败: {}", e.getMessage());
                }
            } else {
                log.info("模拟器 #{} 物理实例不存在，跳过物理删除", index);
                actions.add("物理实例不存在，跳过物理删除");
            }

            // 5. 清理数据库记录
            instanceRepository.delete(instance);
            log.info("已删除模拟器 #{} 的数据库记录", index);
            actions.add("已清理数据库记录");

            // 6. 重建剩余记录的 instanceIndex，使其与 Mumu 物理索引严格一致
            //    （Mumu 删除后不会自动重排 REST index，必须以 name 为锚点重新建立对应）
            try {
                List<Map<String, Object>> remainingPhysical = mumuClientService.getAllEmulators();
                if (remainingPhysical != null) {
                    List<Map<String, Object>> healthy = remainingPhysical.stream()
                        .filter(e -> !"DAMAGED".equals(e.get("status")) && !Boolean.TRUE.equals(e.get("damaged")))
                        .sorted(Comparator.comparingInt(e -> ((Number) e.get("index")).intValue()))
                        .collect(Collectors.toList());

                    List<EmuInstance> remainingDb = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
                    Map<String, EmuInstance> byName = new HashMap<>();
                    for (EmuInstance r : remainingDb) {
                        if (r.getName() != null) byName.put(r.getName(), r);
                    }

                    for (Map<String, Object> emu : healthy) {
                        String name = (String) emu.get("name");
                        EmuInstance dbRec = byName.get(name);
                        int physMumuIdx = ((Number) emu.get("index")).intValue();
                        int expectedDbIdx = physMumuIdx + 1; // 1-based
                        if (dbRec != null && !Objects.equals(dbRec.getInstanceIndex(), expectedDbIdx)) {
                            dbRec.setInstanceIndex(expectedDbIdx);
                            instanceRepository.save(dbRec);
                            log.info("重建索引: name={}, mumuIdx(0-based)={}, dbIndex(1-based)={}",
                                    name, physMumuIdx, expectedDbIdx);
                        }
                    }
                    actions.add("已重建剩余记录的索引");
                }
            } catch (Exception e) {
                log.warn("重建索引失败: {}", e.getMessage());
                actions.add("重建索引失败: " + e.getMessage());
            }

            result.put("success", true);
            result.put("message", "删除成功");
            result.put("actions", actions);
        } catch (Exception e) {
            log.error("删除模拟器 #{} 失败", index, e);
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
            result.put("actions", actions);
        }

        return result;
    }

    /**
     * 根据名称或索引查找 Mumu 模拟器的 0-based index
     * 优先使用名称匹配，确保与物理实例一一对应
     */
    private int findMumuIndexByNameOrIndex(String name, int dbIndex) {
        try {
            List<Map<String, Object>> allEmus = mumuClientService.getAllEmulators();
            if (allEmus == null || allEmus.isEmpty()) {
                return -1;
            }

            // 第一优先级：通过名称精确匹配
            for (Map<String, Object> emu : allEmus) {
                Object emuNameObj = emu.get("name");
                if (emuNameObj instanceof String && name != null && name.equals(emuNameObj)) {
                    Object idxObj = emu.get("index");
                    int emuIndex = parseIndex(idxObj);
                    log.info("通过名称匹配找到模拟器: name={}, mumuIndex={}", name, emuIndex);
                    return emuIndex;
                }
            }

            // 第二优先级：通过索引匹配（instanceIndex = mumuIndex + 1）
            int expectedMumuIndex = dbIndex - 1;
            for (Map<String, Object> emu : allEmus) {
                Object idxObj = emu.get("index");
                int emuIndex = parseIndex(idxObj);
                if (emuIndex == expectedMumuIndex) {
                    // 验证：该实例不应是损坏的
                    String status = (String) emu.get("status");
                    Boolean damaged = (Boolean) emu.get("damaged");
                    if (!"DAMAGED".equals(status) && !Boolean.TRUE.equals(damaged)) {
                        log.info("通过索引匹配找到模拟器: dbIndex={}, mumuIndex={}", dbIndex, emuIndex);
                        return emuIndex;
                    }
                }
            }

            // 第三优先级：忽略损坏状态直接匹配索引
            for (Map<String, Object> emu : allEmus) {
                Object idxObj = emu.get("index");
                int emuIndex = parseIndex(idxObj);
                if (emuIndex == expectedMumuIndex) {
                    log.info("通过索引匹配找到模拟器(含损坏): dbIndex={}, mumuIndex={}", dbIndex, emuIndex);
                    return emuIndex;
                }
            }

            log.warn("未找到匹配的 Mumu 模拟器: name={}, dbIndex={}", name, dbIndex);
            return -1;
        } catch (Exception e) {
            log.warn("查询 Mumu 模拟器列表失败: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 解析 Mumu 模拟器的 index 字段（兼容 Number 和 String 类型）
     */
    private int parseIndex(Object idxObj) {
        if (idxObj instanceof Number) {
            return ((Number) idxObj).intValue();
        } else if (idxObj instanceof String) {
            try {
                return Integer.parseInt((String) idxObj);
            } catch (NumberFormatException e) {
                log.warn("无法解析模拟器 index: {}", idxObj);
            }
        }
        return -1;
    }

    /**
     * 通用方法：优先通过 Agent 执行，否则本地执行
     */
    private Map<String, Object> executeViaAgentOrLocal(String userId, String commandType, Map<String, Object> params, LocalExecutor executor) {
        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        if (!onlineAgents.isEmpty()) {
            for (AgentRegistration agent : onlineAgents) {
                try {
                    Map<String, Object> result = webSocketService
                        .sendCommandAndWait(agent.getDeviceId(), commandType, params)
                        .get(180, TimeUnit.SECONDS);
                    log.info("Agent {} 执行 {} 结果: {}", agent.getDeviceId(), commandType, result.get("status"));
                    return result;
                } catch (Exception e) {
                    log.warn("Agent {} 执行 {} 失败: {}", agent.getDeviceId(), commandType, e.getMessage());
                }
            }
        }
        
        if (localMode) {
            try {
                Map<String, Object> result = executor.execute();
                if (result != null) {
                    return result;
                }
                Map<String, Object> r = new HashMap<>();
                r.put("status", "SUCCESS");
                return r;
            } catch (Exception e) {
                log.error("本地执行 {} 失败: {}", commandType, e.getMessage());
                throw new RuntimeException("操作失败: " + e.getMessage());
            }
        }
        
        throw new RuntimeException("本地 Agent 未上线，无法执行 " + commandType + "。请启动 mumu-agent。");
    }

    @FunctionalInterface
    private interface LocalExecutor {
        Map<String, Object> execute() throws Exception;
    }

    /**
     * 转换为Map
     */
    private Map<String, Object> convertToMap(EmuInstance instance) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", instance.getId());
        item.put("index", instance.getInstanceIndex());
        item.put("name", instance.getName());
        item.put("status", instance.getStatus().name());
        item.put("cpuCores", instance.getCpuCores());
        item.put("memoryGb", instance.getMemoryGb());
        item.put("resolution", instance.getResolution());
        item.put("adbPort", instance.getAdbPort());
        item.put("discordInstalled", instance.getDiscordInstalled());
        item.put("discordLoggedIn", instance.getDiscordLoggedIn());
        item.put("discordOnHome", instance.getDiscordOnHome());
        
        // 查询Discord账号名称
        if (instance.getDiscordAccountId() != null) {
            DiscordAccount account = discordAccountRepository.findById(instance.getDiscordAccountId()).orElse(null);
            item.put("discordAccount", account != null ? account.getName() : instance.getDiscordAccountId().toString());
        } else {
            item.put("discordAccount", "-");
        }
        
        item.put("autoRunning", instance.getAutoRunning());
        item.put("addedCount", instance.getAddedCount());
        item.put("nextAddAt", instance.getNextAddAt() != null ? instance.getNextAddAt().toEpochMilli() : null);
        item.put("lastError", instance.getLastError());
        item.put("autoLastResult", instance.getAutoLastResult());
        return item;
    }

    /**
     * 获取物理模拟器连接状态（供前端轮询检查）
     */
    public Map<String, Object> getPhysicalStatus() {
        Map<String, Object> status = new HashMap<>();
        boolean agentOnline = !webSocketService.getAllOnlineAgents().isEmpty();
        boolean localReachable = mumuClientService.isReachable();
        boolean available = agentOnline || localReachable;

        status.put("available", available);
        status.put("agentOnline", agentOnline);
        status.put("localReachable", localReachable);
        status.put("message", available ? "物理模拟器已连接" : 
            "未检测到本地模拟器。请确保 MuMuPlayer 已安装并启动");
        return status;
    }

    /**
     * 同步物理模拟器与数据库记录的一致性
     * 物理存在但数据库缺失 → 自动创建记录
     * 数据库有记录但物理已删除 → 自动清理记录
     */
    @Transactional
    public Map<String, Object> syncPhysicalAndDb() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        Map<String, Object> result = new HashMap<>();
        List<String> actions = new ArrayList<>();

        List<Map<String, Object>> physicalList;
        try {
            physicalList = mumuClientService.getAllEmulatorsWithError();
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "无法连接到 MumuManager: " + e.getMessage());
            result.put("actions", actions);
            return result;
        }

        List<EmuInstance> dbInstances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        Set<Integer> dbIndices = dbInstances.stream()
            .map(EmuInstance::getInstanceIndex)
            .collect(Collectors.toSet());

        Set<Integer> physicalIndices = new HashSet<>();
        if (physicalList != null) {
            for (Map<String, Object> emu : physicalList) {
                Object idx = emu.get("index");
                if (idx instanceof Number) {
                    physicalIndices.add(((Number) idx).intValue() + 1);
                }
            }
        }

        // 1. 物理有但数据库没有 → 创建记录
        for (int physIdx : physicalIndices) {
            if (!dbIndices.contains(physIdx)) {
                EmuInstance instance = new EmuInstance();
                instance.setMerchantId(merchantId);
                instance.setUserId(userId);
                // 生成 V-prefix 名称（与 Mumu 一致）
                String vName = "V" + String.format("%03d", physIdx);
                instance.setName(vName);
                instance.setInstanceIndex(physIdx);
                instance.setStatus(EmuInstance.EmuStatus.CREATED);
                instance.setCpuCores(1);
                instance.setMemoryGb(1);
                instance.setResolution("720x1280");
                instance.setDiscordInstalled(false);
                instance.setDiscordLoggedIn(false);
                instance.setAutoRunning(false);
                instance.setAddedCount(0);
                instance.setCreatedAt(Instant.now());
                instanceRepository.save(instance);
                actions.add("为物理模拟器 #" + physIdx + " 创建了数据库记录");
                log.info("同步：为物理模拟器 #{} 创建数据库记录, name={}", physIdx, vName);
            }
        }

        // 2. 数据库有但物理没有 → 清理记录
        for (EmuInstance dbInst : dbInstances) {
            if (!physicalIndices.contains(dbInst.getInstanceIndex())) {
                instanceRepository.delete(dbInst);
                actions.add("清理了模拟器 #" + dbInst.getInstanceIndex() + " 的孤立记录（物理实体已不存在）");
                log.info("同步：清理模拟器 #{} 的孤立数据库记录", dbInst.getInstanceIndex());
            }
        }

        result.put("success", true);
        result.put("physicalCount", physicalIndices.size());
        result.put("dbCount", instanceRepository.findByMerchantIdAndUserId(merchantId, userId).size());
        result.put("actions", actions);
        result.put("message", actions.isEmpty() ? "数据一致，无需同步" : "同步完成: " + String.join("; ", actions));
        return result;
    }

    /**
     * 异步任务中更新实例状态（安装 Discord 后）
     */
    private void updateInstanceAfterInstall(int index, boolean discordInstalled) {
        try {
            List<EmuInstance> instances = instanceRepository.findAll();
            for (EmuInstance inst : instances) {
                if (inst.getInstanceIndex() == index) {
                    inst.setDiscordInstalled(discordInstalled);
                    inst.setStatus(EmuInstance.EmuStatus.RUNNING);
                    inst.setUpdatedAt(Instant.now());
                    instanceRepository.save(inst);
                    log.info("已更新模拟器 #{} 的安装状态: {}", index, discordInstalled);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("更新模拟器 #{} 安装状态失败: {}", index, e.getMessage());
        }
    }

    /**
     * 异步任务中更新 Discord 首页状态
     */
    private void updateDiscordOnHome(int index, boolean onHome) {
        try {
            List<EmuInstance> instances = instanceRepository.findAll();
            for (EmuInstance inst : instances) {
                if (inst.getInstanceIndex() == index) {
                    inst.setDiscordOnHome(onHome);
                    inst.setUpdatedAt(Instant.now());
                    instanceRepository.save(inst);
                    log.info("已更新模拟器 #{} 的 Discord 首页状态: {}", index, onHome);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("更新模拟器 #{} Discord 首页状态失败: {}", index, e.getMessage());
        }
    }

    /**
     * 计算健康模拟器数量（过滤损坏实例）
     */
    private int countHealthy(List<Map<String, Object>> emulators) {
        if (emulators == null || emulators.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> emu : emulators) {
            String status = (String) emu.get("status");
            Boolean damaged = (Boolean) emu.get("damaged");
            if (!"DAMAGED".equals(status) && !Boolean.TRUE.equals(damaged)) {
                count++;
            }
        }
        return count;
    }
}
