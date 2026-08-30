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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentTaskService {

    private final AgentTaskRepository agentTaskRepository;
    private final AgentServerRepository agentServerRepository;
    private final DiscordAccountRepository discordAccountRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建一个待执行任务（由前端调用）
     */
    @Transactional
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

    /**
     * 同步等待任务完成（给 MessageService 用）
     * @return result JSON 字符串，失败抛异常
     */
    public String waitForTaskResult(Long taskId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            AgentTask t = agentTaskRepository.findById(taskId).orElse(null);
            if (t == null) throw new IllegalStateException("任务不存在 id=" + taskId);
            String status = t.getStatus();
            if ("SUCCESS".equals(status)) return t.getResult();
            if ("FAILED".equals(status)) {
                String err = t.getResult();
                throw new IllegalStateException("Agent 执行失败: " + (err != null ? err : "未知错误"));
            }
            if ("CANCELLED".equals(status)) throw new IllegalStateException("任务已取消");
            Thread.sleep(500);
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

        // 优先取 RUNNING 的（agent 之前崩了未清理的），否则 PENDING
        Optional<AgentTask> task = agentTaskRepository
                .findFirstByAgentServerAndStatusOrderByCreatedAtAsc(server, "RUNNING");
        if (task.isEmpty()) {
            task = agentTaskRepository
                    .findFirstByAgentServerAndStatusOrderByCreatedAtAsc(server, "PENDING");
        }
        task.ifPresent(t -> {
            t.setStatus("RUNNING");
            t.setUpdatedAt(Instant.now());
            agentTaskRepository.save(t);
        });
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

        // SUCCESS 时对 CAPTURE_DISCORD_ACCOUNT 做后处理：保存 DiscordAccount
        if ("SUCCESS".equals(status) && "CAPTURE_DISCORD_ACCOUNT".equals(task.getType()) && resultMap != null) {
            try {
                DiscordAccount account = upsertCapturedAccount(resultMap, server.getMerchantId(), server);
                task.setDiscordAccount(account);
                log.info("任务 id={} 成功，关联账号 id={}, username={}", taskId, account.getId(), account.getName());
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
        } else {
            account = new DiscordAccount();
        }

        if (username != null) account.setName(username);
        if (email != null) account.setEmail(email);
        if (token != null) account.setToken(token);
        if (discordId != null) account.setDiscordId(discordId);
        if (merchantId != null) account.setMerchantId(merchantId);
        account.setAccountType(AccountType.USER);
        if (account.getStatus() == null) account.setStatus(AccountStatus.ACTIVE);
        // 持久化 profile 路径 + agent 关联
        if (browserProfilePath != null && !browserProfilePath.isBlank()) {
            account.setBrowserProfilePath(browserProfilePath);
        }
        if (server != null) {
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
