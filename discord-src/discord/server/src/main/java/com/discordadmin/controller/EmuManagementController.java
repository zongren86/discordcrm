package com.discordadmin.controller;

import com.discordadmin.discord.member.DiscordMemberService;
import com.discordadmin.discord.member.MemberFetchRequest;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.EmuAccountBinding;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.GuildServer;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.EmuServerBindingRepository;
import com.discordadmin.repository.GuildMemberRepository;
import com.discordadmin.repository.GuildServerRepository;
import com.discordadmin.security.SecurityUtils;
import com.discordadmin.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟器相关API - 账号管理、服务器管理、好友号池、实例管理、APK管理
 */
@Slf4j
@RestController
@RequestMapping("/api/emu")
public class EmuManagementController {

    private final EmuAccountBindingService accountBindingService;
    private final EmuServerBindingService serverBindingService;
    private final EmuFriendPoolService friendPoolService;
    private final EmuInstanceService instanceService;
    private final ApkManagementService apkManagementService;
    private final DiscordMemberService discordMemberService;
    private final GuildServerRepository guildServerRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final EmuServerBindingRepository emuServerBindingRepository;

    public EmuManagementController(EmuAccountBindingService accountBindingService,
                                    EmuServerBindingService serverBindingService,
                                    EmuFriendPoolService friendPoolService,
                                    EmuInstanceService instanceService,
                                    ApkManagementService apkManagementService,
                                    DiscordMemberService discordMemberService,
                                    GuildServerRepository guildServerRepository,
                                    GuildMemberRepository guildMemberRepository,
                                    DiscordAccountRepository discordAccountRepository,
                                    EmuServerBindingRepository emuServerBindingRepository) {
        this.accountBindingService = accountBindingService;
        this.serverBindingService = serverBindingService;
        this.friendPoolService = friendPoolService;
        this.instanceService = instanceService;
        this.apkManagementService = apkManagementService;
        this.discordMemberService = discordMemberService;
        this.guildServerRepository = guildServerRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.discordAccountRepository = discordAccountRepository;
        this.emuServerBindingRepository = emuServerBindingRepository;
    }

    private Long resolveMerchantId() {
        Long id = SecurityUtils.currentMerchantId();
        return id != null ? id : 1L;
    }

    private String resolveUserId() {
        String id = SecurityUtils.currentUserId();
        return id != null ? id : "default";
    }

    // ========== APK 管理 ==========

    /**
     * 检查APK状态
     */
    @GetMapping("/discord/apk-status")
    public Map<String, Object> checkApkStatus() {
        return apkManagementService.checkApkStatus();
    }

