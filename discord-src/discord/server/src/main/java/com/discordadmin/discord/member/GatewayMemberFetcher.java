package com.discordadmin.discord.member;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovisionaries.ws.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * 经 Discord Gateway(WebSocket) 拉取服务器成员
 *
 * <p>使用 nv-websocket-client 库（JDA 依赖）实现 WebSocket 连接，
 * 支持大消息处理和 zlib 压缩。
 */
public class GatewayMemberFetcher {

    private static final Logger log = LoggerFactory.getLogger(GatewayMemberFetcher.class);
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_.";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final double MIN_REQUEST_INTERVAL = 2.5;
    private static final int MAX_RECONNECT = 10;
    // 重连策略常量：初始5次重试 → 暂停5分钟 → 3次重试
    private static final int INITIAL_MAX_RECONNECTS = 5;
    private static final long PAUSE_DURATION_MS = 300000L;  // 5分钟
    private static final int FINAL_MAX_RECONNECTS = 3;
    private int maxPrefixDepth = 5;

    private static final Semaphore CONNECT_SEMAPHORE = new Semaphore(10);
    private static final AtomicInteger connectingCount = new AtomicInteger(0);

    private final String token;
    private final String guildId;
    private final String proxyHost;
    private final int proxyPort;
    private final boolean websocketDirect;  // WebSocket 直连模式
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, JsonNode> members = new ConcurrentHashMap<>();
    private final Set<String> existingMemberIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> guildName = new AtomicReference<>();
    private final AtomicInteger seq = new AtomicInteger(-1);
    private volatile WebSocket webSocket;
    private volatile boolean stop = false;

    /** 请求在当前请求完成后停止抓取 */
    public void stop() {
        this.stop = true;
    }
    private volatile long hbIntervalMs = 41250;
    private volatile String closeReason = null;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnects = new AtomicInteger(0);
    // 重连策略：初始5次重试 → 暂停5分钟 → 3次重试 → 最终失败
    private final AtomicInteger finalReconnectAttempts = new AtomicInteger(0);  // 暂停后的重试次数
    private final AtomicLong pauseUntilMs = new AtomicLong(0);  // 暂停截止时间
    private final AtomicBoolean isPaused = new AtomicBoolean(false);  // 是否处于暂停状态
    private final AtomicLong nextRetryAtMs = new AtomicLong(0);  // 下一次重试时间（用于进度显示）
    private final AtomicBoolean hbStarted = new AtomicBoolean(false);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final AtomicReference<GatewayException> lastError = new AtomicReference<>();

