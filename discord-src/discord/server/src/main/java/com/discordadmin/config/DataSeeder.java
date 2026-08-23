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

    // 定义所有需要的功能菜单代码
    private static final List<String> REQUIRED_FEATURE_CODES = Arrays.asList(
        "dashboard", "chat", "customer", "service", "config", "system", "log",
        "account-numbers", "accounts", "customers", "guilds", "guild-members",
        "friend-manage", "friend-list", "friend-config", "ai-settings", "users", "roles", "features", "audit"
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 快速检查：如果所有必需的功能菜单都已存在，跳过初始化
        if (isDataUpToDate()) {
            log.info("数据已是最新，无需初始化");
            return;
        }

        log.info("检查并初始化功能菜单和角色权限...");

        int newFeatures = seedFeatures();
        int newRoles = seedRolePermissions();

        if (newFeatures > 0 || newRoles > 0) {
            log.info("初始化完成：新增 {} 个功能，新增 {} 个角色配置", newFeatures, newRoles);
        } else {
            log.info("数据已是最新，无需初始化");
        }
    }

    /**
     * 快速检查：所有必需的功能菜单是否都已存在
     */
    private boolean isDataUpToDate() {
        Set<String> existingCodes = featureRepository.findAll().stream()
                .map(SysFeature::getCode)
                .collect(Collectors.toSet());

        for (String required : REQUIRED_FEATURE_CODES) {
            if (!existingCodes.contains(required)) {
                log.info("检测到缺失的功能菜单: {}", required);
                return false;
            }
        }

        // 检查所有必需的角色是否存在
        List<String> requiredRoles = Arrays.asList("super_admin", "admin", "merchant_admin", "sales", "service", "read_only");
        for (String roleCode : requiredRoles) {
            if (roleRepository.findByCode(roleCode).isEmpty()) {
                log.info("检测到缺失的角色: {}", roleCode);
                return false;
            }
        }

        return true;
    }

    /**
     * 增量初始化功能菜单：只创建不存在的功能
     */
    private int seedFeatures() {
        Map<String, SysFeature> existingMap = featureRepository.findAll().stream()
                .collect(Collectors.toMap(SysFeature::getCode, f -> f, (a, b) -> a));

        int created = 0;

        // 定义所有功能（一级菜单）
        created += createFeatureIfMissing(existingMap, "dashboard", "仪表盘", null, "MENU_1", "/stats", "DataAnalysis", 1);
        created += createFeatureIfMissing(existingMap, "chat", "消息中心", null, "MENU_1", "/chat", "ChatDotRound", 2);
        created += createFeatureIfMissing(existingMap, "customer", "客户", null, "MENU_1", null, "UserFilled", 3);
        created += createFeatureIfMissing(existingMap, "service", "服务", null, "MENU_1", null, "Shop", 4);
        created += createFeatureIfMissing(existingMap, "config", "配置", null, "MENU_1", null, "Setting", 5);
        created += createFeatureIfMissing(existingMap, "system", "系统", null, "MENU_1", null, "Tools", 6);
        created += createFeatureIfMissing(existingMap, "log", "日志", null, "MENU_1", null, "Document", 7);

        // 二级菜单需要父ID，先获取已存在的一级菜单
        Map<String, Long> parentIdMap = featureRepository.findAll().stream()
                .collect(Collectors.toMap(SysFeature::getCode, SysFeature::getId));

        Long customerParentId = parentIdMap.get("customer");
        Long serviceParentId = parentIdMap.get("service");
        Long configParentId = parentIdMap.get("config");
        Long systemParentId = parentIdMap.get("system");
        Long logParentId = parentIdMap.get("log");

        // 客户二级菜单
        if (customerParentId != null) {
            created += createFeatureIfMissing(existingMap, "account-numbers", "账号编号管理", customerParentId, "MENU_2", "/account-numbers", "Tickets", 1);
            created += createFeatureIfMissing(existingMap, "accounts", "Discord账号管理", customerParentId, "MENU_2", "/accounts", "User", 2);
            created += createFeatureIfMissing(existingMap, "customers", "客户管理", customerParentId, "MENU_2", "/customers", "UserFilled", 3);
        }

        // 服务二级菜单
        if (serviceParentId != null) {
            created += createFeatureIfMissing(existingMap, "guilds", "服务器列表", serviceParentId, "MENU_2", "/guilds", "OfficeBuilding", 1);
            created += createFeatureIfMissing(existingMap, "guild-members", "服务器成员", serviceParentId, "MENU_2", "/guild-members", "User", 2);
            created += createFeatureIfMissing(existingMap, "friend-manage", "好友管理", serviceParentId, "MENU_2", "/emulator", "Monitor", 3);
            // 好友管理子TAB权限
            created += createFeatureIfMissing(existingMap, "friend-list", "模拟器列表", serviceParentId, "MENU_2", "/emulator", "Monitor", 4);
            created += createFeatureIfMissing(existingMap, "friend-config", "好友配置", serviceParentId, "MENU_2", "/emulator", "Setting", 5);
        }

        // 配置二级菜单
        if (configParentId != null) {
            created += createFeatureIfMissing(existingMap, "ai-settings", "AI配置", configParentId, "MENU_2", "/ai-settings", "Cpu", 1);
        }

        // 系统二级菜单
        if (systemParentId != null) {
            created += createFeatureIfMissing(existingMap, "users", "用户管理", systemParentId, "MENU_2", "/users", "User", 1);
            created += createFeatureIfMissing(existingMap, "roles", "角色管理", systemParentId, "MENU_2", "/roles", "Lock", 2);
            created += createFeatureIfMissing(existingMap, "features", "功能管理", systemParentId, "MENU_2", "/features", "Grid", 3);
        }

        // 日志二级菜单
        if (logParentId != null) {
            created += createFeatureIfMissing(existingMap, "audit", "操作日志", logParentId, "MENU_2", "/audit", "Document", 1);
        }

        if (created > 0) {
            log.info("新增 {} 个功能菜单", created);
        }
        return created;
    }

    /**
     * 如果功能不存在则创建
     */
    private int createFeatureIfMissing(Map<String, SysFeature> existingMap,
                                       String code, String name, Long parentId,
                                       String type, String routePath, String icon, Integer sortOrder) {
        if (existingMap.containsKey(code)) {
            return 0;
        }

        SysFeature feature = new SysFeature();
        feature.setCode(code);
        feature.setName(name);
        feature.setParentId(parentId);
        feature.setType(type);
        feature.setRoutePath(routePath);
        feature.setIcon(icon);
        feature.setSortOrder(sortOrder);

        featureRepository.save(feature);
        existingMap.put(code, feature);
        log.debug("  创建功能: {}", code);
        return 1;
    }

    /**
     * 增量初始化角色权限：只创建不存在的角色，只添加新增的功能权限
     */
    private int seedRolePermissions() {
        Map<String, SysFeature> featureMap = featureRepository.findAll().stream()
                .collect(Collectors.toMap(SysFeature::getCode, f -> f, (a, b) -> a));

        int newRoles = 0;

        // super_admin: 全部功能
        newRoles += ensureRoleIncremental(featureMap, "super_admin", "超级管理员", "拥有系统所有权限",
                Role.RoleType.PLATFORM, true,
                Arrays.asList("dashboard", "chat", "customer", "service", "config", "system", "log",
                        "account-numbers", "accounts", "customers", "guilds", "guild-members",
                        "friend-manage", "friend-list", "friend-config", "ai-settings", "users", "roles", "features", "audit"));

        // admin: 除系统管理外的大部分功能
        newRoles += ensureRoleIncremental(featureMap, "admin", "管理员", "系统管理员角色",
                Role.RoleType.PLATFORM, true,
                Arrays.asList("dashboard", "chat", "customer", "service", "config", "log",
                        "account-numbers", "accounts", "customers", "guilds", "guild-members",
                        "friend-manage", "friend-list", "friend-config", "ai-settings", "audit"));

        // merchant_admin: 商户管理员
        newRoles += ensureRoleIncremental(featureMap, "merchant_admin", "商户管理员",
                "商户管理员角色，可管理好友添加、服务器、账号等",
                Role.RoleType.MERCHANT, true,
                Arrays.asList("dashboard", "chat", "customer", "service",
                        "account-numbers", "accounts", "customers", "guilds", "guild-members",
                        "friend-manage", "friend-list", "friend-config"));

        // sales: 业务相关功能
        newRoles += ensureRoleIncremental(featureMap, "sales", "销售", "销售人员角色",
                Role.RoleType.PLATFORM, true,
                Arrays.asList("dashboard", "chat", "customer", "service",
                        "accounts", "customers", "guild-members", "friend-manage", "friend-list", "account-numbers"));

        // service: 客服相关
        newRoles += ensureRoleIncremental(featureMap, "service", "客服", "客服人员角色",
                Role.RoleType.PLATFORM, true,
                Arrays.asList("chat", "customer", "service",
                        "accounts", "customers", "friend-manage", "friend-list", "account-numbers"));

        // read_only: 只读
        newRoles += ensureRoleIncremental(featureMap, "read_only", "只读用户", "只读权限角色",
                Role.RoleType.PLATFORM, true,
                Arrays.asList("dashboard", "chat", "guild-members", "friend-list"));

        return newRoles;
    }

    /**
     * 增量创建角色并分配权限
     * - 角色不存在 → 创建并分配所有指定权限
     * - 角色已存在 → 只添加新增的权限，不清除旧权限
     */
    private int ensureRoleIncremental(Map<String, SysFeature> featureMap,
                                       String code, String name, String description,
                                       Role.RoleType roleType, boolean builtin,
                                       List<String> featureCodes) {
        Optional<Role> existingOpt = roleRepository.findByCode(code);

        if (existingOpt.isEmpty()) {
            // 角色不存在，创建并分配所有权限
            Role role = new Role();
            role.setCode(code);
            role.setName(name);
            role.setDescription(description);
            role.setBuiltin(builtin);
            role.setRoleType(roleType);

            Set<SysFeature> features = new HashSet<>();
            for (String code2 : featureCodes) {
                SysFeature feature = featureMap.get(code2);
                if (feature != null) {
                    features.add(feature);
                }
            }
            role.setFeatures(features);
            roleRepository.save(role);
            log.info("  创建角色 [{}] 并分配 {} 个功能权限", name, features.size());
            return 1;
        } else {
            // 角色已存在，只添加缺失的权限
            Role role = existingOpt.get();
            Set<SysFeature> existingFeatures = role.getFeatures() != null
                    ? new HashSet<>(role.getFeatures())
                    : new HashSet<>();

            int addedCount = 0;
            for (String code2 : featureCodes) {
                SysFeature feature = featureMap.get(code2);
                if (feature != null && !existingFeatures.contains(feature)) {
                    existingFeatures.add(feature);
                    addedCount++;
                }
            }

            if (addedCount > 0) {
                role.setFeatures(existingFeatures);
                roleRepository.save(role);
                log.info("  为角色 [{}] 新增 {} 个功能权限", name, addedCount);
            }
            return 0;
        }
    }
}