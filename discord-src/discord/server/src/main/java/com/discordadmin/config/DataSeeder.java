package com.discordadmin.config;

import com.discordadmin.entity.Agent;
import com.discordadmin.entity.Role;
import com.discordadmin.entity.SysFeature;
import com.discordadmin.repository.AgentRepository;
import com.discordadmin.repository.RoleRepository;
import com.discordadmin.repository.SysFeatureRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据初始化种子 - 仅在配置 app.data-seed.enabled=true 时执行
 * 默认禁用，避免覆盖用户数据
 */
@Component
@ConditionalOnProperty(prefix = "app.data-seed", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DataSeeder implements CommandLineRunner {

    private final AgentRepository agentRepository;
    private final RoleRepository roleRepository;
    private final SysFeatureRepository featureRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(AgentRepository agentRepository,
                       RoleRepository roleRepository,
                       SysFeatureRepository featureRepository,
                       PasswordEncoder passwordEncoder) {
        this.agentRepository = agentRepository;
        this.roleRepository = roleRepository;
        this.featureRepository = featureRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // 此方法仅在 app.data-seed.enabled=true 时执行
        // 默认情况下不执行任何初始化操作，避免覆盖用户数据
    }

    private void seedFeatures() {
        List<SysFeature> existing = featureRepository.findAll();
        boolean needMigration = existing.stream().anyMatch(f -> "MENU".equals(f.getType()));
        if (needMigration) {
            for (SysFeature f : existing) {
                if ("MENU".equals(f.getType())) {
                    f.setType("MENU_1");
                    featureRepository.save(f);
                }
            }
        }

        // 更新旧菜单名称
        for (SysFeature f : featureRepository.findAll()) {
            if ("stats".equals(f.getCode()) && !"客户统计".equals(f.getName())) {
                f.setName("客户统计");
                featureRepository.save(f);
            }
        }

        Set<String> existingCodes = featureRepository.findAll().stream()
                .map(SysFeature::getCode)
                .collect(Collectors.toSet());

        // 一级菜单
        if (!existingCodes.contains("chat")) {
            createFeature("chat", "消息中心", null, "MENU_1", "/chat", 1);
        }
        if (!existingCodes.contains("conversation")) {
            createFeature("conversation", "会话管理", null, "MENU_1", "/chat", 2);
        }
        if (!existingCodes.contains("customer")) {
            createFeature("customer", "客户管理", null, "MENU_1", "/customers", 3);
        }
        if (!existingCodes.contains("account")) {
            createFeature("account", "Discord账号", null, "MENU_1", "/accounts", 4);
        }
        if (!existingCodes.contains("guild")) {
            createFeature("guild", "服务器成员", null, "MENU_1", "/guilds", 5);
        }
        if (!existingCodes.contains("stats")) {
            createFeature("stats", "客户统计", null, "MENU_1", "/stats", 6);
        }
        if (!existingCodes.contains("reminder")) {
            createFeature("reminder", "提醒中心", null, "MENU_1", "/reminders", 7);
        }
        if (!existingCodes.contains("ai")) {
            createFeature("ai", "AI配置", null, "MENU_1", "/ai-settings", 8);
        }
        if (!existingCodes.contains("admin")) {
            createFeature("admin", "系统管理", null, "MENU_1", null, 9);
        }
        if (!existingCodes.contains("emulator")) {
            createFeature("emulator", "模拟器", null, "MENU_1", "/emulator", 10);
        }

        // 先重新获取一级菜单ID（可能刚刚创建）
        List<SysFeature> menus = featureRepository.findAllByOrderBySortOrderAsc();
        Map<String, Long> menuIds = new HashMap<>();
        for (SysFeature m : menus) {
            menuIds.put(m.getCode(), m.getId());
        }

        // 系统管理二级菜单
        createFeatureIfMissing("role", "角色管理", menuIds.get("admin"), "MENU_2", "/system/roles", 1, existingCodes);
        createFeatureIfMissing("feature", "功能管理", menuIds.get("admin"), "MENU_2", "/system/features", 2, existingCodes);
        createFeatureIfMissing("user", "用户管理", menuIds.get("admin"), "MENU_2", "/system/users", 3, existingCodes);
        createFeatureIfMissing("merchant", "商户管理", menuIds.get("admin"), "MENU_2", "/system/merchants", 4, existingCodes);
        createFeatureIfMissing("audit", "审计日志", menuIds.get("admin"), "MENU_2", "/system/audit", 5, existingCodes);

        // 重新获取所有菜单（含刚创建的二级菜单）
        menus = featureRepository.findAllByOrderBySortOrderAsc();
        menuIds.clear();
        for (SysFeature m : menus) {
            menuIds.put(m.getCode(), m.getId());
        }

        // 消息中心按钮
        createBtnIfMissing("chat.view", "查看消息", menuIds.get("chat"), "TAB", 1, existingCodes);
        createBtnIfMissing("chat.send", "发送消息", menuIds.get("chat"), "TAB", 2, existingCodes);
        createBtnIfMissing("chat.edit", "编辑消息", menuIds.get("chat"), "ROW_ACTION", 3, existingCodes);
        createBtnIfMissing("chat.delete", "删除消息", menuIds.get("chat"), "ROW_ACTION", 4, existingCodes);
        createBtnIfMissing("chat.reaction", "表情反应", menuIds.get("chat"), "ROW_ACTION", 5, existingCodes);
        createBtnIfMissing("chat.translate", "翻译消息", menuIds.get("chat"), "ROW_ACTION", 6, existingCodes);

        // 会话管理按钮
        createBtnIfMissing("conversation.view", "查看会话", menuIds.get("conversation"), "TAB", 1, existingCodes);
        createBtnIfMissing("conversation.manage", "管理会话", menuIds.get("conversation"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("conversation.assign", "分配会话", menuIds.get("conversation"), "TOOLBAR", 3, existingCodes);
        createBtnIfMissing("conversation.transfer", "转移会话", menuIds.get("conversation"), "ROW_ACTION", 4, existingCodes);
        createBtnIfMissing("conversation.stage", "设置阶段", menuIds.get("conversation"), "ROW_ACTION", 5, existingCodes);
        createBtnIfMissing("conversation.pin", "置顶会话", menuIds.get("conversation"), "ROW_ACTION", 6, existingCodes);
        createBtnIfMissing("conversation.remark", "备注会话", menuIds.get("conversation"), "ROW_ACTION", 7, existingCodes);

        // 客户管理按钮
        createBtnIfMissing("customer.view", "查看客户", menuIds.get("customer"), "TAB", 1, existingCodes);
        createBtnIfMissing("customer.manage", "编辑客户", menuIds.get("customer"), "ROW_ACTION", 2, existingCodes);
        createBtnIfMissing("customer.batch", "批量操作", menuIds.get("customer"), "TOOLBAR", 3, existingCodes);
        createBtnIfMissing("customer.export", "导出客户", menuIds.get("customer"), "TOOLBAR", 4, existingCodes);
        createBtnIfMissing("customer.ai-suggest", "AI建议", menuIds.get("customer"), "ROW_ACTION", 5, existingCodes);

        // Discord账号按钮
        createBtnIfMissing("account.view", "查看账号", menuIds.get("account"), "TAB", 1, existingCodes);
        createBtnIfMissing("account.create", "新增账号", menuIds.get("account"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("account.edit", "编辑账号", menuIds.get("account"), "ROW_ACTION", 3, existingCodes);
        createBtnIfMissing("account.delete", "删除账号", menuIds.get("account"), "ROW_ACTION", 4, existingCodes);
        createBtnIfMissing("account.import", "批量导入", menuIds.get("account"), "TOOLBAR", 5, existingCodes);
        createBtnIfMissing("account.sync", "同步连接", menuIds.get("account"), "ROW_ACTION", 6, existingCodes);

        // 服务器成员按钮
        createBtnIfMissing("guild.view", "查看成员", menuIds.get("guild"), "TAB", 1, existingCodes);
        createBtnIfMissing("guild.manage", "管理服务器", menuIds.get("guild"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("guild.fetch", "抓取成员", menuIds.get("guild"), "TOOLBAR", 3, existingCodes);
        createBtnIfMissing("guild.config", "配置服务器", menuIds.get("guild"), "ROW_ACTION", 4, existingCodes);

        // 统计分析按钮
        createBtnIfMissing("stats.view", "查看统计", menuIds.get("stats"), "TAB", 1, existingCodes);
        createBtnIfMissing("stats.export", "导出数据", menuIds.get("stats"), "TOOLBAR", 2, existingCodes);

        // 提醒中心按钮
        createBtnIfMissing("reminder.view", "查看提醒", menuIds.get("reminder"), "TAB", 1, existingCodes);
        createBtnIfMissing("reminder.manage", "管理提醒", menuIds.get("reminder"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("reminder.delete", "删除提醒", menuIds.get("reminder"), "ROW_ACTION", 3, existingCodes);

        // AI配置按钮
        createBtnIfMissing("ai.view", "查看AI配置", menuIds.get("ai"), "TAB", 1, existingCodes);
        createBtnIfMissing("ai.manage", "管理AI配置", menuIds.get("ai"), "ROW_ACTION", 2, existingCodes);
        createBtnIfMissing("ai.delete", "删除AI配置", menuIds.get("ai"), "ROW_ACTION", 3, existingCodes);

        // 系统管理 - 角色管理按钮
        createBtnIfMissing("role.view", "查看角色", menuIds.get("role"), "TAB", 1, existingCodes);
        createBtnIfMissing("role.create", "新增角色", menuIds.get("role"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("role.edit", "编辑角色", menuIds.get("role"), "ROW_ACTION", 3, existingCodes);
        createBtnIfMissing("role.delete", "删除角色", menuIds.get("role"), "ROW_ACTION", 4, existingCodes);
        createBtnIfMissing("role.permissions", "配置权限", menuIds.get("role"), "ROW_ACTION", 5, existingCodes);
        createBtnIfMissing("role.merchants", "配置商户", menuIds.get("role"), "ROW_ACTION", 6, existingCodes);

        // 系统管理 - 功能管理按钮
        createBtnIfMissing("feature.view", "查看功能", menuIds.get("feature"), "TAB", 1, existingCodes);
        createBtnIfMissing("feature.create", "新增功能", menuIds.get("feature"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("feature.edit", "编辑功能", menuIds.get("feature"), "ROW_ACTION", 3, existingCodes);
        createBtnIfMissing("feature.delete", "删除功能", menuIds.get("feature"), "ROW_ACTION", 4, existingCodes);

        // 系统管理 - 用户管理按钮
        createBtnIfMissing("user.view", "查看用户", menuIds.get("user"), "TAB", 1, existingCodes);
        createBtnIfMissing("user.create", "新增用户", menuIds.get("user"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("user.edit", "编辑用户", menuIds.get("user"), "ROW_ACTION", 3, existingCodes);
        createBtnIfMissing("user.delete", "删除用户", menuIds.get("user"), "ROW_ACTION", 4, existingCodes);
        createBtnIfMissing("user.reset-pwd", "重置密码", menuIds.get("user"), "ROW_ACTION", 5, existingCodes);
        createBtnIfMissing("user.roles", "配置角色", menuIds.get("user"), "ROW_ACTION", 6, existingCodes);

        // 系统管理 - 商户管理按钮
        createBtnIfMissing("merchant.view", "查看商户", menuIds.get("merchant"), "TAB", 1, existingCodes);
        createBtnIfMissing("merchant.create", "新增商户", menuIds.get("merchant"), "TOOLBAR", 2, existingCodes);
        createBtnIfMissing("merchant.edit", "编辑商户", menuIds.get("merchant"), "ROW_ACTION", 3, existingCodes);
        createBtnIfMissing("merchant.delete", "删除商户", menuIds.get("merchant"), "ROW_ACTION", 4, existingCodes);

        // 系统管理 - 审计日志按钮
        createBtnIfMissing("audit.view", "查看日志", menuIds.get("audit"), "TAB", 1, existingCodes);
        createBtnIfMissing("audit.export", "导出日志", menuIds.get("audit"), "TOOLBAR", 2, existingCodes);

        // 模拟器按钮
        createBtnIfMissing("emulator.use", "使用模拟器", menuIds.get("emulator"), "TAB", 1, existingCodes);
    }

    private SysFeature createFeature(String code, String name, Long parentId, String type, String routePath, int sortOrder) {
        SysFeature feature = new SysFeature();
        feature.setCode(code);
        feature.setName(name);
        feature.setParentId(parentId);
        feature.setType(type);
        feature.setRoutePath(routePath);
        feature.setSortOrder(sortOrder);
        return featureRepository.save(feature);
    }

    private void createFeatureIfMissing(String code, String name, Long parentId, String type, String routePath, int sortOrder, Set<String> existingCodes) {
        if (existingCodes.contains(code)) return;
        SysFeature feature = new SysFeature();
        feature.setCode(code);
        feature.setName(name);
        feature.setParentId(parentId);
        feature.setType(type);
        feature.setRoutePath(routePath);
        feature.setSortOrder(sortOrder);
        featureRepository.save(feature);
        existingCodes.add(code);
    }

    private void createBtnIfMissing(String code, String name, Long parentId, String btnType, int sortOrder, Set<String> existingCodes) {
        if (existingCodes.contains(code)) return;
        SysFeature feature = new SysFeature();
        feature.setCode(code);
        feature.setName(name);
        feature.setParentId(parentId);
        feature.setType("BUTTON");
        feature.setBtnType(btnType);
        feature.setSortOrder(sortOrder);
        featureRepository.save(feature);
        existingCodes.add(code);
    }

    private void seedBuiltinRoles() {
        String[][] roles = {
                {"平台管理员", "platform_admin", "拥有全部权限", "PLATFORM"},
                {"商户管理员", "merchant_admin", "商户内全部权限", "MERCHANT"},
                {"主管", "manager", "主管角色", "MERCHANT"},
                {"销售", "sales", "销售角色", "MERCHANT"},
                {"客服", "service", "客服角色", "MERCHANT"}
        };

        for (String[] r : roles) {
            // 只在角色不存在时创建，不自动恢复已删除的角色
            Optional<Role> existing = roleRepository.findByCode(r[1]);
            if (existing.isEmpty()) {
                Role role = new Role();
                role.setName(r[0]);
                role.setCode(r[1]);
                role.setDescription(r[2]);
                role.setBuiltin(true);
                role.setEnabled(true);
                role.setRoleType(Role.RoleType.valueOf(r[3]));

                if ("platform_admin".equals(r[1])) {
                    assignAllFeatures(role);
                } else {
                    assignDefaultFeatures(role);
                }
                roleRepository.save(role);
            }
        }
    }

    private void assignAllFeatures(Role role) {
        List<SysFeature> allFeatures = featureRepository.findAllByOrderBySortOrderAsc();
        role.setFeatures(new HashSet<>(allFeatures));
    }

    private void assignDefaultFeatures(Role role) {
        Set<String> features = new HashSet<>();

        switch (role.getCode()) {
            case "merchant_admin":
                features.addAll(java.util.Set.of(
                    "chat.view", "chat.send", "chat.edit", "chat.delete", "chat.reaction", "chat.translate",
                    "conversation.view", "conversation.manage", "conversation.assign",
                    "conversation.transfer", "conversation.stage", "conversation.pin", "conversation.remark",
                    "customer.view", "customer.manage", "customer.batch", "customer.export", "customer.ai-suggest",
                    "account.view", "account.create", "account.edit", "account.delete", "account.import", "account.sync",
                    "guild.view", "guild.manage", "guild.fetch", "guild.config",
                    "stats.view", "stats.export",
                    "reminder.view", "reminder.manage", "reminder.delete",
                    "ai.view", "ai.manage", "ai.delete",
                    "role.view", "role.create", "role.edit", "role.permissions", "role.merchants",
                    "feature.view", "feature.create", "feature.edit",
                    "user.view", "user.create", "user.edit", "user.roles",
                    "merchant.view", "merchant.create", "merchant.edit",
                    "audit.view", "audit.export",
                    "emulator.use"
                ));
                break;
            case "manager":
                features.addAll(java.util.Set.of(
                    "chat.view", "chat.send", "chat.edit", "chat.reaction", "chat.translate",
                    "conversation.view", "conversation.manage", "conversation.assign",
                    "conversation.transfer", "conversation.stage",
                    "customer.view", "customer.manage", "customer.batch", "customer.export",
                    "account.view", "account.create", "account.edit", "account.import",
                    "guild.view", "guild.manage", "guild.fetch",
                    "stats.view", "stats.export",
                    "reminder.view", "reminder.manage",
                    "ai.view", "ai.manage",
                    "emulator.use"
                ));
                break;
            case "sales":
                features.addAll(java.util.Set.of(
                    "chat.view", "chat.send", "chat.edit", "chat.reaction",
                    "conversation.view",
                    "customer.view", "customer.manage",
                    "account.view",
                    "guild.view",
                    "stats.view",
                    "reminder.view",
                    "ai.view",
                    "emulator.use"
                ));
                break;
            case "service":
                features.addAll(java.util.Set.of(
                    "chat.view", "chat.send", "chat.reaction",
                    "conversation.view",
                    "customer.view",
                    "account.view",
                    "guild.view",
                    "reminder.view",
                    "emulator.use"
                ));
                break;
        }

        if (!features.isEmpty()) {
            List<SysFeature> assignedFeatures = featureRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(f -> features.contains(f.getCode()))
                .toList();
            role.setFeatures(new HashSet<>(assignedFeatures));
        }
    }
}