    private volatile CountDownLatch openLatch = new CountDownLatch(1);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);

    // 压缩消息处理
    private final Map<String, byte[]> pendingChunks = new ConcurrentHashMap<>();

    private final ProgressListener progress;
    private final MemberBatchListener memberBatchListener;
    private final int maxRequestsRef;
    private final int maxMembersRef;
    private final int pageDelayMs;

    // 用于分批保存的缓冲区
    private final List<JsonNode> pendingMembers = Collections.synchronizedList(new ArrayList<>());
    private static final int BATCH_SAVE_SIZE = 1;  // 每次请求收到后立即保存

    private final AtomicInteger requestsSent = new AtomicInteger(0);
    private String currentPrefix = "";
    private int prefixesDone = 0;
    private int prefixesTotal = 0;
    private final Queue<String> prefixQueue = new LinkedList<>();
    private final Set<String> visitedPrefixes = ConcurrentHashMap.newKeySet();

    // 深度级统计：追踪每层的扩展/剪枝情况
    private final Map<Integer, int[]> depthStats = new LinkedHashMap<>();  // depth -> [processed, expanded, pruned, no_expand]

    // 进度节流压缩：减少上报频率
    private static final long EMIT_INTERVAL_MS = 2000;  // 至少间隔 2 秒
    private static final int EMIT_PREFIX_BATCH = 5;     // 每 5 个前缀批量上报
    private long lastEmitTime = 0;
    private int pendingPrefixes = 0;

    private Set<String> completedPrefixesFromPrev = new HashSet<>();

    public GatewayMemberFetcher(String token, String guildId,
                                String proxyHost, int proxyPort,
                                ProgressListener progress,
                                int maxRequests, int maxMembers,
                                int pageDelayMs) {
        this(token, guildId, proxyHost, proxyPort, progress, null, maxRequests, maxMembers, pageDelayMs, false);
    }

    public GatewayMemberFetcher(String token, String guildId,
                                String proxyHost, int proxyPort,
                                ProgressListener progress,
                                int maxRequests, int maxMembers,
                                int pageDelayMs, boolean websocketDirect) {
        this(token, guildId, proxyHost, proxyPort, progress, null, maxRequests, maxMembers, pageDelayMs, websocketDirect);
    }

    public GatewayMemberFetcher(String token, String guildId,
                                String proxyHost, int proxyPort,
                                ProgressListener progress,
                                MemberBatchListener memberBatchListener,
                                int maxRequests, int maxMembers,
                                int pageDelayMs) {
        this(token, guildId, proxyHost, proxyPort, progress, memberBatchListener, maxRequests, maxMembers, pageDelayMs, false);
    }

    public GatewayMemberFetcher(String token, String guildId,
                                String proxyHost, int proxyPort,
                                ProgressListener progress,
                                MemberBatchListener memberBatchListener,
                                int maxRequests, int maxMembers,
                                int pageDelayMs, boolean websocketDirect) {
        this.token = token;
        this.guildId = guildId;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.websocketDirect = websocketDirect;
        this.progress = progress;
        this.memberBatchListener = memberBatchListener;
        this.maxRequestsRef = maxRequests;
        this.maxMembersRef = maxMembers;
        this.pageDelayMs = Math.max((int)(MIN_REQUEST_INTERVAL * 1000), pageDelayMs);
    }

    /** 断点续传：设置之前已完成的前缀 */
    public void setCompletedPrefixes(Set<String> completed) {
        this.completedPrefixesFromPrev = completed != null ? new HashSet<>(completed) : new HashSet<>();
    }

    /** 断点续传：设置剩余待处理的前缀队列（优先级高于 completedPrefixes） */
    public void setResumeFrontier(List<String> frontier) {
        if (frontier != null && !frontier.isEmpty()) {
            this.resumeFrontierOverride = new ArrayList<>(frontier);
            log.info("设置断点续传前缀队列: {} 个前缀", frontier.size());
        }
    }

    private List<String> resumeFrontierOverride = null;

    /** 断点续传：设置之前已采集的成员 ID 集合（用于去重，不存入 members map 以避免 null 元素） */
    public void setExistingMemberIds(Set<String> memberIds) {
        if (memberIds != null) {
            this.existingMemberIds.addAll(memberIds);
            log.info("加载已存在成员 ID {} 个用于去重", memberIds.size());
        }
    }

    /** 设置前缀树 BFS 最大下钻深度（默认5） */
    public void setMaxPrefixDepth(int depth) {
        this.maxPrefixDepth = Math.max(1, Math.min(depth, 10));
        log.info("设置前缀树最大下钻深度: {}", this.maxPrefixDepth);
    }

    // ------------------------------------------------------------------ //
    // 抓取入口
    // ------------------------------------------------------------------ //
    public FetchResult fetch() throws GatewayException {
        stop = false;
        closeReason = null;
        lastError.set(null);
        members.clear();
        pendingChunks.clear();
        pendingMembers.clear();
        currentRequestComplete.set(true);
        chunksReceived.set(0);
        reconnects.set(0);
        finalReconnectAttempts.set(0);
        pauseUntilMs.set(0);
        isPaused.set(false);
        nextRetryAtMs.set(0);
        disconnected.set(false);
        seq.set(-1);
        hbStarted.set(false);
        requestsSent.set(0);
        prefixesDone = 0;
        prefixesTotal = 0;
        lastRespondedCount.set(0);
        lastDedupedCount.set(0);
        lastRequestTimeMs.set(0);
        fetchStartTimeMs.set(System.currentTimeMillis());

        // 计算总页数（预估：假设每1000成员一页）
        // 使用 maxMembersRef / 1000 作为预估页数，最少1页
        prefixesTotal = Math.max(1, maxMembersRef / 1000);

        connect();

        try {
            emit("ready", "已连接 Discord Gateway，开始抓取成员...");
            
            if (!readyLatch.await(30, TimeUnit.SECONDS)) {
                throw new GatewayException("等待 Gateway READY 超时(30秒)，可能 Token 无效或网络不稳定", 408);
            }

            log.info("开始执行 fetchAll()，最大请求数={}, 最大成员数={}", maxRequestsRef, maxMembersRef);
            emit("fetching", "开始抓取成员...");
            fetchAll();

            log.info("fetchAll() 执行完成，成员数={}, 请求数={}", members.size(), requestsSent.get());
            emit("done", String.format("抓取完成：%d 名成员", members.size()));
        } catch (GatewayException e) {
            log.error("fetchAll() 抛出 GatewayException: code={}, msg={}", e.getCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getSimpleName();
            }
            log.error("fetchAll() 抛出未知异常: {}", msg, e);
            throw new GatewayException("抓取成员时出错: " + msg, 500);
        } finally {
            log.info("执行 flushAllRemainingMembers() 和 disconnect()");
            flushAllRemainingMembers();
            disconnect();
        }

        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : members.values()) {
            if (node != null) {
                result.add(node);
            }
        }
        return new FetchResult(guildName.get(), result);
    }

    /**
     * 使用前缀树 BFS 遍历抓取所有成员
     * 策略：每个前缀只发送一次请求（因为 query 模式下 after 参数无效）
     * 如果响应返回100条成员，说明还有更多数据，自动扩展38个子前缀继续抓取
     */
    private void fetchAll() throws GatewayException {
        log.info("开始前缀树 BFS 抓取，最大请求数={}, 最大成员数={}, 最大深度={}", maxRequestsRef, maxMembersRef, maxPrefixDepth);
        log.info("抓取参数: guildId={}, token长度={}, pageDelayMs={}", guildId, token != null ? token.length() : 0, pageDelayMs);
        lastEmitTime = 0;

        // 初始化前缀队列
        initPrefixes();

        int timeoutCount = 0;
        long startTime = System.currentTimeMillis();
        int loopCount = 0;
        int expandedTotal = 0;
        int prunedTotal = 0;
        int noExpandTotal = 0;
        String exitReason = "未知";
        String lastExpandedPrefix = "";

        while (!prefixQueue.isEmpty() && !stop) {
            loopCount++;
            
            // 每 50 个循环打印一次状态摘要
            if (loopCount % 50 == 0) {
                long elapsedMs = System.currentTimeMillis() - startTime;
                log.info("[状态检查] 循环次数={}, 队列大小={}, 已发送请求={}/{}, 成员数={}/{}, stop={}, disconnected={}, 耗时={}ms",
                        loopCount, prefixQueue.size(), requestsSent.get(), maxRequestsRef,
                        members.size(), maxMembersRef, stop, disconnected.get(), elapsedMs);
                // 打印深度统计
                logDepthStats();
            }
            
            if (disconnected.get()) {
                log.warn("检测到连接断开，尝试重连... (队列大小={}, 已发送请求={})", prefixQueue.size(), requestsSent.get());
                try {
                    reconnect();
                    currentRequestComplete.set(true);
                    log.info("重连成功，继续抓取...");
                } catch (GatewayException e) {
                    log.error("重连失败，抛出异常终止采集: {}", e.getMessage(), e);
                    throw e;
                }
            }

            if (requestsSent.get() >= maxRequestsRef) {
                log.info("达到最大请求数限制: {} (队列大小={}, 已处理前缀={})", maxRequestsRef, prefixQueue.size(), prefixesDone);
                exitReason = "达到最大请求数限制";
                emit("done", exitReason);
                break;
            }
            if (members.size() >= maxMembersRef) {
                log.info("达到最大成员数限制: {}", maxMembersRef);
                exitReason = "达到最大成员数限制";
                emit("done", exitReason);
                break;
            }

            String prefix = prefixQueue.poll();
            currentPrefix = prefix;
            int depth = prefix.length();
            int beforeSize = members.size();

            log.info("开始处理前缀 '{}' (深度={})", prefix, depth);

            try {
                // 每个前缀只请求一次（query模式下after参数无效）
                long requestStart = System.currentTimeMillis();
                int respondedCount = sendRequestGuildMembers(null);
                long requestTime = System.currentTimeMillis() - requestStart;
                requestsSent.incrementAndGet();

                // 累计统计
                totalRespondedAll.addAndGet(respondedCount);
                totalResponseTimeMs.addAndGet(requestTime);

                // 本次请求统计
                lastRespondedCount.set(respondedCount);
                lastRequestTimeMs.set(requestTime);

                if (respondedCount == 0) {
                    log.info("前缀 '{}' 响应为空或超时, 耗时={}ms", prefix, requestTime);
                    timeoutCount = 0;
                } else {
                    timeoutCount = 0;
                }

                visitedPrefixes.add(prefix);
                prefixesDone++;
                int newMemberCount = members.size() - beforeSize;
                lastDedupedCount.set(newMemberCount);
                log.info("前缀 '{}' 完成: 响应{}条, 去重后新增{}条, 耗时={}ms, 总计{} ({}/{})", 
                        prefix, respondedCount, newMemberCount, requestTime, members.size(), prefixesDone, prefixesTotal);

                // 更新深度统计
                depthStats.computeIfAbsent(depth, k -> new int[4]);
                depthStats.get(depth)[0]++; // processed

                // 剪枝/扩展判断
                if (respondedCount == 0) {
                    // 剪枝: 前缀返回 0 成员 → 该分支已无数据
                    prunedTotal++;
                    depthStats.get(depth)[2]++; // pruned
                    log.info("前缀 '{}' 返回 0 条，剪枝（不再下钻） | 深度统计: 处理{} 扩展{} 剪枝{} 不扩展{}",
                            prefix, depthStats.get(depth)[0], depthStats.get(depth)[1], depthStats.get(depth)[2], depthStats.get(depth)[3]);
                } else if (depth < maxPrefixDepth) {
                    // 扩展: 只要有数据且未到最大深度，就扩展子前缀
                    // 原因: Discord Gateway 使用 query 时返回最多100条，但可能存在更多匹配成员需要通过更精确的前缀细分来获取
                    List<String> subPrefixes = generateSubPrefixes(prefix);
                    int added = 0;
                    for (String sub : subPrefixes) {
                        if (!visitedPrefixes.contains(sub) && !prefixQueue.contains(sub)) {
                            prefixQueue.offer(sub);
                            added++;
                        }
                    }
                    expandedTotal++;
                    depthStats.get(depth)[1]++; // expanded
                    lastExpandedPrefix = prefix;
                    if (added > 0) {
                        if (respondedCount >= 100) {
                            log.info("前缀 '{}' 命中上限(100条)下钻: 扩展 {} 个子前缀 (深度={}) | 深度统计: 处理{} 扩展{} 剪枝{} 不扩展{}",
                                    prefix, added, depth + 1, depthStats.get(depth)[0], depthStats.get(depth)[1], depthStats.get(depth)[2], depthStats.get(depth)[3]);
                        } else {
                            log.info("前缀 '{}' 返回{}条 (< 100) 但仍扩展: 扩展 {} 个子前缀 (深度={}) | 深度统计: 处理{} 扩展{} 剪枝{} 不扩展{}",
                                    prefix, respondedCount, added, depth + 1, depthStats.get(depth)[0], depthStats.get(depth)[1], depthStats.get(depth)[2], depthStats.get(depth)[3]);
                        }
                    } else {
                        log.info("前缀 '{}' 有数据({}条)但所有子前缀已访问/已在队列中（添加0个）", prefix, respondedCount);
                    }
                } else {
                    // 不扩展: 已达最大深度
                    noExpandTotal++;
                    depthStats.get(depth)[3]++; // no_expand
                    log.info("前缀 '{}' 返回{}条 但已达最大深度{}，不再扩展 | 深度统计: 处理{} 扩展{} 剪枝{} 不扩展{}",
                            prefix, respondedCount, maxPrefixDepth, depthStats.get(depth)[0], depthStats.get(depth)[1], depthStats.get(depth)[2], depthStats.get(depth)[3]);
                }

                sleepQuietly(pageDelayMs);
            } catch (GatewayException e) {
                int status = e.getCode();
                log.warn("Gateway 错误: status={}, msg={}", status, e.getMessage());
                
                if (status == 503) {
                    timeoutCount++;
                    log.warn("Gateway 连接错误, 第 {} 次, 尝试重连", timeoutCount);
                    if (timeoutCount >= 5) {
                        log.error("连续失败过多（{}次），停止抓取", timeoutCount);
                        exitReason = "连续Gateway连接错误";
                        throw e;
                    }
                    try {
                        reconnect();
                        currentRequestComplete.set(true);
                        continue;
                    } catch (GatewayException re) {
                        log.error("重连失败: {}", re.getMessage());
                        exitReason = "重连失败";
                        throw re;
                    }
                } else {
                    exitReason = "Gateway错误: " + e.getMessage();
                    throw e;
                }
            }

            long now = System.currentTimeMillis();
            if ((now - lastEmitTime) >= EMIT_INTERVAL_MS) {
                emitProgress("fetching", prefix);
                lastEmitTime = now;
            }
        }

        // 循环结束日志：详细说明为什么结束
        long endTime = System.currentTimeMillis();
        long elapsedMs = endTime - startTime;

        // 分析退出原因（如果 break 时已设置 exitReason，则使用已设置的值）
        if (exitReason.equals("未知")) {
            if (stop) {
                exitReason = "stop标志被设置（可能是外部取消或连接断开导致）";
            } else if (prefixQueue.isEmpty()) {
                exitReason = "前缀队列已空（所有前缀处理完毕，无更多数据可采集）";
            }
            // 其他情况（如异常退出）会在 catch 块中设置 exitReason
        }

        log.info("========== 采集任务结束 ==========");
        log.info("【退出原因】{}", exitReason);
        log.info("循环次数={}, 队列是否为空={}, stop={}, 已断开={}", loopCount, prefixQueue.isEmpty(), stop, disconnected.get());
        log.info("最终状态: 队列大小={}, 已发送请求={}/{}, 成员数={}/{}, 已处理前缀={}/{}, 耗时={}ms",
                prefixQueue.size(), requestsSent.get(), maxRequestsRef,
                members.size(), maxMembersRef, prefixesDone, prefixesTotal, elapsedMs);
        log.info("深度统计汇总: 扩展总计={}, 剪枝总计={}, 不扩展总计={}, 最后扩展前缀={}",
                expandedTotal, prunedTotal, noExpandTotal, lastExpandedPrefix);
        log.info("各深度统计 [深度]: 处理/扩展/剪枝/不扩展");
        for (Map.Entry<Integer, int[]> entry : depthStats.entrySet()) {
            int[] s = entry.getValue();
            log.info("  深度{}: 处理={} 扩展={} 剪枝={} 不扩展={}", entry.getKey(), s[0], s[1], s[2], s[3]);
        }
        log.info("累计响应成员(不去重)={}, 累计响应时间={}ms", totalRespondedAll.get(), totalResponseTimeMs.get());

        // 计算完成率
        double requestRate = maxRequestsRef > 0 ? (requestsSent.get() * 100.0 / maxRequestsRef) : 0;
        double memberRate = maxMembersRef > 0 ? (members.size() * 100.0 / maxMembersRef) : 0;
        log.info("请求完成率: {}/{} ({:.1f}%), 成员采集率: {}/{} ({:.1f}%)",
                requestsSent.get(), maxRequestsRef, requestRate,
                members.size(), maxMembersRef, memberRate);

        emitProgress("fetching", "completed");
        log.info("全量抓取完成，共抓取 {} 名成员，发送 {} 个请求，完成 {} 个前缀", 
                members.size(), requestsSent.get(), prefixesDone);
    }

    private void logDepthStats() {
        if (depthStats.isEmpty()) return;
        StringBuilder sb = new StringBuilder("[深度统计] ");
        for (Map.Entry<Integer, int[]> entry : depthStats.entrySet()) {
            int[] s = entry.getValue();
            sb.append(String.format("D%d(处理=%d 扩=%d 剪=%d 不扩=%d) ", entry.getKey(), s[0], s[1], s[2], s[3]));
        }
        log.info(sb.toString());
    }

    /**
     * 前缀树 BFS: 生成子前缀列表
     * 当前缀命中100条时，扩展为38个子前缀
     */
    private List<String> generateSubPrefixes(String prefix) {
        List<String> subPrefixes = new ArrayList<>(ALPHABET.length());
        for (char c : ALPHABET.toCharArray()) {
            subPrefixes.add(prefix + c);
        }
        return subPrefixes;
    }

    /**
     * 初始化前缀队列（支持断点续传）
     */
    private void initPrefixes() {
        prefixQueue.clear();
        visitedPrefixes.clear();
        requestsSent.set(0);
        prefixesDone = 0;

        // 如果有断点续传的前缀队列，直接使用
        if (resumeFrontierOverride != null && !resumeFrontierOverride.isEmpty()) {
            for (String prefix : resumeFrontierOverride) {
                if (prefix != null && !prefix.isEmpty()) {
                    prefixQueue.offer(prefix);
                }
            }
            for (String prefix : completedPrefixesFromPrev) {
                visitedPrefixes.add(prefix);
            }
            prefixesTotal = ALPHABET.length();
            log.info("断点续传: 使用保存的前缀队列 {} 个，已完成 {} 个", 
                    prefixQueue.size(), completedPrefixesFromPrev.size());
            return;
        }

        // 38个前缀: a-z, 0-9, _, .
        for (char c : ALPHABET.toCharArray()) {
            String prefix = String.valueOf(c);
            if (!completedPrefixesFromPrev.contains(prefix)) {
                prefixQueue.offer(prefix);
            } else {
                visitedPrefixes.add(prefix);
            }
        }
        prefixesTotal = ALPHABET.length();
        
        if (!completedPrefixesFromPrev.isEmpty()) {
            log.info("断点续传: 已完成 {} 个前缀，剩余 {} 个", 
                    completedPrefixesFromPrev.size(), prefixQueue.size());
        }
    }

    private final AtomicInteger totalRespondedAll = new AtomicInteger(0);  // 累计响应成员数
    private final AtomicLong totalResponseTimeMs = new AtomicLong(0);     // 累计响应时间(ms)

    // 本次请求统计（用于实时展示）
    private final AtomicInteger lastRespondedCount = new AtomicInteger(0);   // 本次响应数
    private final AtomicInteger lastDedupedCount = new AtomicInteger(0);     // 本次去重数
    private final AtomicLong lastRequestTimeMs = new AtomicLong(0);          // 本次耗时(ms)
    private final AtomicLong fetchStartTimeMs = new AtomicLong(0);            // 采集开始时间

    private void emitProgress(String stage, String prefix) {
        if (progress == null) return;
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("stage", stage);
            info.put("p", prefix);                      // current page label
            info.put("r", requestsSent.get());         // requestsSent
            info.put("m", members.size());             // membersUnique (去重后)
            info.put("d", prefixesDone);               // pagesDone
            info.put("t", prefixesTotal);              // estimatedTotalPages
            info.put("x", reconnects.get());           // reconnects
            info.put("rm", totalRespondedAll.get());   // respondedMembers (响应总数)
            info.put("rt", totalResponseTimeMs.get()); // responseTimeMs (累计响应时间)
            // 本次请求统计
            info.put("lr", lastRespondedCount.get());  // 本次响应数
            info.put("ld", lastDedupedCount.get());    // 本次去重数
            info.put("lrt", lastRequestTimeMs.get()); // 本次耗时(ms)
            long elapsed = fetchStartTimeMs.get() > 0 ? (System.currentTimeMillis() - fetchStartTimeMs.get()) : 0;
            info.put("elapsed", elapsed);              // 总耗时(ms)
            // 同步设置的最大限制值
            info.put("maxRequests", maxRequestsRef);   // 最大请求数
            info.put("maxMembers", maxMembersRef);    // 最大成员数
            // 新增重连策略相关字段
            info.put("finalReconnectAttempts", finalReconnectAttempts.get()); // 最终重试次数
            info.put("isPaused", isPaused.get());      // 是否处于暂停状态
            info.put("nextRetryAtMs", nextRetryAtMs.get()); // 下一次重试时间戳
            info.put("maxInitialReconnects", INITIAL_MAX_RECONNECTS); // 初始最大重试次数
            info.put("maxFinalReconnects", FINAL_MAX_RECONNECTS);     // 最终最大重试次数
            info.put("pauseDurationMs", PAUSE_DURATION_MS);           // 暂停时长
            progress.onProgress(info);
        } catch (Exception ignore) {
        }
    }

    private final AtomicReference<String> lastMemberUserId = new AtomicReference<>();

    private int sendRequestGuildMembers(String afterUserId) throws GatewayException {
        if (disconnected.get()) {
            log.error("发送请求失败: WebSocket 已断开");
            throw new GatewayException("WebSocket 连接已断开", 503);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", 8); // Request Guild Members
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("guild_id", guildId);
        
        // 使用当前前缀作为 query 参数
        // Discord Gateway 规则: 使用 query 时每次最多返回 100 条
        // 要获取更多，需要多次分页
        String query = currentPrefix != null ? currentPrefix : "";
        data.put("limit", 100);
        data.put("query", query);
        
        if (afterUserId != null && !afterUserId.isEmpty()) {
            data.put("after", afterUserId);
        }
        payload.put("d", data);

        JsonNode root = mapper.valueToTree(payload);
        String json = root.toString();

        WebSocket ws = webSocket;
        if (ws == null || !ws.isOpen()) {
            log.error("发送请求失败: WebSocket 未连接 (null={}, isOpen={})", ws == null, ws != null && ws.isOpen());
            disconnected.set(true);
            throw new GatewayException("WebSocket 连接未建立或已关闭", 503);
        }

        int beforeSize = members.size();
        
        currentRequestComplete.set(false);
        chunksReceived.set(0);
        totalRespondedMembers.set(0);
        currentChunkIndex.set(-1);
        currentChunkCount.set(0);
        
        ws.sendText(json);
        log.info("→ 发送 Request Guild Members: guildId={}, query='{}', limit=100", 
                guildId, query);
        log.debug("→ Payload: {}", json);

        // 等待所有分块接收完成 (最多等待 10 秒，超时直接跳过)
        long timeoutMs = 10000;
        long timeoutAt = System.currentTimeMillis() + timeoutMs;
        long waitStart = System.currentTimeMillis();
        
        synchronized (currentRequestComplete) {
            while (!currentRequestComplete.get() && System.currentTimeMillis() < timeoutAt) {
                try {
                    currentRequestComplete.wait(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        long waitedMs = System.currentTimeMillis() - waitStart;
        boolean completed = currentRequestComplete.get();
        int afterSize = members.size();
        int newMemberCount = afterSize - beforeSize;
        int totalChunks = chunksReceived.get();
        int respondedCount = totalRespondedMembers.get();

        if (completed) {
            log.info("✓ Request Guild Members 完成: 耗时={}ms, chunks={}, 响应成员={}, 去重后新增={}, 总计={}",
                    waitedMs, totalChunks, respondedCount, newMemberCount, members.size());
        } else {
            log.warn("⏰ Request Guild Members 超时 ({}ms): chunks={}, 响应成员={}, 去重后新增={}, 跳过当前请求",
                    waitedMs, totalChunks, respondedCount, newMemberCount);
            // 超时直接跳过，返回0
            return 0;
        }

        return respondedCount;
    }

    private final AtomicReference<String> nextAfterUserId = new AtomicReference<>();

    /**
     * 快速探测代理是否可用于 WebSocket CONNECT 隧道。
     * 仅在配置了 proxyHost/proxyPort 时使用；不改变 restClient 的构造，
     * 只决定 WebSocket 是否走代理。
     *
     * @return true 表示代理支持 CONNECT 隧道（可用于 wss）
     */
    private static boolean isProxyUsableForWebSocket(String proxyHost, int proxyPort) {
        if (proxyHost == null || proxyHost.isBlank() || proxyPort <= 0) {
            return false;
        }
        java.net.Socket sock = null;
        try {
            sock = new java.net.Socket();
            sock.connect(new InetSocketAddress(proxyHost, proxyPort), 3000);
            sock.setSoTimeout(5000);
            java.io.OutputStream out = sock.getOutputStream();
            String connectReq = "CONNECT gateway.discord.gg:443 HTTP/1.1\r\n"
                    + "Host: gateway.discord.gg:443\r\n"
                    + "Proxy-Connection: Keep-Alive\r\n\r\n";
            out.write(connectReq.getBytes(StandardCharsets.UTF_8));
            out.flush();
            java.io.InputStream in = sock.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            if (n <= 0) {
                log.warn("代理 {}:{} CONNECT 探测失败: 无响应", proxyHost, proxyPort);
                return false;
            }
            String resp = new String(buf, 0, n, StandardCharsets.UTF_8);
            boolean ok = resp.startsWith("HTTP/1.1 200") || resp.startsWith("HTTP/1.0 200");
            log.info("代理 {}:{} CONNECT 探测结果: {} - {}", proxyHost, proxyPort, ok ? "OK" : "FAIL", resp.split("\r\n")[0]);
            return ok;
        } catch (Exception e) {
            log.warn("代理 {}:{} CONNECT 探测异常: {}", proxyHost, proxyPort, e.getMessage());
            return false;
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignore) {}
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 连接管理
    // ------------------------------------------------------------------ //
    private void connect() throws GatewayException {
        // 仅在初始连接时创建新的 latch，重连时复用
        if (!reconnecting.get()) {
            openLatch = new CountDownLatch(1);
            readyLatch = new CountDownLatch(1);
        }
        disconnected.set(false);
        pendingChunks.clear();
        lastError.set(null);

        boolean connectionAcquired = false;
        try {
            if (connectingCount.get() > 0) {
                log.info("GatewayMemberFetcher 等待连接信号量（当前 {} 个实例正在连接）...", connectingCount.get());
            }

            boolean acquired = CONNECT_SEMAPHORE.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new GatewayException("获取连接信号量超时，可能有其他实例正在连接，请稍后重试", 503);
            }
            connectingCount.incrementAndGet();
            connectionAcquired = true;
            log.info("GatewayMemberFetcher 已获取连接信号量，开始建立连接（当前连接数: {}）", connectingCount.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("获取连接信号量被中断", 500);
        }

        try {
            WebSocketFactory factory = new WebSocketFactory();
            factory.setConnectionTimeout(30000);
            factory.setSocketTimeout(60000);

            if (websocketDirect) {
                // 直连模式：跳过代理，直接连接 Discord Gateway
                log.info("WebSocket 直连模式：跳过代理，直接连接 Discord Gateway");
            } else {
                // 代理模式：探测代理是否可用于 WebSocket CONNECT 隧道
                boolean proxyUsable = isProxyUsableForWebSocket(proxyHost, proxyPort);
                if (proxyUsable) {
                    ProxySettings proxySettings = factory.getProxySettings();
                    proxySettings.setHost(proxyHost);
                    proxySettings.setPort(proxyPort);
                    log.info("WebSocketFactory 使用代理: {}:{}", proxyHost, proxyPort);
                } else if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                    log.warn("代理 {}:{} 不支持 WebSocket CONNECT 隧道或不可达，回退为直连 Discord", proxyHost, proxyPort);
                }
            }

            webSocket = factory.createSocket(GATEWAY_URL);
            webSocket.setMaxPayloadSize(64 * 1024 * 1024);
            webSocket.addHeader("User-Agent", UA);

            webSocket.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket ws, Map<String, List<String>> headers) {
                    log.info("WebSocket 已连接, maxPayloadSize={}", ws.getMaxPayloadSize());
                    openLatch.countDown();
                    sendIdentify();
                    startHeartbeat();
                }

                @Override
                public void onTextMessage(WebSocket ws, String text) {
                    if (text == null) return;
                    int msgSize = text.getBytes(StandardCharsets.UTF_8).length;
                    if (msgSize > 50 * 1024) {
                        log.warn("收到大文本消息: 大小={} bytes", msgSize);
                    }
                    handle(text);
                }

                @Override
                public void onBinaryMessage(WebSocket ws, byte[] binary) {
                    if (binary == null) return;
                    int msgSize = binary.length;
                    if (msgSize > 50 * 1024) {
                        log.warn("收到大二进制消息: 大小={} bytes", msgSize);
                    }
                    handleBinary(binary);
                }

                @Override
                public void onDisconnected(WebSocket ws, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    int code = serverCloseFrame != null ? serverCloseFrame.getCloseCode() : -1;
                    String reason = (serverCloseFrame != null && serverCloseFrame.getCloseReason() != null) 
                            ? serverCloseFrame.getCloseReason() : "无";
                    log.warn("WebSocket 断开: code={}, reason={}, closedByServer={}", code, reason, closedByServer);
                    closeReason = reason;
                    
                    if (stop) return;
                    
                    // 仅在非重连状态下才标记为断开
                    // 重连期间 onDisconnected 可能因旧连接关闭而触发，不应覆盖新连接的状态
                    if (!reconnecting.get()) {
                        disconnected.set(true);
                        
                        // 唤醒可能在等待旧 latch 的线程
                        openLatch.countDown();
                        readyLatch.countDown();
                    }
                    
                    GatewayException err = lastError.get();
                    if (err == null) {
                        lastError.set(new GatewayException(
                            "连接未建立或已关闭（code: " + code + "）", code));
                    }
                    
                    // 如果不在重连中，启动异步重连
                    if (!reconnecting.get() && reconnects.get() < MAX_RECONNECT) {
                        reconnecting.set(true);
                        // 在独立线程中执行重连，不阻塞回调线程
                        Thread reconnectThread = new Thread(() -> {
                            try {
                                reconnectInternal();
                            } catch (Exception e) {
                                log.error("异步重连失败: {}", e.getMessage());
                                lastError.set(new GatewayException(
                                    "重连失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), 500));
                            } finally {
                                reconnecting.set(false);
                            }
                        }, "gateway-reconnect-" + reconnects.incrementAndGet());
                        reconnectThread.setDaemon(true);
                        reconnectThread.start();
                    } else if (reconnects.get() >= MAX_RECONNECT) {
                        lastError.set(new GatewayException(
                            "重连次数已达上限（" + MAX_RECONNECT + " 次），连接无法恢复", code));
                    }
                }

                @Override
                public void onError(WebSocket ws, WebSocketException cause) {
                    log.error("WebSocket 错误: {}", cause != null ? cause.getMessage() : "unknown");
                    String errMsg = (cause != null && cause.getMessage() != null) 
                            ? cause.getMessage() : "未知错误";
                    lastError.set(new GatewayException("WebSocket 错误: " + errMsg, 500));
                }
            });

            log.info("正在连接 Discord Gateway...");
            webSocket.connect();

            if (!openLatch.await(30, TimeUnit.SECONDS)) {
                throw new GatewayException("WebSocket 连接超时(30秒)", 504);
            }

            if (!readyLatch.await(30, TimeUnit.SECONDS)) {
                throw new GatewayException("等待 Gateway READY 超时(30秒)，可能 Token 无效或网络不稳定", 408);
            }

            if (lastError.get() != null) {
                throw lastError.get();
            }

            log.info("✓ 成功连接 Discord Gateway");
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            log.error("WebSocket 连接失败", e);
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getSimpleName();
            }
            throw new GatewayException("无法连接 Discord Gateway: " + msg, 502);
        } finally {
            if (connectionAcquired) {
                connectingCount.decrementAndGet();
                CONNECT_SEMAPHORE.release();
            }
        }
    }

    private void disconnect() {
        stop = true;
        if (webSocket != null && webSocket.isOpen()) {
            try {
                webSocket.disconnect();
            } catch (Exception e) {
                log.debug("断开 WebSocket 时出错: {}", e.getMessage());
            }
        }
    }

    private void reconnectInternal() throws GatewayException {
        long now = System.currentTimeMillis();

        // 检查是否处于暂停状态
        if (isPaused.get()) {
            if (now < pauseUntilMs.get()) {
                // 仍在暂停期，等待到时间
                long remainingPauseMs = pauseUntilMs.get() - now;
                log.info("处于暂停状态，还有 {} 秒恢复重连", remainingPauseMs / 1000);
                nextRetryAtMs.set(pauseUntilMs.get());
                emitPauseCountdown(remainingPauseMs);
                sleepQuietly(remainingPauseMs);
                isPaused.set(false);
                log.info("暂停结束，开始尝试重连");
            } else {
                // 暂停时间已到但未被正确恢复
                isPaused.set(false);
                log.info("暂停时间已到，恢复重连");
            }
        }

        // 判断是否进入最终重试阶段
        int initialAttempt = reconnects.get();
        int finalAttempt = finalReconnectAttempts.get();

        if (!isPaused.get() && initialAttempt >= INITIAL_MAX_RECONNECTS) {
            // 初始5次重试已用完，进入暂停状态
            if (finalAttempt == 0) {
                log.info("初始 {} 次重试已全部用完，暂停 {} 秒后进行最终 {} 次重试",
                        INITIAL_MAX_RECONNECTS, PAUSE_DURATION_MS / 1000, FINAL_MAX_RECONNECTS);
                isPaused.set(true);
                pauseUntilMs.set(now + PAUSE_DURATION_MS);
                nextRetryAtMs.set(pauseUntilMs.get());
                emitPauseCountdown(PAUSE_DURATION_MS);
                sleepQuietly(PAUSE_DURATION_MS);
                isPaused.set(false);
            }
        }

        // 计算本次重试的次数和退避时间
        int attempt;
        long backoff;
        if (initialAttempt < INITIAL_MAX_RECONNECTS) {
            // 初始重试阶段
            attempt = reconnects.incrementAndGet();
            backoff = Math.min(30, 1L << (attempt - 1));
            log.info("连接断开，{} 秒后进行初始阶段第 {}/{} 次重连", backoff, attempt, INITIAL_MAX_RECONNECTS);
        } else {
            // 最终重试阶段
            attempt = finalReconnectAttempts.incrementAndGet();
            if (attempt > FINAL_MAX_RECONNECTS) {
                log.error("所有重连尝试已耗尽（初始{}次 + 最终{}次）", INITIAL_MAX_RECONNECTS, FINAL_MAX_RECONNECTS);
                throw new GatewayException("重连失败：所有重试次数已耗尽", 503);
            }
            backoff = Math.min(30, 1L << (attempt - 1));
            log.info("连接断开，{} 秒后进行最终阶段第 {}/{} 次重连", backoff, attempt, FINAL_MAX_RECONNECTS);
        }

        nextRetryAtMs.set(System.currentTimeMillis() + backoff * 1000);

        seq.set(-1);
        hbStarted.set(false);
        
        // 创建新的 latch 以便新一轮连接使用
        openLatch = new CountDownLatch(1);
        readyLatch = new CountDownLatch(1);
        
        sleepQuietly(backoff * 1000);

        try {
            disconnected.set(false);
            lastError.set(null);
            connect();
            // 重连成功，重置最终重试计数
            finalReconnectAttempts.set(0);
            nextRetryAtMs.set(0);
            log.info("重连成功");
        } catch (GatewayException e) {
            log.error("重连失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 发送暂停倒计时进度
     */
    private void emitPauseCountdown(long remainingMs) {
        if (progress == null) return;
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("stage", "paused");
            info.put("pauseRemainingMs", remainingMs);
            info.put("reconnects", reconnects.get());
            info.put("finalReconnectAttempts", finalReconnectAttempts.get());
            info.put("isPaused", true);
            info.put("nextRetryAtMs", nextRetryAtMs.get());
            progress.onProgress(info);
        } catch (Exception ignore) {
        }
    }

    /** 供外部调用的重连方法（由 fetch 主线程检测到断开时调用） */
    private void reconnect() throws GatewayException {
        if (reconnecting.get()) {
            log.info("已在重连中，等待完成...");
            // 等待重连完成（最长等待 10 分钟，以适应暂停期）
            int waitCount = 0;
            while (reconnecting.get() && waitCount < 1200) {  // 1200 * 500ms = 10 分钟
                sleepQuietly(500);
                waitCount++;
            }
            if (disconnected.get()) {
                throw new GatewayException("重连等待超时，连接未恢复", 503);
            }
            return;
        }
        
        reconnecting.set(true);
        try {
            reconnectInternal();
        } finally {
            reconnecting.set(false);
        }
    }

    // ------------------------------------------------------------------ //
    // Discord Gateway 协议处理
    // ------------------------------------------------------------------ //
    private void sendIdentify() {
        try {
            Map<String, Object> identify = new LinkedHashMap<>();
            identify.put("op", 2);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("token", token);
            // GUILD_MEMBERS = 1 << 1 = 2, 这是获取服务器成员所必需的意图
            int GUILD_MEMBERS_INTENT = 1 << 1;
            d.put("intents", GUILD_MEMBERS_INTENT);
            d.put("properties", Map.of(
                    "os", "MacOS",
                    "browser", "Chrome",
                    "device", "Mac"
            ));
            d.put("compress", true);
            d.put("large_threshold", 250);
            d.put("shard", new int[]{0, 1});
            identify.put("d", d);

            JsonNode root = mapper.valueToTree(identify);
            String json = root.toString();
            webSocket.sendText(json);
            log.info("发送 IDENTIFY: intents={}, tokenLength={}", GUILD_MEMBERS_INTENT, token != null ? token.length() : 0);
            log.debug("IDENTIFY payload: {}", json);
        } catch (Exception e) {
            log.error("发送 IDENTIFY 失败", e);
        }
    }

    private void startHeartbeat() {
        if (hbStarted.getAndSet(true)) return;

        Thread hbThread = new Thread(() -> {
            while (!stop && webSocket != null && webSocket.isOpen()) {
                try {
                    Map<String, Object> heartbeat = new LinkedHashMap<>();
                    heartbeat.put("op", 1);
                    heartbeat.put("d", System.currentTimeMillis());
                    JsonNode root = mapper.valueToTree(heartbeat);
                    webSocket.sendText(root.toString());
                    log.debug("发送心跳");
                } catch (Exception e) {
                    log.warn("发送心跳失败: {}", e.getMessage());
                    break;
                }
                sleepQuietly(hbIntervalMs);
            }
        }, "gateway-heartbeat");
        hbThread.setDaemon(true);
        hbThread.start();
    }

    private void handle(String text) {
        try {
            JsonNode root = mapper.readTree(text);
            int op = root.path("op").asInt(-1);
            JsonNode d = root.path("d");
            int s = root.path("s").asInt(-1);
            String t = root.path("t").asText(null);

            log.info("收到 Gateway 消息: op={}, s={}, t={}", op, s, t);

            switch (op) {
                case 0 -> { // DISPATCH
                    if (s >= 0) seq.set(s);
                    handleDispatch(t, d);
                }
                case 7 -> { // RECONNECT
                    log.info("收到 RECONNECT 指令");
                    if (!stop && !reconnecting.get()) {
                        reconnecting.set(true);
                        Thread reconnectThread = new Thread(() -> {
                            try {
                                reconnectInternal();
                            } catch (Exception e) {
                                log.error("RECONNECT 重连失败: {}", e.getMessage());
                            } finally {
                                reconnecting.set(false);
                            }
                        }, "gateway-reconnect-op7");
                        reconnectThread.setDaemon(true);
                        reconnectThread.start();
                    }
                }
                case 9 -> { // INVALID_SESSION
                    log.warn("收到 INVALID_SESSION，尝试重新连接");
                    if (!stop && !reconnecting.get()) {
                        reconnecting.set(true);
                        Thread reconnectThread = new Thread(() -> {
                            try {
                                sleepQuietly(1000);
                                reconnectInternal();
                            } catch (Exception e) {
                                log.error("INVALID_SESSION 重连失败: {}", e.getMessage());
                            } finally {
                                reconnecting.set(false);
                            }
                        }, "gateway-reconnect-op9");
                        reconnectThread.setDaemon(true);
                        reconnectThread.start();
                    }
                }
                case 10 -> { // HELLO
                    hbIntervalMs = d.path("heartbeat_interval").asLong(41250);
                    log.info("收到 HELLO，心跳间隔: {}ms", hbIntervalMs);
                }
                case 11 -> { // HEARTBEAT_ACK
                    log.debug("收到心跳 ACK");
                }
                default -> log.debug("未知 op={}", op);
            }
        } catch (Exception e) {
            log.error("处理 Gateway 消息失败", e);
        }
    }

    private void handleBinary(byte[] binary) {
        try {
            log.info("收到压缩二进制消息: 大小={} bytes", binary.length);
            byte[] decompressed = decompressZlib(binary);
            log.info("解压后大小={} bytes", decompressed.length);
            String text = new String(decompressed, StandardCharsets.UTF_8);
            handle(text);
        } catch (Exception e) {
            log.error("处理压缩消息失败", e);
        }
    }

    private byte[] decompressZlib(byte[] compressed) throws Exception {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] temp = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(temp);
                buffer.write(temp, 0, count);
            }
            return buffer.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private void handleDispatch(String type, JsonNode data) {
        if (type == null) return;

        switch (type) {
            case "READY" -> {
                guildName.set(data.path("guilds").path(0).path("name").asText(null));
                log.info("收到 READY，服务器: {}", guildName.get());
                readyLatch.countDown();
            }
            case "GUILD_CREATE" -> {
                String gId = data.path("id").asText(null);
                int memberCount = data.path("members").isArray() ? data.path("members").size() : 0;
                log.info("收到 GUILD_CREATE: guildId={}, 成员数={}", gId, memberCount);
                if (guildId.equals(gId) && memberCount > 0) {
                    JsonNode membersArray = data.path("members");
                    List<JsonNode> newMembers = new ArrayList<>();
                    for (JsonNode member : membersArray) {
                        String memberId = member.path("user").path("id").asText();
                        if (memberId.isEmpty()) continue;
                        if (!members.containsKey(memberId) && !existingMemberIds.contains(memberId)) {
                            members.put(memberId, member);
                            newMembers.add(member);
                        }
                    }
                    log.info("GUILD_CREATE 提取到 {} 名成员，总计 {} 名", newMembers.size(), members.size());
                    if (!newMembers.isEmpty() && memberBatchListener != null) {
                        pendingMembers.addAll(newMembers);
                        if (pendingMembers.size() >= BATCH_SAVE_SIZE) {
                            flushPendingMembers();
                        }
                    }
                }
            }
            case "GUILD_MEMBERS_CHUNK" -> {
                log.info("收到 GUILD_MEMBERS_CHUNK: chunk {}/{}, complete={}", 
                        data.path("chunk_index").asInt(-1),
                        data.path("chunk_count").asInt(0),
                        data.path("complete").asBoolean(false));
                handleGuildMembersChunk(data);
            }
            default -> log.info("未处理的 Gateway 事件: type={}", type);
        }
    }

    // 用于跟踪当前请求的所有分块是否都已接收
    private final AtomicInteger currentChunkIndex = new AtomicInteger(-1);
    private final AtomicInteger currentChunkCount = new AtomicInteger(0);
    private final AtomicBoolean currentRequestComplete = new AtomicBoolean(true);
    private final AtomicInteger chunksReceived = new AtomicInteger(0);  // 本次请求收到的分块数
    private final AtomicInteger totalRespondedMembers = new AtomicInteger(0);  // 本次请求实际响应的成员总数（不去重）

    private void handleGuildMembersChunk(JsonNode data) {
        chunksReceived.incrementAndGet();
        int chunkIndex = data.path("chunk_index").asInt(-1);
        int chunkCount = data.path("chunk_count").asInt(0);
        boolean complete = data.path("complete").asBoolean(false);
        JsonNode membersArray = data.path("members");
        int memberCount = membersArray.isArray() ? membersArray.size() : 0;
        
        // 累加实际响应的成员数（不去重）
        totalRespondedMembers.addAndGet(memberCount);
        
        log.info("⬅ 收到 GUILD_MEMBERS_CHUNK: chunk {}/{}, complete={}, members={}, totalResponded={}", 
                chunkIndex, chunkCount, complete, memberCount, totalRespondedMembers.get());
        
        if (membersArray.isArray()) {
            List<JsonNode> newMembers = new ArrayList<>();
            String lastUserId = null;
            
            for (JsonNode member : membersArray) {
                JsonNode userNode = member.path("user");
                if (userNode.isMissingNode()) continue;
                String memberId = userNode.path("id").asText();
                if (memberId.isEmpty()) continue;
                
                if (!members.containsKey(memberId) && !existingMemberIds.contains(memberId)) {
                    members.put(memberId, member);
                    newMembers.add(member);
                }
                lastUserId = memberId;
            }
            
            if (lastUserId != null) {
                lastMemberUserId.set(lastUserId);
            }
            
            if (newMembers.size() > 0 || memberCount > 0) {
                log.info("   分块处理: 总成员={}, 去重后新增={}, 已存在={}", 
                        memberCount, newMembers.size(), memberCount - newMembers.size());
            }

            if (!newMembers.isEmpty() && memberBatchListener != null) {
                pendingMembers.addAll(newMembers);
                if (pendingMembers.size() >= BATCH_SAVE_SIZE) {
                    flushPendingMembers();
                }
            }
        }

        currentChunkIndex.set(chunkIndex);
        currentChunkCount.set(chunkCount);
        
        log.info("   当前进度: 总计 {} 名成员, chunksReceived={}", members.size(), chunksReceived.get());
        
        // 判断请求完成的条件：
        // 1. Discord 明确返回 complete=true
        // 2. 或者已经收到所有 chunk（chunk_count > 0 && chunksReceived >= chunk_count）
        boolean allChunksReceived = chunkCount > 0 && chunksReceived.get() >= chunkCount;
        if (complete || allChunksReceived) {
            log.info("✓ 成员数据加载完成: chunks={}, 总成员={}, complete={}, allChunksReceived={}", 
                    chunkCount, members.size(), complete, allChunksReceived);
            currentRequestComplete.set(true);
            synchronized (currentRequestComplete) {
                currentRequestComplete.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 工具方法
    // ------------------------------------------------------------------ //
    
    /**
     * 获取剩余待处理的前缀队列（用于断点续传）
     */
    public List<String> getRemainingFrontier() {
        synchronized (prefixQueue) {
            return new ArrayList<>(prefixQueue);
        }
    }
    
    /**
     * 获取已完成的前缀集合（用于断点续传）
     */
    public Set<String> getCompletedPrefixes() {
        return new HashSet<>(visitedPrefixes);
    }
    
    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void emit(String stage, String message) {
        if (progress == null) return;
        try {
            // 计算当前进度百分比
            int pagesDone = requestsSent.get();
            int totalPages = Math.max(1, maxRequestsRef);
            String progressLabel = "page_" + pagesDone;
            
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("stage", stage);
            info.put("s", guildName.get());   // serverName
            info.put("p", progressLabel);     // current page label
            info.put("r", requestsSent.get());// requestsSent
            info.put("m", members.size());  // membersUnique
            info.put("d", pagesDone);        // pagesDone
            info.put("t", totalPages);       // totalPages (maxRequestsRef)
            info.put("x", reconnects.get());  // reconnects
            info.put("msg", message);        // message
            // maxMembers 和 maxRequests 只在 ready 阶段传递
            if ("ready".equals(stage)) {
                info.put("M", maxMembersRef);   // maxMembers
                info.put("R", maxRequestsRef);  // maxRequests
            }
            progress.onProgress(info);
        } catch (Exception ignore) {
        }
    }

    // ------------------------------------------------------------------ //
    // 分批保存
    // ------------------------------------------------------------------ //
    private void flushPendingMembers() {
        if (pendingMembers.isEmpty() || memberBatchListener == null) return;
        try {
            List<JsonNode> batch = new ArrayList<>(pendingMembers);
            pendingMembers.clear();
            memberBatchListener.onBatchMembers(batch);
        } catch (Exception e) {
            log.warn("分批保存成员失败: {}", e.getMessage());
        }
    }

    /** 在 fetch() 结束时调用，确保所有剩余数据被保存 */
    public void flushAllRemainingMembers() {
        flushPendingMembers();
    }

    // ------------------------------------------------------------------ //
    // 结果 & 异常
    // ------------------------------------------------------------------ //
    public record FetchResult(String serverName, List<JsonNode> members) {}

    public interface ProgressListener {
        void onProgress(Map<String, Object> info);
    }

    public interface MemberBatchListener {
        void onBatchMembers(List<JsonNode> members);
    }

    public static class GatewayException extends Exception {
        private final int code;
        public GatewayException(String message, int code) {
            super(message);
            this.code = code;
        }
        public int getCode() {
            return code;
        }
    }
}
