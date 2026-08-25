package com.discordadmin.service;

import com.discordadmin.entity.AgentRegistration;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.EmuInstance;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.EmuInstanceRepository;
import com.discordadmin.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmuInstanceService {

    @Lazy
    @Autowired
    private EmuInstanceService self;

    private final EmuInstanceRepository instanceRepository;
    private final MumuClientService mumuClientService;
    private final CloudWebSocketService webSocketService;
    private final ApkManagementService apkManagementService;
    private final DiscordAccountRepository discordAccountRepository;
    private final DiscordService discordService;
    private final com.discordadmin.repository.GuildMemberRepository guildMemberRepository;
    private final com.discordadmin.repository.GifFavoriteRepository gifFavoriteRepository;
    private final jakarta.persistence.EntityManager entityManager;
    private final EmulatorService emulatorService;

    @Value("${emulator.local-mode:false}")
    private boolean localMode;

    public EmuInstanceService(EmuInstanceRepository instanceRepository, 
                               MumuClientService mumuClientService,
                               CloudWebSocketService webSocketService,
                               ApkManagementService apkManagementService,
                               DiscordAccountRepository discordAccountRepository,
                               DiscordService discordService,
                               com.discordadmin.repository.GuildMemberRepository guildMemberRepository,
                               com.discordadmin.repository.GifFavoriteRepository gifFavoriteRepository,
                               jakarta.persistence.EntityManager entityManager,
                               EmulatorService emulatorService) {
        this.instanceRepository = instanceRepository;
        this.mumuClientService = mumuClientService;
        this.webSocketService = webSocketService;
        this.apkManagementService = apkManagementService;
        this.discordAccountRepository = discordAccountRepository;
        this.discordService = discordService;
        this.guildMemberRepository = guildMemberRepository;
        this.gifFavoriteRepository = gifFavoriteRepository;
        this.entityManager = entityManager;
        this.emulatorService = emulatorService;
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
        
        // 自动同步：确保数据库与物理模拟器完全一致
        if (!localMode) {
            try {
                List<Map<String, Object>> physicalEmulators = webSocketService.getEmulatorsFromAgent(userId);
                if (physicalEmulators != null) {
                    syncDatabaseWithPhysical(merchantId, userId, instances, physicalEmulators);
                    // 重新查询
                    instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
                }
            } catch (Exception e) {
                log.warn("自动同步物理模拟器失败: {}", e.getMessage());
            }
        }
        
        // 使用原生查询获取 auto_running 字段值，解决 JPA 映射问题
        Map<Long, Boolean> autoRunningMap = new HashMap<>();
        if (!instances.isEmpty()) {
            List<Long> instanceIds = instances.stream().map(EmuInstance::getId).collect(Collectors.toList());
            String sql = "SELECT id, auto_running FROM emu_instances WHERE id IN (:ids)";
            List<?> results = entityManager.createNativeQuery(sql)
                .setParameter("ids", instanceIds)
                .getResultList();
            for (Object row : results) {
                Object[] parts = (Object[]) row;
                Long id = ((Number) parts[0]).longValue();
                // MySQL TINYINT(1) 在某些驱动下返回 Boolean，需要兼容处理
                Boolean autoRunning;
                if (parts[1] instanceof Boolean) {
                    autoRunning = (Boolean) parts[1];
                } else if (parts[1] instanceof Number) {
                    autoRunning = ((Number) parts[1]).intValue() == 1;
                } else {
                    autoRunning = false;
                }
                autoRunningMap.put(id, autoRunning);
            }
            log.info("原生查询 auto_running 结果: {}", autoRunningMap.size());
        }
        
        // 从 MumuManager 获取最新物理状态并合并
        List<Map<String, Object>> physicalList = null;
        try {
            physicalList = localMode ? mumuClientService.getAllEmulatorsWithError() : webSocketService.getEmulatorsFromAgent(userId);
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
                        // Discord登录状态：物理数据为 true 时更新，DB 已有 true 时不覆盖
                        Object discordLoggedIn = phys.get("discordLoggedIn");
                        Boolean dbLoggedIn = (Boolean) emu.get("discordLoggedIn");
                        if (discordLoggedIn != null) {
                            boolean physLoggedIn = Boolean.TRUE.equals(discordLoggedIn);
                            if (physLoggedIn) {
                                // 物理检测为 true → 强制更新
                                emu.put("discordLoggedIn", true);
                            } else if (dbLoggedIn != null && dbLoggedIn) {
                                // DB 已经是 true → 保持不变
                                emu.put("discordLoggedIn", true);
                            }
                            // DB 为 null 且物理为 false → 保持 null（等检测任务来确认）
                        } else if (dbLoggedIn != null) {
                            emu.put("discordLoggedIn", dbLoggedIn);
                        }
                        // Discord账号：优先使用数据库中的值（已导入账号或检测到的用户名）
                        Object physDiscordAccount = phys.get("discordAccount");
                        Object dbDiscordAccount = emu.get("discordAccount");
                        Object dbDiscordAccountName = emu.get("discordAccountName");
                        if (physDiscordAccount != null && !physDiscordAccount.toString().isEmpty()
                                && !"未登录".equals(physDiscordAccount.toString())
                                && (dbDiscordAccount == null || dbDiscordAccount.toString().isEmpty())
                                && (dbDiscordAccountName == null || dbDiscordAccountName.toString().isEmpty())) {
                            // 数据库没有值，物理检测到账号 → 补充显示
                            emu.put("discordAccount", physDiscordAccount.toString());
                        } else if (dbDiscordAccount != null && !dbDiscordAccount.toString().isEmpty()) {
                            // 数据库有账号 → 保持不变（优先）
                            emu.put("discordAccount", dbDiscordAccount.toString());
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
                            int memMb = ((Number) memoryMB).intValue();
                            emu.put("memoryGb", memMb / 1024);
                        }
                        // 合并自动加好友相关字段（物理层有有效值时才覆盖，避免默认值0/null覆盖DB）
                        Object physAutoRunning = phys.get("autoRunning");
                        if (physAutoRunning != null) {
                            boolean pAR = Boolean.TRUE.equals(physAutoRunning);
                            Boolean dbAR = (Boolean) emu.get("autoRunning");
                            // 只有物理层为 true 时才升级，否则保留 DB 原值
                            if (pAR) {
                                emu.put("autoRunning", true);
                            } else if (dbAR != null) {
                                emu.put("autoRunning", dbAR);
                            }
                        }
                        Object physAddedCount = phys.get("addedCount");
                        if (physAddedCount instanceof Number && ((Number) physAddedCount).intValue() > 0) {
                            emu.put("addedCount", ((Number) physAddedCount).intValue());
                        }
                        Object physNextAddAt = phys.get("nextAddAt");
                        if (physNextAddAt instanceof Number && ((Number) physNextAddAt).longValue() > 0) {
                            emu.put("nextAddAt", ((Number) physNextAddAt).longValue());
                        }
                        Object physAutoLastResult = phys.get("autoLastResult");
                        if (physAutoLastResult != null && !physAutoLastResult.toString().isEmpty()) {
                            emu.put("autoLastResult", physAutoLastResult.toString());
                        }
                        break;
                    }
                }
                
                // 调试日志：输出每个模拟器的合并结果
                log.info("模拟器 #{} 合并结果: discordLoggedIn={}, discordAccount={}, discordInstalled={}",
                        emu.get("index"), emu.get("discordLoggedIn"), 
                        emu.get("discordAccount"), emu.get("discordInstalled"));
            }
        }
        
        // 为每个模拟器添加按模拟器维度的好友添加统计
        for (Map<String, Object> emu : result) {
            int emuIndex = ((Number) emu.get("index")).intValue();
            try {
                int assignedCount = (int) guildMemberRepository.countAssigningByEmulatorIndex(emuIndex);
                int successCount = (int) guildMemberRepository.countSuccessByEmulatorIndex(emuIndex);
                int failedCount = (int) guildMemberRepository.countFailedByEmulatorIndex(emuIndex);
                emu.put("assignedCount", assignedCount);
                emu.put("successCount", successCount);
                emu.put("failedCount", failedCount);
                // addedCount 保留历史字段，显示本模拟器已处理的总数
                emu.put("addedCount", assignedCount + successCount + failedCount);
            } catch (Exception e) {
                emu.put("assignedCount", 0);
                emu.put("successCount", 0);
                emu.put("failedCount", 0);
            }
        }
        
        // 使用原生查询的 autoRunningMap 覆盖结果，确保正确返回 autoRunning 状态
        if (!autoRunningMap.isEmpty()) {
            for (Map<String, Object> emu : result) {
                Long emuId = ((Number) emu.get("id")).longValue();
                if (autoRunningMap.containsKey(emuId)) {
                    emu.put("autoRunning", autoRunningMap.get(emuId));
                }
            }
            log.info("已使用 autoRunningMap 覆盖 {} 个模拟器的 autoRunning 状态", autoRunningMap.size());
        }
        
        // 为每个模拟器添加 Agent 信息
        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        if (!onlineAgents.isEmpty()) {
            // 如果只有一个 Agent，直接关联
            AgentRegistration defaultAgent = onlineAgents.get(0);
            String agentLabel = defaultAgent.getOs() != null ? 
                (defaultAgent.getOs().equals("darwin") ? "macOS" : 
                 defaultAgent.getOs().equals("win32") ? "Windows" : 
                 defaultAgent.getOs().equals("linux") ? "Linux" : defaultAgent.getOs()) : "Agent";
            
            for (Map<String, Object> emu : result) {
                emu.put("agentDeviceId", defaultAgent.getDeviceId());
                emu.put("agentUserId", defaultAgent.getUserId());
                emu.put("agentLabel", agentLabel);
                emu.put("agentOs", defaultAgent.getOs());
            }
            log.info("已为 {} 个模拟器关联 Agent 信息 (deviceId={})", result.size(), defaultAgent.getDeviceId());
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
     * @param mode 'set'(默认，设置总数量，会删除超出的旧记录) 或 'add'(追加模式，在现有基础上新增 count 台)
     */
    @Transactional
    public List<Map<String, Object>> setInstanceCount(int count, int cpuCores, int memoryGb, String mode, String deviceId) {
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

        // 2. 记录当前数据库记录数量
        List<EmuInstance> existingInstances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        int existingCount = existingInstances.size();
        log.info("当前数据库已有 {} 条模拟器记录", existingCount);

        // 计算目标总数
        int targetTotal;
        boolean addMode = "add".equalsIgnoreCase(mode);
        if (addMode) {
            if (count <= 0) count = 1;
            targetTotal = existingCount + count;
        } else {
            targetTotal = count;
        }
        log.info("模拟器模式={}, 新增数量={}, 现有数量={}, 目标总数={}", addMode ? "ADD" : "SET", count, existingCount, targetTotal);

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

        if (localMode && localReachable) {
            log.info("使用本地模式创建模拟器（localMode=true, localReachable=true, targetTotal={})", targetTotal);
            try {
                physicalList = mumuClientService.setEmulatorCount(targetTotal, cpuCores, memoryGb);
                log.info("MumuManager 返回: {} 个模拟器", physicalList != null ? physicalList.size() : 0);

                int healthyInResult = countHealthy(physicalList);
                log.info("健康模拟器数量: 需要 {}, 实际 {}", targetTotal, healthyInResult);

                if (healthyInResult >= targetTotal) {
                    physicalSuccess = true;
                    createMethod = "local";
                } else {
                    log.error("MumuManager 返回的健康模拟器数量不足: 需要 {}, 实际健康 {}", targetTotal, healthyInResult);
                    throw new RuntimeException("MumuManager 创建模拟器失败：健康实例数量不足 (需要 " + targetTotal + ", 实际 " + healthyInResult + ")");
                }
            } catch (Exception e) {
                log.error("本地模式创建模拟器失败: {}", e.getMessage());
                throw new RuntimeException("创建物理模拟器失败: " + e.getMessage());
            }
        } else if (agentOnline) {
            log.info("使用 Agent 模式创建模拟器");
            List<AgentRegistration> onlineAgents = webSocketService.getAllOnlineAgents();
            List<AgentRegistration> targetAgents;
            if (deviceId != null && !deviceId.isEmpty()) {
                targetAgents = onlineAgents.stream()
                    .filter(a -> deviceId.equals(a.getDeviceId()))
                    .collect(Collectors.toList());
                log.info("指定 Agent: {}, 匹配 {} 个", deviceId, targetAgents.size());
            } else {
                targetAgents = onlineAgents;
            }
            for (AgentRegistration agent : targetAgents) {
                try {
                    Map<String, Object> params = new HashMap<>();
                    if (addMode) {
                        // 追加模式：传递新增数量，告诉 Agent 直接创建这么多
                        params.put("count", count);
                        params.put("addMode", true);
                    } else {
                        // 设置模式：传递目标总数
                        params.put("count", targetTotal);
                        params.put("addMode", false);
                    }
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
                        } else if (data instanceof Map) {
                            // Agent 返回的是 { success, message, results: [...] } 结构
                            Map<?, ?> dataMap = (Map<?, ?>) data;
                            Object results = dataMap.get("results");
                            if (results instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> agentList = (List<Map<String, Object>>) results;
                                if (agentList != null && !agentList.isEmpty()) {
                                    physicalList = agentList;
                                    log.info("Agent 返回 {} 个模拟器 (从results)", physicalList.size());
                                }
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

        // 5. 验证物理实体
        int healthyAfterCreate = 0;
        try {
            List<Map<String, Object>> finalList;
            
            if ("agent".equals(createMethod)) {
                // Agent 模式：通过 Agent 获取模拟器列表
                log.info("使用 Agent 模式验证物理实体");
                List<AgentRegistration> onlineAgents = webSocketService.getAllOnlineAgents();
                finalList = new ArrayList<>();
                List<AgentRegistration> verifyAgents;
                if (deviceId != null && !deviceId.isEmpty()) {
                    verifyAgents = onlineAgents.stream()
                        .filter(a -> deviceId.equals(a.getDeviceId()))
                        .collect(Collectors.toList());
                } else {
                    verifyAgents = onlineAgents;
                }
                for (AgentRegistration agent : verifyAgents) {
                    try {
                        CompletableFuture<Map<String, Object>> future =
                            webSocketService.sendCommandAndWait(agent.getDeviceId(), "GET_EMULATORS", new HashMap<>());
                        Map<String, Object> result = future.get(30, TimeUnit.SECONDS);
                        log.info("Agent {} GET_EMULATORS 结果: status={}", agent.getDeviceId(), result.get("status"));
                        
                        if ("SUCCESS".equals(result.get("status"))) {
                            // handleTaskResult 会把 data 展开到 result 顶层（putAll）
                            // 所以需要同时兼容两种格式
                            List<Map<String, Object>> agentEmulators = null;
                            
                            // 方式1: emulators 已在顶层
                            Object emulatorsTop = result.get("emulators");
                            if (emulatorsTop instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> list = (List<Map<String, Object>>) emulatorsTop;
                                agentEmulators = list;
                            }
                            
                            // 方式2: emulators 在 data 子对象中
                            if (agentEmulators == null) {
                                Object data = result.get("data");
                                if (data instanceof Map) {
                                    Object emulatorsNested = ((Map<?, ?>) data).get("emulators");
                                    if (emulatorsNested instanceof List) {
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> list = (List<Map<String, Object>>) emulatorsNested;
                                        agentEmulators = list;
                                    }
                                }
                            }
                            
                            if (agentEmulators != null) {
                                finalList.addAll(agentEmulators);
                                log.info("Agent 返回 {} 个模拟器", agentEmulators.size());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Agent {} 获取模拟器列表失败: {}", agent.getDeviceId(), e.getMessage());
                    }
                }
                
                if (finalList.isEmpty()) {
                    log.warn("Agent 模式获取模拟器列表为空，但创建命令已发送成功，跳过验证");
                    // Agent 模式下，如果创建命令成功发送，即使获取列表为空也不报错
                    // 因为模拟器可能需要一些时间来启动
                    healthyAfterCreate = targetTotal; // 假设全部创建成功
                }
            } else {
                // 本地模式：使用 MumuManager API
                finalList = mumuClientService.getAllEmulatorsWithError();
                if (finalList == null || finalList.isEmpty()) {
                    throw new RuntimeException("物理模拟器创建失败：MumuManager 返回空列表，物理实例可能未真正创建");
                }
            }
            
            if (!finalList.isEmpty()) {
                healthyAfterCreate = countHealthy(finalList);
                log.info("物理模拟器验证: 创建方式={}, 总数={}, 健康={}, 需要目标总数={}", 
                        createMethod, finalList.size(), healthyAfterCreate, targetTotal);
                
                if ("local".equals(createMethod) && healthyAfterCreate < targetTotal) {
                    throw new RuntimeException(String.format("物理模拟器数量验证失败: 需要 %d 个健康实例，实际只有 %d 个。物理创建可能未成功，请检查 MumuManager 日志", targetTotal, healthyAfterCreate));
                }
                
                physicalList = finalList;
                log.info("物理模拟器验证通过，实际存在 {} 个健康实体", healthyAfterCreate);
            } else {
                log.info("Agent 模式下跳过验证，模拟器列表为空但创建命令已发送");
                healthyAfterCreate = targetTotal;
            }
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

        // 7. 写数据库记录
        // - 追加模式(add)：保留已有记录，仅为缺少的物理实例建立 DB 记录，保留原有字段
        // - 设置模式(set)：删除旧记录、按最新物理列表重建
        List<Map<String, Object>> instances;
        if (addMode) {
            instances = syncInstanceDatabaseIncremental(merchantId, userId, existingInstances, targetTotal,
                    cpuCores, memoryGb, physicalList);
        } else {
            instances = syncInstanceDatabase(merchantId, userId, targetTotal, cpuCores, memoryGb, physicalList);
        }

        // 8. 如果 APK 存在，异步启动【新增的】模拟器并自动安装 Discord
        if (apkDownloaded) {
            CompletableFuture.runAsync(() -> {
                try {
                    log.info("开始自动启动新创建的模拟器并安装 Discord...");
                    // 追加模式只处理 index > existingCount 的新记录
                    int startIndex = addMode ? existingCount : 0;
                    for (Map<String, Object> inst : instances) {
                        int index = ((Number) inst.get("index")).intValue();
                        if (index <= startIndex) continue; // 跳过已有记录
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
     * 追加模式下增量同步：保留已有 DB 记录，只为新增的物理实例创建新 DB 记录；
     * 已存在的记录（按 instanceIndex 匹配）完全保留原有字段（autoRunning/discordAccountNumber 等）。
     */
    private List<Map<String, Object>> syncInstanceDatabaseIncremental(Long merchantId, String userId,
                                                                     List<EmuInstance> existingInstances,
                                                                     int targetTotal,
                                                                     int cpuCores, int memoryGb,
                                                                     List<Map<String, Object>> physicalList) {
        // 已有 instanceIndex 集合
        Set<Integer> existingIndexSet = existingInstances.stream()
                .map(EmuInstance::getInstanceIndex)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 收集物理实例中健康的
        List<Map<String, Object>> healthyList = physicalList.stream()
                .filter(e -> !"DAMAGED".equals(e.get("status")) && !Boolean.TRUE.equals(e.get("damaged")))
                .sorted(Comparator.comparingInt(e -> ((Number) e.get("index")).intValue()))
                .collect(Collectors.toList());

        log.info("追加模式: 现有DB {} 条, 健康物理 {} 条, 目标总数 {}", existingIndexSet.size(), healthyList.size(), targetTotal);

        // 为缺失的物理实例创建 DB 记录
        int created = 0;
        int newTarget = Math.min(targetTotal, healthyList.size());
        for (int i = 0; i < healthyList.size() && created < newTarget; i++) {
            Map<String, Object> mumuEmu = healthyList.get(i);
            int mumuIndex = ((Number) mumuEmu.get("index")).intValue();
            int dbIndex = mumuIndex + 1;

            if (existingIndexSet.contains(dbIndex)) {
                continue; // 已有记录，保留，跳过
            }

            String mumuName = (String) mumuEmu.get("name");
            EmuInstance instance = new EmuInstance();
            instance.setMerchantId(merchantId);
            instance.setUserId(userId);
            instance.setName(mumuName != null ? mumuName : "V" + String.format("%03d", dbIndex));
            instance.setInstanceIndex(dbIndex);
            instance.setStatus(mapMumuStatus((String) mumuEmu.get("status")));
            instance.setCpuCores(cpuCores);
            instance.setMemoryGb(memoryGb);
            instance.setResolution("720x1280");
            instance.setAdbPort(mumuEmu.get("adbPort") != null ? ((Number) mumuEmu.get("adbPort")).intValue() : null);
            // 保持默认"未安装/未登录/不自动加好友"，由用户手动配置
            instance.setDiscordInstalled(false);
            instance.setDiscordLoggedIn(false);
            instance.setAutoRunning(false);
            instance.setAddedCount(0);
            instance.setCreatedAt(Instant.now());
            instance.setUpdatedAt(Instant.now());
            instanceRepository.save(instance);
            created++;
            log.info("追加创建DB记录: {} (DB index={})", instance.getName(), dbIndex);
        }

        log.info("追加完成: 新增 {} 条, 保留 {} 条", created, existingIndexSet.size());
        return instanceRepository.findByMerchantIdAndUserId(merchantId, userId).stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
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
    /**
     * 同步数据库与物理模拟器：确保一一对应
     * - 物理存在但数据库不存在 -> 创建
     * - 物理不存在但数据库存在 -> 删除
     * - 配置不一致 -> 更新
     */
    private void syncDatabaseWithPhysical(Long merchantId, String userId,
                                           List<EmuInstance> existingInstances,
                                           List<Map<String, Object>> physicalEmulators) {
        log.info("开始同步数据库: 数据库现有 {} 条, 物理 {} 条", existingInstances.size(), physicalEmulators.size());
        
        // 构建物理模拟器索引映射 (key=物理index, value=物理数据)
        Map<Integer, Map<String, Object>> physicalMap = new HashMap<>();
        for (Map<String, Object> phys : physicalEmulators) {
            Object idx = phys.get("index");
            if (idx instanceof Number) {
                physicalMap.put(((Number) idx).intValue(), phys);
            }
        }
        
        // 构建数据库记录索引映射 (key=数据库index, value=数据库记录)
        Map<Integer, EmuInstance> dbMap = new HashMap<>();
        for (EmuInstance inst : existingInstances) {
            if (inst.getInstanceIndex() != null) {
                dbMap.put(inst.getInstanceIndex(), inst);
            }
        }
        
        int created = 0, deleted = 0, updated = 0;
        
        // 1. 处理物理模拟器：创建或更新
        for (Map<String, Object> phys : physicalEmulators) {
            Object idxObj = phys.get("index");
            if (!(idxObj instanceof Number)) continue;
            int physIndex = ((Number) idxObj).intValue();
            int dbIndex = physIndex + 1;
            
            String physName = (String) phys.get("name");
            String physStatusStr = phys.get("status") != null ? phys.get("status").toString() : "STOPPED";
            EmuInstance.EmuStatus physStatus = mapMumuStatus(physStatusStr);
            int physCpu = phys.get("cpuCount") != null ? ((Number) phys.get("cpuCount")).intValue() : 1;
            int physMem = phys.get("memoryMB") != null ? ((Number) phys.get("memoryMB")).intValue() / 1024 : 1;
            Integer physAdbPort = phys.get("adbPort") != null ? ((Number) phys.get("adbPort")).intValue() : null;
            
            if (dbMap.containsKey(dbIndex)) {
                // 更新已有记录
                EmuInstance existing = dbMap.get(dbIndex);
                boolean needUpdate = false;
                
                if (physName != null && !physName.equals(existing.getName())) {
                    existing.setName(physName);
                    needUpdate = true;
                }
                if (existing.getStatus() != physStatus) {
                    existing.setStatus(physStatus);
                    needUpdate = true;
                }
                if (physCpu != existing.getCpuCores()) {
                    existing.setCpuCores(physCpu);
                    needUpdate = true;
                }
                if (physMem != existing.getMemoryGb()) {
                    existing.setMemoryGb(physMem);
                    needUpdate = true;
                }
                if (physAdbPort != null && !physAdbPort.equals(existing.getAdbPort())) {
                    existing.setAdbPort(physAdbPort);
                    needUpdate = true;
                }
                
                if (needUpdate) {
                    existing.setUpdatedAt(Instant.now());
                    instanceRepository.save(existing);
                    updated++;
                    log.info("同步更新: {} (index={})", existing.getName(), dbIndex);
                }
            } else {
                // 创建新记录
                EmuInstance inst = new EmuInstance();
                inst.setMerchantId(merchantId);
                inst.setUserId(userId);
                inst.setName(physName != null ? physName : "V" + String.format("%03d", dbIndex));
                inst.setInstanceIndex(dbIndex);
                inst.setStatus(physStatus);
                inst.setCpuCores(physCpu);
                inst.setMemoryGb(physMem);
                inst.setResolution("720x1280");
                inst.setAdbPort(physAdbPort);
                inst.setDiscordInstalled(false);
                inst.setDiscordLoggedIn(false);
                inst.setAutoRunning(false);
                inst.setAddedCount(0);
                inst.setCreatedAt(Instant.now());
                inst.setUpdatedAt(Instant.now());
                instanceRepository.save(inst);
                created++;
                log.info("同步创建: {} (index={})", inst.getName(), dbIndex);
            }
        }
        
        // 2. 处理多余的数据库记录（物理不存在）
        for (Map.Entry<Integer, EmuInstance> entry : dbMap.entrySet()) {
            int dbIndex = entry.getKey();
            int physIndex = dbIndex - 1;
            if (!physicalMap.containsKey(physIndex)) {
                EmuInstance toDelete = entry.getValue();
                instanceRepository.delete(toDelete);
                deleted++;
                log.info("同步删除: {} (dbIndex={}, 物理index={} 不存在)", toDelete.getName(), dbIndex, physIndex);
            }
        }
        
        log.info("同步完成: 创建 {} 条, 更新 {} 条, 删除 {} 条", created, updated, deleted);
    }

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
        
        log.info("startInstance 开始: index={}, merchantId={}, userId={}", index, merchantId, userId);

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在 (merchantId=%d, userId=%s)", index, merchantId, userId)));

        // 检查是否有在线 Agent
        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        boolean useAgent = !onlineAgents.isEmpty();
        log.info("startInstance: 在线Agent数量={}, useAgent={}", onlineAgents.size(), useAgent);

        // 如果没有在线 Agent，检查本地模拟器是否存在
        if (!useAgent && !mumuClientService.emulatorExists(index)) {
            throw new RuntimeException(String.format("物理模拟器 #%d 不存在，无法启动。请先创建模拟器。", index));
        }
        // 如果有在线 Agent，信任 Agent 端的模拟器状态，跳过本地检查
        if (useAgent) {
            log.info("Agent 模式: 通过 Agent {} 启动模拟器 #{}", onlineAgents.get(0).getDeviceId(), index);
        }

        // 异步启动，不等待完成
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 启动模拟器（优先通过 Agent，否则本地）
                Map<String, Object> startResult;
                if (useAgent) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("index", index - 1); // 转为 Mumu 0-based 索引
                    AgentRegistration agent = onlineAgents.get(0);
                    startResult = webSocketService.sendCommandAndWait(agent.getDeviceId(), "START_EMULATOR", params)
                        .get(60, TimeUnit.SECONDS);
                    log.info("通过 Agent {} 启动模拟器 #{}", agent.getDeviceId(), index);
                } else {
                    startResult = mumuClientService.startEmulator(index);
                }
                log.info("模拟器 #{} 启动指令执行完成, discordInstalled={}", index, startResult.get("discordInstalled"));

                // 2. 等待模拟器完全启动（8秒）
                Thread.sleep(8000);

                // 3. 从启动结果或物理数据判断是否安装了 Discord
                boolean discordInstalled = Boolean.TRUE.equals(startResult.get("discordInstalled"));
                if (!discordInstalled) {
                    // 从物理模拟器数据再检查一次（Agent模式通过Agent获取，本地模式通过本地获取）
                    List<Map<String, Object>> physList;
                    if (useAgent) {
                        physList = webSocketService.getEmulatorsFromAgent(userId);
                    } else {
                        physList = mumuClientService.getAllEmulatorsWithError();
                    }
                    if (physList != null) {
                        int mumuIdx = index - 1;
                        for (Map<String, Object> phys : physList) {
                            Object pIdx = phys.get("index");
                            if (pIdx instanceof Number && ((Number) pIdx).intValue() == mumuIdx) {
                                Object instObj = phys.get("discordInstalled");
                                discordInstalled = Boolean.TRUE.equals(instObj) ||
                                        "true".equalsIgnoreCase(String.valueOf(instObj));
                                break;
                            }
                        }
                    }
                }

                // 4. 先更新安装状态到数据库
                EmuInstance inst = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(
                        resolveMerchantId(), resolveUserId(), index).orElse(null);
                if (inst != null) {
                    inst.setDiscordInstalled(discordInstalled);
                    inst.setUpdatedAt(Instant.now());
                    instanceRepository.save(inst);
                }

                // 5. 如果已安装 Discord，打开并等待加载后检查登录状态
                if (discordInstalled) {
                    log.info("模拟器 #{} Discord 已安装，自动打开...", index);
                    try {
                        mumuClientService.launchDiscord(index);
                        log.info("模拟器 #{} Discord 已自动打开", index);
                        updateDiscordOnHome(index, true);
                        // 等待 Discord 加载完成
                        Thread.sleep(5000);
                        // 检查并更新所有 Discord 状态
                        checkAndUpdateDiscordStatus(index);
                    } catch (Exception e) {
                        log.warn("模拟器 #{} 自动打开 Discord 失败: {}", index, e.getMessage());
                        // 即使打开失败也检查一次状态
                        checkAndUpdateDiscordStatus(index);
                    }
                } else {
                    // 未安装 Discord，只更新安装状态
                    log.info("模拟器 #{} 未安装 Discord", index);
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

        log.info("stopInstance 开始: index={}, merchantId={}, userId={}", index, merchantId, userId);

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        // 检查是否有在线 Agent
        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        boolean useAgent = !onlineAgents.isEmpty();

        // 如果没有在线 Agent，检查本地模拟器是否存在
        if (!useAgent && !mumuClientService.emulatorExists(index)) {
            throw new RuntimeException(String.format("物理模拟器 #%d 不存在，无法停止", index));
        }

        // 异步停止，不等待完成
        CompletableFuture.runAsync(() -> {
            try {
                if (useAgent) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("index", index - 1);
                    AgentRegistration agent = onlineAgents.get(0);
                    webSocketService.sendCommandAndWait(agent.getDeviceId(), "STOP_EMULATOR", params)
                        .get(60, TimeUnit.SECONDS);
                    log.info("通过 Agent {} 停止模拟器 #{}", agent.getDeviceId(), index);
                } else {
                    mumuClientService.stopEmulator(index);
                }
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

        log.info("restartInstance 开始: index={}, merchantId={}, userId={}", index, merchantId, userId);

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        // 检查是否有在线 Agent
        List<AgentRegistration> onlineAgents = webSocketService.getOnlineAgentsByUserId(userId);
        boolean useAgent = !onlineAgents.isEmpty();

        // 如果没有在线 Agent，检查本地模拟器是否存在
        if (!useAgent && !mumuClientService.emulatorExists(index)) {
            throw new RuntimeException(String.format("物理模拟器 #%d 不存在，无法重启", index));
        }

        // 异步重启，不等待完成
        CompletableFuture.runAsync(() -> {
            try {
                if (useAgent) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("index", index - 1);
                    AgentRegistration agent = onlineAgents.get(0);
                    // 先停止再启动
                    webSocketService.sendCommandAndWait(agent.getDeviceId(), "STOP_EMULATOR", params)
                        .get(30, TimeUnit.SECONDS);
                    Thread.sleep(3000);
                    webSocketService.sendCommandAndWait(agent.getDeviceId(), "START_EMULATOR", params)
                        .get(60, TimeUnit.SECONDS);
                    log.info("通过 Agent {} 重启模拟器 #{}", agent.getDeviceId(), index);
                } else {
                    mumuClientService.restartEmulator(index);
                }
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
     * 启动所有模拟器 — 异步执行，立即返回
     * 串行启动每台模拟器，避免 MuMu 并发启动导致进程异常退出
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
            // 获取目标模拟器列表
            List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
            List<Integer> targetIndexes = instances.stream()
                .map(EmuInstance::getInstanceIndex)
                .sorted()
                .collect(Collectors.toList());

            // 异步串行启动，不阻塞前端
            CompletableFuture.runAsync(() -> {
                log.info("开始异步批量启动 {} 台模拟器", targetIndexes.size());
                int successCount = 0;
                int failCount = 0;
                Random random = new Random();

                for (int i = 0; i < targetIndexes.size(); i++) {
                    int idx = targetIndexes.get(i);
                    log.info("批量启动进度: {}/{} (index={})", i + 1, targetIndexes.size(), idx);

                    // 启动前随机等待 0.5-2 秒
                    try { Thread.sleep(500 + random.nextInt(1500)); } catch (InterruptedException ignored) {}

                    try {
                        Map<String, Object> startResult = mumuClientService.startEmulator(idx);
                        boolean success = "RUNNING".equals(startResult.get("status"));

                        // 更新数据库状态
                        EmuInstance inst = instanceRepository
                            .findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, idx).orElse(null);
                        if (inst != null) {
                            if (success) {
                                inst.setStatus(EmuInstance.EmuStatus.RUNNING);
                                inst.setLastError(null);
                                successCount++;
                                log.info("模拟器 #{} 启动成功", idx);

                                // 启动 Discord
                                boolean discordInstalled = Boolean.TRUE.equals(startResult.get("discordInstalled"));
                                inst.setDiscordInstalled(discordInstalled);
                                if (discordInstalled) {
                                    try {
                                        mumuClientService.launchDiscord(idx);
                                        log.info("模拟器 #{} Discord 已自动打开", idx);
                                    } catch (Exception de) {
                                        log.warn("模拟器 #{} Discord 打开失败: {}", idx, de.getMessage());
                                    }
                                }
                            } else {
                                inst.setStatus(EmuInstance.EmuStatus.STOPPED);
                                inst.setLastError((String) startResult.getOrDefault("lastError", "启动失败"));
                                failCount++;
                                log.warn("模拟器 #{} 启动失败: {}", idx, inst.getLastError());
                            }
                            inst.setUpdatedAt(Instant.now());
                            instanceRepository.save(inst);
                        }

                        // 启动下一台前随机等待 1-5 秒
                        if (i < targetIndexes.size() - 1) {
                            int delay = 1000 + random.nextInt(4000);
                            log.info("随机等待 {}ms 后启动下一台...", delay);
                            try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                        }
                    } catch (Exception e) {
                        log.error("模拟器 #{} 启动异常", idx, e);
                        EmuInstance inst = instanceRepository
                            .findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, idx).orElse(null);
                        if (inst != null) {
                            inst.setStatus(EmuInstance.EmuStatus.ERROR);
                            inst.setLastError(e.getMessage());
                            inst.setUpdatedAt(Instant.now());
                            instanceRepository.save(inst);
                        }
                        failCount++;
                    }
                }
                log.info("批量启动完成: 成功={}, 失败={}", successCount, failCount);
            });

            // 先给前端一个响应（清除错误状态）
            List<EmuInstance> initInstances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
            for (EmuInstance inst : initInstances) {
                inst.setLastError(null);
                inst.setUpdatedAt(Instant.now());
                instanceRepository.save(inst);
            }
        } else {
            throw new RuntimeException("本地 Agent 未上线，无法启动模拟器。请启动 mumu-agent。");
        }

        List<EmuInstance> instances = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
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
    public Map<String, Object> startAutoAdd(int index, Long serverId) {
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

        // 如果前端传了serverId，保存到实例中，供AutoAddService使用
        if (serverId != null) {
            instance.setGuildServerId(serverId);
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
     * 更新模拟器绑定的Discord账号编号（1=V001对应编号1...）
     * @param number 传 null 表示清除显式绑定，回退到默认 instanceIndex 对应
     */
    @Transactional
    public Map<String, Object> updateDiscordAccountNumber(int index, Integer number) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException("模拟器不存在"));
        if (number != null && (number < 1 || number > 999999)) {
            throw new RuntimeException("账号编号范围无效 (1~999999)");
        }
        instance.setDiscordAccountNumber(number);
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
     * 注意：不在此方法上使用 @Transactional，因为物理操作耗时较长会导致连接泄漏
     */
    public Map<String, Object> deleteInstance(int index) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        // 添加权限校验日志
        log.info("删除模拟器请求: merchantId={}, userId={}, index={}, localMode={}", merchantId, userId, index, localMode);

        EmuInstance instance = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, index)
            .orElseThrow(() -> new RuntimeException(String.format("模拟器 #%d 不存在", index)));

        log.info("开始同步删除模拟器 #{} (name={}, merchantId={}, userId={})", index, instance.getName(), merchantId, userId);
        Map<String, Object> result = new HashMap<>();
        List<String> actions = new ArrayList<>();

        try {
            // 1. 获取物理模拟器的 0-based index
            int mumuTargetIndex = index - 1; // 数据库是1-based，物理是0-based
            boolean physicalExists;

            if (localMode) {
                // 本地模式：直接查找
                mumuTargetIndex = findMumuIndexByNameOrIndex(instance.getName(), index);
                physicalExists = mumuTargetIndex >= 0;
            } else {
                // Agent 模式：通过 Agent 检查是否存在
                physicalExists = webSocketService.emulatorExistsOnAgent(userId, mumuTargetIndex);
            }

            log.info("模拟器 #{} 物理状态: {}, mumuTargetIndex(0-based)={}, localMode={}", index, physicalExists ? "存在" : "不存在", mumuTargetIndex, localMode);

            if (physicalExists) {
                // 2. 先停止
                try {
                    if (localMode) {
                        mumuClientService.stopEmulator(index);
                    } else {
                        webSocketService.stopEmulatorOnAgent(userId, mumuTargetIndex);
                    }
                    log.info("模拟器 #{} 物理实例已停止", index);
                    actions.add("已停止物理实例");
                    Thread.sleep(1500);
                } catch (Exception e) {
                    log.warn("停止物理模拟器 #{} 失败: {}，继续删除", index, e.getMessage());
                    actions.add("停止失败: " + e.getMessage());
                }

                // 3. 删除物理模拟器
                try {
                    if (localMode) {
                        mumuClientService.deleteEmulator(index);
                    } else {
                        webSocketService.deleteEmulatorOnAgent(userId, mumuTargetIndex);
                    }
                    log.info("物理模拟器 #{} 已删除 (mumuIndex={})", index, mumuTargetIndex);
                    actions.add("已删除物理实例");
                    Thread.sleep(1000);
                } catch (Exception e) {
                    log.error("删除物理模拟器 #{} 失败: {}", index, e.getMessage());
                    actions.add("物理删除失败: " + e.getMessage());
                }

                // 4. 验证物理删除
                try {
                    boolean stillExists;
                    if (localMode) {
                        stillExists = mumuClientService.emulatorExists(index);
                    } else {
                        stillExists = webSocketService.emulatorExistsOnAgent(userId, mumuTargetIndex);
                    }
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

            // 5. 清理数据库记录（使用独立事务）
            try {
                self.deleteInstanceInTransaction(instance);
                log.info("已删除模拟器 #{} 的数据库记录", index);
                actions.add("已清理数据库记录");
            } catch (Exception e) {
                log.error("删除数据库记录失败: {}", e.getMessage());
                actions.add("数据库删除失败: " + e.getMessage());
                throw new RuntimeException("数据库记录删除失败: " + e.getMessage(), e);
            }

            // 6. 重建剩余记录的 instanceIndex（使用独立事务）
            try {
                List<Map<String, Object>> remainingPhysical;
                if (localMode) {
                    remainingPhysical = mumuClientService.getAllEmulators();
                } else {
                    remainingPhysical = webSocketService.getEmulatorsFromAgent(userId);
                }
                self.rebuildIndicesInTransaction(merchantId, userId, remainingPhysical);
                actions.add("已重建剩余记录的索引");
            } catch (Exception e) {
                log.warn("重建索引失败: {}", e.getMessage());
                actions.add("重建索引失败: " + e.getMessage());
            }

            result.put("success", true);
            result.put("message", "删除成功");
            result.put("actions", actions);
        } catch (Exception e) {
            log.error("删除模拟器 #{} 失败, merchantId={}, userId={}", index, merchantId, userId, e);
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
            result.put("actions", actions);
        }

        return result;
    }

    /**
     * 事务方法：删除模拟器实例（短事务，避免连接泄漏）
     */
    @Transactional
    public void deleteInstanceInTransaction(EmuInstance instance) {
        instanceRepository.delete(instance);
    }

    /**
     * 事务方法：重建剩余模拟器的索引（短事务，避免连接泄漏）
     */
    @Transactional
    public void rebuildIndicesInTransaction(Long merchantId, String userId, List<Map<String, Object>> remainingPhysical) {
        if (remainingPhysical == null) return;

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
            int expectedDbIdx = physMumuIdx + 1;
            if (dbRec != null && !Objects.equals(dbRec.getInstanceIndex(), expectedDbIdx)) {
                dbRec.setInstanceIndex(expectedDbIdx);
                instanceRepository.save(dbRec);
                log.info("重建索引: name={}, mumuIdx(0-based)={}, dbIndex(1-based)={}",
                        name, physMumuIdx, expectedDbIdx);
            }
        }
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
        
        // Discord账号显示逻辑：
        // 1. 如果有关联的已导入账号，显示那个账号的名字
        // 2. 如果没有关联账号，但检测到了用户名，显示检测到的用户名
        String displayAccount = null;
        if (instance.getDiscordAccountId() != null) {
            DiscordAccount account = discordAccountRepository.findById(instance.getDiscordAccountId()).orElse(null);
            displayAccount = account != null ? account.getName() : null;
        }
        // 如果没有关联账号，但检测到了用户名，显示检测到的用户名
        if (displayAccount == null && instance.getDiscordAccountName() != null 
                && !instance.getDiscordAccountName().isEmpty()) {
            displayAccount = instance.getDiscordAccountName();
        }
        item.put("discordAccount", displayAccount);
        item.put("discordAccountName", instance.getDiscordAccountName());
        item.put("discordAccountId", instance.getDiscordAccountId());
        // 显示优先级：显式设置的discordAccountNumber > 默认instanceIndex（1-based即V001→1）
        int displayNumber = instance.getDiscordAccountNumber() != null
                ? instance.getDiscordAccountNumber()
                : instance.getInstanceIndex();
        item.put("discordAccountNumber", displayNumber);
        item.put("discordAccountNumberExplicit", instance.getDiscordAccountNumber() != null);
        
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
        String userId = resolveUserId();
        Long merchantId = resolveMerchantId();

        // 检查当前商户的 Agent 是否在线（不是全局所有 Agent）
        List<AgentRegistration> myAgents = webSocketService.getOnlineAgentsByUserId(userId);
        boolean agentOnline = !myAgents.isEmpty();

        // 本地（云服务器）检测：仅在 localMode 下才检测本地 MuMu
        // Agent 模式下，localReachable 不应该依赖服务器本地环境
        boolean localReachable = false;
        if (localMode) {
            localReachable = mumuClientService.isReachable();
        }

        // 可用条件：Agent 在线 或 本地模式下有 MuMu
        boolean available = agentOnline || localReachable;

        status.put("available", available);
        status.put("agentOnline", agentOnline);
        status.put("agentCount", myAgents.size());
        status.put("localReachable", localReachable);
        status.put("localMode", localMode);
        status.put("userId", userId);
        status.put("merchantId", merchantId);

        if (agentOnline) {
            status.put("message", "已连接 Agent (" + myAgents.size() + " 台设备在线)");
        } else if (localReachable) {
            // 本地模式下，检查是否有运行中的模拟器
            int physicalCount = 0;
            try {
                java.util.List<com.discordadmin.model.EmulatorInfo> emus = emulatorService.getAllEmulators();
                physicalCount = emus != null ? emus.size() : 0;
            } catch (Exception e) {
                // 忽略
            }
            if (physicalCount > 0) {
                status.put("message", "本地环境已就绪，共 " + physicalCount + " 台模拟器");
            } else {
                status.put("message", "本地环境已就绪，但暂无运行中的模拟器");
            }
            status.put("physicalCount", physicalCount);
        } else {
            status.put("message", "未检测到在线 Agent。请启动 Agent 或 MuMuPlayer");
        }

        return status;
    }

    /**
     * 获取当前用户所有在线 Agent 的详细信息
     */
    public Map<String, Object> getOnlineAgentDetails() {
        Map<String, Object> result = new HashMap<>();
        String userId = resolveUserId();
        
        List<AgentRegistration> myAgents = webSocketService.getOnlineAgentsByUserId(userId);
        
        List<Map<String, Object>> agentDetails = new ArrayList<>();
        for (AgentRegistration agent : myAgents) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("deviceId", agent.getDeviceId());
            detail.put("userId", agent.getUserId());
            detail.put("os", agent.getOs());
            detail.put("osVersion", agent.getOsVersion());
            detail.put("muMuPath", agent.getMuMuPath());
            detail.put("status", agent.getStatus());
            detail.put("lastHeartbeatAt", agent.getLastHeartbeatAt());
            detail.put("createdAt", agent.getCreatedAt());
            detail.put("updatedAt", agent.getUpdatedAt());
            
            // 计算在线时长
            if (agent.getLastHeartbeatAt() != null) {
                long secondsSinceHeartbeat = Instant.now().getEpochSecond() - agent.getLastHeartbeatAt().getEpochSecond();
                detail.put("secondsSinceHeartbeat", secondsSinceHeartbeat);
                detail.put("heartbeatStatus", secondsSinceHeartbeat < 90 ? "正常" : "超时");
            }
            
            agentDetails.add(detail);
        }
        
        result.put("userId", userId);
        result.put("agentCount", myAgents.size());
        result.put("agents", agentDetails);
        return result;
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
            if (localMode) {
                physicalList = mumuClientService.getAllEmulatorsWithError();
            } else {
                physicalList = webSocketService.getEmulatorsFromAgent(userId);
                if (physicalList == null) {
                    physicalList = new ArrayList<>();
                    actions.add("Agent 未返回模拟器列表");
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "无法获取模拟器列表: " + e.getMessage());
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

        // 1. 物理有但数据库没有 → 创建记录（使用物理数据的真实 CPU/内存）
        Map<Integer, Map<String, Object>> physicalDataMap = new HashMap<>();
        if (physicalList != null) {
            for (Map<String, Object> emu : physicalList) {
                Object idx = emu.get("index");
                if (idx instanceof Number) {
                    int cloudIdx = ((Number) idx).intValue() + 1;
                    physicalDataMap.put(cloudIdx, emu);
                }
            }
        }

        for (int physIdx : physicalIndices) {
            if (!dbIndices.contains(physIdx)) {
                EmuInstance instance = new EmuInstance();
                instance.setMerchantId(merchantId);
                instance.setUserId(userId);
                String vName = "V" + String.format("%03d", physIdx);
                instance.setName(vName);
                instance.setInstanceIndex(physIdx);
                instance.setStatus(EmuInstance.EmuStatus.STOPPED);
                // 使用物理数据中的真实 CPU/内存配置
                Map<String, Object> physData = physicalDataMap.get(physIdx);
                int cpuCores = 1;
                int memoryGb = 1;
                if (physData != null) {
                    Object cpuObj = physData.get("cpuCount");
                    if (cpuObj instanceof Number) {
                        cpuCores = ((Number) cpuObj).intValue();
                        if (cpuCores <= 0) cpuCores = 1;
                    }
                    Object memObj = physData.get("memoryMB");
                    if (memObj instanceof Number) {
                        int memMB = ((Number) memObj).intValue();
                        if (memMB > 0) {
                            memoryGb = Math.max(1, memMB / 1024);
                        }
                    }
                }
                instance.setCpuCores(cpuCores);
                instance.setMemoryGb(memoryGb);
                instance.setResolution("720x1280");
                instance.setDiscordInstalled(false);
                instance.setDiscordLoggedIn(false);
                instance.setAutoRunning(false);
                instance.setAddedCount(0);
                instance.setCreatedAt(Instant.now());
                instanceRepository.save(instance);
                actions.add("为物理模拟器 #" + physIdx + " 创建了数据库记录(" + cpuCores + "核/" + memoryGb + "GB)");
                log.info("同步：为物理模拟器 #{} 创建数据库记录, name={}, cpu={}, mem={}GB", physIdx, vName, cpuCores, memoryGb);
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
     * 检查并更新模拟器的Discord登录状态
     * 使用 DiscordService 直接通过 ADB 检测真实的 Discord 登录状态
     */
    private void checkAndUpdateDiscordStatus(int index) {
        try {
            // 设置当前用户 ID，以便 DiscordService 在 Agent 模式下正确执行 ADB 命令
            String userId = resolveUserId();
            discordService.setCurrentUserId(userId);
            
            EmuInstance inst = instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(
                    resolveMerchantId(), userId, index).orElse(null);
            if (inst == null) {
                log.warn("模拟器 #{} 不存在，跳过Discord状态检查", index);
                return;
            }

            int mumuIndex = index - 1; // DiscordService 使用 0-based index
            boolean updated = false;

            // 1. 检查 Discord 是否安装（通过物理模拟器数据）
            List<Map<String, Object>> physicalList = mumuClientService.getAllEmulatorsWithError();
            boolean discordInstalled = false;
            if (physicalList != null) {
                for (Map<String, Object> phys : physicalList) {
                    Object idx = phys.get("index");
                    if (idx instanceof Number && ((Number) idx).intValue() == mumuIndex) {
                        Object installedObj = phys.get("discordInstalled");
                        discordInstalled = Boolean.TRUE.equals(installedObj) ||
                                "true".equalsIgnoreCase(String.valueOf(installedObj));
                        break;
                    }
                }
            }
            if (discordInstalled != Boolean.TRUE.equals(inst.getDiscordInstalled())) {
                inst.setDiscordInstalled(discordInstalled);
                log.info("模拟器 #{} Discord安装状态: {}", index, discordInstalled);
                updated = true;
            }

            // 2. 如果已安装 Discord，检测登录状态
            if (discordInstalled) {
                // 2a. 确认 Discord 在前台
                boolean isForeground = discordService.isDiscordForeground(mumuIndex);
                log.info("模拟器 #{} Discord前台状态: {}", index, isForeground);

                if (isForeground) {
                    // 2b. 检测是否已登录
                    boolean loggedIn = discordService.isDiscordLoggedIn(mumuIndex);
                    inst.setDiscordLoggedIn(loggedIn);
                    log.info("模拟器 #{} Discord登录状态: {}", index, loggedIn);
                    updated = true;

                    if (loggedIn) {
                        // 2c. 获取登录用户名，只存储用户名不创建占位账号
                        String username = discordService.getLoggedInUser(mumuIndex);
                        if (username != null && !username.isEmpty()) {
                            inst.setDiscordAccountName(username);
                            // 查找已导入的账号（只匹配，不创建）
                            DiscordAccount account = discordAccountRepository.findByName(username).orElse(null);
                            if (account != null) {
                                inst.setDiscordAccountId(account.getId());
                                log.info("模拟器 #{} Discord账号已匹配: {} -> 账号ID={}", index, username, account.getId());
                            } else {
                                // 未导入的账号，只记录用户名用于显示
                                inst.setDiscordAccountId(null);
                                log.info("模拟器 #{} Discord账号未导入: {}（仅显示，不创建记录）", index, username);
                            }
                        }
                        // 清除之前的错误
                        if (inst.getLastError() != null && inst.getLastError().contains("Discord")) {
                            inst.setLastError(null);
                            updated = true;
                        }
                    } else {
                        // 未登录：清除账号信息以及残留的登录结果提示
                        inst.setDiscordAccountId(null);
                        inst.setDiscordAccountName(null);
                        inst.setAutoLastResult(null);
                        inst.setLastError(null);
                        updated = true;
                    }
                } else {
                    // Discord 不在前台，保持现有登录状态不变
                    log.info("模拟器 #{} Discord不在前台，跳过登录状态检测", index);
                }
            }

            if (updated) {
                inst.setUpdatedAt(Instant.now());
                instanceRepository.save(inst);
                log.info("模拟器 #{} Discord状态已更新到数据库", index);
            }
        } catch (Exception e) {
            log.warn("检查模拟器 #{} Discord状态失败: {}", index, e.getMessage());
        }
    }

    /**
     * 定时检测所有运行中模拟器的 Discord 登录状态
     * 每 30 秒运行一次，确保后台回写 Discord 账号信息
     */
    @Scheduled(fixedRate = 30000)
    public void scheduledCheckDiscordStatus() {
        try {
            // 获取物理模拟器列表
            List<Map<String, Object>> physicalList = null;
            try {
                physicalList = mumuClientService.getAllEmulatorsWithError();
            } catch (Exception e) {
                log.info("定时检测: 无法获取物理模拟器列表: {}", e.getMessage());
                return;
            }
            
            if (physicalList == null || physicalList.isEmpty()) {
                log.info("定时检测: 物理模拟器列表为空");
                return;
            }
            
            // 从物理列表中筛选运行中的模拟器
            List<Map<String, Object>> runningPhysicals = new ArrayList<>();
            for (Map<String, Object> phys : physicalList) {
                Object status = phys.get("status");
                if ("running".equalsIgnoreCase(String.valueOf(status))) {
                    runningPhysicals.add(phys);
                }
            }
            
            if (runningPhysicals.isEmpty()) {
                log.info("定时检测: 无运行中的物理模拟器");
                return;
            }
            
            log.info("定时检测: 物理运行中的模拟器数量={}", runningPhysicals.size());
            
            int checked = 0;
            for (Map<String, Object> phys : runningPhysicals) {
                Object idxObj = phys.get("index");
                if (!(idxObj instanceof Number)) continue;
                int mumuIndex = ((Number) idxObj).intValue();
                int instanceIndex = mumuIndex + 1;
                
                // 查找对应的数据库实例
                EmuInstance inst = instanceRepository.findFirstByInstanceIndex(instanceIndex);
                if (inst == null) {
                    log.debug("定时检测: 模拟器 #{} 无对应数据库实例，跳过", instanceIndex);
                    continue;
                }
                
                try {
                    checkAndUpdateDiscordStatusForInstance(inst, phys);
                    checked++;
                } catch (Exception e) {
                    log.warn("定时检测模拟器 #{} Discord 状态失败: {}", instanceIndex, e.getMessage());
                }
            }
            log.info("定时检测完成: 已检测 {} 个运行中模拟器的 Discord 状态", checked);
        } catch (Exception e) {
            log.error("定时检测 Discord 状态异常", e);
        }
    }

    /**
     * 无安全上下文版本 - 直接对指定实例检查并回写 Discord 状态
     * 用于定时任务、批量检测等场景
     * @param inst 数据库实例
     * @param physData 物理模拟器数据（来自 getAllEmulatorsWithError）
     */
    private void checkAndUpdateDiscordStatusForInstance(EmuInstance inst, Map<String, Object> physData) {
        // 设置当前用户 ID，以便 DiscordService 在 Agent 模式下正确执行 ADB 命令
        discordService.setCurrentUserId(resolveUserId());
        
        int index = inst.getInstanceIndex();
        int mumuIndex = index - 1;
        boolean updated = false;

        // 1. 从传入的物理数据检查 Discord 是否安装
        boolean discordInstalled = false;
        if (physData != null) {
            Object installedObj = physData.get("discordInstalled");
            discordInstalled = Boolean.TRUE.equals(installedObj) ||
                    "true".equalsIgnoreCase(String.valueOf(installedObj));
        }
        if (discordInstalled != Boolean.TRUE.equals(inst.getDiscordInstalled())) {
            inst.setDiscordInstalled(discordInstalled);
            updated = true;
        }

        // 2. 如果已安装 Discord，检测登录状态
        if (discordInstalled) {
            // 2a. 确认 Discord 在前台
            boolean isForeground = discordService.isDiscordForeground(mumuIndex);
            
            if (!isForeground) {
                // 不在前台 → 自动打开 Discord 并等待
                log.debug("模拟器 #{} Discord不在前台，尝试打开...", index);
                try {
                    discordService.launchDiscord(mumuIndex);
                    Thread.sleep(2000);
                    isForeground = discordService.isDiscordForeground(mumuIndex);
                } catch (Exception e) {
                    log.debug("模拟器 #{} 打开 Discord 失败: {}", index, e.getMessage());
                }
            }

            if (isForeground) {
                // 2b. 检测是否已登录
                boolean loggedIn = discordService.isDiscordLoggedIn(mumuIndex);
                if (loggedIn != Boolean.TRUE.equals(inst.getDiscordLoggedIn())) {
                    inst.setDiscordLoggedIn(loggedIn);
                    updated = true;
                }

                if (loggedIn) {
                    // 2c. 获取登录用户名，只存储用户名不创建占位账号
                    String username = discordService.getLoggedInUser(mumuIndex);
                    if (username != null && !username.isEmpty()) {
                        inst.setDiscordAccountName(username);
                        // 查找已导入的账号（只匹配，不创建）
                        DiscordAccount account = discordAccountRepository.findByName(username).orElse(null);
                        if (account != null) {
                            if (inst.getDiscordAccountId() == null || !inst.getDiscordAccountId().equals(account.getId())) {
                                inst.setDiscordAccountId(account.getId());
                                updated = true;
                            }
                        } else {
                            // 未导入的账号，清除accountId只记录用户名
                            if (inst.getDiscordAccountId() != null) {
                                inst.setDiscordAccountId(null);
                                updated = true;
                            }
                        }
                        // 清除之前的错误
                        if (inst.getLastError() != null && inst.getLastError().contains("Discord")) {
                            inst.setLastError(null);
                            updated = true;
                        }
                    }
                } else if (inst.getDiscordLoggedIn() != null && inst.getDiscordLoggedIn()) {
                    // 之前已登录现在未登录
                    inst.setDiscordAccountId(null);
                    inst.setDiscordAccountName(null);
                    inst.setDiscordLoggedIn(false);
                    inst.setAutoLastResult(null);
                    inst.setLastError(null);
                    updated = true;
                }
            }
        }

        if (updated) {
            inst.setUpdatedAt(Instant.now());
            instanceRepository.save(inst);
            log.info("模拟器 #{} Discord状态已定时更新: installed={}, loggedIn={}, account={}",
                    index, inst.getDiscordInstalled(), inst.getDiscordLoggedIn(),
                    inst.getDiscordAccountId() != null ? "已绑定" : "无");
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
