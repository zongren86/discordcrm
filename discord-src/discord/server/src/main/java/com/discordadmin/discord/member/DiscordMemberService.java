package com.discordadmin.discord.member;

import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord 成员采集服务（编排层）。
 * 支持断点续抓、批量去重入库。
 */
@Service
public class DiscordMemberService {

    private static final Logger log = LoggerFactory.getLogger(DiscordMemberService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "discord-member-fetch");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    // 服务器锁：防止同一服务器并发同步
    private final Map<Long, String> serverLocks = new ConcurrentHashMap<>();
    // 活跃 fetcher 引用，用于暂停/停止
    private final Map<String, GatewayMemberFetcher> activeFetchers = new ConcurrentHashMap<>();

    private final GuildMemberRepository guildMemberRepository;
    private final GuildServerRepository guildServerRepository;
    private final FetchProgressRepository fetchProgressRepository;
    private final FriendRepository friendRepository;

    @Value("${discord.proxy.host:}")
    private String proxyHost;

    @Value("${discord.proxy.port:0}")
    private int proxyPort;

    public DiscordMemberService(GuildMemberRepository guildMemberRepository,
                                 GuildServerRepository guildServerRepository,
                                 FetchProgressRepository fetchProgressRepository,
                                 FriendRepository friendRepository) {
        this.guildMemberRepository = guildMemberRepository;
        this.guildServerRepository = guildServerRepository;
        this.fetchProgressRepository = fetchProgressRepository;
        this.friendRepository = friendRepository;
    }

    /**
     * 服务启动时，从数据库恢复未完成的任务状态
     */
    @PostConstruct
    public void restoreTasks() {
        try {
            List<FetchProgress> runningProgresses = fetchProgressRepository
                    .findAll().stream()
                    .filter(p -> "RUNNING".equals(p.getStatus()) || "PENDING".equals(p.getStatus()))
                    .toList();

            if (!runningProgresses.isEmpty()) {
                log.info("发现 {} 个未完成的任务，正在恢复状态...", runningProgresses.size());
                for (FetchProgress progress : runningProgresses) {
                    // 将未完成的任务标记为 FAILED（因为后端重启了，任务无法继续）
                    progress.setStatus("FAILED");
                    progress.setErrorMessage("后端重启导致任务中断");
                    progress.setFailureReason("后端重启导致任务中断");
                    progress.setCompletedAt(Instant.now());
                    fetchProgressRepository.save(progress);

                    // 创建任务状态供前端查询
                    String taskId = "restored_" + progress.getId();
                    TaskState st = new TaskState();
                    st.guildServerId = progress.getGuildServerId();
                    st.discordAccountId = progress.getDiscordAccountId();
                    st.guildId = progress.getGuildId() != null ? progress.getGuildId() : "";
                    st.status = "FAILED";
                    st.progressMessage = "任务因后端重启中断，可重新同步";
                    st.requestsSent = progress.getRequestCount() != null ? progress.getRequestCount() : 0;
                    st.membersUnique = progress.getRawMemberCount() != null ? progress.getRawMemberCount() : 0;
                    st.prefixesDone = progress.getCompletedPages() != null ? progress.getCompletedPages() : 0;
                    st.prefixesTotal = progress.getTotalPages() != null ? progress.getTotalPages() : 0;
                    st.reconnects = progress.getRetryCount() != null ? progress.getRetryCount() : 0;
                    st.error = "后端重启导致任务中断";
                    st.failureReason = "后端重启导致任务中断";
                    st.totalRespondedMembers = progress.getTotalRespondedMembers() != null ? progress.getTotalRespondedMembers() : 0;
                    st.totalResponseTimeMs = progress.getTotalResponseTimeMs() != null ? progress.getTotalResponseTimeMs() : 0L;
                    st.lastPrefix = progress.getLastPrefix() != null ? progress.getLastPrefix() : "";
                    st.startedAt = progress.getStartedAt() != null ? progress.getStartedAt().toEpochMilli() : null;
                    st.completedAt = progress.getCompletedAt() != null ? progress.getCompletedAt().toEpochMilli() : System.currentTimeMillis();
                    tasks.put(taskId, st);
                }
                log.info("已恢复 {} 个任务状态", runningProgresses.size());
            }
        } catch (Exception e) {
            log.warn("恢复任务状态失败: {}", e.getMessage());
        }
    }

