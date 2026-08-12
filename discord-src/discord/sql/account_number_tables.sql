-- ================================================
-- 账号编号管理相关表结构
-- ================================================

-- 1. 账号编号表
CREATE TABLE IF NOT EXISTS discord_account_numbers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    discord_account_id BIGINT COMMENT '绑定的 Discord 账号ID',
    bound_account VARCHAR(256) COMMENT '绑定的账号名称（冗余存储）',
    creator_id BIGINT COMMENT '创建人ID',
    creator_name VARCHAR(64) COMMENT '创建人用户名（冗余存储）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_discord_account_id (discord_account_id),
    INDEX idx_creator_id (creator_id),
    INDEX idx_bound_account (bound_account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号编号表';

-- 2. 用户-账号编号关联表
CREATE TABLE IF NOT EXISTS agent_account_number_rels (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    agent_id BIGINT NOT NULL COMMENT '用户ID',
    account_number_id BIGINT NOT NULL COMMENT '账号编号ID',
    linked_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_agent_id (agent_id),
    INDEX idx_account_number_id (account_number_id),
    UNIQUE KEY uk_agent_number (agent_id, account_number_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-账号编号关联表';

-- 3. 绑定历史表
CREATE TABLE IF NOT EXISTS account_binding_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    account_number_id BIGINT NOT NULL COMMENT '账号编号ID',
    old_account VARCHAR(256) COMMENT '修改前账号',
    new_account VARCHAR(256) COMMENT '修改后账号',
    change_reason VARCHAR(512) COMMENT '修改原因',
    operator_id BIGINT COMMENT '修改人ID',
    operator_name VARCHAR(64) COMMENT '修改人用户名',
    changed_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    INDEX idx_account_number_id (account_number_id),
    INDEX idx_operator_id (operator_id),
    INDEX idx_changed_at (changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绑定历史表';
