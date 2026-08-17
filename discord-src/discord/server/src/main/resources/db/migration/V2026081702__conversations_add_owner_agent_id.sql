-- ================================================
-- conversations 表：添加 owner_agent_id 字段
-- 用于权限控制：普通用户仅能看到 ownerAgentId = 当前用户ID 的会话
-- ================================================
ALTER TABLE conversations
  ADD COLUMN IF NOT EXISTS owner_agent_id BIGINT NULL COMMENT '当前归属的用户ID（用于权限控制）';

CREATE INDEX IF NOT EXISTS idx_conv_owner_agent_id
  ON conversations(owner_agent_id);

-- 为现有会话初始化 owner_agent_id
-- 关系链：conversations.discord_account_id -> discord_account_numbers.discord_account_id 
--       -> agent_account_number_rel.account_number_id -> agent_account_number_rel.agent_id
UPDATE conversations c
INNER JOIN discord_account_numbers dan ON dan.discord_account_id = c.discord_account_id
INNER JOIN agent_account_number_rel aanr ON aanr.account_number_id = dan.id
SET c.owner_agent_id = aanr.agent_id
WHERE c.owner_agent_id IS NULL
  AND c.discord_account_id IS NOT NULL;