    /**
     * 上传APK
     */
    @PostMapping("/discord/upload")
    public Map<String, Object> uploadApk(@RequestParam("file") MultipartFile file) {
        try {
            return apkManagementService.uploadApk(file);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 下载APK（模拟）
     */
    @PostMapping("/discord/download")
    public Map<String, Object> downloadApk() {
        return apkManagementService.downloadLatestApk();
    }

    // ========== 实例管理 ==========

    /**
     * 获取当前用户的所有模拟器实例
     */
    @GetMapping("/emulators")
    public List<Map<String, Object>> getEmulators() {
        return instanceService.getCurrentUserInstances();
    }

    /**
     * 设置模拟器数量 / 新增模拟器
     * body.mode: 'set'(默认，设置总数量，删除超出旧记录) | 'add'(追加，在现有基础上新增 count 台，保留已有记录)
     */
    @PostMapping("/emulators/count")
    public List<Map<String, Object>> setEmulatorCount(@RequestBody Map<String, Object> body) {
        int count = Integer.parseInt(body.get("count").toString());
        int cpuCores = body.containsKey("cpuCores") ? Integer.parseInt(body.get("cpuCores").toString()) : 1;
        int memoryGb = body.containsKey("memoryGb") ? Integer.parseInt(body.get("memoryGb").toString()) : 1;
        String mode = body.containsKey("mode") ? String.valueOf(body.get("mode")) : "set";
        return instanceService.setInstanceCount(count, cpuCores, memoryGb, mode);
    }

    /**
     * 启动模拟器
     */
    @PostMapping("/emulators/{index}/start")
    public Map<String, Object> startEmulator(@PathVariable int index) {
        return instanceService.startInstance(index);
    }

    /**
     * 停止模拟器
     */
    @PostMapping("/emulators/{index}/stop")
    public Map<String, Object> stopEmulator(@PathVariable int index) {
        return instanceService.stopInstance(index);
    }

    /**
     * 重启模拟器
     */
    @PostMapping("/emulators/{index}/restart")
    public Map<String, Object> restartEmulator(@PathVariable int index) {
        return instanceService.restartInstance(index);
    }

    /**
     * 启动所有模拟器
     */
    @PostMapping("/emulators/startAll")
    public List<Map<String, Object>> startAllEmulators() {
        return instanceService.startAllInstances();
    }

    /**
     * 停止所有模拟器
     */
    @PostMapping("/emulators/stopAll")
    public List<Map<String, Object>> stopAllEmulators() {
        return instanceService.stopAllInstances();
    }

    /**
     * 删除模拟器
     */
    @DeleteMapping("/emulators/{index}")
    public Map<String, Object> deleteEmulator(@PathVariable int index) {
        return instanceService.deleteInstance(index);
    }

    /**
     * 更新模拟器绑定的 Discord 账号编号
     * body: { number: 5 }  传 number=null 清除显式绑定（回退到默认instanceIndex对应）
     */
    @PutMapping("/emulators/{index}/discord-account-number")
    public Map<String, Object> updateDiscordAccountNumber(@PathVariable int index,
                                                          @RequestBody Map<String, Object> body) {
        Integer number = null;
        Object v = body.get("number");
        if (v != null && !"".equals(v) && !"null".equalsIgnoreCase(String.valueOf(v))) {
            try { number = Integer.parseInt(String.valueOf(v)); }
            catch (NumberFormatException e) { throw new RuntimeException("number 参数必须是整数"); }
        }
        return instanceService.updateDiscordAccountNumber(index, number);
    }

    /**
     * 检查物理模拟器连接状态
     */
    @GetMapping("/emulators/physical-status")
    public Map<String, Object> getPhysicalStatus() {
        return instanceService.getPhysicalStatus();
    }

    /**
     * 同步物理模拟器与数据库记录
     */
    @PostMapping("/emulators/sync")
    public Map<String, Object> syncEmulators() {
        return instanceService.syncPhysicalAndDb();
    }

    // ========== Discord 控制 ==========

    /**
     * 安装Discord到模拟器
     */
    @PostMapping("/discord/install/{index}")
    public Map<String, Object> installDiscord(@PathVariable int index) {
        return instanceService.installDiscord(index);
    }

    /**
     * 安装Discord到所有模拟器
     */
    @PostMapping("/discord/installAll")
    public Map<String, Object> installAllDiscord() {
        var instances = instanceService.getCurrentUserInstances();
        
        int successCount = 0;
        StringBuilder errors = new StringBuilder();
        
        for (var inst : instances) {
            try {
                instanceService.installDiscord((Integer) inst.get("index"));
                successCount++;
            } catch (Exception e) {
                if (errors.length() > 0) errors.append("; ");
                errors.append("#").append(inst.get("index")).append(" ").append(e.getMessage());
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", instances.size() - successCount);
        if (errors.length() > 0) {
            result.put("errors", errors.toString());
        }
        return result;
    }

    /**
     * 启动Discord
     */
    @PostMapping("/discord/launch/{index}")
    public Map<String, Object> launchDiscord(@PathVariable int index) {
        return instanceService.launchDiscord(index);
    }

    /**
     * 更新Discord首页状态（由模拟器调用）
     */
    @PostMapping("/discord/home-status/{index}")
    public Map<String, Object> updateDiscordHomeStatus(@PathVariable int index,
                                                       @RequestBody Map<String, Boolean> body) {
        boolean onHome = body.get("onHome");
        return instanceService.updateDiscordHomeStatus(index, onHome);
    }

    /**
     * 更新Discord登录状态（由模拟器调用）
     */
    @PostMapping("/discord/login-status/{index}")
    public Map<String, Object> updateDiscordLoginStatus(@PathVariable int index,
                                                        @RequestBody Map<String, Boolean> body) {
        boolean loggedIn = body.get("loggedIn");
        return instanceService.updateDiscordLoginStatus(index, loggedIn);
    }

    // ========== 自动加好友 ==========

    /**
     * 启动自动加好友
     */
    @PostMapping("/autoadd/{index}/start")
    public Map<String, Object> startAutoAdd(@PathVariable int index,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Long serverId = null;
        if (body != null && body.get("serverId") != null) {
            serverId = Long.valueOf(body.get("serverId").toString());
        }
        return instanceService.startAutoAdd(index, serverId);
    }

    /**
     * 停止自动加好友
     */
    @PostMapping("/autoadd/{index}/stop")
    public Map<String, Object> stopAutoAdd(@PathVariable int index) {
        return instanceService.stopAutoAdd(index);
    }

    /**
     * 全部启动自动加好友
     */
    @PostMapping("/autoadd/startAll")
    public List<Map<String, Object>> startAllAutoAdd() {
        return instanceService.startAllAutoAdd();
    }

    /**
     * 全部停止自动加好友
     */
    @PostMapping("/autoadd/stopAll")
    public List<Map<String, Object>> stopAllAutoAdd() {
        return instanceService.stopAllAutoAdd();
    }

    /**
     * 保存自动加好友配置
     */
    @PostMapping("/data/autoconfig")
    public Map<String, Object> saveAutoConfig(@RequestBody Map<String, Object> config) {
        // 存储配置到商户级别的配置中
        // 这里简化处理，实际应该使用MerchantConfig
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 获取自动加好友配置
     */
    @GetMapping("/data/autoconfig")
    public Map<String, Object> getAutoConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("intervalSeconds", 900);
        result.put("delayMinSeconds", 60);
        result.put("delayMaxSeconds", 800);
        return result;
    }

    // ========== 账号管理 ==========

    /**
     * 获取已添加的账号列表
     */
    @GetMapping("/accounts/added")
    public List<Map<String, Object>> getAddedAccounts() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        return accountBindingService.getAddedAccounts(merchantId, userId);
    }

    /**
     * 获取可用的账号列表（可添加的）
     */
    @GetMapping("/accounts/available")
    public List<Map<String, Object>> getAvailableAccounts(@RequestParam(required = false) String keyword) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        return accountBindingService.getAvailableAccounts(merchantId, userId, keyword);
    }

    /**
     * 添加账号
     */
    @PostMapping("/accounts/add")
    public Map<String, Object> addAccount(@RequestBody Map<String, Long> body) {
        Long discordAccountId = body.get("discordAccountId");
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuAccountBinding binding = accountBindingService.addAccount(merchantId, userId, discordAccountId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", binding.getId());
        result.put("success", true);
        return result;
    }

    /**
     * 移除账号
     */
    @DeleteMapping("/accounts/{bindingId}")
    public Map<String, Object> removeAccount(@PathVariable Long bindingId) {
        accountBindingService.removeAccount(bindingId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    // ========== 服务器管理 ==========

    /**
     * 获取已添加的服务器列表
     */
    @GetMapping("/servers/added")
    public List<Map<String, Object>> getAddedServers() {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        return serverBindingService.getAddedServers(merchantId, userId);
    }

    /**
     * 获取可用的服务器列表（可添加的）
     * 支持按账号ID筛选
     */
    @GetMapping("/servers/available")
    public List<Map<String, Object>> getAvailableServers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long accountId) {
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();
        return serverBindingService.getAvailableServers(merchantId, userId, keyword, accountId);
    }

    /**
     * 添加服务器
     */
    @PostMapping("/servers/add")
    public Map<String, Object> addServer(@RequestBody Map<String, Object> body) {
        Long serverId = Long.valueOf(body.get("serverId").toString());
        Long discordAccountId = body.containsKey("discordAccountId") && body.get("discordAccountId") != null 
            ? Long.valueOf(body.get("discordAccountId").toString()) : null;
        Long merchantId = resolveMerchantId();
        String userId = resolveUserId();

        EmuServerBinding binding = serverBindingService.addServer(merchantId, userId, serverId, discordAccountId);
        
        // 检查现有成员数量，如果为空则触发抓取
        try {
            long memberCount = guildMemberRepository.countByGuildServerId(serverId);
            
            // 更新绑定记录中的成员数为实际值
            if (memberCount > 0) {
                binding.setMemberCount((int) memberCount);
                binding.setLastSyncAt(java.time.Instant.now());
                emuServerBindingRepository.save(binding);
                
                // 同时更新GuildServer的member_count
                GuildServer server = guildServerRepository.findById(serverId).orElse(null);
                if (server != null) {
                    server.setMemberCount((int) memberCount);
                    server.setLastFetchAt(java.time.Instant.now());
                    guildServerRepository.save(server);
                }
            }
            
            if (memberCount == 0) {
                // 成员为空，自动触发抓取流程
                log.info("添加服务器后成员为空，触发抓取流程：serverId={}", serverId);
                
                GuildServer server = guildServerRepository.findById(serverId).orElse(null);
                if (server != null && server.getGuildId() != null && !server.getGuildId().isBlank()) {
                    // 获取 Discord 账号
                    DiscordAccount account = null;
                    if (server.getDiscordAccountId() != null) {
                        account = discordAccountRepository.findById(server.getDiscordAccountId()).orElse(null);
                    }
                    if (account == null) {
                        account = discordAccountRepository.findAll().stream()
                                .filter(a -> a.getToken() != null && !a.getToken().isBlank())
                                .findFirst()
                                .orElse(null);
                    }
                    
                    if (account != null && account.getToken() != null && !account.getToken().isBlank()) {
                        // 触发抓取任务
                        MemberFetchRequest req = new MemberFetchRequest();
                        req.setToken(account.getToken());
                        req.setLink(server.getGuildId());
                        req.setGuildServerId(serverId);
                        req.setDiscordAccountId(account.getId());
                        req.setMaxMembers(2000000);
                        req.setPageDelay(1.0);
                        req.setMaxDepth(5);
                        req.setMaxRequests(1000);
                        req.setResumeSync(true);

                        String taskId = discordMemberService.startFetch(req);
                        log.info("添加服务器后自动触发抓取：serverId={}, taskId={}", serverId, taskId);
                    } else {
                        log.warn("添加服务器后无法触发抓取：无有效Discord账号");
                    }
                }
            } else {
                // 有成员数据，初始化好友池状态
                log.info("添加服务器后成员已存在：serverId={}, 成员数量={}", serverId, memberCount);
            }
        } catch (Exception e) {
            log.warn("添加服务器后同步好友失败：serverId={}, error={}", serverId, e.getMessage());
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", binding.getId());
        result.put("success", true);
        return result;
    }

    /**
     * 移除服务器
     */
    @DeleteMapping("/servers/{bindingId}")
    public Map<String, Object> removeServer(@PathVariable Long bindingId) {
        serverBindingService.removeServer(bindingId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 从服务器同步成员到好友号池
     * 如果服务器成员数据为空，自动触发抓取流程
     */
    @PostMapping("/servers/{serverId}/sync-friends")
    public Map<String, Object> syncFriendsFromServer(@PathVariable Long serverId) {
        Long merchantId = resolveMerchantId();
        log.info("开始同步好友：serverId={}, merchantId={}", serverId, merchantId);

        Map<String, Object> result = new HashMap<>();

        // 1. 检查 GuildServer 是否存在
        GuildServer server = guildServerRepository.findById(serverId)
                .orElseThrow(() -> new RuntimeException("服务器不存在 (ID: " + serverId + ")"));
        log.info("服务器信息: id={}, guildId={}, accountId={}, name={}", 
                server.getId(), server.getGuildId(), server.getDiscordAccountId(), server.getName());

        // 2. 获取 Discord 账号（优先使用服务器关联的账号，否则查找系统中任意有效账号）
        DiscordAccount account = null;
        if (server.getDiscordAccountId() != null) {
            account = discordAccountRepository.findById(server.getDiscordAccountId()).orElse(null);
        }
        if (account == null) {
            account = discordAccountRepository.findAll().stream()
                    .filter(a -> a.getToken() != null && !a.getToken().isBlank())
                    .findFirst()
                    .orElse(null);
        }
        
        if (account == null) {
            result.put("success", false);
            result.put("message", "系统中没有有效的 Discord 账号。请先添加至少一个有效的 Discord 账号");
            result.put("diagnostic", Map.of(
                "serverId", serverId,
                "serverName", server.getName(),
                "step", "check_account"
            ));
            return result;
        }
        log.info("使用账号: id={}, name={}, token有效={}", account.getId(), account.getName(), 
                account.getToken() != null && !account.getToken().isBlank());

        // 3. 检查现有成员数量
        long memberCount = guildMemberRepository.countByGuildServerId(serverId);
        log.info("现有成员数量: {}", memberCount);

        // 4. 检查是否有最近的抓取任务
        DiscordMemberService.TaskState lastTask = discordMemberService.getLatestTaskForServer(serverId);
        if (lastTask != null && ("RUNNING".equals(lastTask.status) || "PENDING".equals(lastTask.status))) {
            log.info("有正在进行的抓取任务: status={}, members={}", lastTask.status, lastTask.membersUnique);
            result.put("success", false);
            result.put("fetchStarted", true);
            result.put("fetching", true);
            result.put("message", "成员抓取进行中，已获取 " + lastTask.membersUnique + " 个成员，请等待抓取完成后再同步");
            result.put("progress", lastTask);
            return result;
        }

        // 5. 如果成员为空，触发抓取
        if (memberCount == 0) {
            log.info("成员数量为0，开始触发抓取流程");
            
            if (server.getGuildId() == null || server.getGuildId().isBlank()) {
                result.put("success", false);
                result.put("message", "服务器的 Guild ID 为空，无法抓取成员。请先在服务器列表中添加有效的 Discord 服务器链接");
                result.put("diagnostic", Map.of(
                    "guildId", server.getGuildId(),
                    "serverName", server.getName(),
                    "step", "check_guild_id"
                ));
                return result;
            }

            if (account.getToken() == null || account.getToken().isBlank()) {
                result.put("success", false);
                result.put("message", "Discord账号Token为空，无法抓取成员。请先添加有效的Discord账号或更新Token");
                result.put("diagnostic", Map.of(
                    "accountId", account.getId(),
                    "accountName", account.getName(),
                    "tokenEmpty", true,
                    "step", "check_token"
                ));
                return result;
            }

            try {
                MemberFetchRequest req = new MemberFetchRequest();
                req.setToken(account.getToken());
                req.setLink(server.getGuildId());
                req.setGuildServerId(serverId);
                req.setDiscordAccountId(account.getId());
                req.setMaxMembers(2000000);
                req.setPageDelay(1.0);
                req.setMaxDepth(5);
                req.setMaxRequests(1000);
                req.setResumeSync(true);

                String taskId = discordMemberService.startFetch(req);
                log.info("成员抓取任务已启动: taskId={}", taskId);
                
                result.put("fetchStarted", true);
                result.put("taskId", taskId);
                result.put("message", "已启动成员抓取任务，请稍等片刻后再次点击同步（任务ID: " + taskId + "）");
                result.put("success", true);
                result.put("diagnostic", Map.of(
                    "taskId", taskId,
                    "guildId", server.getGuildId(),
                    "estimatedTime", "根据服务器规模，可能需要几分钟到几十分钟"
                ));
                return result;
            } catch (Exception e) {
                log.error("启动抓取任务失败", e);
                result.put("success", false);
                result.put("message", "启动抓取任务失败: " + e.getMessage());
                result.put("diagnostic", Map.of(
                    "error", e.getMessage(),
                    "step", "start_fetch"
                ));
                return result;
            }
        }

        // 6. 如果成员不为空，直接同步到好友池（成员默认为待添加状态）
        log.info("成员已存在，不需要额外同步：{} 个成员", memberCount);
        
        // 更新绑定记录中的成员数
        List<EmuServerBinding> bindings = emuServerBindingRepository.findByServerId(serverId);
        for (EmuServerBinding binding : bindings) {
            binding.setMemberCount((int) memberCount);
            binding.setLastSyncAt(java.time.Instant.now());
            emuServerBindingRepository.save(binding);
        }
        
        result.put("addedCount", 0);
        result.put("fetchStarted", false);
        result.put("success", true);
        result.put("totalMembers", memberCount);
        result.put("pendingCount", memberCount);
        result.put("message", "共有 " + memberCount + " 个成员可用于添加好友");
        return result;
    }

    /**
     * 查询抓取任务状态
     */
    @GetMapping("/servers/fetch-status")
    public Map<String, Object> getFetchStatus(@RequestParam(required = false) Long serverId,
                                               @RequestParam(required = false) String taskId) {
        Map<String, Object> result = new HashMap<>();

        if (taskId != null && !taskId.isBlank()) {
            DiscordMemberService.TaskState task = discordMemberService.getTask(taskId);
            if (task != null) {
                result.put("status", task.status);
                result.put("progressMessage", task.progressMessage);
                result.put("membersUnique", task.membersUnique);
                result.put("requestsSent", task.requestsSent);
                result.put("prefixesDone", task.prefixesDone);
                result.put("prefixesTotal", task.prefixesTotal);
                result.put("error", task.error);
                result.put("found", true);
            } else {
                result.put("found", false);
                result.put("message", "任务不存在或已过期");
            }
        } else if (serverId != null) {
            DiscordMemberService.TaskState lastTask = discordMemberService.getLatestTaskForServer(serverId);
            if (lastTask != null) {
                result.put("status", lastTask.status);
                result.put("progressMessage", lastTask.progressMessage);
                result.put("membersUnique", lastTask.membersUnique);
                result.put("requestsSent", lastTask.requestsSent);
                result.put("prefixesDone", lastTask.prefixesDone);
                result.put("prefixesTotal", lastTask.prefixesTotal);
                result.put("error", lastTask.error);
                result.put("found", true);
            } else {
                result.put("found", false);
                result.put("message", "该服务器没有抓取任务记录");
            }
        } else {
            result.put("success", false);
            result.put("message", "需要提供 serverId 或 taskId 参数");
        }

        return result;
    }

    // ========== 好友号池 ==========

    /**
     * 获取好友号池（按服务器）
     */
    @GetMapping("/friend-pool")
    public Map<String, Object> getFriendPool(
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Map<String, Object> result = new HashMap<>();
        
        if (serverId != null) {
            // 按服务器获取好友池
            Page<Map<String, Object>> friendPoolPage = friendPoolService.getFriendPoolPage(
                serverId, status, PageRequest.of(page, size));
            
            result.put("serverId", serverId);
            result.put("content", friendPoolPage.getContent());
            result.put("totalElements", friendPoolPage.getTotalElements());
            result.put("totalPages", friendPoolPage.getTotalPages());
            result.put("number", friendPoolPage.getNumber());
            result.put("size", friendPoolPage.getSize());
        } else {
            // 获取所有已添加服务器的好友池汇总
            Long merchantId = resolveMerchantId();
            List<Map<String, Object>> allFriends = new java.util.ArrayList<>();
            
            List<Map<String, Object>> serverList = serverBindingService.getAddedServers(merchantId, resolveUserId());
            for (Map<String, Object> serverInfo : serverList) {
                Long sId = Long.valueOf(serverInfo.get("serverId").toString());
                List<Map<String, Object>> friends = friendPoolService.getFriendPool(sId, status);
                allFriends.addAll(friends);
            }
            
            // 手动分页
            int start = page * size;
            int end = Math.min(start + size, allFriends.size());
            List<Map<String, Object>> pageContent = start >= allFriends.size() 
                ? new java.util.ArrayList<>() 
                : allFriends.subList(start, end);
            
            result.put("serverId", null);
            result.put("content", pageContent);
            result.put("totalElements", allFriends.size());
            result.put("totalPages", (allFriends.size() + size - 1) / size);
            result.put("number", page);
            result.put("size", size);
        }
        
        return result;
    }

    /**
     * 获取好友号池统计（按服务器或按商家）
     */
    @GetMapping("/friend-pool/stats")
    public Map<String, Object> getFriendPoolStats(@RequestParam(required = false) Long serverId) {
        if (serverId != null) {
            return friendPoolService.getFriendPoolStats(serverId);
        } else {
            Long merchantId = resolveMerchantId();
            return friendPoolService.getFriendPoolStatsByMerchant(merchantId);
        }
    }

    @GetMapping("/friend-pool/stats-by-server")
    public List<Map<String, Object>> getFriendPoolStatsByServer() {
        Long merchantId = resolveMerchantId();
        return friendPoolService.getFriendPoolStatsByServer(merchantId);
    }

    /**
     * 分配好友给任务
     */
    @PostMapping("/friend-pool/assign")
    public Map<String, Object> assignFriendsToTask(@RequestBody Map<String, Object> body) {
        Long serverId = Long.valueOf(body.get("serverId").toString());
        Long taskId = Long.valueOf(body.get("taskId").toString());
        Long discordAccountId = body.containsKey("discordAccountId") && body.get("discordAccountId") != null 
            ? Long.valueOf(body.get("discordAccountId").toString()) : null;
        int count = Integer.parseInt(body.get("count").toString());

        List<GuildMember> assigned = friendPoolService.assignFriendsToTask(serverId, taskId, discordAccountId, count);
        
        Map<String, Object> result = new HashMap<>();
        result.put("assignedCount", assigned.size());
        result.put("success", true);
        return result;
    }

    /**
     * 更新好友添加结果
     */
    @PutMapping("/friend-pool/{memberId}/result")
    public Map<String, Object> updateFriendResult(@PathVariable Long memberId, 
                                                   @RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        String error = (String) body.get("error");
        
        int statusValue = switch (status.toUpperCase()) {
            case "PENDING" -> 0;
            case "ASSIGNED" -> 1;
            case "SUCCESS" -> 2;
            case "FAILED" -> 3;
            default -> 0;
        };
        
        friendPoolService.updateFriendResult(memberId, statusValue, error);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * 重置好友状态
     */
    @PostMapping("/friend-pool/{memberId}/reset")
    public Map<String, Object> resetFriendStatus(@PathVariable Long memberId) {
        friendPoolService.resetFriendStatus(memberId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }
}