    /** 单个采集任务的状态 */
    public static class TaskState {
        public String status = "PENDING";
        public String progressMessage = "初始化...";
        public String guildId = "";
        public String serverName = "";
        public List<MemberRecord> records = new ArrayList<>();
        public int totalFetched = 0;
        public String error = "";
        public String currentPrefix = "";
        public int requestsSent = 0;
        public int membersUnique = 0;
        public int prefixesDone = 0;
        public int prefixesTotal = 0;
        public int reconnects = 0;
        public int maxDepth = 5;
        public int maxRequests = 1000;
        public int maxMembers = 2000000;
        public long guildServerId = 0;
        public Long discordAccountId;
        public String lastBatchId = "";
        public Long progressId;  // 本次任务关联的 FetchProgress ID
        /** 上次同步停止时尚未处理完的前缀队列，用于断点续抓 */
        public List<String> resumeFrontier = new ArrayList<>();
        /** 历史已完成的前缀集合，用于增量续采 */
        public Set<String> completedPrefixes = new LinkedHashSet<>();
        /** 本次完成的前缀集合 */
        public Set<String> newlyCompletedPrefixes = new LinkedHashSet<>();
        
        // 详细统计信息（用于结果展示）
        /** 累计响应成员数（不去重） */
        public int totalRespondedMembers = 0;
        /** 累计响应时间（ms） */
        public long totalResponseTimeMs = 0;
        /** 采集开始时间 */
        public Long startedAt = null;
        /** 采集结束时间 */
        public Long completedAt = null;
        /** 最后处理的前缀 */
        public String lastPrefix = "";
        /** 失败/中断原因 */
        public String failureReason = "";
        
        // 本次请求统计（用于实时展示）
        /** 本次响应数 */
        public int lastResponded = 0;
        /** 本次去重数 */
        public int lastDeduped = 0;
        /** 本次耗时(ms) */
        public long lastRequestTimeMs = 0;
        /** 总耗时(ms) */
        public long elapsedMs = 0;
    }

    /**
     * 启动一次采集，立即返回任务 ID
     */
    public String startFetch(MemberFetchRequest req) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TaskState st = new TaskState();
        st.maxDepth = req.getMaxDepth();
        st.maxRequests = req.getMaxRequests();
        st.maxMembers = req.getMaxMembers();
        st.guildServerId = req.getGuildServerId() != null ? req.getGuildServerId() : 0;
        st.discordAccountId = req.getDiscordAccountId();

        // 检查同服务器是否有正在运行的任务
        if (st.guildServerId > 0) {
            String existingTaskId = serverLocks.get(st.guildServerId);
            if (existingTaskId != null && tasks.containsKey(existingTaskId)) {
                TaskState existing = tasks.get(existingTaskId);
                if ("RUNNING".equals(existing.status) || "PENDING".equals(existing.status)) {
                    log.warn("服务器 {} 已有正在运行的同步任务 {}，拒绝新任务", st.guildServerId, existingTaskId);
                    throw new IllegalStateException("该服务器正在同步中，请等待完成后再试");
                }
                // 旧任务已完成，清理锁
                serverLocks.remove(st.guildServerId);
            }
        }

        // 检查是否有上次的抓取进度，支持断点续抓 + 增量续采
        if (st.guildServerId > 0 && req.isResumeSync()) {
            fetchProgressRepository.findTopByGuildServerIdOrderByCreatedAtDesc(st.guildServerId)
                .ifPresent(prev -> {
                    st.lastBatchId = prev.getLastBatchId() != null ? prev.getLastBatchId() : "";
                    // 始终加载已完成前缀和剩余前缀，支持增量同步
                    st.resumeFrontier = fromJson(prev.getResumeFrontier());
                    st.completedPrefixes = new LinkedHashSet<>(fromJson(prev.getCompletedPrefixes()));

                    if ("FAILED".equals(prev.getStatus())) {
                        // 失败：从断点续抓
                        if (st.resumeFrontier.isEmpty() && st.completedPrefixes.isEmpty()) {
                            log.info("断点续抓: guildServerId={}, 无剩余前缀也无已完成前缀，改为全量扫描", st.guildServerId);
                            st.completedPrefixes.clear();
                        } else {
                            log.info("断点续抓: guildServerId={}, lastBatchId={}, 剩余前缀 {} 个, 已完成前缀 {} 个",
                                    st.guildServerId, st.lastBatchId, st.resumeFrontier.size(), st.completedPrefixes.size());
                        }
                    } else if ("COMPLETED".equals(prev.getStatus()) && !st.resumeFrontier.isEmpty()) {
                        // 上次完成但仍有未处理完的前缀（理论上不常见），继续从断点续抓
                        log.info("增量续抓: guildServerId={}, lastBatchId={}, 继续处理剩余前缀 {} 个",
                                st.guildServerId, st.lastBatchId, st.resumeFrontier.size());
                    } else {
                        // 增量同步：跳过已完成前缀，只处理新前缀
                        log.info("增量同步: guildServerId={}, lastBatchId={}, 已完成前缀 {} 个, 跳过已处理",
                                st.guildServerId, st.lastBatchId, st.completedPrefixes.size());
                    }
                });
        }

