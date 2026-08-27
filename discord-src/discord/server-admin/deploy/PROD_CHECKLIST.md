# Discord CRM Admin — 生产环境检查清单

## 应用服务器 (101.47.41.149 — 4C/8G, Ubuntu 22.04)

### 1. 系统依赖
```bash
# Java 17 (必须!)
apt install -y openjdk-17-jdk
java -version  # openjdk version "17.0.x"

# JAVA_HOME
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> /etc/profile.d/java.sh
```

### 2. 内核参数 (避免连接数满)
```bash
# 编辑 /etc/sysctl.conf:
echo "net.core.somaxconn = 65535" >> /etc/sysctl.conf
echo "net.ipv4.tcp_tw_reuse = 1" >> /etc/sysctl.conf
sysctl -p
```

### 3. 安全加固
```bash
# 防火墙只开 9090 (应用) + 22 (SSH)
ufw allow 22
ufw allow 9090
ufw enable
```

## DB 服务器 (101.47.41.155 — 2C/4G, Ubuntu 22.04)

### MySQL 参数 (/etc/mysql/mysql.conf.d/mysqld.cnf)
```ini
[mysqld]
# ============ 针对 2C/4G 共用库 ============
max_connections = 150              # 默认 151, 别改太大
innodb_buffer_pool_size = 1G       # 4G 机器给 1G (25%)
innodb_log_file_size = 256M
innodb_flush_method = O_DIRECT
# ============ 重要! 解决 HikariCP max-lifetime ============
wait_timeout = 28800                # 8小时 (Hikari 每 20min 主动换连接, 所以这个不用改小)
interactive_timeout = 28800
# ============ 其他 ============
slow_query_log = ON
slow_query_log_file = /var/log/mysql/slow.log
long_query_time = 2
```

### 生产首次部署必须执行 (建表 + ALTER)
```sql
-- DiscordAdminApplication 会自动 ddl-auto=validate
-- 但新增列需要手动 ALTER:
ALTER TABLE agent_registrations ADD COLUMN last_db_save_at DATETIME(6) NULL AFTER last_heartbeat_at;

-- 检查索引 (EmuAutoAddDispatcher 高频查询)
SHOW INDEX FROM emu_instances;
-- 如缺: ALTER TABLE emu_instances ADD INDEX idx_merchant_user_status(merchant_id, user_id, status);
```

## 首次启动前必须替换的占位符

| 文件 | 占位符 | 替换为 |
|------|--------|--------|
| `deploy/discord-admin.service` | `CHANGE_ME_DB_PASSWORD` | 真实 DB 密码 |
| `deploy/discord-admin.service` | `CHANGE_ME_TO_A_RANDOM_SECRET_32_BYTES_LONG` | JWT Secret (openssl rand -base64 64) |

## 监控 (生产必看)
```bash
# 实时日志
tail -f /var/log/discord-admin/discord-admin.log
tail -f /var/log/discord-admin/error.log

# 连接池状态 (生产看 SQL 日志里 HikariCP 的 DEBUG 日志)
# 临时开启:
#   logger.name="com.zaxxer.hikari" level="DEBUG" → 重启看 active/idle/pending

# 系统资源
htop
nload

# 连接数
mysql -e "SHOW GLOBAL STATUS LIKE 'Threads_%';"
```
