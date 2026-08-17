-- 历史数据初始化脚本：将会话的 owner_agent_id 设置为其所属 Discord 账号关联的第一个非管理员用户
-- 执行说明：
--   1. 仅更新 owner_agent_id 为 NULL 的会话
--   2. 优先选择 discord_account_id 关联的非管理员用户（role 不是 PLATFORM_ADMIN 或 MERCHANT_ADMIN）
--   3. 如果没有关联用户，则不更新

-- 第一步：查看需要初始化的会话数量
SELECT COUNT(*) as need_init_count
FROM conversations
WHERE owner_agent_id IS NULL
  AND discord_account_id IS NOT NULL;

-- 第二步：查看关联关系（用于确认数据）
-- SELECT c.id as conv_id, c.discord_account_id, daa.agent_id, a.username, a.role
-- FROM conversations c
-- JOIN agent_discord_accounts daa ON c.discord_account_id = daa.discord_account_id
-- JOIN agents a ON daa.agent_id = a.id
-- WHERE c.owner_agent_id IS NULL
--   AND c.discord_account_id IS NOT NULL
-- ORDER BY c.id, a.id;

-- 第三步：更新 owner_agent_id 为第一个非管理员用户
-- 注意：此脚本使用窗口函数选择每个 discord_account_id 下的第一个非管理员用户
UPDATE conversations c
SET owner_agent_id = (
    SELECT sub.agent_id
    FROM (
        SELECT daa.discord_account_id, daa.agent_id,
               ROW_NUMBER() OVER (PARTITION BY daa.discord_account_id ORDER BY daa.agent_id) as rn
        FROM agent_discord_accounts daa
        JOIN agents a ON daa.agent_id = a.id
        WHERE a.role NOT IN ('PLATFORM_ADMIN', 'MERCHANT_ADMIN')
    ) sub
    WHERE sub.discord_account_id = c.discord_account_id
      AND sub.rn = 1
    LIMIT 1
)
WHERE c.owner_agent_id IS NULL
  AND c.discord_account_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM agent_discord_accounts daa
      JOIN agents a ON daa.agent_id = a.id
      WHERE daa.discord_account_id = c.discord_account_id
        AND a.role NOT IN ('PLATFORM_ADMIN', 'MERCHANT_ADMIN')
  );

-- 第四步：查看初始化结果
SELECT COUNT(*) as updated_count
FROM conversations
WHERE owner_agent_id IS NOT NULL;

-- 第五步：查看未初始化的会话（discord_account_id 没有关联任何非管理员用户）
SELECT c.*
FROM conversations c
WHERE c.owner_agent_id IS NULL
  AND c.discord_account_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM agent_discord_accounts daa
      JOIN agents a ON daa.agent_id = a.id
      WHERE daa.discord_account_id = c.discord_account_id
        AND a.role NOT IN ('PLATFORM_ADMIN', 'MERCHANT_ADMIN')
  );
