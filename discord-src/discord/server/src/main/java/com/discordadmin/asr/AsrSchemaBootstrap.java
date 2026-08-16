package com.discordadmin.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 启动时，检查 messages 表是否存在 ASR 相关字段；
 * 如果不存在，执行 src/main/resources/db/migration/V2026081501__messages_add_asr_columns.sql 中的 DDL。
 * 这样即使 ddl-auto=none 也能自动把字段补齐，避免 Unknown column 错误。
 */
@Component
public class AsrSchemaBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AsrSchemaBootstrap.class);

    private final JdbcTemplate jdbcTemplate;

    public AsrSchemaBootstrap(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!hasAsrColumns()) {
                log.warn("[ASR] messages 表缺少 asr_* 字段，尝试自动迁移...");
                List<String> stmts = loadMigrationStatements();
                for (String s : stmts) {
                    String sql = s.trim();
                    if (sql.isEmpty() || sql.startsWith("--")) continue;
                    try {
                        jdbcTemplate.execute(sql);
                        log.info("[ASR] 执行DDL成功: {}", sql.length() > 120 ? sql.substring(0, 120) + "..." : sql);
                    } catch (Exception ex) {
                        // MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，会报 Duplicate column name
                        // 这种情况忽略即可
                        String msg = ex.getMessage() == null ? "" : ex.getMessage();
                        if (msg.contains("Duplicate column name")
                                || msg.contains("Duplicate key name")
                                || msg.contains("already exists")) {
                            log.info("[ASR] DDL已存在，忽略: {}", msg);
                        } else {
                            log.warn("[ASR] 执行DDL失败: {} | SQL = {}", msg,
                                    sql.length() > 200 ? sql.substring(0, 200) : sql);
                        }
                    }
                }
                log.info("[ASR] messages 表 ASR 字段迁移完成");
            }
        } catch (Exception e) {
            log.warn("[ASR] 自动迁移失败，请手动执行 resources/db/migration/*.sql", e);
        }
    }

    private boolean hasAsrColumns() {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND COLUMN_NAME LIKE 'asr_%'",
                    String.class);
            return names != null && names.size() >= 5
                    && names.contains("asr_text")
                    && names.contains("asr_translated")
                    && names.contains("asr_language")
                    && names.contains("asr_status")
                    && names.contains("asr_error");
        } catch (Exception e) {
            log.warn("[ASR] 检查字段失败，假设不存在: {}", e.getMessage());
            return false;
        }
    }

    private List<String> loadMigrationStatements() {
        // 优先使用 MySQL 友好的语句（逐条检查 INFORMATION_SCHEMA，避免 ADD COLUMN IF NOT EXISTS 语法在 MySQL 不支持）
        return mysqlFriendlyStatements();
    }

    private List<String> splitStatements(String content) {
        // 按分号切，保留注释行；每个语句单独执行
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : content.split("\n")) {
            String trim = line.trim();
            if (trim.isEmpty() || trim.startsWith("--")) {
                // 注释行不参与拼装
                continue;
            }
            buf.append(line).append("\n");
            if (trim.endsWith(";")) {
                out.add(buf.toString().replaceFirst(";\\s*$", ""));
                buf.setLength(0);
            }
        }
        if (!buf.isEmpty()) {
            String tail = buf.toString().trim();
            if (!tail.isEmpty()) out.add(tail.replaceFirst(";\\s*$", ""));
        }
        return out;
    }

    private List<String> fallbackStatements() {
        List<String> l = new ArrayList<>();
        l.add("ALTER TABLE messages ADD COLUMN asr_text TEXT NULL");
        l.add("ALTER TABLE messages ADD COLUMN asr_translated TEXT NULL");
        l.add("ALTER TABLE messages ADD COLUMN asr_language VARCHAR(16) NULL");
        l.add("ALTER TABLE messages ADD COLUMN asr_status VARCHAR(16) NULL");
        l.add("ALTER TABLE messages ADD COLUMN asr_error VARCHAR(512) NULL");
        l.add("ALTER TABLE messages ADD INDEX idx_messages_asr_status (asr_status)");
        return l;
    }

    private List<String> mysqlFriendlyStatements() {
        // 每条 DDL 独立执行，避免 ADD COLUMN IF NOT EXISTS 语法不被 MySQL 支持而失败
        List<String> l = new ArrayList<>();
        String[] cols = {
                "asr_text TEXT NULL COMMENT '语音转文字原文（ASR输出）'",
                "asr_translated TEXT NULL COMMENT '语音转文字译文（默认为中文）'",
                "asr_language VARCHAR(16) NULL COMMENT 'ASR 检测到的语言，如 en/zh/ja 等'",
                "asr_status VARCHAR(16) NULL COMMENT '转写状态：pending/done/failed'",
                "asr_error VARCHAR(512) NULL COMMENT 'ASR 失败原因'"
        };
        for (String c : cols) {
            // 取出列名
            int sp = c.indexOf(' ');
            String colName = sp > 0 ? c.substring(0, sp) : c;
            if (!columnExists(colName)) {
                l.add("ALTER TABLE messages ADD COLUMN " + c);
            } else {
                log.info("[ASR] 字段已存在，跳过: messages.{}", colName);
            }
        }
        if (!indexExists("idx_messages_asr_status")) {
            l.add("ALTER TABLE messages ADD INDEX idx_messages_asr_status (asr_status)");
        }
        return l;
    }

    private boolean columnExists(String columnName) {
        try {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND COLUMN_NAME = ?",
                    Integer.class, columnName);
            return c != null && c > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean indexExists(String indexName) {
        try {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND INDEX_NAME = ?",
                    Integer.class, indexName);
            return c != null && c > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
