package com.discordadmin.service;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Role;
import com.discordadmin.entity.DiscordAccount;
import com.discordadmin.entity.Merchant;
import com.discordadmin.entity.MerchantConfig;
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
    private final com.discordadmin.repository.RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final MerchantConfigService merchantConfigService;

    public UserService(AgentRepository agentRepository,
                       DiscordAccountRepository accountRepository,
                       MerchantRepository merchantRepository,
                       com.discordadmin.repository.RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       MerchantConfigService merchantConfigService) {
        this.agentRepository = agentRepository;
        this.accountRepository = accountRepository;
        this.merchantRepository = merchantRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.merchantConfigService = merchantConfigService;
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
                    map.put("email", agent.getEmail());
                    map.put("notes", agent.getNotes());
                    map.put("accountType", agent.getAccountType());
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
        if (req.username() == null || req.username().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        
        // 检查用户名是否已存在
        agentRepository.findByUsername(req.username()).ifPresent(existing -> {
            throw new IllegalArgumentException("用户名「" + req.username() + "」已存在，请使用其他用户名");
        });
        
        Long merchantId = resolveMerchantId(req);

        // 用户数上限检查
        if (merchantId != null && !SecurityUtils.isPlatformAdmin()) {
            MerchantConfig config = merchantConfigService.getOrCreateConfig(merchantId);
            Integer maxUsers = config.getMaxUsers() != null ? config.getMaxUsers() : 10;
            long currentUserCount = agentRepository.findByMerchantId(merchantId).size();
            if (currentUserCount >= maxUsers) {
                throw new IllegalStateException("您的用户数已达上限，如果增加数量请联系平台客服");
            }
        }

        Agent agent = new Agent();
        agent.setUsername(req.username());
        agent.setPasswordHash(passwordEncoder.encode(req.password()));
        agent.setDisplayName(req.displayName());
        agent.setEmail(req.email());
        agent.setNotes(req.notes());
        agent.setAccountType(req.accountType() != null ? req.accountType() : 1);
        agent.setMerchantId(merchantId);
        agent.setEnabled(true);
        
        if (req.roleIds() != null && !req.roleIds().isEmpty()) {
            agent.setRoleIds(new HashSet<>(req.roleIds()));
        }
        
        return agentRepository.save(agent);
    }

    private Long resolveMerchantId(UserRequest req) {
        if (SecurityUtils.isPlatformAdmin()) {
            Integer accountType = req.accountType();
            if (accountType != null && accountType == 0) {
                // 管理员身份：如果指定了商户则使用指定商户，否则为平台管理员（无商户）
                return req.merchantId();
            } else {
                // 普通账号：必须选择商户
                if (req.merchantId() != null) {
                    return req.merchantId();
                } else {
                    throw new IllegalArgumentException("普通账号必须选择商户");
                }
            }
        }
        return SecurityUtils.currentMerchantId();
    }

    @Transactional
    public Agent update(Long id, UserRequest req) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (req.displayName() != null) agent.setDisplayName(req.displayName());
        if (req.email() != null) agent.setEmail(req.email());
        if (req.notes() != null) agent.setNotes(req.notes());
        if (req.accountType() != null) agent.setAccountType(req.accountType());
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
        if (SecurityUtils.isPlatformAdmin() && req.accountType() != null) {
            // 如果设为管理员且未指定商户，则为平台管理员
            if (req.accountType() == 0 && req.merchantId() == null) {
                agent.setMerchantId(null);
            }
        }
        return agentRepository.save(agent);
    }

    @Transactional
    public Agent setRoles(Long id, java.util.Set<Long> roleIds) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        if (!SecurityUtils.isPlatformAdmin()) {
            Long currentMerchantId = SecurityUtils.currentMerchantId();
            if (currentMerchantId == null || !currentMerchantId.equals(agent.getMerchantId())) {
                throw new IllegalStateException("不能为其他商户的用户分配角色");
            }
        }
        
        if (roleIds != null && !roleIds.isEmpty()) {
            List<Role> roles = roleRepository.findAllById(roleIds);
            boolean isPlatform = SecurityUtils.isPlatformAdmin();
            for (Role role : roles) {
                if (!isRoleApplicableToUser(role, agent, isPlatform)) {
                    throw new IllegalStateException("角色「" + role.getName() + "」不适用于该用户");
                }
            }
        }
        
        agent.getRoleIds().clear();
        if (roleIds != null) {
            agent.getRoleIds().addAll(roleIds);
        }
        return agentRepository.save(agent);
    }
    
    private boolean isRoleApplicableToUser(Role role, Agent targetAgent, boolean isPlatform) {
        if (role.getRoleType() == Role.RoleType.PLATFORM) {
            return isPlatform && targetAgent.getAccountType() != null && targetAgent.getAccountType() == 0;
        }
        Long targetMerchantId = targetAgent.getMerchantId();
        if (targetMerchantId == null) {
            return false;
        }
        if (role.getMerchantId() != null) {
            return role.getMerchantId().equals(targetMerchantId);
        }
        if (role.getMerchantIds() != null && !role.getMerchantIds().isEmpty()) {
            return role.getMerchantIds().contains(targetMerchantId);
        }
        return true;
    }

    @Transactional
    public void delete(Long id) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if ("admin".equalsIgnoreCase(agent.getUsername())) {
            throw new IllegalStateException("admin 账号不可以删除");
        }
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

        // 关联账号上限检查
        if (agent.getMerchantId() != null) {
            MerchantConfig config = merchantConfigService.getOrCreateConfig(agent.getMerchantId());
            Integer maxLinkedAccounts = config.getMaxLinkedAccounts() != null ? config.getMaxLinkedAccounts() : 20;
            int currentLinkedCount = agent.getDiscordAccounts().size();
            if (currentLinkedCount >= maxLinkedAccounts) {
                throw new IllegalStateException("该用户关联账号已达上限，如果要增加数量，请联系平台客服");
            }
        }

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
                               String email, String notes,
                               Integer accountType, Long merchantId, Boolean enabled,
                               List<Long> roleIds, Boolean clearRoles) {}
}
