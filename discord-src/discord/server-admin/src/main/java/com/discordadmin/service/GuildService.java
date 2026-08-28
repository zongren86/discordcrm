package com.discordadmin.service;

import com.discordadmin.discord.DiscordUserClient;
import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GuildService {

    private static final Logger log = LoggerFactory.getLogger(GuildService.class);

    private final GuildServerRepository guildServerRepository;
    private final GuildMemberRepository guildMemberRepository;
    private final FetchProgressRepository fetchProgressRepository;
    private final MerchantConfigRepository merchantConfigRepository;
    private final DiscordAccountRepository accountRepository;
    private final DiscordUserClient discordUserClient;

    /** 进度缓存：guildServerId -> 当前进度 */
    private final Map<Long, FetchProgress> progressCache = new ConcurrentHashMap<>();

    public GuildService(GuildServerRepository guildServerRepository,
                        GuildMemberRepository guildMemberRepository,
                        FetchProgressRepository fetchProgressRepository,
                        MerchantConfigRepository merchantConfigRepository,
                        DiscordAccountRepository accountRepository,
                        DiscordUserClient discordUserClient) {
        this.guildServerRepository = guildServerRepository;
        this.guildMemberRepository = guildMemberRepository;
        this.fetchProgressRepository = fetchProgressRepository;
        this.merchantConfigRepository = merchantConfigRepository;
        this.accountRepository = accountRepository;
        this.discordUserClient = discordUserClient;
    }

    /** 保存或更新服务器配置 */
    @Transactional
    public GuildServer saveGuildServer(GuildServer server) {
        return guildServerRepository.save(server);
    }

    /** 获取商户下的所有服务器 - 严格按商户隔离，不允许任何回退 */
    public List<GuildServer> listGuildServers(Long merchantId, Long discordAccountId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户ID不能为空");
        }
        if (discordAccountId != null) {
            return guildServerRepository.findByMerchantIdAndDiscordAccountId(merchantId, discordAccountId);
        }
        return guildServerRepository.findByMerchantId(merchantId);
    }

    /** 删除服务器配置 */
    @Transactional
    public void deleteGuildServer(Long id) {
        guildMemberRepository.deleteByGuildServerId(id);
        guildServerRepository.deleteById(id);
    }

    /** 获取服务器成员列表 */
    public List<GuildMember> listMembers(Long guildServerId) {
        return guildMemberRepository.findByGuildServerId(guildServerId);
    }

    /** 分页获取服务器成员列表 */
    public Page<GuildMember> listMembersPaginated(Long guildServerId, String keyword, Integer friendStatus, String discordStatus, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        
        // 使用 Specification 构建筛选条件
        Specification<GuildMember> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("guildServerId"), guildServerId));
            
            // 关键词搜索（使用 OR 组合）
            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("username")), kw),
                    cb.like(cb.lower(root.get("nick")), kw),
                    cb.like(cb.lower(root.get("displayName")), kw),
                    cb.like(cb.lower(root.get("userId")), kw)
                ));
            }
            
            // 好友池状态筛选
            if (friendStatus != null) {
                if (friendStatus == 0) {
                    predicates.add(cb.or(
                        cb.equal(root.get("friendStatus"), 0),
                        cb.isNull(root.get("friendStatus"))
                    ));
                } else {
                    predicates.add(cb.equal(root.get("friendStatus"), friendStatus));
                }
            }

            // Discord 原生状态筛选
            if (discordStatus != null && !discordStatus.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("discordStatus"), discordStatus.trim()));
            }
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        
        return guildMemberRepository.findAll(spec, pageable);
    }

    /** 获取成员数量 */
    public long countMembers(Long guildServerId) {
        return guildMemberRepository.countByGuildServerId(guildServerId);
    }

    /** 按关键词获取成员数量 */
    public long countMembers(Long guildServerId, String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return guildMemberRepository.countByGuildServerIdAndKeyword(guildServerId, keyword.trim());
        }
        return guildMemberRepository.countByGuildServerId(guildServerId);
    }

    /** 获取或创建商户配置 */
    @Transactional
    public MerchantConfig getOrCreateConfig(Long merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户ID不能为空");
        }
        return merchantConfigRepository.findByMerchantId(merchantId)
                .orElseGet(() -> {
                    MerchantConfig config = new MerchantConfig();
                    config.setMerchantId(merchantId);
                    return merchantConfigRepository.save(config);
                });
    }

    /** 更新商户配置 */
    @Transactional
    public MerchantConfig updateConfig(Long merchantId, MerchantConfig config) {
        if (merchantId == null) {
            throw new IllegalArgumentException("商户ID不能为空");
        }
        MerchantConfig existing = getOrCreateConfig(merchantId);
        if (config.getFetchLimit() != null) existing.setFetchLimit(config.getFetchLimit());
        if (config.getRequestInterval() != null) existing.setRequestInterval(config.getRequestInterval());
        if (config.getRequestCount() != null) existing.setRequestCount(config.getRequestCount());
        if (config.getMaxDepth() != null) existing.setMaxDepth(config.getMaxDepth());
        if (config.getMaxRequests() != null) existing.setMaxRequests(config.getMaxRequests());
        if (config.getArchiveDays() != null) existing.setArchiveDays(config.getArchiveDays());
        if (config.getMaxUsers() != null) existing.setMaxUsers(config.getMaxUsers());
        if (config.getMaxLinkedAccounts() != null) existing.setMaxLinkedAccounts(config.getMaxLinkedAccounts());
        existing.setUpdatedAt(Instant.now());
        return merchantConfigRepository.save(existing);
    }

    /** 开始异步抓取成员数据 */
    @Async
    @Transactional
    public void startFetchMembers(Long guildServerId, MerchantConfig config) {
        GuildServer server = guildServerRepository.findById(guildServerId).orElse(null);
        if (server == null) return;

        DiscordAccount account = accountRepository.findById(server.getDiscordAccountId()).orElse(null);
        if (account == null) return;

        // 获取当前商户下所有服务器的ID，用于跨服务器去重
        Long merchantId = server.getMerchantId();
        Set<Long> merchantServerIds = new HashSet<>();
        merchantServerIds.add(server.getId());
        if (merchantId != null) {
            List<GuildServer> merchantServers = guildServerRepository.findByMerchantId(merchantId);
            for (GuildServer ms : merchantServers) {
                merchantServerIds.add(ms.getId());
            }
        }
        log.info("商户级去重: 当前服务器={}, 商户下所有服务器{}台", server.getId(), merchantServerIds.size());

        // 创建进度记录
        FetchProgress progress = new FetchProgress();
        progress.setGuildServerId(guildServerId);
        progress.setDiscordAccountId(account.getId());
        progress.setGuildId(server.getGuildId());
        progress.setStatus("RUNNING");
        progress.setStartedAt(Instant.now());
        progress.setRequestCount(0);
        progress.setRawMemberCount(0);
        progress.setDedupedMemberCount(0);
        progress.setCompletedPages(0);
        progress.setRetryCount(0);

        // 查找上一次抓取的最后一个成员ID，用于断点续抓
        Optional<FetchProgress> lastProgress = fetchProgressRepository
                .findTopByGuildServerIdAndStatusOrderByCreatedAtDesc(guildServerId, "COMPLETED");
        String lastMemberId = null;
        if (lastProgress.isPresent() && lastProgress.get().getLastBatchId() != null) {
            lastMemberId = lastProgress.get().getLastBatchId();
        }

        FetchProgress saved = fetchProgressRepository.save(progress);
        progressCache.put(guildServerId, saved);

        try {
            fetchMembersBatch(account, server, config, progress, lastMemberId, merchantServerIds);
            
            progress.setStatus("COMPLETED");
            progress.setCompletedAt(Instant.now());
            server.setLastFetchAt(Instant.now());
            server.setMemberCount((int) guildMemberRepository.countByGuildServerId(guildServerId));
            guildServerRepository.save(server);
        } catch (Exception e) {
            log.error("抓取服务器[id={}]成员失败", guildServerId, e);
            progress.setStatus("FAILED");
            progress.setErrorMessage(e.getMessage());
        }

        fetchProgressRepository.save(progress);
        progressCache.put(guildServerId, progress);
    }

    /** 批量抓取成员（带分页、去重、断点续抓、商户级跨服务器去重） */
    private void fetchMembersBatch(DiscordAccount account, GuildServer server, 
                                    MerchantConfig config, FetchProgress progress, 
                                    String startAfter, Set<Long> merchantServerIds) throws Exception {
        String token = account.getToken();
        String guildId = server.getGuildId();
        int limit = Math.min(config.getRequestCount(), 1000);
        int maxRequests = config.getMaxRequests();
        int intervalSec = config.getRequestInterval();

        String after = startAfter;
        int requestCount = 0;
        int totalRaw = 0;
        int totalDeduped = 0;
        int totalCrossServerDeduped = 0;
        Set<String> seenUserIds = new HashSet<>();
        
        // 将商户级服务器ID集合转为List用于查询
        List<Long> merchantServerIdList = new ArrayList<>(merchantServerIds);

        while (requestCount < maxRequests) {
            requestCount++;
            progress.setRequestCount(requestCount);

            JsonNode membersNode;
            try {
                membersNode = discordUserClient.listGuildMembers(token, guildId, limit, after);
            } catch (DiscordUserClient.DiscordUserApiException e) {
                if (e.statusCode == 429) {
                    // 限流，等待后重试
                    Thread.sleep(intervalSec * 1000L * 2L);
                    requestCount--;
                    continue;
                }
                throw e;
            }

            if (membersNode == null || !membersNode.isArray() || membersNode.isEmpty()) {
                break;
            }

            List<GuildMember> batch = new ArrayList<>();
            String lastUserId = null;

            for (JsonNode m : membersNode) {
                JsonNode user = m.path("user");
                if (user.isMissingNode() || user.isNull()) continue;

                String userId = user.path("id").asText(null);
                lastUserId = userId;

                // 本地内存去重（当前批次内）
                if (userId != null && seenUserIds.contains(userId)) continue;
                if (userId != null) seenUserIds.add(userId);

                totalRaw++;

                // 1. 先检查当前服务器是否已存在
                Optional<GuildMember> existingInServer = guildMemberRepository
                        .findByGuildServerIdAndUserId(server.getId(), userId);
                if (existingInServer.isPresent()) {
                    // 当前服务器已存在 → 更新
                    totalDeduped++;
                    GuildMember member = existingInServer.get();
                    updateMemberFromJson(member, m, server.getId(), guildId);
                    batch.add(member);
                    continue;
                }

                // 2. 商户级跨服务器去重：检查是否在商户的其他服务器中已存在
                if (userId != null && merchantServerIdList.size() > 1) {
                    List<GuildMember> existingInOtherServers = guildMemberRepository
                            .findExistingInOtherServers(merchantServerIdList, userId, server.getId());
                    if (!existingInOtherServers.isEmpty()) {
                        // 已在其他服务器存在 → 跳过，不重复采集
                        totalCrossServerDeduped++;
                        log.debug("用户 {} 已在其他服务器(id={})中存在，跳过服务器(id={})的重复采集",
                                userId, existingInOtherServers.get(0).getGuildServerId(), server.getId());
                        continue;
                    }
                }

                // 3. 完全新用户 → 插入
                totalDeduped++;
                GuildMember member = new GuildMember();
                member.setGuildServerId(server.getId());
                member.setGuildId(guildId);
                member.setUserId(userId);
                fillMemberFromJson(member, m);
                batch.add(member);
            }

            // 批量保存
            if (!batch.isEmpty()) {
                guildMemberRepository.saveAll(batch);
            }

            after = lastUserId;
            progress.setLastBatchId(after);
            progress.setRawMemberCount(totalRaw);
            progress.setDedupedMemberCount(totalDeduped);
            progress.setCompletedPages(requestCount);

            // 更新缓存中的进度
            progressCache.put(server.getId(), progress);

            // 保存进度到数据库
            fetchProgressRepository.save(progress);

            // 请求间隔
            if (intervalSec > 0) {
                Thread.sleep(intervalSec * 1000L);
            }

            // 如果返回数量不足 limit，说明已到末尾
            if (membersNode.size() < limit) break;
        }

        log.info("服务器[id={}]成员抓取完成：请求{}次，原始{}条，当前服务器去重{}条，跨服务器去重{}条",
                server.getId(), requestCount, totalRaw, totalDeduped, totalCrossServerDeduped);
    }

    private void fillMemberFromJson(GuildMember member, JsonNode m) {
        JsonNode user = m.path("user");
        member.setUsername(user.path("username").asText("Unknown"));
        member.setGlobalName(user.path("global_name").asText(null));
        member.setDisplayName(m.path("nick").asText(null));
        if (member.getDisplayName() == null || member.getDisplayName().isBlank()) {
            member.setDisplayName(member.getGlobalName() != null ? member.getGlobalName() : member.getUsername());
        }
        String avatarHash = user.path("avatar").asText(null);
        String userId = user.path("id").asText(null);
        if (userId != null && avatarHash != null) {
            String ext = avatarHash.startsWith("a_") ? "gif" : "png";
            member.setAvatarUrl("https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + "." + ext);
        }
        member.setIsBot(user.path("bot").asBoolean(false));
        member.setJoinedAt(m.path("joined_at").asText(null) != null 
                ? Instant.parse(m.path("joined_at").asText()) 
                : null);
        
        JsonNode rolesNode = m.path("roles");
        if (rolesNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode r : rolesNode) {
                if (sb.length() > 0) sb.append(",");
                sb.append(r.asText());
            }
            member.setRoles(sb.toString());
        }
        member.setLastFetchedAt(Instant.now());
    }

    private void updateMemberFromJson(GuildMember member, JsonNode m, Long serverId, String guildId) {
        fillMemberFromJson(member, m);
    }

    /** 获取当前抓取进度 */
    public FetchProgress getProgress(Long guildServerId) {
        FetchProgress cached = progressCache.get(guildServerId);
        if (cached != null && "RUNNING".equals(cached.getStatus())) {
            return cached;
        }
        return fetchProgressRepository.findTopByGuildServerIdOrderByCreatedAtDesc(guildServerId).orElse(null);
    }

    /** 获取最近的进度记录 */
    public List<FetchProgress> listProgressHistory(Long guildServerId) {
        return fetchProgressRepository.findByGuildServerIdOrderByCreatedAtDesc(guildServerId);
    }

    /**
     * 清理商户级跨服务器重复成员记录。
     * 规则：同一userId在多个服务器中都存在时，保留最早采集的那条（id最小），删除其余。
     * @return 清理结果统计
     */
    @Transactional
    public Map<String, Object> cleanCrossServerDuplicates() {
        List<GuildMember> duplicates = guildMemberRepository.findCrossServerDuplicates();
        long totalDuplicateUsers = guildMemberRepository.countCrossServerDuplicates();
        
        Map<String, Object> result = new HashMap<>();
        result.put("totalDuplicateUsers", totalDuplicateUsers);
        
        if (duplicates.isEmpty()) {
            result.put("cleanedCount", 0);
            result.put("message", "没有重复数据");
            return result;
        }

        // 按userId分组，每组保留最早的（id最小），删除其余
        Map<String, List<GuildMember>> groupedByUserId = new LinkedHashMap<>();
        for (GuildMember m : duplicates) {
            groupedByUserId.computeIfAbsent(m.getUserId(), k -> new ArrayList<>()).add(m);
        }

        int cleanedCount = 0;
        Map<Long, Integer> cleanedByServer = new HashMap<>();

        for (Map.Entry<String, List<GuildMember>> entry : groupedByUserId.entrySet()) {
            List<GuildMember> members = entry.getValue();
            if (members.size() <= 1) continue;
            
            // 按id升序排序，保留最早的
            members.sort(Comparator.comparingLong(GuildMember::getId));
            
            // 保留第一个，删除其余
            for (int i = 1; i < members.size(); i++) {
                GuildMember toDelete = members.get(i);
                guildMemberRepository.delete(toDelete);
                cleanedCount++;
                Long serverId = toDelete.getGuildServerId();
                cleanedByServer.merge(serverId, 1, Integer::sum);
                log.info("清理重复成员: userId={}, 保留服务器id={}, 删除服务器id={}",
                        entry.getKey(), members.get(0).getGuildServerId(), serverId);
            }
        }

        // 重新统计各服务器成员数
        Map<Long, Long> updatedCounts = new HashMap<>();
        for (Long serverId : cleanedByServer.keySet()) {
            long count = guildMemberRepository.countByGuildServerId(serverId);
            updatedCounts.put(serverId, count);
            GuildServer server = guildServerRepository.findById(serverId).orElse(null);
            if (server != null) {
                server.setMemberCount((int) count);
                guildServerRepository.save(server);
            }
        }

        result.put("cleanedCount", cleanedCount);
        result.put("cleanedByServer", cleanedByServer);
        result.put("updatedCounts", updatedCounts);
        result.put("message", "清理完成：共清理 " + cleanedCount + " 条重复记录，涉及 " + cleanedByServer.size() + " 台服务器");
        log.info("跨服务器重复成员清理完成: 清理{}条，涉及{}台服务器", cleanedCount, cleanedByServer.size());
        return result;
    }

    /**
     * 统计商户级跨服务器重复成员数量
     */
    public Map<String, Object> countCrossServerDuplicates() {
        long totalUsers = guildMemberRepository.countCrossServerDuplicates();
        
        // 按服务器统计重复数
        List<GuildMember> duplicates = guildMemberRepository.findCrossServerDuplicates();
        Map<Long, Integer> byServer = new HashMap<>();
        Set<String> seenUsers = new HashSet<>();
        for (GuildMember m : duplicates) {
            if (!seenUsers.contains(m.getUserId())) {
                seenUsers.add(m.getUserId());
                // 该userId出现在多个服务器中时，除了第一个，其余都算重复
            }
            byServer.merge(m.getGuildServerId(), 1, Integer::sum);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("totalDuplicateUsers", totalUsers);
        result.put("affectedServers", byServer.size());
        result.put("byServer", byServer);
        return result;
    }
}
