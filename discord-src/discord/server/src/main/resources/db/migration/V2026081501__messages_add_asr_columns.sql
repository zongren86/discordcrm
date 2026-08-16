-- ================================================
-- messages 表：语音转文字(ASR)相关字段迁移
-- 在实体新增 asr_text / asr_translated / asr_language / asr_status / asr_error 后执行
-- ================================================
ALTER TABLE messages
  ADD COLUMN IF NOT EXISTS asr_text       TEXT           NULL COMMENT '语音转文字原文（ASR输出）',
  ADD COLUMN IF NOT EXISTS asr_translated TEXT           NULL COMMENT '语音转文字译文（默认为中文）',
  ADD COLUMN IF NOT EXISTS asr_language   VARCHAR(16)    NULL COMMENT 'ASR 检测到的语言，如 en/zh/ja 等',
  ADD COLUMN IF NOT EXISTS asr_status     VARCHAR(16)    NULL COMMENT '转写状态：pending/done/failed',
  ADD COLUMN IF NOT EXISTS asr_error      VARCHAR(512)   NULL COMMENT 'ASR 失败原因';

CREATE INDEX IF NOT EXISTS idx_messages_asr_status
  ON messages(asr_status);
