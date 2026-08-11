package com.discordadmin.service;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Merchant;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.DiscordAccountRepository;
import com.discordadmin.repository.MerchantRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final AgentRepository agentRepository;
    private final DiscordAccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(AgentRepository agentRepository,
                       DiscordAccountRepository accountRepository,
                       MerchantRepository merchantRepository,
                       PasswordEncoder passwordEncoder) {
        this.agentRepository = agentRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Map<String, Object>> list() {
        Long merchantId = SecurityUtils.currentMerchantId();
        List<Agent> agents;
        if (SecurityUtils.isPlatformAdmin()) {
            agents = agentRepository.findAll();
        } else {
            agents = agentRepository.findByMerchantId(merchantId);
        }
        
        // 查询所有商户
        Map<Long, String> merchantNames = merchantRepository.findAll().stream()
                .collect(Collectors.toMap(Merchant::getId, Merchant::getName));
        
        // 转换为Map，包含merchantName
        return agents.stream()
                .map(agent -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", agent.getId());
                    map.put("username", agent.getUsername());
                    map.put("displayName", agent.getDisplayName());
                    map.put("role", agent.getRole() != null ? agent.getRole().name() : null);
                    map.put("merchantId", agent.getMerchantId());
                    map.put("merchantName", agent.getMerchantId() != null ? 
                            merchantNames.getOrDefault(agent.getMerchantId(), "未知商户") : null);
                    map.put("enabled", agent.getEnabled());
                    map.put("roleIds", agent.getRoleIds() != null ? new ArrayList<>(agent.getRoleIds()) : new ArrayList<>());
                    return map;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public Agent create(UserRequest req) {
        Long merchantId = resolveMerchantId(req);

        Agent agent = new Agent();
        agent.setUsername(req.username());
        agent.setPasswordHash(passwordEncoder.encode(req.password()));
        agent.setDisplayName(req.displayName());
        agent.setRole(Agent.AgentRole.valueOf(req.role()));
        agent.setMerchantId(merchantId);
        agent.setEnabled(true);
        
        if (req.roleIds() != null && !req.roleIds().isEmpty()) {
            agent.setRoleIds(new HashSet<>(req.roleIds()));
        }
        
        return agentRepository.save(agent);
    }

    private Long resolveMerchantId(UserRequest req) {
        if (SecurityUtils.isPlatformAdmin()) {
            Agent.AgentRole role = Agent.AgentRole.valueOf(req.role());
            if (role == Agent.AgentRole.PLATFORM_ADMIN) {
                return null;
            } else if (req.merchantId() != null) {
                return req.merchantId();
            } else {
                throw new IllegalArgumentException("商户身份的用户必须选择商户");
            }
        }
        return SecurityUtils.currentMerchantId();
    }

    @Transactional
    public Agent update(Long id, UserRequest req) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (req.displayName() != null) agent.setDisplayName(req.displayName());
        if (req.role() != null) agent.setRole(Agent.AgentRole.valueOf(req.role()));
        if (req.enabled() != null) agent.setEnabled(req.enabled());
        if (req.password() != null && !req.password().isBlank()) {
            agent.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        if (req.roleIds() != null) {
            agent.getRoleIds().clear();
            agent.getRoleIds().addAll(req.roleIds());
        } else if (req.clearRoles() != null && req.clearRoles()) {
            agent.getRoleIds().clear();
        }
        if (SecurityUtils.isPlatformAdmin() && req.merchantId() != null) {
            agent.setMerchantId(req.merchantId());
        }
        if (SecurityUtils.isPlatformAdmin() && req.role() != null) {
            Agent.AgentRole role = Agent.AgentRole.valueOf(req.role());
            if (role == Agent.AgentRole.PLATFORM_ADMIN) {
                agent.setMerchantId(null);
            }
        }
        return agentRepository.save(agent);
    }

    @Transactional
    public Agent setRoles(Long id, java.util.Set<Long> roleIds) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        agent.getRoleIds().clear();
        if (roleIds != null) {
            agent.getRoleIds().addAll(roleIds);
        }
        return agentRepository.save(agent);
    }

    @Transactional
    public void delete(Long id) {
        agentRepository.deleteById(id);
    }

    public List<DiscordAccount> listLinkedAccounts(Long id) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        SecurityUtils.checkMerchantAccess(agent.getMerchantId());
        return List.copyOf(agent.getDiscordAccounts());
    }

    @Transactional
    public Agent linkAccount(Long id, Long accountId) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        DiscordAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Discord账号不存在"));
        SecurityUtils.checkMerchantAccess(agent.getMerchantId());

        validateCrossMerchantLink(agent, account);
        validateNoDuplicateLink(account, id);

        agent.getDiscordAccounts().add(account);
        return agentRepository.save(agent);
    }

    private void validateCrossMerchantLink(Agent agent, DiscordAccount account) {
        if (!java.util.Objects.equals(agent.getMerchantId(), account.getMerchantId())) {
            String agentScope = agent.getMerchantId() != null ? "商户级" : "平台级";
            String accountScope = account.getMerchantId() != null
                    ? "商户" + account.getMerchantId() + "级"
                    : "平台级";
            throw new IllegalStateException(
                    "该Discord账号归属为" + accountScope + "，当前用户为" + agentScope + "，不可跨商户关联");
        }
    }

    private void validateNoDuplicateLink(DiscordAccount account, Long currentAgentId) {
        List<Agent> alreadyLinked = agentRepository.findByDiscordAccountsContaining(account);
        for (Agent existing : alreadyLinked) {
            if (!existing.getId().equals(currentAgentId)) {
                String ownerName = existing.getDisplayName() != null ? existing.getDisplayName() : existing.getUsername();
                throw new IllegalStateException("该Discord账号已关联给用户「" + ownerName + "」，不可重复关联");
            }
        }
    }

    @Transactional
    public Agent unlinkAccount(Long id, Long accountId) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        DiscordAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Discord账号不存在"));
        SecurityUtils.checkMerchantAccess(agent.getMerchantId());
        agent.getDiscordAccounts().remove(account);
        return agentRepository.save(agent);
    }

    public record UserRequest(String username, String password, String displayName,
                               String role, Long merchantId, Boolean enabled,
                               List<Long> roleIds, Boolean clearRoles) {}
}
