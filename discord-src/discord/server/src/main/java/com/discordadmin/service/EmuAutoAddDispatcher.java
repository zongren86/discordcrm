package com.discordadmin.service;

import com.discordadmin.entity.EmuInstance;
import com.discordadmin.entity.EmuServerBinding;
import com.discordadmin.entity.GuildMember;
import com.discordadmin.model.AutoAddConfig;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟器自动加好友调度器（新流程：加一台 → 关一台 → 间隔到再开）
 *
 * 调度规则：
 * 1. 读取 autoRunning=true 的模拟器记录（按当前商户+用户）
 * 2. 已在 runningBusy 中的跳过（正在执行本次加好友流程）
 * 3. 候选：nextAddAt == null 或 nextAddAt <= now（达到加好友间隔，否则不可启动）
 * 4. 按 nextAddAt 升序 + instanceIndex 升序排序，逐台提交到 worker
 * 5. 启动之间相隔 emulatorStartIntervalSec 秒（排队启动，防止瞬时打爆资源）
 * 6. 每台模拟器单次生命周期（worker内）：
 *    - 启动模拟器（若已 STOPPED）
 *    - 打开 Discord 并进入首页
 *    - 检测登录态：已登录就用现有登录态，不强制重新登录
 *    - 【生产模式】：从好友号池取 1 个 PENDING 目标 → 调用 DiscordService.addFriendByUsername → 写结果 → 立即关闭模拟器
 *    - 【测试模式】：不执行加好友动作，把登录/首页/打开结果写入最后添加结果字段 → 立即关闭模拟器
 *    - 设置 nextAddAt = now + intervalSeconds + [delayMin..delayMax] 随机延迟
 *    - 下一次 tick 到时重新启动模拟器 + 重新打开 Discord + 进入首页 + 执行动作
 */
@Slf4j
@Service
public class EmuAutoAddDispatcher {

    private final EmuInstanceRepository instanceRepository;
    private final MumuClientService mumuClientService;
    private final DiscordService discordService;
    private final DataStoreService dataStore;
    private final GuildMemberRepository memberRepository;
    private final EmuFriendPoolService friendPoolService;
    private final EmuServerBindingRepository serverBindingRepository;

    /** 已经在 worker 中执行生命周期的模拟器 dbIndex（1-based），避免 tick 重复提交 */
    private final Set<Integer> runningBusy = ConcurrentHashMap.newKeySet();
    /** 当前并发计数（运行中的 worker 数） */
    private final AtomicInteger concurrencyCount = new AtomicInteger(0);
    /** 专用 worker 池（限制线程数 = 最大并发上限 200，避免cached线程无限扩增） */
    private final ExecutorService worker = Executors.newFixedThreadPool(200, r -> {
        Thread t = new Thread(r, "auto-add-dispatcher");
        t.setDaemon(true);
        return t;
    });
    /** 排队启动节流：上一次启动的时间戳（ms），用于每启动一台间隔 N 秒 */
    private volatile long lastLaunchAt = 0L;

