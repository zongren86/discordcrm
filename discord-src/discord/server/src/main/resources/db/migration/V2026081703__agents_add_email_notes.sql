-- ================================================
-- agents 表：添加 email 和 notes 字段
-- 用于用户管理中的邮箱和备注
-- ================================================
ALTER TABLE agents
  ADD COLUMN IF NOT EXISTS email VARCHAR(128) NULL COMMENT '邮箱';

ALTER TABLE agents
  ADD COLUMN IF NOT EXISTS notes VARCHAR(500) NULL COMMENT '备注';
