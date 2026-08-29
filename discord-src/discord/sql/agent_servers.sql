CREATE TABLE IF NOT EXISTS agent_servers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE COMMENT '节点名称',
    token           VARCHAR(128) NOT NULL COMMENT '认证token',
    server_address  VARCHAR(512) COMMENT '代理服务器地址',
    merchant_id     BIGINT COMMENT '所属商户ID',
    status          VARCHAR(16) DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE',
    node_version    VARCHAR(64) COMMENT 'Node.js 版本',
    browser_type    VARCHAR(32) COMMENT '浏览器类型',
    notes           VARCHAR(500) COMMENT '备注',
    last_seen_at    DATETIME(6) COMMENT '最后心跳时间',
    created_at      DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_agent_server_name (name),
    INDEX idx_agent_server_status (status),
    INDEX idx_agent_server_merchant (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理服务器节点';

CREATE TABLE IF NOT EXISTS agent_tasks (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    type                  VARCHAR(64) NOT NULL COMMENT '任务类型',
    agent_server_id       BIGINT COMMENT '分配的代理节点',
    status                VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    params                TEXT COMMENT '任务参数(JSON)',
    result                TEXT COMMENT '执行结果(JSON)',
    created_by_user_id    BIGINT COMMENT '发起人',
    discord_account_id    BIGINT COMMENT '关联账号ID',
    created_at            DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_agent_task_status (status),
    INDEX idx_agent_task_server (agent_server_id),
    INDEX idx_agent_task_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理任务';