    public EmuAutoAddDispatcher(EmuInstanceRepository instanceRepository,
                                MumuClientService mumuClientService,
                                DiscordService discordService,
                                DataStoreService dataStore,
                                GuildMemberRepository memberRepository,
                                EmuFriendPoolService friendPoolService,
                                EmuServerBindingRepository serverBindingRepository) {
        this.instanceRepository = instanceRepository;
        this.mumuClientService = mumuClientService;
        this.discordService = discordService;
        this.dataStore = dataStore;
        this.memberRepository = memberRepository;
        this.friendPoolService = friendPoolService;
        this.serverBindingRepository = serverBindingRepository;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        Long merchantId = SecurityUtils.currentMerchantId();
        String userId = SecurityUtils.currentUserId();
        if (merchantId == null) merchantId = 1L;
        if (userId == null) userId = "default";

        AutoAddConfig cfg = dataStore.getConfig();
        int maxCon = cfg.getMaxConcurrentEmulators();
        Instant now = Instant.now();

        List<EmuInstance> all = instanceRepository.findByMerchantIdAndUserId(merchantId, userId);
        // 候选：autoRunning=true + 不在busy中 + 间隔达标
        List<EmuInstance> candidates = new ArrayList<>();
        for (EmuInstance e : all) {
            if (!Boolean.TRUE.equals(e.getAutoRunning())) continue;
            if (runningBusy.contains(e.getInstanceIndex())) continue;
            if (e.getNextAddAt() != null && e.getNextAddAt().isAfter(now)) continue;
            candidates.add(e);
        }
        // 按 nextAddAt ASC（null最小，视为立刻需要），再 instanceIndex ASC
        candidates.sort((a, b) -> {
            Instant na = a.getNextAddAt(), nb = b.getNextAddAt();
            if (na == null && nb == null) return Integer.compare(a.getInstanceIndex(), b.getInstanceIndex());
            if (na == null) return -1;
            if (nb == null) return 1;
            int c = na.compareTo(nb);
            return c != 0 ? c : Integer.compare(a.getInstanceIndex(), b.getInstanceIndex());
        });

        for (EmuInstance emu : candidates) {
            int curCon = concurrencyCount.get();
            if (curCon >= maxCon) break; // 达到并发上限，等下一轮

            int dbIndex = emu.getInstanceIndex();
            int expected = curCon;
            if (!concurrencyCount.compareAndSet(expected, expected + 1)) continue; // CAS失败，跳过等待下一轮
            if (!runningBusy.add(dbIndex)) {
                concurrencyCount.decrementAndGet();
                continue;
            }
            final Long merchantIdF = merchantId;
            final String userIdF = userId;

            // 排队启动间隔：距离上一台启动的时间间隔必须 >= emulatorStartIntervalSec
            long intervalMs = cfg.getEmulatorStartIntervalSec() * 1000L;
            long waitMs;
            synchronized (this) {
                long nowMs = System.currentTimeMillis();
                long earliestNext = lastLaunchAt + intervalMs;
                waitMs = Math.max(0L, earliestNext - nowMs);
                if (waitMs == 0L) lastLaunchAt = nowMs;
                else lastLaunchAt = earliestNext;
            }
            final long sleepMs = waitMs;
            final AutoAddConfig cfgF = cfg;
            final EmuInstance emuF = emu;

            worker.submit(() -> {
                try {
                    if (sleepMs > 0) Thread.sleep(sleepMs);
                    runOneLifecycle(emuF, cfgF, merchantIdF, userIdF);
                } catch (InterruptedException ie) {
                    log.warn("调度启动被中断 emu#{}", dbIndex);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("模拟器#{} 生命周期执行异常", dbIndex, e);
                    safeUpdateLastError(dbIndex, merchantIdF, userIdF, "调度异常: " + truncate(e.getMessage(), 250));
                } finally {
                    runningBusy.remove(dbIndex);
                    concurrencyCount.decrementAndGet();
                }
            });
        }
    }