        tasks.put(taskId, st);

        // 创建 FetchProgress 记录
        FetchProgress progress = new FetchProgress();
        progress.setGuildServerId(req.getGuildServerId() != null ? req.getGuildServerId() : 0L);
        progress.setDiscordAccountId(req.getDiscordAccountId());
        progress.setGuildId(req.getLink());
        progress.setStatus("RUNNING");
        progress.setStartedAt(Instant.now());
        progress.setMaxRequests(req.getMaxRequests());
        progress.setMaxMembers(req.getMaxMembers());
        progress = fetchProgressRepository.save(progress);  // 保存并获取ID
        st.progressId = progress.getId();  // 关联进度ID

        // 加锁：标记该服务器正在同步
        if (st.guildServerId > 0) {
            serverLocks.put(st.guildServerId, taskId);
        }

        pool.submit(() -> runTask(taskId, req));
        return taskId;
    }

    private void runTask(String taskId, MemberFetchRequest req) {
        TaskState st = tasks.get(taskId);
        if (st == null) return;

        st.status = "RUNNING";
        st.startedAt = System.currentTimeMillis();

        FetchProgress progress = fetchProgressRepository.findTopByGuildServerIdOrderByCreatedAtDesc(st.guildServerId)
            .orElse(null);

        GatewayMemberFetcher fetcher = null;
        try {
            String guildId = resolveGuildId(req.getLink());
            st.guildId = guildId;
            st.progressMessage = "已解析服务器 ID: " + guildId + "，连接 Discord Gateway...";

            if (progress != null) {
                progress.setGuildId(guildId);
                progress.setStatus("RUNNING");
                fetchProgressRepository.save(progress);
            }

            GatewayMemberFetcher.ProgressListener listener = info -> {
                TaskState t = tasks.get(taskId);
                if (t == null) return;

                String stage = (String) info.getOrDefault("stage", "");
                t.currentPrefix = (String) info.getOrDefault("p", "");
                t.requestsSent = safeInt(info, "r");
                t.membersUnique = safeInt(info, "m");
                t.prefixesDone = safeInt(info, "d");
                t.prefixesTotal = safeInt(info, "t");
                t.reconnects = safeInt(info, "x");
                int respondedMembers = safeInt(info, "rm");
                long responseTimeMs = safeLong(info, "rt");

                // 本次请求统计
                t.lastResponded = safeInt(info, "lr");
                t.lastDeduped = safeInt(info, "ld");
                t.lastRequestTimeMs = safeLong(info, "lrt");
                t.elapsedMs = safeLong(info, "elapsed");
                
                // 更新详细统计
                t.totalRespondedMembers = respondedMembers;
                t.totalResponseTimeMs = responseTimeMs;
                if (t.currentPrefix != null && !t.currentPrefix.isEmpty()) {
                    t.lastPrefix = t.currentPrefix;
                }

                // ready 阶段获取服务器名称和配置
                if ("ready".equals(stage) && info.get("s") != null) {
                    t.serverName = (String) info.get("s");
                }

                // 构建进度消息
                StringBuilder progressMsg = new StringBuilder();
                progressMsg.append("[").append(stage).append("] ");
                if ("fetching".equals(stage)) {
                    // fetching 阶段：请求批次 前缀 本次响应数量 本次去重数
                    progressMsg.append(t.requestsSent).append(" ");
                    progressMsg.append(t.currentPrefix != null ? t.currentPrefix : "-").append(" ");
                    progressMsg.append(t.lastResponded).append(" ");
                    progressMsg.append(t.lastDeduped);
                } else {
                    String msg = (String) info.get("msg");
                    if (msg != null) progressMsg.append(msg);
                    progressMsg.append(" | ").append(t.currentPrefix);
                    progressMsg.append(" ").append(t.requestsSent).append("请求");
                    progressMsg.append(" 响应").append(respondedMembers).append("条");
                    progressMsg.append(" 去重").append(t.membersUnique).append("条");
                    progressMsg.append(" ").append(t.prefixesDone).append("/").append(t.prefixesTotal);
                    progressMsg.append(" 耗时").append(responseTimeMs / 1000.0).append("s");
                    progressMsg.append(" ").append(t.reconnects).append("重连");
                }
                t.progressMessage = progressMsg.toString();

                // 节流保存：每 5 次上报保存一次，或在关键节点保存
                if (progress != null) {
                    boolean isCritical = "ready".equals(stage) || "done".equals(stage);
                    if (isCritical || t.prefixesDone % 5 == 0 || t.prefixesDone == t.prefixesTotal) {
                        progress.setRequestCount(t.requestsSent);
                        progress.setRawMemberCount(t.membersUnique);
                        progress.setCompletedPages(t.prefixesDone);
                        progress.setTotalPages(t.prefixesTotal);
                        progress.setRetryCount(t.reconnects);
                        progress.setTotalRespondedMembers(t.totalRespondedMembers);
                        progress.setTotalResponseTimeMs(t.totalResponseTimeMs);
                        progress.setLastPrefix(t.lastPrefix);
                        fetchProgressRepository.save(progress);
                    }
                }
            };

            int pageDelayMs = (int) (req.getPageDelay() * 1000);

            // 分批保存监听器：在采集过程中实时保存成员数据
            List<MemberRecord> allRecords = Collections.synchronizedList(new ArrayList<>());
            GatewayMemberFetcher.MemberBatchListener batchListener = batch -> {
                try {
                    List<MemberRecord> batchRecs = normalize(batch, st.serverName, req.getMaxMembers());
                    allRecords.addAll(batchRecs);
                    if (st.guildServerId > 0 && !batchRecs.isEmpty()) {
                        batchSaveMembers(st.guildServerId, guildId, batchRecs);
                    }
                } catch (Exception e) {
                    log.warn("分批保存失败: {}", e.getMessage());
                }
            };

            fetcher = new GatewayMemberFetcher(
                    req.getToken(), guildId, proxyHost, proxyPort,
                    listener, batchListener, req.getMaxRequests(), req.getMaxMembers(), pageDelayMs);

            // 注册 fetcher 引用，用于后续停止
            activeFetchers.put(taskId, fetcher);

            // 设置前缀树 BFS 最大下钻深度
            fetcher.setMaxPrefixDepth(req.getMaxDepth());

            // 断点续传：设置之前已完成的前缀
            if (!st.completedPrefixes.isEmpty()) {
                fetcher.setCompletedPrefixes(st.completedPrefixes);
                log.info("断点续传: 设置已完成前缀 {} 个", st.completedPrefixes.size());
            }

            // 断点续传：传递剩余待处理的前缀队列
            if (!st.resumeFrontier.isEmpty()) {
                fetcher.setResumeFrontier(st.resumeFrontier);
                log.info("断点续传: 设置剩余前缀队列 {} 个", st.resumeFrontier.size());
            }

            // 断点续传：加载已存在的成员 ID 集合（用于去重）
            if (st.guildServerId > 0) {
                List<GuildMember> existingMembers = guildMemberRepository.findByGuildServerId(st.guildServerId);
                if (!existingMembers.isEmpty()) {
                    Set<String> existingIds = new HashSet<>();
                    for (GuildMember m : existingMembers) {
                        existingIds.add(m.getUserId());
                    }
                    fetcher.setExistingMemberIds(existingIds);
                    log.info("断点续传: 加载已存在成员 {} 个", existingIds.size());
                }
            }

            GatewayMemberFetcher.FetchResult res = fetcher.fetch();

            st.serverName = res.serverName() != null ? res.serverName() : "";
            List<MemberRecord> recs = normalize(res.members(), st.serverName, req.getMaxMembers());
            st.records = recs;
            st.totalFetched = res.members().size();
            st.status = "COMPLETED";
            st.completedAt = System.currentTimeMillis();
            if (st.startedAt != null) {
                st.elapsedMs = st.completedAt - st.startedAt;
            }
            st.progressMessage = "完成，有效成员 " + recs.size() + " 条（原始 " + res.members().size() + " 条）";
            st.failureReason = "";  // 成功无失败原因

            Set<String> completedPrefixes = new LinkedHashSet<>(fetcher.getCompletedPrefixes());
            st.newlyCompletedPrefixes = new LinkedHashSet<>(completedPrefixes);
            st.completedPrefixes.addAll(completedPrefixes);

            // 数据已在分批保存过程中入库，这里仅更新服务器统计信息
            if (st.guildServerId > 0) {
                guildServerRepository.findById(st.guildServerId).ifPresent(server -> {
                    long total = guildMemberRepository.countByGuildServerId(st.guildServerId);
                    server.setMemberCount((int) total);
                    server.setLastFetchAt(Instant.now());
                    guildServerRepository.save(server);

                    // 匹配好友（使用完整记录）
                    if (!recs.isEmpty()) {
                        matchFriendsToGuild(st.guildServerId, server.getName(), recs);
                    }
                });
            }

            if (progress != null) {
                progress.setStatus("COMPLETED");
                progress.setRawMemberCount(res.members().size());
                progress.setDedupedMemberCount(recs.size());
                progress.setCompletedAt(Instant.now());
                String marker = (st.currentPrefix != null && !st.currentPrefix.isEmpty())
                        ? st.currentPrefix
                        : "batch_" + System.currentTimeMillis();
                progress.setLastBatchId(marker);
                progress.setLastPrefix(st.lastPrefix);
                progress.setTotalRespondedMembers(st.totalRespondedMembers);
                progress.setTotalResponseTimeMs(st.totalResponseTimeMs);
                progress.setFailureReason("");
                progress.setResumeFrontier(toJson(fetcher.getRemainingFrontier()));
                progress.setCompletedPrefixes(toJson(new ArrayList<>(fetcher.getCompletedPrefixes())));
                fetchProgressRepository.save(progress);
            }

        } catch (GatewayMemberFetcher.GatewayException e) {
            st.status = "FAILED";
            String msg = e.getMessage() != null ? e.getMessage() : "未知网关异常";
            st.error = msg + (e.getCode() != 0 ? " (code " + e.getCode() + ")" : "");
            st.progressMessage = "错误: " + st.error;
            st.completedAt = System.currentTimeMillis();
            st.failureReason = st.error;

            if (progress != null) {
                progress.setStatus("FAILED");
                progress.setErrorMessage(st.error);
                progress.setCompletedAt(Instant.now());
                progress.setFailureReason(st.error);
                progress.setLastPrefix(st.lastPrefix);
                progress.setTotalRespondedMembers(st.totalRespondedMembers);
                progress.setTotalResponseTimeMs(st.totalResponseTimeMs);
                if (st.currentPrefix != null && !st.currentPrefix.isEmpty()) {
                    progress.setLastBatchId(st.currentPrefix);
                }
                if (fetcher != null) {
                    progress.setResumeFrontier(toJson(fetcher.getRemainingFrontier()));
                    progress.setCompletedPrefixes(toJson(new ArrayList<>(fetcher.getCompletedPrefixes())));
                }
                fetchProgressRepository.save(progress);
            }
            log.error("成员抓取失败: {}", e.getMessage(), e);
        } catch (Exception e) {
            st.status = "FAILED";
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getSimpleName();
            }
            st.error = msg;
            st.progressMessage = "异常: " + msg;
            st.completedAt = System.currentTimeMillis();
            if (st.startedAt != null) {
                st.elapsedMs = st.completedAt - st.startedAt;
            }
            st.failureReason = msg;

            if (progress != null) {
                progress.setStatus("FAILED");
                progress.setErrorMessage(st.error);
                progress.setCompletedAt(Instant.now());
                progress.setFailureReason(msg);
                progress.setLastPrefix(st.lastPrefix);
                progress.setTotalRespondedMembers(st.totalRespondedMembers);
                progress.setTotalResponseTimeMs(st.totalResponseTimeMs);
                if (st.currentPrefix != null && !st.currentPrefix.isEmpty()) {
                    progress.setLastBatchId(st.currentPrefix);
                }
                if (fetcher != null) {
                    progress.setResumeFrontier(toJson(fetcher.getRemainingFrontier()));
                    progress.setCompletedPrefixes(toJson(new ArrayList<>(fetcher.getCompletedPrefixes())));
                }
                fetchProgressRepository.save(progress);
            }
            log.error("成员抓取异常", e);
        } finally {
            // 清理 fetcher 引用
            activeFetchers.remove(taskId);
            
            if (st.guildServerId > 0) {
                String lockedTaskId = serverLocks.get(st.guildServerId);
                if (taskId.equals(lockedTaskId)) {
                    serverLocks.remove(st.guildServerId);
                    log.info("服务器 {} 的同步任务 {} 已释放锁", st.guildServerId, taskId);
                }
            }
        }
    }

    /**
     * 批量去重存入数据库（增量处理）：
     * - 已存在的成员：用拉取到的最新数据更新
     * - 不存在的成员：插入
     * - 不删除任何已保存的数据
     */
    private void batchSaveMembers(Long guildServerId, String guildId, List<MemberRecord> records) {
        // 仅查询当前批次涉及的 userId，避免全表扫描
        List<String> userIds = records.stream().map(MemberRecord::getUserId).toList();
        List<GuildMember> existing = guildMemberRepository.findByGuildServerIdAndUserIdIn(guildServerId, userIds);
        Map<String, GuildMember> existingById = new HashMap<>();
        for (GuildMember m : existing) {
            existingById.put(m.getUserId(), m);
        }

        List<GuildMember> toSave = new ArrayList<>();
        int inserted = 0;
        int updated = 0;

        for (MemberRecord rec : records) {
            GuildMember gm = existingById.get(rec.getUserId());
            if (gm == null) {
                gm = new GuildMember();
                gm.setGuildServerId(guildServerId);
                gm.setGuildId(guildId);
                gm.setUserId(rec.getUserId());
                inserted++;
            } else {
                updated++;
            }
            applyMemberFields(gm, rec);
            toSave.add(gm);
        }

        // 批量保存（每 100 条一批，每批 flush 确保数据立即可查）
        int batchSize = 100;
        for (int i = 0; i < toSave.size(); i += batchSize) {
            List<GuildMember> batch = toSave.subList(i, Math.min(i + batchSize, toSave.size()));
            guildMemberRepository.saveAllAndFlush(batch);
        }

        log.info("批量保存成员: 新增 {} 条，更新 {} 条，远端共 {} 条（不删除本地已有数据）",
                inserted, updated, records.size());
    }

    /**
     * 手动触发匹配：将指定服务器的所有成员与好友进行关联。
     */
    public long matchAllFriendsToServer(Long guildServerId) {
        GuildServer server = guildServerRepository.findById(guildServerId).orElse(null);
        if (server == null) {
            log.warn("服务器不存在，无法匹配好友: guildServerId={}", guildServerId);
            return 0;
        }

        List<GuildMember> members = guildMemberRepository.findByGuildServerId(guildServerId);
        if (members.isEmpty()) {
            log.info("服务器无成员，跳过好友匹配: guildServerId={}", guildServerId);
            return 0;
        }

        List<MemberRecord> records = members.stream().map(gm -> {
            MemberRecord rec = new MemberRecord();
            rec.setUserId(gm.getUserId());
            rec.setUsername(gm.getUsername());
            return rec;
        }).toList();

        int beforeMatch = 0;
        String serverName = server.getName();
        List<String> userIds = members.stream()
                .map(GuildMember::getUserId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .toList();

        if (userIds.isEmpty()) return 0;

        List<Friend> friends = friendRepository.findByFriendDiscordUserIdIn(userIds);
        Map<String, Friend> friendMap = new HashMap<>();
        for (Friend f : friends) {
            friendMap.put(f.getFriendDiscordUserId(), f);
        }

        List<Friend> toUpdate = new ArrayList<>();
        for (GuildMember gm : members) {
            Friend friend = friendMap.get(gm.getUserId());
            if (friend != null) {
                friend.setGuildServerId(guildServerId);
                friend.setServerName(serverName);
                friend.setServerMatched(true);
                friend.setSyncedAt(Instant.now());
                toUpdate.add(friend);
            }
        }

        if (!toUpdate.isEmpty()) {
            friendRepository.saveAll(toUpdate);
            log.info("手动匹配：已匹配 {} 个好友到服务器「{}」（ID={}）",
                    toUpdate.size(), serverName, guildServerId);
        }

        return toUpdate.size();
    }

    /**
     * 通过用户ID匹配好友与服务器，更新Friend表的服务器关联信息。
     */
    private void matchFriendsToGuild(Long guildServerId, String serverName, List<MemberRecord> records) {
        List<String> userIds = records.stream()
                .map(MemberRecord::getUserId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .toList();

        if (userIds.isEmpty()) return;

        List<Friend> friends = friendRepository.findByFriendDiscordUserIdIn(userIds);
        if (friends.isEmpty()) {
            log.info("服务器成员匹配：无匹配好友（服务器ID={}, 名称={}, 成员数={}）",
                    guildServerId, serverName, userIds.size());
            return;
        }

        Map<String, Friend> friendMap = new HashMap<>();
        for (Friend f : friends) {
            friendMap.put(f.getFriendDiscordUserId(), f);
        }

        List<Friend> toUpdate = new ArrayList<>();
        for (MemberRecord rec : records) {
            Friend friend = friendMap.get(rec.getUserId());
            if (friend != null) {
                friend.setGuildServerId(guildServerId);
                friend.setServerName(serverName);
                friend.setServerMatched(true);
                friend.setSyncedAt(Instant.now());
                toUpdate.add(friend);
            }
        }

        if (!toUpdate.isEmpty()) {
            friendRepository.saveAll(toUpdate);
            log.info("服务器成员匹配：已匹配 {} 个好友到服务器「{}」（ID={}）",
                    toUpdate.size(), serverName, guildServerId);
        }
    }

    /**
     * 用远端拉取的数据填充成员字段（新增与更新共用）。
     */
    private void applyMemberFields(GuildMember gm, MemberRecord rec) {
        gm.setUsername(rec.getUsername());
        gm.setNick(rec.getNick());
        gm.setGlobalName(rec.getGlobalName());
        gm.setDisplayName(rec.getDisplayName());
        gm.setAvatarUrl(rec.getAvatarUrl());
        gm.setIsBot(rec.getIsBot());
        gm.setLastFetchedAt(Instant.now());

        if (rec.getJoinedAt() != null && !rec.getJoinedAt().isEmpty()) {
            try {
                gm.setJoinedAt(Instant.parse(rec.getJoinedAt()));
            } catch (Exception ignored) {}
        }

        if (rec.getRoles() != null) {
            gm.setRoles(rec.getRoles());
        }
    }

    /**
     * 解析服务器链接/ID
     */
    public String resolveGuildId(String link) throws GatewayMemberFetcher.GatewayException {
        if (link == null || link.isBlank()) {
            throw new GatewayMemberFetcher.GatewayException("服务器链接不能为空", 0);
        }
        String s = link.trim();
        if (s.matches("\\d{10,}")) {
            return s;
        }
        Matcher m = Pattern.compile("/channels/(\\d{10,})").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        throw new GatewayMemberFetcher.GatewayException("无法解析服务器链接（仅支持服务器数字 ID 或频道 URL）", 0);
    }

    /**
     * 解析 URL 获取 guildId 和 channelId
     */
    public Map<String, String> resolveUrl(String url) {
        Map<String, String> result = new HashMap<>();
        if (url == null || url.isBlank()) return result;

        Matcher m = Pattern.compile("/channels/(\\d{10,})/(\\d{10,})").matcher(url.trim());
        if (m.find()) {
            result.put("guildId", m.group(1));
            result.put("channelId", m.group(2));
        } else {
            Matcher m2 = Pattern.compile("/channels/(\\d{10,})").matcher(url.trim());
            if (m2.find()) {
                result.put("guildId", m2.group(1));
            }
        }
        return result;
    }

    /**
     * 将原始成员归一化为统一结构
     */
    public List<MemberRecord> normalize(List<JsonNode> raw, String serverName, int maxMembers) {
        List<MemberRecord> out = new ArrayList<>();
        int idx = 0;
        String now = LocalDateTime.now().format(FMT);

        // 用于内存去重
        Set<String> seenIds = new HashSet<>();

        for (JsonNode m : raw) {
            if (idx >= maxMembers) break;
            if (m == null) continue;  // 防御性 null 检查

            JsonNode u = m.path("user");
            String userId = u.path("id").asText("");
            if (seenIds.contains(userId)) continue;
            seenIds.add(userId);

            MemberRecord r = new MemberRecord();
            r.setUserId(userId);
            r.setUsername(u.path("username").asText(""));
            r.setGlobalName(u.path("global_name").asText(""));
            r.setNick(m.path("nick").asText(""));
            String display = !r.getGlobalName().isEmpty() ? r.getGlobalName()
                    : (!r.getNick().isEmpty() ? r.getNick() : r.getUsername());
            r.setDisplayName(display);
            r.setJoinedAt(m.path("joined_at").asText(""));
            r.setServerName(serverName);
            r.setSource("gateway");
            r.setFetchedAt(now);
            r.setIndex(++idx);
            r.setIsBot(u.path("bot").asBoolean(false));

            JsonNode roles = m.path("roles");
            if (roles.isArray()) {
                List<String> roleNames = new ArrayList<>();
                for (JsonNode role : roles) {
                    roleNames.add(role.asText(""));
                }
                r.setRoles(String.join(",", roleNames));
            }

            out.add(r);
        }
        return out;
    }

    public TaskState getTask(String taskId) {
        return tasks.get(taskId);
    }

    public Map<String, TaskState> getTasks() {
        return tasks;
    }

    /**
     * 请求任务在当前请求完成后停止（暂停同步）。
     * 停止后任务状态标记为 COMPLETED，保留已采集数据。
     * @return true 表示已发送停止请求，false 表示任务不存在或已结束
     */
    public boolean stopFetch(String taskId) {
        TaskState st = tasks.get(taskId);
        if (st == null) {
            return false;
        }
        if (!"RUNNING".equals(st.status) && !"PENDING".equals(st.status)) {
            return false;
        }
        GatewayMemberFetcher fetcher = activeFetchers.get(taskId);
        if (fetcher != null) {
            fetcher.stop();
            log.info("已请求停止任务 {}, 将在当前请求完成后停止", taskId);
            return true;
        }
        return false;
    }

    /**
     * 从数据库获取指定服务器最近的任务记录
     */
    public TaskState getLatestTaskForServer(Long guildServerId) {
        Optional<FetchProgress> latest = fetchProgressRepository
                .findTopByGuildServerIdOrderByCreatedAtDesc(guildServerId);

        if (latest.isEmpty()) {
            return null;
        }

        FetchProgress progress = latest.get();
        TaskState st = new TaskState();
        st.guildServerId = progress.getGuildServerId();
        st.discordAccountId = progress.getDiscordAccountId();
        st.guildId = progress.getGuildId() != null ? progress.getGuildId() : "";
        st.status = progress.getStatus() != null ? progress.getStatus() : "UNKNOWN";
        st.requestsSent = progress.getRequestCount() != null ? progress.getRequestCount() : 0;
        st.membersUnique = progress.getRawMemberCount() != null ? progress.getRawMemberCount() : 0;
        st.prefixesDone = progress.getCompletedPages() != null ? progress.getCompletedPages() : 0;
        st.prefixesTotal = progress.getTotalPages() != null ? progress.getTotalPages() : 0;
        st.reconnects = progress.getRetryCount() != null ? progress.getRetryCount() : 0;
        st.error = progress.getErrorMessage() != null ? progress.getErrorMessage() : "";
        st.progressMessage = buildProgressMessage(progress);

        // 填充结果统计字段
        st.totalRespondedMembers = progress.getTotalRespondedMembers() != null ? progress.getTotalRespondedMembers() : 0;
        st.totalResponseTimeMs = progress.getTotalResponseTimeMs() != null ? progress.getTotalResponseTimeMs() : 0L;
        st.lastPrefix = progress.getLastPrefix() != null ? progress.getLastPrefix() : "";
        st.failureReason = progress.getFailureReason() != null ? progress.getFailureReason() : "";
        st.startedAt = progress.getStartedAt() != null ? progress.getStartedAt().toEpochMilli() : null;
        st.completedAt = progress.getCompletedAt() != null ? progress.getCompletedAt().toEpochMilli() : null;
        st.maxRequests = progress.getMaxRequests() != null ? progress.getMaxRequests() : 1000;
        st.maxMembers = progress.getMaxMembers() != null ? progress.getMaxMembers() : 2000000;

        // 计算总耗时
        if (st.startedAt != null && st.completedAt != null) {
            st.elapsedMs = st.completedAt - st.startedAt;
        } else if (st.startedAt != null) {
            st.elapsedMs = System.currentTimeMillis() - st.startedAt;
        }

        return st;
    }

    private String buildProgressMessage(FetchProgress progress) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(progress.getStatus() != null ? progress.getStatus() : "UNKNOWN");
        sb.append("] ");
        if (progress.getErrorMessage() != null && !progress.getErrorMessage().isEmpty()) {
            sb.append(progress.getErrorMessage());
        } else {
            sb.append("请求 ").append(progress.getRequestCount() != null ? progress.getRequestCount() : 0);
            sb.append(" 去重 ").append(progress.getRawMemberCount() != null ? progress.getRawMemberCount() : 0);
            sb.append(" 响应 ").append(progress.getTotalRespondedMembers() != null ? progress.getTotalRespondedMembers() : 0);
            sb.append(" 完成 ").append(progress.getCompletedPages() != null ? progress.getCompletedPages() : 0);
            sb.append("/").append(progress.getTotalPages() != null ? progress.getTotalPages() : 0);
            sb.append(" 耗时 ").append(progress.getTotalResponseTimeMs() != null ? progress.getTotalResponseTimeMs() / 1000.0 : 0).append("s");
        }
        return sb.toString();
    }

    /** 将前缀队列序列化为 JSON（存入 fetch_progress.resume_frontier） */
    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    /** 反序列化上次同步的前缀队列 */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 安全提取 int 值，避免 null 拆箱导致 NPE */
    private static int safeInt(Map<String, Object> info, String key) {
        Object val = info.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Long l) return (int) (long) l;
        return 0;
    }

    /** 安全提取 long 值，避免 null 拆箱导致 NPE */
    private static long safeLong(Map<String, Object> info, String key) {
        Object val = info.get(key);
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return (long) i;
        return 0L;
    }
}
