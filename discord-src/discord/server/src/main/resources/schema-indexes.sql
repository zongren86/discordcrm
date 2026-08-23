-- ================================================
-- 索引创建脚本 - Discord Admin 系统
-- 用于优化列表页查询性能
-- 执行前请确保数据库已备份
-- ================================================

-- discord_accounts 表索引
ALTER TABLE discord_accounts ADD INDEX idx_merchant_id (merchant_id);
ALTER TABLE discord_accounts ADD INDEX idx_status (status);
ALTER TABLE discord_accounts ADD INDEX idx_merchant_status (merchant_id, status);
ALTER TABLE discord_accounts ADD INDEX idx_account_type (account_type);
ALTER TABLE discord_accounts ADD INDEX idx_discord_id (discord_id);
ALTER TABLE discord_accounts ADD INDEX idx_email (email);
ALTER TABLE discord_accounts ADD INDEX idx_merchant_status_type (merchant_id, status, account_type);

-- user 表索引
ALTER TABLE user ADD INDEX idx_user_merchant_id (merchant_id);
ALTER TABLE user ADD INDEX idx_user_account_type (account_type);
ALTER TABLE user ADD INDEX idx_user_merchant_enabled (merchant_id, enabled);

-- guild_servers 表索引
ALTER TABLE guild_servers ADD INDEX idx_guild_merchant_id (merchant_id);
ALTER TABLE guild_servers ADD INDEX idx_guild_discord_account_id (discord_account_id);
ALTER TABLE guild_servers ADD INDEX idx_guild_status (status);
ALTER TABLE guild_servers ADD INDEX idx_guild_merchant_status (merchant_id, status);

-- fetch_progress 表索引
ALTER TABLE fetch_progress ADD INDEX idx_fp_guild_server_id (guild_server_id);
ALTER TABLE fetch_progress ADD INDEX idx_fp_status (status);
ALTER TABLE fetch_progress ADD INDEX idx_fp_guild_server_status (guild_server_id, status);
ALTER TABLE fetch_progress ADD INDEX idx_fp_discord_account_id (discord_account_id);

-- conversations 表索引
ALTER TABLE conversations ADD INDEX idx_conv_merchant_id (merchant_id);
ALTER TABLE conversations ADD INDEX idx_conv_status (status);
ALTER TABLE conversations ADD INDEX idx_conv_last_message_at (last_message_at);
ALTER TABLE conversations ADD INDEX idx_conv_merchant_status (merchant_id, status);
ALTER TABLE conversations ADD INDEX idx_conv_merchant_last_msg (merchant_id, last_message_at);
ALTER TABLE conversations ADD INDEX idx_conv_merchant_status_last_msg (merchant_id, status, last_message_at);
ALTER TABLE conversations ADD INDEX idx_conv_merchant_account (merchant_id, discord_account_id);
ALTER TABLE conversations ADD INDEX idx_conv_merchant_stage (merchant_id, stage);
ALTER TABLE conversations ADD INDEX idx_conv_channel_id (channel_id);
ALTER TABLE conversations ADD INDEX idx_conv_discord_user_id (discord_user_id);

-- messages 表索引
ALTER TABLE messages ADD INDEX idx_messages_conversation (conversation_id);
ALTER TABLE messages ADD INDEX idx_messages_merchant_id (merchant_id);
ALTER TABLE messages ADD INDEX idx_messages_conversation_created (conversation_id, created_at);
ALTER TABLE messages ADD INDEX idx_messages_conversation_direction (conversation_id, direction, discord_created_at);
ALTER TABLE messages ADD INDEX idx_messages_created_at (created_at);
ALTER TABLE messages ADD INDEX idx_messages_direction (direction);
ALTER TABLE messages ADD INDEX idx_messages_sender_id (sender_discord_user_id);
ALTER TABLE messages ADD INDEX idx_messages_merchant_created (merchant_id, created_at);

-- merchants 表索引
ALTER TABLE merchants ADD INDEX idx_merchant_status (status);

-- guild_members 表索引
ALTER TABLE guild_members ADD INDEX idx_gm_guild_server_id (guild_server_id);
ALTER TABLE guild_members ADD INDEX idx_gm_user_id (user_id);
ALTER TABLE guild_members ADD INDEX idx_gm_guild_user (guild_server_id, user_id);

-- ================================================
-- 关联表索引
-- ================================================

-- user_discord_accounts 关联表索引
ALTER TABLE user_discord_accounts ADD INDEX idx_uda_user_id (user_id);
ALTER TABLE user_discord_accounts ADD INDEX idx_uda_account_id (discord_account_id);

-- user_role_ids 关联表索引
ALTER TABLE user_role_ids ADD INDEX idx_ari_user_id (user_id);
