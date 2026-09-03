package com.discordadmin.service;

import com.discordadmin.entity.AgentServer;
import com.discordadmin.repository.AgentServerRepository;
import com.discordadmin.repository.AgentTaskRepository;
import com.discordadmin.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentServerService {

    private final AgentServerRepository agentServerRepository;
    private final AgentTaskRepository agentTaskRepository;

    /** 生成 32 字节随机 token（Base64 编码） */
    private String generateToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 新增代理服务器节点
     * @return 创建的节点（含 token —— 只在这一次返回明文）
     */
    @Transactional
    public AgentServer create(String name, String notes) {
        if (agentServerRepository.existsByName(name)) {
            throw new IllegalArgumentException("节点名称已存在: " + name);
        }
        AgentServer server = new AgentServer();
        server.setName(name);
        server.setToken(generateToken());
        server.setNotes(notes);
        server.setStatus("OFFLINE");
        // 自动填充当前用户的 merchantId
        Long merchantId = SecurityUtils.currentMerchantId();
        server.setMerchantId(merchantId);
        AgentServer saved = agentServerRepository.save(server);
        log.info("新增代理节点 name={}, id={}", name, saved.getId());
        return saved;
    }

    /** 列表：普通用户只能看自己商户的；管理员看全部 */
    public List<AgentServer> list() {
        String role = SecurityUtils.currentRole();
        if ("PLATFORM_ADMIN".equals(role)) {
            return agentServerRepository.findAll();
        }
        Long merchantId = SecurityUtils.currentMerchantId();
        return agentServerRepository.findByMerchantIdOrMerchantIdIsNull(merchantId);
    }

    public Optional<AgentServer> findById(Long id) {
        return agentServerRepository.findById(id);
    }

    public Optional<AgentServer> findByName(String name) {
        return agentServerRepository.findByName(name);
    }

    /** agent 心跳上报 */
    @Transactional
    public AgentServer heartbeat(String token, String name, String serverAddress, String nodeVersion, String browserType) {
        AgentServer server = agentServerRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("无效 token"));
        // 新增：同时校验 agentName 和 token 匹配
        if (name != null && !name.isBlank() && !name.equals(server.getName())) {
            throw new IllegalArgumentException("代理名称不匹配！token 属于节点「" + server.getName() + "」，但 config.json 里写的是「" + name + "」");
        }
        server.setStatus("ONLINE");
        server.setLastSeenAt(Instant.now());
        if (serverAddress != null) server.setServerAddress(serverAddress);
        if (nodeVersion != null) server.setNodeVersion(nodeVersion);
        if (browserType != null) server.setBrowserType(browserType);
        return agentServerRepository.save(server);
    }

    /** 重置 token（管理员操作） */
    @Transactional
    public AgentServer resetToken(Long id) {
        AgentServer server = agentServerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在 id=" + id));
        server.setToken(generateToken());
        return agentServerRepository.save(server);
    }

    @Transactional
    public void delete(Long id) {
        Optional<AgentServer> server = agentServerRepository.findById(id);
        if (server.isEmpty()) {
            throw new IllegalArgumentException("节点不存在: id=" + id);
        }
        // 先清子表 agent_tasks（外键 FK）
        long taskCount = agentTaskRepository.countByAgentServerId(id);
        if (taskCount > 0) {
            agentTaskRepository.deleteByAgentServerId(id);
            log.info("删除节点前清理了 {} 条关联任务", taskCount);
        }
        agentServerRepository.deleteById(id);
        log.info("删除代理节点 id={}", id);
    }
}
