package com.discordadmin.controller;

import com.discordadmin.dto.AuthDtos.LoginRequest;
import com.discordadmin.dto.AuthDtos.LoginResponse;
import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Merchant;
import com.discordadmin.entity.Role;
import com.discordadmin.entity.SysFeature;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.MerchantRepository;
import com.discordadmin.repository.RoleRepository;
import com.discordadmin.repository.SysFeatureRepository;
import com.discordadmin.security.JwtAuthFilter;
import com.discordadmin.security.JwtUtil;
import com.discordadmin.security.SecurityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AgentRepository agentRepository;
    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RoleRepository roleRepository;
    private final SysFeatureRepository featureRepository;

    public AuthController(AgentRepository agentRepository,
                          MerchantRepository merchantRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil,
                          RoleRepository roleRepository,
                          SysFeatureRepository featureRepository) {
        this.agentRepository = agentRepository;
        this.merchantRepository = merchantRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.roleRepository = roleRepository;
        this.featureRepository = featureRepository;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        Agent agent = agentRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (agent.getEnabled() == null || !agent.getEnabled()) {
            throw new IllegalArgumentException("账号已被禁用");
        }

        if (!passwordEncoder.matches(request.password(), agent.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(agent.getId(), agent.getUsername(),
                agent.getAccountType(), agent.getMerchantId());

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
        Agent agentEntity = agentRepository.findById(agent.agentId()).orElse(null);
        if (agentEntity == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        List<String> permissions = getAgentPermissions(agentEntity);
        java.util.Set<String> featureCodes = new java.util.HashSet<>(permissions);
        java.util.Set<String> menuPaths = new java.util.HashSet<>();
        
        // 1. 直接匹配：功能码本身有 route_path（菜单级功能，如 chat, customer）
        for (String code : permissions) {
            featureRepository.findByCode(code).ifPresent(f -> {
                if (f.getRoutePath() != null && !f.getRoutePath().isBlank()) {
                    menuPaths.add(f.getRoutePath());
                }
            });
        }
        
        // 2. 推断匹配：按钮级功能（如 chat.view, chat.send）推断菜单级路径
        for (String code : permissions) {
            int dotIdx = code.indexOf('.');
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
        Agent agentEntity = agentRepository.findById(agent.agentId()).orElse(null);
        if (agentEntity == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        
        List<String> permissions = getAgentPermissions(agentEntity);
        Set<String> permissionSet = new HashSet<>(permissions);
        
        // 获取所有功能
        List<SysFeature> allFeatures = featureRepository.findAllByOrderBySortOrderAsc();
        
        // 过滤出用户有权限的功能
        List<SysFeature> accessibleFeatures = allFeatures.stream()
            .filter(f -> permissionSet.contains(f.getCode()))
            .collect(Collectors.toList());
        
        // 构建菜单树（一级菜单）
        return buildMenuTree(accessibleFeatures, null);
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
                
                // 递归构建子菜单
                List<Map<String, Object>> children = buildMenuTree(features, f.getId());
                node.put("children", children);
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

        Agent agentEntity = agentRepository.findById(agent.agentId()).orElse(null);
        if (agentEntity != null) {
            result.put("displayName", agentEntity.getDisplayName());
            List<String> permissions = getAgentPermissions(agentEntity);
            result.put("permissions", permissions);
            
            // 获取分配的角色信息
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
                result.put("assignedRoles", roles);
            }
        }

        if (agent.merchantId() != null) {
            merchantRepository.findById(agent.merchantId()).ifPresent(m -> {
                result.put("merchantName", m.getName());
            });
        }

        return result;
    }

    /**
     * 获取用户权限列表
     * 优先使用自定义角色权限，无自定义角色或自定义角色无权限时根据内置角色类型返回默认权限
     */
    private List<String> getAgentPermissions(Agent agent) {
        java.util.Set<String> permissions = new java.util.HashSet<>();
        
        boolean hasCustomRoles = agent.getRoleIds() != null && !agent.getRoleIds().isEmpty();
        boolean hasPermissions = false;
        
        if (hasCustomRoles) {
            // 有自定义角色：使用自定义角色的权限
            List<Role> customRoles = roleRepository.findByIdInWithFeatures(agent.getRoleIds());
            for (Role customRole : customRoles) {
                for (SysFeature feature : customRole.getFeatures()) {
                    permissions.add(feature.getCode());
                    hasPermissions = true;
                }
            }
        }
        
        // 如果没有自定义角色或自定义角色没有分配权限，则根据账号类型返回默认权限
        if (!hasPermissions) {
            Integer accountType = agent.getAccountType();
            if (accountType == null) {
                accountType = 1;
            }
            if (accountType == 0) {
                // 管理员：区分平台管理员和商户管理员
                if (agent.getMerchantId() == null) {
                    permissions.addAll(getPlatformAdminPermissions());
                } else {
                    permissions.addAll(getMerchantAdminPermissions());
                }
            } else {
                // 普通账号：基础权限
                permissions.addAll(getSalesPermissions());
            }
        }
        
        return List.copyOf(permissions);
    }

    private java.util.Set<String> getAllFeatureCodes() {
        return featureRepository.findAllByOrderBySortOrderAsc().stream()
            .map(SysFeature::getCode)
            .collect(Collectors.toSet());
    }

    private List<String> getPlatformAdminPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service", "config", "system", "log",
            "account-numbers", "accounts", "customers", "guilds", "guild-members",
            "friend-manage", "friend-list", "friend-config", "ai-settings", "users", "roles", "features", "audit"
        );
    }

    private List<String> getMerchantAdminPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service", "config", "log",
            "account-numbers", "accounts", "customers", "guilds", "guild-members",
            "friend-manage", "friend-list", "friend-config", "ai-settings", "audit"
        );
    }

    private List<String> getManagerPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service", "config", "log",
            "account-numbers", "accounts", "customers", "guilds", "guild-members",
            "friend-manage", "friend-list", "friend-config", "ai-settings", "audit"
        );
    }

    private List<String> getSalesPermissions() {
        return List.of(
            "dashboard", "chat", "customer", "service",
            "accounts", "customers", "guild-members", "friend-manage", "friend-list", "account-numbers"
        );
    }

    private List<String> getServicePermissions() {
        return List.of(
            "chat", "customer", "service",
            "accounts", "customers", "friend-manage", "friend-list", "account-numbers"
        );
    }
}
