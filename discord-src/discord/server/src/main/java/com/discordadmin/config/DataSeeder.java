package com.discordadmin.config;

import com.discordadmin.entity.Role;
import com.discordadmin.entity.SysFeature;
import com.discordadmin.repository.RoleRepository;
import com.discordadmin.repository.SysFeatureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data-seed.enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private final SysFeatureRepository featureRepository;
    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("========================================");
        log.info("开始初始化数据...");

        seedFeatures();
        seedRolePermissions();

        log.info("数据初始化完成！");
        log.info("========================================");
    }

    private void seedFeatures() {
        log.info("--- 初始化功能菜单 ---");

        // 清除现有角色功能关联
        List<Role> allRoles = roleRepository.findAll();
        for (Role role : allRoles) {
            role.getFeatures().clear();
        }
        roleRepository.saveAll(allRoles);
        roleRepository.flush();

        // 清除现有功能
        featureRepository.deleteAll();
        featureRepository.flush();

        // 一级菜单
        SysFeature dashboard = createFeature("dashboard", "仪表盘", null, "MENU_1", "/stats", "DataAnalysis", 1);
        SysFeature chat = createFeature("chat", "消息中心", null, "MENU_1", "/chat", "ChatDotRound", 2);
        SysFeature customer = createFeature("customer", "客户", null, "MENU_1", null, "UserFilled", 3);
        SysFeature service = createFeature("service", "服务", null, "MENU_1", null, "Shop", 4);
        SysFeature config = createFeature("config", "配置", null, "MENU_1", null, "Setting", 5);
        SysFeature system = createFeature("system", "系统", null, "MENU_1", null, "Tools", 6);
        SysFeature logMenu = createFeature("log", "日志", null, "MENU_1", null, "Document", 7);

        // 客户二级菜单
        SysFeature accountNumbers = createFeature("account-numbers", "账号编号管理", customer.getId(), "MENU_2", "/account-numbers", "Tickets", 1);
        SysFeature accounts = createFeature("accounts", "Discord账号管理", customer.getId(), "MENU_2", "/accounts", "User", 2);
        SysFeature customers = createFeature("customers", "客户管理", customer.getId(), "MENU_2", "/customers", "UserFilled", 3);

        // 服务二级菜单
        SysFeature guilds = createFeature("guilds", "服务器列表", service.getId(), "MENU_2", "/guilds", "OfficeBuilding", 1);
        SysFeature guildMembers = createFeature("guild-members", "服务器成员", service.getId(), "MENU_2", "/guild-members", "User", 2);
        SysFeature friendManage = createFeature("friend-manage", "好友管理", service.getId(), "MENU_2", "/emulator", "Monitor", 3);

        // 配置二级菜单
        SysFeature aiConfig = createFeature("ai-settings", "AI配置", config.getId(), "MENU_2", "/ai-settings", "Cpu", 1);

        // 系统二级菜单
        SysFeature users = createFeature("users", "用户管理", system.getId(), "MENU_2", "/users", "User", 1);
        SysFeature roles = createFeature("roles", "角色管理", system.getId(), "MENU_2", "/roles", "Lock", 2);
        SysFeature features = createFeature("features", "功能管理", system.getId(), "MENU_2", "/features", "Grid", 3);

        // 日志二级菜单
        SysFeature audit = createFeature("audit", "操作日志", logMenu.getId(), "MENU_2", "/audit", "Document", 1);

        log.info("功能菜单初始化完成，共 {} 个功能", featureRepository.count());
    }

    private SysFeature createFeature(String code, String name, Long parentId, String type, String routePath, String icon, Integer sortOrder) {
        SysFeature feature = new SysFeature();
        feature.setCode(code);
        feature.setName(name);
        feature.setParentId(parentId);
        feature.setType(type);
        feature.setRoutePath(routePath);
        feature.setIcon(icon);
        feature.setSortOrder(sortOrder);
        return featureRepository.save(feature);
    }

    private void seedRolePermissions() {
        log.info("--- 初始化角色权限 ---");

        Map<String, SysFeature> featureMap = featureRepository.findAll().stream()
                .collect(Collectors.toMap(SysFeature::getCode, f -> f));

        List<Role> existingRoles = roleRepository.findAll();

        // super_admin: 全部功能
        ensureRole(existingRoles, featureMap, "super_admin", "超级管理员", "拥有系统所有权限", true,
                Arrays.asList("dashboard", "chat", "customer", "service", "config", "system", "log",
                        "account-numbers", "accounts", "customers", "guilds", "guild-members",
                        "friend-manage", "ai-settings", "users", "roles", "features", "audit"));

        // admin: 除系统管理外的大部分功能
        ensureRole(existingRoles, featureMap, "admin", "管理员", "系统管理员角色", true,
                Arrays.asList("dashboard", "chat", "customer", "service", "config", "log",
                        "account-numbers", "accounts", "customers", "guilds", "guild-members",
                        "friend-manage", "ai-settings", "audit"));

        // merchant_admin: 商户管理员 - 商户级管理员，拥有好友管理全部权限
        ensureMerchantRole(existingRoles, featureMap, "merchant_admin", "商户管理员", "商户管理员角色，可管理好友添加、服务器、账号等", true,
                Arrays.asList("dashboard", "chat", "customer", "service",
                        "account-numbers", "accounts", "customers", "guilds", "guild-members",
                        "friend-manage"));

        // sales: 业务相关功能
        ensureRole(existingRoles, featureMap, "sales", "销售", "销售人员角色", true,
                Arrays.asList("dashboard", "chat", "customer", "service",
                        "accounts", "customers", "guild-members", "friend-manage", "account-numbers"));

        // service: 客服相关
        ensureRole(existingRoles, featureMap, "service", "客服", "客服人员角色", true,
                Arrays.asList("chat", "customer", "service",
                        "accounts", "customers", "friend-manage", "account-numbers"));

        // read_only: 只读
        ensureRole(existingRoles, featureMap, "read_only", "只读用户", "只读权限角色", true,
                Arrays.asList("dashboard", "chat", "guild-members"));

        log.info("角色权限初始化完成，共 {} 个角色", roleRepository.count());
    }

    private void ensureMerchantRole(List<Role> existingRoles, Map<String, SysFeature> featureMap,
                                    String code, String name, String description, boolean builtin,
                                    List<String> featureCodes) {
        Role role = existingRoles.stream()
                .filter(r -> code.equals(r.getCode()))
                .findFirst()
                .orElseGet(() -> {
                    Role newRole = new Role();
                    newRole.setCode(code);
                    newRole.setName(name);
                    newRole.setDescription(description);
                    newRole.setBuiltin(builtin);
                    newRole.setRoleType(Role.RoleType.MERCHANT);
                    return roleRepository.save(newRole);
                });

        role.setName(name);
        role.setDescription(description);
        role.setBuiltin(builtin);
        role.setRoleType(Role.RoleType.MERCHANT);

        Set<SysFeature> features = new HashSet<>();
        for (String code2 : featureCodes) {
            SysFeature feature = featureMap.get(code2);
            if (feature != null) {
                features.add(feature);
            }
        }
        role.setFeatures(features);
        roleRepository.save(role);

        log.info("  商户角色 [{}] 分配了 {} 个功能权限", name, features.size());
    }

    private void ensureRole(List<Role> existingRoles, Map<String, SysFeature> featureMap,
                           String code, String name, String description, boolean builtin,
                           List<String> featureCodes) {
        Role role = existingRoles.stream()
                .filter(r -> code.equals(r.getCode()))
                .findFirst()
                .orElseGet(() -> {
                    Role newRole = new Role();
                    newRole.setCode(code);
                    newRole.setName(name);
                    newRole.setDescription(description);
                    newRole.setBuiltin(builtin);
                    newRole.setRoleType(Role.RoleType.PLATFORM);
                    return roleRepository.save(newRole);
                });

        // 更新角色信息
        role.setName(name);
        role.setDescription(description);
        role.setBuiltin(builtin);
        role.setRoleType(Role.RoleType.PLATFORM);

        // 分配功能权限
        Set<SysFeature> features = new HashSet<>();
        for (String code2 : featureCodes) {
            SysFeature feature = featureMap.get(code2);
            if (feature != null) {
                features.add(feature);
            }
        }
        role.setFeatures(features);
        roleRepository.save(role);

        log.info("  角色 [{}] 分配了 {} 个功能权限", name, features.size());
    }
}