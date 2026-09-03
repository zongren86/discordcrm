package com.discordadmin.service;

import com.discordadmin.entity.AgentServer;
import com.discordadmin.entity.AgentTask;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.DiscordAccount.AccountStatus;
import com.discordadmin.entity.DiscordAccount.AccountType;
import com.discordadmin.repository.AgentServerRepository;
import com.discordadmin.repository.AgentTaskRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.security.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTaskService {

    private final AgentTaskRepository agentTaskRepository;
    private final AgentServerRepository agentServerRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    /**
     * 创建一个待执行任务（由前端调用）
     * 用 REQUIRES_NEW —— 独立事务，外层（如 MessageService 的 sendMessage）回滚不影响 task 持久化
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTask createTask(Long agentServerId, String type, String paramsJson) {
        AgentServer server = agentServerRepository.findById(agentServerId)
                .orElseThrow(() -> new IllegalArgumentException("代理节点不存在 id=" + agentServerId));

        AgentTask task = new AgentTask();
        task.setType(type);
        task.setAgentServer(server);
        task.setStatus("PENDING");
        task.setParams(paramsJson);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        Long userId = SecurityUtils.currentUserId();
        task.setCreatedByUserId(userId);

        AgentTask saved = agentTaskRepository.save(task);
        log.info("创建 agent task id={}, type={}, serverId={}", saved.getId(), type, agentServerId);
        return saved;
    }

    /** 带延迟创建任务 —— 防风控 */
    public AgentTask createTaskWithDelay(Long agentServerId, String type, String paramsJson, int delayMinutes) {
        AgentTask task = createTask(agentServerId, type, paramsJson);
        task.setScheduledAt(Instant.now().plusSeconds(delayMinutes * 60L));
        task.setUpdatedAt(Instant.now());
        agentTaskRepository.save(task);
        log.info("⏳ 延迟下发 taskId={} type={} 延迟={}分钟 执行时间={}", task.getId(), type, delayMinutes, task.getScheduledAt());
        return task;
    }

    /**
     * 同步等待任务完成（给 MessageService 用）
     * @return result JSON 字符串，失败抛异常
     */
    public String waitForTaskResult(Long taskId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            // 关键：clear Hibernate 一级缓存，强制每次都查真实 DB
            // 因为 sendReply 有 @Transactional，createTask + waitFor 在同事务里，
            // findById 会返回缓存的 PENDING，即使 agent 已经在另一个事务 report SUCCESS 了
            entityManager.clear();
            AgentTask t = agentTaskRepository.findById(taskId).orElse(null);
            if (t == null) throw new IllegalStateException("任务不存在 id=" + taskId);
            String status = t.getStatus();
            log.debug("[waitFor] taskId={} status={}", taskId, status);
            if ("SUCCESS".equals(status)) {
                log.info("[waitFor] taskId={} 完成 SUCCESS, 返回结果", taskId);
                return t.getResult();
            }
            if ("FAILED".equals(status)) {
                String err = t.getResult();
                log.warn("[waitFor] taskId={} FAILED, result={}", taskId, err);
                throw new IllegalStateException("Agent 执行失败: " + (err != null ? err : "未知错误"));
            }
            if ("CANCELLED".equals(status)) {
                log.warn("[waitFor] taskId={} CANCELLED", taskId);
                throw new IllegalStateException("任务已取消");
            }
            Thread.sleep(500);
        }
        // 超时了，把 task 标记为 CANCELLED（避免 agent 后续 poll 到又执行）
        entityManager.clear();
        AgentTask t = agentTaskRepository.findById(taskId).orElse(null);
        if (t != null && !"SUCCESS".equals(t.getStatus()) && !"FAILED".equals(t.getStatus())) {
            t.setStatus("CANCELLED");
            t.setResult("{\"error\":\"timeout_cancelled_by_server\"}");
            t.setUpdatedAt(Instant.now());
            agentTaskRepository.save(t);
        }
        throw new IllegalStateException("等待 Agent 执行超时（" + (timeoutMs/1000) + "秒）");
    }

    /**
     * agent poll —— 拉取下一个待执行任务并标记 RUNNING
     */
    @Transactional
    public Optional<AgentTask> pollNext(String token, String agentName) {
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效 token"));

        // 0. 自动清理僵死的 RUNNING 任务（>3分钟没更新，说明 agent 崩了或卡了）
        Instant staleCutoff = Instant.now().minusSeconds(180);
        Optional<AgentTask> stale = agentTaskRepository
                .findFirstByAgentServerAndStatusOrderByCreatedAtAsc(server, "RUNNING");
        while (stale.isPresent()) {
            AgentTask st = stale.get();
            if (st.getUpdatedAt() != null && st.getUpdatedAt().isBefore(staleCutoff)) {
                st.setStatus("FAILED");
                st.setResult("{\"error\":\"stale_running_timeout\"}");
                st.setUpdatedAt(Instant.now());
                agentTaskRepository.save(st);
                log.warn("[pollNext] 清理僵死 RUNNING taskId={} type={}", st.getId(), st.getType());
                stale = agentTaskRepository.findFirstByAgentServerAndStatusOrderByCreatedAtAsc(server, "RUNNING");
            } else {
                break; // 还有没僵死的，agent 可能正在执行
            }
        }

        // 优先取 PENDING 的（RUNNING 僵死已清理）
        // 只取 scheduledAt <= now 或 scheduledAt IS NULL 的任务（防风控延迟）
        Optional<AgentTask> task = agentTaskRepository
                .findFirstReady(server, "PENDING", Instant.now());

        task.ifPresent(t -> {
            t.setStatus("RUNNING");
            t.setUpdatedAt(Instant.now());
            agentTaskRepository.save(t);
            log.info("[pollNext] agent={} serverId={} 领取 taskId={} type={} → RUNNING",
                    agentName, server.getId(), t.getId(), t.getType());
        });
        if (task.isEmpty()) {
            log.debug("[pollNext] agent={} serverId={} 无待执行任务", agentName, server.getId());
        }
        return task;
    }

    /**
     * agent report —— 回传任务结果
     * 对 CAPTURE_DISCORD_ACCOUNT 类型：自动把抓到的用户保存为 DiscordAccount
     */
    @Transactional
    public AgentTask reportTask(String token, Long taskId, String status, Map<String, Object> resultMap) {
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效 token"));
        log.info("[reportTask] taskId={} status={} serverId={}", taskId, status, server.getId());

        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在 id=" + taskId));

        String agentId = task.getAgentServer() != null ? String.valueOf(task.getAgentServer().getId()) : "-";
        if (!agentId.equals(String.valueOf(server.getId()))) {
            throw new IllegalArgumentException("任务不属于当前代理节点");
        }

        try {
            task.setResult(objectMapper.writeValueAsString(resultMap));
        } catch (JsonProcessingException e) {
            task.setResult("{\"error\":\"序列化失败\"}");
        }
        task.setStatus(status);
        task.setUpdatedAt(Instant.now());

        // ✅ 从 resultMap 提取 error 存到 error_message 列（FAILED/CANCELLED 时尤其重要）
        if (resultMap != null && resultMap.containsKey("error")) {
            String errMsg = String.valueOf(resultMap.get("error"));
            if (errMsg.length() > 1000) errMsg = errMsg.substring(0, 1000);
            task.setErrorMessage(errMsg);
        } else if ("FAILED".equals(status)) {
            task.setErrorMessage("agent 未上报具体错误信息");
        } else if ("CANCELLED".equals(status)) {
            task.setErrorMessage("任务已取消（用户操作或超时）");
        }

        // SUCCESS 时对 CAPTURE_DISCORD_ACCOUNT / LAUNCH_BROWSER 做后处理：更新 DiscordAccount
        boolean needUpsert = ("CAPTURE_DISCORD_ACCOUNT".equals(task.getType()) || "LAUNCH_BROWSER".equals(task.getType()));
        if ("SUCCESS".equals(status) && needUpsert && resultMap != null && resultMap.containsKey("token")) {
            try {
                DiscordAccount account = upsertCapturedAccount(resultMap, server.getMerchantId(), server);
                task.setDiscordAccount(account);
                log.info("任务 id={} 成功，关联账号 id={}, username={}", taskId, account.getId(), account.getName());

                // 只有首次 CAPTURE 才触发好友同步，LAUNCH_BROWSER 只是更新 token
                if ("CAPTURE_DISCORD_ACCOUNT".equals(task.getType())) {
                try {
                    Map<String, Object> friendsParams = new HashMap<>();
                    friendsParams.put("accountId", account.getId());
                    friendsParams.put("token", account.getToken());
                    // 随机延迟 5~15 分钟，避免刚登录就高频操作触发风控
                    int delay = 5 + (int)(Math.random() * 11);
                    AgentTask ft = createTaskWithDelay(server.getId(), "FULL_SYNC_FRIENDS",
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(friendsParams), delay);
                    log.info("📡 采集完成 → 已延迟 5 分钟下发 FULL_SYNC_FRIENDS taskId={} (accountId={})",
                        ft.getId(), account.getId());
                } catch (Exception fe) {
                    log.warn("采集完成但下发好友同步失败（不影响主流程）: {}", fe.getMessage());
                }
                } // end if CAPTURE_DISCORD_ACCOUNT
            } catch (Exception e) {
                log.error("任务后处理（保存账号）失败: {}", e.getMessage());
                task.setStatus("FAILED");
                task.setResult("{\"error\":\"保存账号失败: " + e.getMessage() + "\", \"raw\": " + task.getResult() + "}");
            }
        }

        return agentTaskRepository.save(task);
    }

    /**
     * 把 agent 回传的用户数据存为 DiscordAccount（upsert by discordId）
     */
    private DiscordAccount upsertCapturedAccount(Map<String, Object> result, Long merchantId, AgentServer server) {
        String discordId = (String) result.get("discordId");
        String username = (String) result.get("username");
        String email = (String) result.get("email");
        String token = (String) result.get("token");
        String avatarUrl = (String) result.get("avatarUrl");
        String browserProfilePath = (String) result.get("browserProfilePath");

        DiscordAccount account;
        if (discordId != null) {
            Optional<DiscordAccount> existing = discordAccountRepository.findByDiscordId(discordId);
            account = existing.orElseGet(DiscordAccount::new);
            if (existing.isPresent()) {
                log.info("[upsert] 账号已存在 id={} discordId={} → 更新 token/信息", account.getId(), discordId);
            }
        } else {
            account = new DiscordAccount();
        }

        if (username != null) account.setName(username);
        if (discordId != null) account.setDiscordId(discordId);
        // 从 resultMap 再取 discordName（Discord 上的显示名，可能和 username 不同）
        String discordName = (String) result.get("discordName");
        if (discordName != null) account.setDiscordName(discordName);
        else if (username != null) account.setDiscordName(username);
        if (email != null) account.setEmail(email);
        if (token != null) account.setToken(token);
        if (avatarUrl != null) account.setAvatarUrl(avatarUrl);
        if (merchantId != null) account.setMerchantId(merchantId);
        account.setAccountType(AccountType.USER);
        account.setStatus(AccountStatus.ACTIVE);
        // 持久化 profile 路径 + agent 关联
        if (browserProfilePath != null && !browserProfilePath.isBlank()) {
            account.setBrowserProfilePath(browserProfilePath);
        }
        if (server != null) {
            // 校验该代理节点账号上限
            int max = server.getMaxAccounts() != null ? server.getMaxAccounts() : 500;
            long current = discordAccountRepository.countByAgentServerId(server.getId());
            if (current >= max) {
                throw new RuntimeException(
                    "代理节点 [" + server.getName() + "] 已达账号上限 (" + current + "/" + max + ")，" +
                    "请先解绑部分账号或在代理管理中调高上限"
                );
            }
            account.setAgentServerId(server.getId());
        }
        account.setSource("AGENT");

        account = discordAccountRepository.save(account);
        log.info("保存代理采集的账号 id={}, discordId={}, username={}, profile={}",
                account.getId(), discordId, username, browserProfilePath);
        return account;
    }

    /** 查询任务详情（基础） */
    public Optional<AgentTask> findById(Long id) {
        return agentTaskRepository.findById(id);
    }

    /** 查询任务详情 + 预取 discordAccount（LAZY 关联） */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> findTaskDetail(Long id) {
        return agentTaskRepository.findById(id).map(t -> {
            java.util.Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("id", t.getId());
            resp.put("type", t.getType());
            resp.put("status", t.getStatus());
            resp.put("result", t.getResult());
            resp.put("createdAt", t.getCreatedAt());
            // 在事务内访问 LAZY 关联 —— 不会炸
            if (t.getDiscordAccount() != null) {
                com.discordadmin.entity.DiscordAccount a = t.getDiscordAccount();
                java.util.Map<String, Object> acct = new java.util.HashMap<>();
                acct.put("id", a.getId());
                acct.put("name", a.getName());
                acct.put("discordId", a.getDiscordId());
                acct.put("browserProfilePath", a.getBrowserProfilePath());
                acct.put("agentServerId", a.getAgentServerId());
                resp.put("discordAccount", acct);
            }
            return resp;
        }).orElse(null);
    }

    /** agent 用 token 取消自己的任务 */
    @Transactional
    public AgentTask cancelByAgent(String token, Long taskId) {
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效 token"));
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在 id=" + taskId));
        if (task.getAgentServer() == null || !task.getAgentServer().getId().equals(server.getId())) {
            throw new IllegalArgumentException("任务不属于当前代理节点");
        }
        task.setStatus("CANCELLED");
        task.setResult("{\"error\":\"agent_cancelled\"}");
        task.setUpdatedAt(Instant.now());
        return agentTaskRepository.save(task);
    }

    /** 前端用户取消任务 */
    @Transactional
    public AgentTask cancelByUser(Long taskId) {
        AgentTask task = agentTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在 id=" + taskId));
        if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            throw new IllegalArgumentException("任务已结束，无法取消");
        }
        task.setStatus("CANCELLED");
        task.setResult("{\"error\":\"user_cancelled\"}");
        task.setUpdatedAt(Instant.now());
        return agentTaskRepository.save(task);
    }
}
