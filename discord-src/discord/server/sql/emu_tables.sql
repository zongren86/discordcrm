-- 模拟器管理相关表
-- 创建时间: 2026-08-17

-- 模拟器账号绑定表
CREATE TABLE IF NOT EXISTS emu_account_bindings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    discord_account_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ADDED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_account (merchant_id, discord_account_id),
    INDEX idx_merchant (merchant_id),
    INDEX idx_user (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模拟器服务器绑定表
CREATE TABLE IF NOT EXISTS emu_server_bindings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    server_id BIGINT,
    guild_id VARCHAR(64),
    server_name VARCHAR(256),
    member_count INT DEFAULT 0,
    discord_account_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'ADDED',
    last_sync_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_merchant (merchant_id),
    INDEX idx_user (user_id),
    INDEX idx_server (server_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 好友号池表
CREATE TABLE IF NOT EXISTS emu_friend_pool (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    server_id BIGINT,
    discord_user_id VARCHAR(64) NOT NULL,
    username VARCHAR(256),
    global_name VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    assigned_task_id BIGINT,
    last_error VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_pool_merchant (merchant_id),
    INDEX idx_pool_server (server_id),
    INDEX idx_pool_status (status),
    INDEX idx_pool_user_id (discord_user_id),
    UNIQUE KEY uk_merchant_user_status (merchant_id, discord_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模拟器实例表（存储mumu模拟器信息）
CREATE TABLE IF NOT EXISTS emu_instances (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(128),
    instance_index INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    cpu_cores INT DEFAULT 1,
    memory_gb INT DEFAULT 1,
    resolution VARCHAR(32) DEFAULT '720x1280',
    adb_port INT,
    discord_installed TINYINT DEFAULT 0,
    discord_logged_in TINYINT DEFAULT 0,
    discord_account_id BIGINT,
    auto_running TINYINT DEFAULT 0,
    added_count INT DEFAULT 0,
    next_add_at TIMESTAMP NULL,
    last_error VARCHAR(512),
    auto_last_result VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_merchant (merchant_id),
    INDEX idx_status (status),
    INDEX idx_discord_account (discord_account_id),
    INDEX idx_instance_index (instance_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- APK版本表
CREATE TABLE IF NOT EXISTS apk_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_code INT NOT NULL,
    version_name VARCHAR(64),
    file_path VARCHAR(512),
    file_size BIGINT,
    download_url VARCHAR(1024),
    is_uploaded TINYINT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
