package com.discordadmin.controller;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.SysFeature;
import com.discordadmin.entity.Merchant;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.RoleRepository;
import com.discordadmin.repository.SysFeatureRepository;
import com.discordadmin.repository.MerchantRepository;
import com.discordadmin.security.JwtAuthFilter;
import com.discordadmin.security.JwtUtil;
import com.discordadmin.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AgentRepository agentRepository;
    private final RoleRepository roleRepository;
    private final SysFeatureRepository featureRepository;
    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @PersistenceContext
    private EntityManager entityManager;

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, Long agentId, String username,
                                String displayName, Integer accountType,
                                Long merchantId, String merchantName,
                                List<String> permissions) {}

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        Agent agent = agentRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (agent.getEnabled() != null && !agent.getEnabled()) {
            throw new IllegalArgumentException("账号已禁用");
        }

        if (!passwordEncoder.matches(request.password(), agent.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(agent.getId(), agent.getId(), agent.getUsername(),
                agent.getAccountType(), agent.getMerchantId(), agent.getRoleIds());

        String merchantName = null;
        if (agent.getMerchantId() != null) {
            merchantName = merchantRepository.findById(agent.getMerchantId())
                    .map(Merchant::getName).orElse(null);
        }

        List<String> permissions = getAgentPermissions(agent);

        return new LoginResponse(token, agent.getId(), agent.getUsername(),
                agent.getDisplayName(), agent.getAccountType(),
                agent.getMerchantId(), merchantName, permissions);
    }

    @GetMapping("/my-permissions")
    public Map<String, Object> myPermissions() {
        JwtAuthFilter.AuthenticatedAgent agent = SecurityUtils.currentAgent();
        if (agent == null) {
            throw new IllegalArgumentException("未登录");
        }
        Agent agentEntity = agentRepository.findByIdWithRoleIds(agent.agentId()).orElse(null);
        if (agentEntity == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        List<String> permissions = getAgentPermissions(agentEntity);
        java.util.Set<String> featureCodes = new java.util.HashSet<>(permissions);
        java.util.Set<String> menuPaths = new java.util.HashSet<>();
        
        for (String code : permissions) {
            featureRepository.findByCode(code).ifPresent(f -> {
                if (f.getRoutePath() != null && !f.getRoutePath().isBlank()) {
                    menuPaths.add(f.getRoutePath());
                }
            });
        }
        
        for (String code : permissions) {
            int dotIdx = code.indexOf(".");
            if (dotIdx > 0) {
                String prefix = code.substring(0, dotIdx);
                featureRepository.findByCode(prefix).ifPresent(f -> {
                    if (f.getRoutePath() != null && !f.getRoutePath().isBlank()) {
                        menuPaths.add(f.getRoutePath());
                    }
                });
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("featureCodes", featureCodes);
        result.put("menuPaths", menuPaths);
        return result;
    }

    @GetMapping("/menu-tree")
    public List<Map<String, Object>> menuTree() {
        JwtAuthFilter.AuthenticatedAgent agent = SecurityUtils.currentAgent();
        if (agent == null) {
            throw new IllegalArgumentException("未登录");
        }
        Agent agentEntity = agentRepository.findByIdWithRoleIds(agent.agentId()).orElse(null);
        if (agentEntity == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        
        List<String> permissions = getAgentPermissions(agentEntity);
        Set<String> permissionSet = new HashSet<>(permissions);
        
        List<SysFeature> allFeatures = featureRepository.findAllByOrderBySortOrderAsc();
        
        List<SysFeature> accessibleFeatures = allFeatures.stream()
            .filter(f -> permissionSet.contains(f.getCode()))
            .collect(Collectors.toList());
        
        Set<Long> accessibleFeatureIds = accessibleFeatures.stream()
            .map(SysFeature::getId)
            .collect(Collectors.toSet());
        
        List<Map<String, Object>> tree = new ArrayList<>(buildMenuTree(accessibleFeatures, null));
        
        for (SysFeature f : accessibleFeatures) {
            if ((f.getType().equals("MENU_2") || f.getType().equals("MENU_3")) 
                && f.getRoutePath() != null && !f.getRoutePath().isBlank()) {
                Long parentId = f.getParentId();
                if (parentId == null || !accessibleFeatureIds.contains(parentId)) {
                    boolean alreadyAdded = tree.stream().anyMatch(node -> 
                        f.getCode().equals(node.get("code")) || 
                        f.getRoutePath().equals(node.get("path"))
                    );
                    if (!alreadyAdded) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("id", f.getId());
                        node.put("code", f.getCode());
                        node.put("title", f.getName());
                        node.put("path", f.getRoutePath());
                        node.put("icon", f.getIcon());
                        node.put("type", f.getType());
                        node.put("sortOrder", f.getSortOrder() != null ? f.getSortOrder() : 0);
                        node.put("children", new ArrayList<>());
                        tree.add(node);
                    }
                }
            }
        }
        
        tree.sort((a, b) -> ((Integer) a.get("sortOrder")).compareTo((Integer) b.get("sortOrder")));
        
        return tree;
    }
    
    private List<Map<String, Object>> buildMenuTree(List<SysFeature> features, Long parentId) {
        return features.stream()
            .filter(f -> Objects.equals(f.getParentId(), parentId))
            .filter(f -> "MENU_1".equals(f.getType()) || "MENU_2".equals(f.getType()) || "MENU_3".equals(f.getType()))
            .map(f -> {
                Map<String, Object> node = new HashMap<>();
                node.put("id", f.getId());
                node.put("code", f.getCode());
                node.put("title", f.getName());
                node.put("path", f.getRoutePath());
                node.put("icon", f.getIcon());
                node.put("type", f.getType());
                node.put("sortOrder", f.getSortOrder() != null ? f.getSortOrder() : 0);
                
                List<Map<String, Object>> children = buildMenuTree(features, f.getId());
                List<Map<String, Object>> validChildren = children.stream()
                    .filter(child -> child.get("path") != null || !child.containsKey("children") || ((List<?>) child.get("children")).size() > 0)
                    .collect(Collectors.toList());
                node.put("children", validChildren);
                return node;
            })
            .sorted((a, b) -> ((Integer) a.get("sortOrder")).compareTo((Integer) b.get("sortOrder")))
            .collect(Collectors.toList());
    }

    @GetMapping("/me")
    public Map<String, Object> getCurrentUser() {
        JwtAuthFilter.AuthenticatedAgent agent = SecurityUtils.currentAgent();
        if (agent == null) {
            throw new IllegalArgumentException("未登录");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agent.agentId());
        result.put("username", agent.username());
        result.put("accountType", agent.accountType());
        result.put("merchantId", agent.merchantId());

        Agent agentEntity = agentRepository.findByIdWithRoleIds(agent.agentId()).orElse(null);
        if (agentEntity != null) {
            result.put("displayName", agentEntity.getDisplayName());
            List<String> permissions = getAgentPermissions(agentEntity);
            result.put("permissions", permissions);
            
            if (agentEntity.getRoleIds() != null && !agentEntity.getRoleIds().isEmpty()) {
                List<Map<String, Object>> roles = roleRepository.findAllById(agentEntity.getRoleIds()).stream()
                    .map(r -> {
                        Map<String, Object> roleInfo = new HashMap<>();
                        roleInfo.put("id", r.getId());
                        roleInfo.put("name", r.getName());
                        roleInfo.put("code", r.getCode());
                        return roleInfo;
                    })
                    .collect(Collectors.toList());
                result.put("roles", roles);
            } else {
                result.put("roles", Collections.emptyList());
            }
        }

        return result;
    }

    /**
     * 获取用户权限列表
     * 有自定义角色(roleIds非空)时，直接查数据库role_feature表获取角色分配的权限
     * 无自定义角色时，按账号类型返回默认权限
     */
    private List<String> getAgentPermissions(Agent agent) {
        java.util.Set<String> permissions = new java.util.HashSet<>();
        
        boolean hasCustomRoles = agent.getRoleIds() != null && !agent.getRoleIds().isEmpty();
        
        if (hasCustomRoles) {
            // 直接查数据库role_feature表，绕过Hibernate ManyToMany关联问题
            String placeholders = agent.getRoleIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
            List<String> featureCodes = entityManager.createNativeQuery(
                "SELECT f.code FROM role_feature rf JOIN sys_features f ON rf.feature_id = f.id WHERE rf.role_id IN (" + placeholders + ")"
            ).getResultList();
            permissions.addAll(featureCodes);
        } else {
            Integer accountType = agent.getAccountType();
            if (accountType == null) {
                accountType = 1;
            }
            if (accountType == 0) {
                if (agent.getMerchantId() == null) {
                    permissions.addAll(getPlatformAdminPermissions());
                } else {
                    permissions.addAll(getMerchantAdminPermissions());
                }
            } else {
                permissions.addAll(getSalesPermissions());
            }
        }
        
        // 自动补全祖先菜单权限：有子菜单权限就必须有父菜单权限
        List<SysFeature> allFeatures = featureRepository.findAllByOrderBySortOrderAsc();
        java.util.Map<Long, SysFeature> featureById = new java.util.HashMap<>();
        for (SysFeature f : allFeatures) {
            featureById.put(f.getId(), f);
        }
        java.util.Map<String, SysFeature> featureByCode = new java.util.HashMap<>();
        for (SysFeature f : allFeatures) {
            featureByCode.put(f.getCode(), f);
        }
        
        boolean changed = true;
        while (changed) {
            changed = false;
            java.util.Set<String> codesSnapshot = new java.util.HashSet<>(permissions);
            for (String code : codesSnapshot) {
                SysFeature f = featureByCode.get(code);
                if (f != null && f.getParentId() != null) {
                    SysFeature parent = featureById.get(f.getParentId());
                    if (parent != null && !permissions.contains(parent.getCode())) {
                        permissions.add(parent.getCode());
                        changed = true;
                    }
                }
            }
        }
        
        return List.copyOf(permissions);
    }

    private List<String> getPlatformAdminPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service", "config", "system", "log",
            "account-numbers", "accounts", "customers", "guilds", "guild-members",
            "friend-manage", "ai-settings", "merchants", "users", "roles", "features", "audit"
        );
    }

    private List<String> getMerchantAdminPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service", "config", "log",
            "account-numbers", "accounts", "customers", "guilds", "guild-members",
            "friend-manage", "ai-settings", "audit"
        );
    }

    private List<String> getManagerPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service", "config", "log",
            "account-numbers", "accounts", "customers", "guilds", "guild-members",
            "friend-manage", "ai-settings", "audit"
        );
    }

    private List<String> getSalesPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service",
            "accounts", "customers", "guild-members", "friend-manage", "account-numbers"
        );
    }

    private List<String> getServicePermissions() {
        return List.of(
            "chat", "customer", "service",
            "accounts", "customers", "friend-manage", "account-numbers"
        );
    }
}
