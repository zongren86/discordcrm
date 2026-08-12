-- =============================================
-- 账号编号管理功能完整初始化脚本
-- 执行时间：2026-08-12
-- 说明：初始化账号编号管理相关的表、菜单、按钮权限和角色权限
-- =============================================

-- 1. 创建账号编号表（如果不存在）
CREATE TABLE IF NOT EXISTS discord_account_numbers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    discord_account_id BIGINT NULL COMMENT '绑定的 Discord 账号ID',
    bound_account VARCHAR(256) NULL COMMENT '绑定的账号名称',
    creator_id BIGINT NULL COMMENT '创建人ID',
    creator_name VARCHAR(64) NULL COMMENT '创建人用户名',
    created_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_discord_account_id (discord_account_id),
    INDEX idx_bound_account (bound_account),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号编号表';

-- 2. 创建绑定历史表（如果不存在）
CREATE TABLE IF NOT EXISTS account_binding_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_number_id BIGINT NOT NULL COMMENT '账号编号ID',
    old_account VARCHAR(256) NULL COMMENT '修改前账号',
    new_account VARCHAR(256) NULL COMMENT '修改后账号',
    change_reason VARCHAR(512) NULL COMMENT '修改原因',
    operator_id BIGINT NULL COMMENT '修改人ID',
    operator_name VARCHAR(64) NULL COMMENT '修改人用户名',
    changed_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_account_number_id (account_number_id),
    INDEX idx_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绑定历史表';

-- 3. 创建用户账号编号关联表（如果不存在）
CREATE TABLE IF NOT EXISTS agent_account_number_rels (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL COMMENT '用户ID',
    account_number_id BIGINT NOT NULL COMMENT '账号编号ID',
    linked_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '关联时间',
    created_at DATETIME(6) NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_agent_id (agent_id),
    INDEX idx_account_number_id (account_number_id),
    UNIQUE KEY uk_agent_account (agent_id, account_number_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账号编号关联表';

-- 4. 添加账号编号管理菜单（如果不存在）
INSERT INTO sys_features (code, name, parent_id, type, route_path, icon, sort_order, created_at)
VALUES ('account_number', '账号编号管理', NULL, 'MENU_1', '/account-numbers', 'Key', 11, NOW())
ON DUPLICATE KEY UPDATE 
    name = VALUES(name),
    route_path = VALUES(route_path),
    icon = VALUES(icon),
    sort_order = VALUES(sort_order);

-- 获取菜单ID
SET @menu_id = (SELECT id FROM sys_features WHERE code = 'account_number' LIMIT 1);

-- 5. 添加按钮权限（如果不存在）
INSERT INTO sys_features (code, name, parent_id, type, btn_type, sort_order, created_at) VALUES
('account_number.view', '查看编号', @menu_id, 'BUTTON', 'TAB', 1, NOW()),
('account_number.create', '新增编号', @menu_id, 'BUTTON', 'TOOLBAR', 2, NOW()),
('account_number.edit', '编辑编号', @menu_id, 'BUTTON', 'ROW_ACTION', 3, NOW()),
('account_number.delete', '删除编号', @menu_id, 'BUTTON', 'ROW_ACTION', 4, NOW()),
('account_number.bind', '绑定账号', @menu_id, 'BUTTON', 'ROW_ACTION', 5, NOW()),
('account_number.unbind', '解绑账号', @menu_id, 'BUTTON', 'ROW_ACTION', 6, NOW()),
('account_number.history', '绑定历史', @menu_id, 'BUTTON', 'ROW_ACTION', 7, NOW()),
('account_number.batch-create', '批量创建', @menu_id, 'BUTTON', 'TOOLBAR', 8, NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 6. 为平台管理员角色分配权限
INSERT IGNORE INTO role_feature (role_id, feature_id)
SELECT r.id, f.id FROM roles r, sys_features f 
WHERE r.code = 'platform_admin' 
AND f.code IN ('account_number', 'account_number.view', 'account_number.create', 
               'account_number.edit', 'account_number.delete', 'account_number.bind',
               'account_number.unbind', 'account_number.history', 'account_number.batch-create');

-- 7. 为商户管理员角色分配权限
INSERT IGNORE INTO role_feature (role_id, feature_id)
SELECT r.id, f.id FROM roles r, sys_features f 
WHERE r.code = 'merchant_admin' 
AND f.code IN ('account_number', 'account_number.view', 'account_number.create', 
               'account_number.edit', 'account_number.delete', 'account_number.bind',
               'account_number.unbind', 'account_number.history', 'account_number.batch-create');

-- 8. 为主管角色分配权限
INSERT IGNORE INTO role_feature (role_id, feature_id)
SELECT r.id, f.id FROM roles r, sys_features f 
WHERE r.code = 'manager' 
AND f.code IN ('account_number', 'account_number.view', 'account_number.create',
               'account_number.edit', 'account_number.bind', 'account_number.history');

-- 9. 为销售角色分配权限
INSERT IGNORE INTO role_feature (role_id, feature_id)
SELECT r.id, f.id FROM roles r, sys_features f 
WHERE r.code = 'sales' 
AND f.code IN ('account_number', 'account_number.view');

-- 10. 为客服角色分配权限
INSERT IGNORE INTO role_feature (role_id, feature_id)
SELECT r.id, f.id FROM roles r, sys_features f 
WHERE r.code = 'service' 
AND f.code IN ('account_number', 'account_number.view');

-- =============================================
-- 验证结果
-- =============================================
SELECT '表结构验证' as check_type, 'discord_account_numbers' as object_name, COUNT(*) as row_count FROM discord_account_numbers
UNION ALL
SELECT '表结构验证', 'account_binding_history', COUNT(*) FROM account_binding_history
UNION ALL
SELECT '表结构验证', 'agent_account_number_rels', COUNT(*) FROM agent_account_number_rels
UNION ALL
SELECT '菜单验证', '账号编号管理菜单', COUNT(*) FROM sys_features WHERE code = 'account_number'
UNION ALL
SELECT '菜单验证', '按钮权限数量', COUNT(*) FROM sys_features WHERE code LIKE 'account_number.%'
UNION ALL
SELECT '权限分配', '平台管理员权限', COUNT(*) FROM role_feature rf JOIN sys_features f ON rf.feature_id = f.id JOIN roles r ON rf.role_id = r.id WHERE r.code = 'platform_admin' AND f.code LIKE 'account_number%'
UNION ALL
SELECT '权限分配', '商户管理员权限', COUNT(*) FROM role_feature rf JOIN sys_features f ON rf.feature_id = f.id JOIN roles r ON rf.role_id = r.id WHERE r.code = 'merchant_admin' AND f.code LIKE 'account_number%'
UNION ALL
SELECT '权限分配', '主管权限', COUNT(*) FROM role_feature rf JOIN sys_features f ON rf.feature_id = f.id JOIN roles r ON rf.role_id = r.id WHERE r.code = 'manager' AND f.code LIKE 'account_number%'
UNION ALL
SELECT '权限分配', '销售权限', COUNT(*) FROM role_feature rf JOIN sys_features f ON rf.feature_id = f.id JOIN roles r ON rf.role_id = r.id WHERE r.code = 'sales' AND f.code LIKE 'account_number%'
UNION ALL
SELECT '权限分配', '客服权限', COUNT(*) FROM role_feature rf JOIN sys_features f ON rf.feature_id = f.id JOIN roles r ON rf.role_id = r.id WHERE r.code = 'service' AND f.code LIKE 'account_number%';
