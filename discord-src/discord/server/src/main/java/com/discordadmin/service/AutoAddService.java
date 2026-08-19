package com.discordadmin.service;

import com.discordadmin.entity.GuildMember;
import com.discordadmin.entity.EmuInstance;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.model.DiscordAccount;
import com.discordadmin.model.EmulatorInfo;
import com.discordadmin.repository.EmuInstanceRepository;
import com.discordadmin.repository.EmuServerBindingRepository;
import com.discordadmin.repository.GuildMemberRepository;
import com.discordadmin.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class AutoAddService {

    private final EmulatorService emulatorService;
    private final DiscordService discordService;
    private final DataStoreService dataStore;
    private final EmuInstanceRepository instanceRepository;
    private final EmuFriendPoolService friendPoolService;
    private final GuildMemberRepository memberRepository;
    private final EmuServerBindingRepository serverBindingRepository;

    // 每台模拟器的自动化开关（内存态，持久化交给 EmulatorInfo.autoRunning）
    private final Set<Integer> running = ConcurrentHashMap.newKeySet();
    // 正在执行加好友动作的模拟器，防止心跳重复触发同一台
    private final Set<Integer> busy = ConcurrentHashMap.newKeySet();
    // 各模拟器并行执行加好友动作
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "auto-add-worker");
        t.setDaemon(true);
        return t;
    });

    public AutoAddService(EmulatorService emulatorService, DiscordService discordService, 
                          DataStoreService dataStore, EmuInstanceRepository instanceRepository,
                          EmuFriendPoolService friendPoolService,
                          GuildMemberRepository memberRepository,
                          EmuServerBindingRepository serverBindingRepository) {
        this.emulatorService = emulatorService;
        this.discordService = discordService;
        this.dataStore = dataStore;
        this.instanceRepository = instanceRepository;
        this.friendPoolService = friendPoolService;
        this.memberRepository = memberRepository;
        this.serverBindingRepository = serverBindingRepository;
    }

    private Long resolveMerchantId() {
        Long id = SecurityUtils.currentMerchantId();
        return id != null ? id : 1L;
    }

    private String resolveUserId() {
        String id = SecurityUtils.currentUserId();
        return id != null ? id : "default";
    }

    /** 同步内存态状态到数据库（index 为 MuMu 0-based，需转 DB 1-based） */
    private void syncToDb(int index) {
        try {
            Long merchantId = resolveMerchantId();
            String userId = resolveUserId();
            
            EmulatorInfo info = emulatorService.getEmulator(index);
            // DB instance_index 是 1-based，MuMu index 是 0-based
            int dbIndex = index + 1;
            EmuInstance instance = instanceRepository
                .findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, dbIndex)
                .orElse(null);
            
            if (instance != null && info != null) {
                instance.setAutoRunning(info.isAutoRunning());
                instance.setAutoLastResult(info.getAutoLastResult());
                instance.setAddedCount(info.getAddedCount());
                instance.setUpdatedAt(Instant.now());
                instanceRepository.save(instance);
            }
        } catch (Exception e) {
            log.warn("同步模拟器{}状态到数据库失败: {}", index, e.getMessage());
        }
    }

    /** 启动某台模拟器的自动加好友：确保 Discord 已打开并登录（未登录则从账号池取号自行登录），再排程 */
    public synchronized String start(int index) {
        EmulatorInfo info = emulatorService.getEmulator(index);
        if (info == null) return "ERROR: 模拟器不存在";
        if (!"RUNNING".equals(info.getStatus())) return "ERROR: 模拟器未运行，请先启动";
        // 没装 Discord 就直接拒绝，否则自动化会点到别的应用（如游戏中心）上
        if (!discordService.checkDiscordInstalled(index)) {
            info.setAutoLastResult("未安装 Discord，无法启动自动加好友");
            return "ERROR: 模拟器未安装 Discord，请先安装";
        }

        String name = info.getName() != null ? info.getName() : ("V" + String.format("%03d", index + 1));

        // 1) 检测 Discord 是否已打开（前台），未打开则先打开
        boolean opened = discordService.isDiscordForeground(index);
        if (!opened) {
            log.info("模拟器{} Discord 未打开，先打开", index + 1);
            String launch = discordService.launchDiscord(index);
            if (!"SUCCESS".equals(launch)) {
                info.setAutoRunning(false);
                info.setAutoLastResult("打开 Discord 失败: " + launch);
                return "ERROR: 打开 Discord 失败 -> " + launch;
            }
        }

        // 2) 判定登录态：停在登录页（含账号+密码两个输入框）即视为未登录；
        //    否则再校验是否真正已登录（解析到用户名）。
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        boolean onLoginPage = discordService.isOnLoginPage(index);
        boolean alreadyLoggedIn = !onLoginPage && discordService.isDiscordLoggedIn(index);

        // 2-1 未登录（停在登录页，或不在登录页但解析不到用户名）
        if (!alreadyLoggedIn) {
            boolean autoLoginEnabled = dataStore.getConfig().isAutoLoginDiscord();
            if (!autoLoginEnabled) {
                // 2-1-2 开关关闭：不尝试登录，直接返回未登录并结束自动加好友
                info.setDiscordLoggedIn(false);
                info.setDiscordLoginFailed(false);
                info.setAutoRunning(false);
                info.setAutoLastResult(name + " Discord 未登录");
                return "ERROR: " + name + " Discord 未登录";
            }
            // 2-1-1 开关打开：从账号池取号并模拟自动登录
            DiscordAccount acc = dataStore.takeAccount(index);
            if (acc == null) {
                info.setDiscordLoggedIn(false);
                info.setDiscordLoginFailed(false);
                info.setAutoRunning(false);
                info.setAutoLastResult(name + " 未登录 Discord，且账号池已无可分配账号，无法自动登录");
                return "ERROR: " + name + " 未登录，且账号池无可分配账号";
            }
            info.setDiscordAccount(acc.getEmail());
            info.setDiscordLoggedIn(false);
            info.setDiscordLoginFailed(false);
            info.setDiscordLoginError(null);
            log.info("模拟器{} 从账号池分配账号 {} 并自动登录", index + 1, acc.getEmail());

            // 同步等待自动登录结果（带超时）
            String loginRes;
            try {
                loginRes = discordService.autoLogin(index, acc.getEmail(), acc.getPassword()).get(120, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                loginRes = "ERROR: 自动登录超时(120s)未完成";
            } catch (Exception e) {
                loginRes = "ERROR: 自动登录异常: " + e.getMessage();
            }

            // 真正校验登录结果（兜底：登录后仍在登录页 = 不成功）
            if (loginRes == null || !loginRes.startsWith("SUCCESS") || discordService.isOnLoginPage(index)) {
                String reason = (loginRes != null && loginRes.length() > 6) ? loginRes.substring(6) : "未知原因";
                if (discordService.isOnLoginPage(index))
                    reason = "登录后仍在登录页，Discord 登录不成功(账号或密码错误/需要验证)";
                // 登录失败：记录失败状态与原因，不启动加好友，结束
                info.setDiscordLoggedIn(false);
                info.setDiscordLoginFailed(true);
                info.setDiscordLoginError(reason);
                dataStore.markAccountLogin(index, false, reason);
                info.setAutoRunning(false);
                info.setAutoLastResult(name + " Discord 登录不成功: " + reason);
                return "ERROR: " + name + " Discord 登录不成功 -> " + reason;
            }

            // 登录成功：回写绑定账号状态并尝试回填实际用户名
            dataStore.markAccountLogin(index, true, null);
            info.setDiscordLoggedIn(true);
            info.setDiscordLoginFailed(false);
            String user = discordService.getLoggedInUser(index);
            emulatorService.setDiscordActualUser(index, user);
            log.info("模拟器{} Discord 自动登录成功，开始自动加好友", index + 1);
            info.setAutoLastResult("Discord 登录成功(" + acc.getEmail() + ")，开始自动加好友");
        } else {
            // 2-2 已登录：只在有显式绑定账号时才显示登录信息
            DiscordAccount bound = dataStore.getAccountByEmulator(index);
            if (bound != null) {
                info.setDiscordLoggedIn(true);
                info.setDiscordLoginFailed(false);
                info.setDiscordLoginError(null);
                info.setDiscordAccount(bound.getEmail());
                String user = discordService.getLoggedInUser(index);
                emulatorService.setDiscordActualUser(index, user);
                log.info("模拟器{} Discord 已登录(绑定账号: {})，开始自动加好友", index + 1, bound.getEmail());
                info.setAutoLastResult("Discord 已登录" + (user != null ? "(" + user + ")" : "") + "，开始自动加好友");
            } else {
                // 无绑定账号：保持登录状态为true，仅清除账号信息
                info.setDiscordLoggedIn(true);
                info.setDiscordAccount(null);
                String user = discordService.getLoggedInUser(index);
                if (user != null && !user.isEmpty()) {
                    info.setDiscordAccount(user);
                }
                info.setAutoLastResult("Discord 已登录" + (user != null ? "(" + user + ")" : "") + "，未绑定系统账号");
                log.info("模拟器{} 检测到 Discord 登录态但未绑定账号，显示登录状态但不绑定账号", index + 1);
            }
        }

        info.setAutoRunning(true);
        info.setAddedCount(countConsumed(resolveMerchantId()));
        // 首次立即执行一次加好友（不等待间隔/延迟），
        // 之后再由 doAddOne 按 间隔 + 随机延迟 排程后续执行
        info.setNextAddAt(System.currentTimeMillis());
        running.add(index);
        syncToDb(index);
        return "SUCCESS";
    }

    /** 停止某台模拟器的自动加好友 */
    public synchronized String stop(int index) {
        EmulatorInfo info = emulatorService.getEmulator(index);
        if (info != null) {
            info.setAutoRunning(false);
            info.setNextAddAt(0);
        }
        running.remove(index);
        syncToDb(index);
        return "SUCCESS";
    }

    public void stopAll() {
        for (int idx : new ArrayList<>(running)) stop(idx);
    }

    public boolean isRunning(int index) {
        return running.contains(index);
    }

    /** 该模拟器是否正在执行加好友动作（busy 时自动抓取应跳过，避免争抢 ADB） */
    public boolean isBusy(int index) {
        return busy.contains(index);
    }

    /** 默认全局启动：对所有 RUNNING 模拟器启动（已运行的不重复登录） */
    public synchronized void startAll() {
        for (EmulatorInfo info : emulatorService.getAllEmulators()) {
            if ("RUNNING".equals(info.getStatus()) && !running.contains(info.getIndex())) {
                start(info.getIndex());
            }
        }
    }

    private long randomDelay() {
        int min = dataStore.getConfig().getDelayMinSeconds();
        int max = dataStore.getConfig().getDelayMaxSeconds();
        if (max <= min) return (long) min * 1000;
        return (long) (min + new Random().nextInt(max - min + 1)) * 1000;
    }

    /** 心跳：每秒检查各台是否到达排程时间 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        long now = System.currentTimeMillis();
        for (int index : new ArrayList<>(running)) {
            EmulatorInfo info = emulatorService.getEmulator(index);
            if (info == null || !"RUNNING".equals(info.getStatus())) {
                // 设备已停止，自动关闭
                stop(index);
                continue;
            }
            if (info.getNextAddAt() > 0 && now >= info.getNextAddAt()) {
                // 一次加好友要几十秒（冷启动 Discord + 等待渲染），
                // 必须异步执行，否则会卡住心跳、拖累其它模拟器。
                // busy 标记防止同一台重复进入。
                if (busy.add(index)) {
                    worker.submit(() -> {
                        try {
                            doAddOne(index);
                        } catch (Exception e) {
                            log.error("模拟器{} 自动加好友异常", index + 1, e);
                        } finally {
                            busy.remove(index);
                        }
                    });
                }
            }
        }
    }

    private void doAddOne(int index) {
        EmulatorInfo info = emulatorService.getEmulator(index);
        if (info == null) return;

        Long merchantId = resolveMerchantId();
        
        // 获取第一个已添加的服务器ID
        Long serverId = getFirstServerId(merchantId);
        
        if (serverId == null) {
            info.setAutoRunning(false);
            info.setNextAddAt(0);
            running.remove(index);
            info.setAutoLastResult("未绑定服务器");
            syncToDb(index);
            return;
        }
        
        // 原子取号：从数据库好友池取一个 PENDING 状态的好友
        List<GuildMember> pendingFriends = memberRepository.findPendingByGuildServerId(serverId);
        
        if (pendingFriends.isEmpty()) {
            // 号池为空，返回失败
            info.setAutoRunning(false);
            info.setNextAddAt(0);
            running.remove(index);
            info.setAutoLastResult("号池为空");
            syncToDb(index);
            return;
        }
        
        // 取第一个待处理的好友
        GuildMember target = pendingFriends.get(0);
        String username = target.getUsername();
        if (username == null || username.isEmpty()) {
            username = target.getUserId();
        }

        // 标记为已分配
        target.setFriendStatus(EmuFriendPoolService.STATUS_ASSIGNED);
        target.setEmulatorIndex(index + 1);
        target.setStartedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        memberRepository.save(target);

        String res = discordService.addFriendByUsername(index, username);
        if (res.startsWith("SUCCESS")) {
            // 成功：标记成功
            target.setFriendStatus(EmuFriendPoolService.STATUS_SUCCESS);
            target.setLastError(null);
            info.setAutoLastResult("已发送好友请求(成功): " + username);
        } else {
            // 失败：标记失败
            target.setFriendStatus(EmuFriendPoolService.STATUS_FAILED);
            target.setLastError(res);
            info.setAutoLastResult("添加失败: " + username + " -> " + res);
        }
        target.setFinishedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        memberRepository.save(target);
        
        info.setAddedCount(countConsumed(merchantId));

        // 排程下一次：间隔 + 随机延迟
        info.setNextAddAt(System.currentTimeMillis()
                + (long) dataStore.getConfig().getIntervalSeconds() * 1000 + randomDelay());
        syncToDb(index);
    }
    
    /**
     * 获取第一个绑定的服务器ID
     */
    private Long getFirstServerId(Long merchantId) {
        try {
            List<EmuServerBinding> bindings = serverBindingRepository.findByMerchantId(merchantId);
            if (!bindings.isEmpty()) {
                return bindings.get(0).getServerId();
            }
            return null;
        } catch (Exception e) {
            log.warn("获取绑定服务器失败: {}", e.getMessage());
            return null;
        }
    }

    /** 统计商户已消耗的号码数（SUCCESS + FAILED） */
    private int countConsumed(Long merchantId) {
        try {
            int total = 0;
            List<EmuServerBinding> bindings = serverBindingRepository.findByMerchantId(merchantId);
            for (EmuServerBinding binding : bindings) {
                Long serverId = binding.getServerId();
                long success = memberRepository.countByGuildServerIdAndFriendStatus(serverId, EmuFriendPoolService.STATUS_SUCCESS);
                long failed = memberRepository.countByGuildServerIdAndFriendStatus(serverId, EmuFriendPoolService.STATUS_FAILED);
                total += (int) (success + failed);
            }
            return total;
        } catch (Exception e) {
            log.warn("统计已消耗号码数失败: {}", e.getMessage());
            return 0;
        }
    }
}