    /** 单次生命周期：启动 → 开Discord → 加好友/测试 → 关闭模拟器 → 写入nextAddAt */
    private void runOneLifecycle(EmuInstance emu, AutoAddConfig cfg, Long merchantId, String userId) {
        int dbIndex = emu.getInstanceIndex();
        int mumuIndex = dbIndex - 1;
        boolean testMode = cfg.isTestModeEnabled();
        Instant startTs = Instant.now();

        // ========== Step 1: 启动模拟器（STOPPED才启动）==========
        EmuInstance fresh = refresh(emu);
        if (fresh == null) {
            log.warn("模拟器#{} 记录已被删除，跳过", dbIndex);
            return;
        }
        if (fresh.getStatus() == null || fresh.getStatus() != EmuInstance.EmuStatus.RUNNING) {
            log.info("调度启动：模拟器#{} 状态={}，启动物理模拟器", dbIndex, fresh.getStatus());
            try {
                Map<String, Object> r = mumuClientService.startEmulator(dbIndex);
                // Mumu startEmulator 返回启动结果；等待完全启动
                Thread.sleep(8000);
                log.info("模拟器#{} 启动指令返回：{}", dbIndex, shortStr(r));
            } catch (Exception e) {
                log.error("模拟器#{} 启动失败", dbIndex, e);
                writeResult(dbIndex, merchantId, userId, testMode,
                        false, "模拟器启动失败: " + truncate(e.getMessage(), 200),
                        null, null, null, startTs);
                scheduleNextAndClose(dbIndex, merchantId, userId, cfg, false);
                return;
            }
            // 同步DB状态
            fresh = refresh(dbIndex, merchantId, userId);
            if (fresh != null) {
                fresh.setStatus(EmuInstance.EmuStatus.RUNNING);
                fresh.setLastError(null);
                fresh.setUpdatedAt(Instant.now());
                instanceRepository.save(fresh);
            }
        }

        // ========== Step 2: 打开Discord进入首页 ==========
        boolean discordReady = false;
        boolean discordLoggedIn = false;
        String discordUser = null;
        String openErr = null;
        try {
            // 检查/安装DS状态（跳过，前端控制）
            boolean opened = discordService.isDiscordForeground(mumuIndex);
            if (!opened) {
                String launch = discordService.launchDiscord(mumuIndex);
                if (!"SUCCESS".equals(launch)) {
                    openErr = "打开Discord失败: " + truncate(launch, 150);
                }
            }
            if (openErr == null) {
                Thread.sleep(3000);
                boolean onLogin = discordService.isOnLoginPage(mumuIndex);
                boolean logged = !onLogin && discordService.isDiscordLoggedIn(mumuIndex);
                discordLoggedIn = logged;
                if (logged) {
                    discordUser = discordService.getLoggedInUser(mumuIndex);
                } else if (onLogin) {
                    openErr = "Discord停在登录页，需要手动登录账号";
                } else {
                    openErr = "Discord已打开但未检测到登录态";
                }
                discordReady = logged; // 未登录的话视为不具备加好友条件
                // 同步状态到DB
                fresh = refresh(dbIndex, merchantId, userId);
                if (fresh != null) {
                    fresh.setDiscordLoggedIn(discordLoggedIn);
                    fresh.setDiscordOnHome(discordReady);
                    fresh.setDiscordAccountName(discordUser);
                    fresh.setUpdatedAt(Instant.now());
                    instanceRepository.save(fresh);
                }
            }
        } catch (Exception e) {
            log.error("模拟器#{} 打开Discord异常", dbIndex, e);
            openErr = "打开Discord异常: " + truncate(e.getMessage(), 150);
        }

        // ========== Step 3: 测试模式/生产模式分支 ==========
        if (testMode) {
            // 测试：不添加好友；只把打开/登录状态作为最后添加结果回传
            boolean ok = discordReady;
            String msg = ok ? "【测试】Discord已进入首页，账号登录: " + (discordUser != null ? discordUser : "(已登录)")
                           : "【测试】" + (openErr != null ? openErr : "打开Discord未就绪");
            writeResult(dbIndex, merchantId, userId, true, ok, msg, discordLoggedIn, discordOnHome(discordReady), discordUser, startTs);
            scheduleNextAndClose(dbIndex, merchantId, userId, cfg, true);
            return;
        }

        // === 生产模式：加一个好友 ===
        if (!discordReady) {
            writeResult(dbIndex, merchantId, userId, false,
                    false, "无法添加: " + (openErr == null ? "Discord未就绪" : openErr),
                    null, null, null, startTs);
            scheduleNextAndClose(dbIndex, merchantId, userId, cfg, false);
            return;
        }

        Long serverId = resolveServerId(merchantId, userId, dbIndex);
        if (serverId == null) {
            writeResult(dbIndex, merchantId, userId, false,
                    false, "无法添加: 未绑定服务器", null, null, null, startTs);
            scheduleNextAndClose(dbIndex, merchantId, userId, cfg, false);
            return;
        }

        List<GuildMember> pending = memberRepository.findPendingByGuildServerId(serverId);
        if (pending == null || pending.isEmpty()) {
            writeResult(dbIndex, merchantId, userId, false,
                    false, "号池为空，没有待添加好友", null, null, null, startTs);
            // 号池空了停止该模拟器，避免反复启动
            stopAutoRunning(dbIndex, merchantId, userId);
            scheduleNextAndClose(dbIndex, merchantId, userId, cfg, false);
            return;
        }

        GuildMember target = pending.get(0);
        String username = (target.getUsername() != null && !target.getUsername().isBlank())
                ? target.getUsername() : target.getUserId();
        // 标记已分配
        target.setFriendStatus(friendPoolService.STATUS_ASSIGNED);
        target.setEmulatorIndex(dbIndex);
        target.setStartedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        memberRepository.save(target);

        String addRes;
        try {
            addRes = discordService.addFriendByUsername(mumuIndex, username);
        } catch (Exception e) {
            log.error("模拟器#{} 添加好友异常 user={}", dbIndex, username, e);
            addRes = "ERROR: " + truncate(e.getMessage(), 150);
        }

        boolean success = addRes != null && addRes.startsWith("SUCCESS");
        if (success) {
            target.setFriendStatus(friendPoolService.STATUS_SUCCESS);
            target.setLastError(null);
        } else {
            target.setFriendStatus(friendPoolService.STATUS_FAILED);
            target.setLastError(truncate(addRes, 255));
        }
        target.setFinishedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
        memberRepository.save(target);

        // 更新addedCount（到EmuInstance）
        int countNow = countByEmulatorIndex(dbIndex);
        int addedInc = success ? countNow + 1 : countNow;
        writeResult(dbIndex, merchantId, userId, false, success,
                (success ? "添加成功: " : "添加失败: ") + username + (success ? "" : (" -> " + truncate(addRes, 100))),
                discordLoggedIn, discordOnHome(true), discordUser, startTs);
        fresh = refresh(dbIndex, merchantId, userId);
        if (fresh != null) {
            fresh.setAddedCount(addedInc);
            instanceRepository.save(fresh);
        }
        scheduleNextAndClose(dbIndex, merchantId, userId, cfg, success);
    }

    /** 关闭模拟器（生产/测试均要关闭）；写入nextAddAt */
    private void scheduleNextAndClose(int dbIndex, Long merchantId, String userId, AutoAddConfig cfg, boolean actionHappened) {
        // 关模拟器
        try {
            log.info("模拟器#{} 完成本次任务，关闭模拟器", dbIndex);
            mumuClientService.stopEmulator(dbIndex);
            Thread.sleep(2000);
        } catch (Exception e) {
            log.warn("模拟器#{} 关闭失败", dbIndex, e);
        }
        // 计算 nextAddAt：intervalSeconds + [delayMin, delayMax] 随机
        long nextTs = System.currentTimeMillis()
                + (long) cfg.getIntervalSeconds() * 1000
                + randomDelayMs(cfg);
        Instant next = Instant.ofEpochMilli(nextTs);
        EmuInstance e = refresh(dbIndex, merchantId, userId);
        if (e != null) {
            e.setStatus(EmuInstance.EmuStatus.STOPPED);
            e.setNextAddAt(next);
            e.setDiscordOnHome(false);
            e.setUpdatedAt(Instant.now());
            instanceRepository.save(e);
        }
    }

    /** 写 autoLastResult / lastError / discordLoggedIn / discordOnHome / discordAccountName 到 EmuInstance（即"回传给后台模拟器列表"） */
    private void writeResult(int dbIndex, Long merchantId, String userId, boolean testMode,
                             boolean ok, String msg, Boolean discordLoggedIn, Boolean onHome, String accountName,
                             Instant startTs) {
        EmuInstance e = refresh(dbIndex, merchantId, userId);
        if (e == null) return;
        e.setAutoLastResult(msg != null ? truncate(msg, 256) : null);
        e.setLastError(ok ? null : truncate(msg, 512));
        if (discordLoggedIn != null) e.setDiscordLoggedIn(discordLoggedIn);
        if (onHome != null) e.setDiscordOnHome(onHome);
        if (accountName != null && !accountName.isBlank()) e.setDiscordAccountName(accountName);
        e.setUpdatedAt(Instant.now());
        instanceRepository.save(e);
    }

    private void stopAutoRunning(int dbIndex, Long merchantId, String userId) {
        EmuInstance e = refresh(dbIndex, merchantId, userId);
        if (e == null) return;
        e.setAutoRunning(false);
        e.setUpdatedAt(Instant.now());
        instanceRepository.save(e);
    }

    private void safeUpdateLastError(int dbIndex, Long merchantId, String userId, String msg) {
        try { stopAutoRunning(dbIndex, merchantId, userId);
            EmuInstance e = refresh(dbIndex, merchantId, userId);
            if (e != null) {
                e.setLastError(truncate(msg, 512));
                e.setUpdatedAt(Instant.now());
                instanceRepository.save(e);
            }
        } catch (Exception ignore) {}
    }

    private long randomDelayMs(AutoAddConfig cfg) {
        int min = cfg.getDelayMinSeconds(), max = cfg.getDelayMaxSeconds();
        if (max <= min) return (long) min * 1000;
        return (long) (min + new Random().nextInt(max - min + 1)) * 1000;
    }

    private EmuInstance refresh(EmuInstance e) {
        if (e == null || e.getId() == null) return null;
        return instanceRepository.findById(e.getId()).orElse(null);
    }
    private EmuInstance refresh(int dbIndex, Long merchantId, String userId) {
        return instanceRepository.findByMerchantIdAndUserIdAndInstanceIndex(merchantId, userId, dbIndex).orElse(null);
    }
    private int countByEmulatorIndex(int dbIndex) {
        try {
            long assigned = memberRepository.countAssignedByEmulatorIndex(dbIndex);
            long success = memberRepository.countSuccessByEmulatorIndex(dbIndex);
            long failed = memberRepository.countFailedByEmulatorIndex(dbIndex);
            return (int)(assigned + success + failed);
        } catch (Exception e) { return 0; }
    }
    private Long resolveServerId(Long merchantId, String userId, int dbIndex) {
        EmuInstance e = refresh(dbIndex, merchantId, userId);
        if (e != null && e.getGuildServerId() != null) return e.getGuildServerId();
        try {
            List<EmuServerBinding> bindings = serverBindingRepository.findByMerchantId(merchantId);
            return bindings == null || bindings.isEmpty() ? null : bindings.get(0).getServerId();
        } catch (Exception ex) { return null; }
    }
    private static boolean discordOnHome(boolean ready) { return ready; }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static String shortStr(Object o) {
        if (o == null) return "null";
        try { String s = String.valueOf(o); return s.length() <= 120 ? s : s.substring(0, 120); }
        catch (Exception e) { return o.getClass().getSimpleName(); }
    }
}
