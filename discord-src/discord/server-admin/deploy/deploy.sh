#!/usr/bin/env bash
# =================================================================
# Discord CRM Admin — server-admin 生产部署脚本
# 目标: 101.47.41.149 (应用服务器, 4C/8G, Ubuntu 22.04)
# 使用: bash deploy.sh           # 上传并重启
# =================================================================

set -euo pipefail

APP_NAME="discord-admin-server-admin"
APP_DIR="/opt/discord-admin/server-admin"
LOG_DIR="/var/log/discord-admin"
REMOTE="root@101.47.41.149"
LOCAL_JAR="$(find target -name 'discord-admin-server-admin-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)"

if [ -z "$LOCAL_JAR" ]; then
  echo "❌ 找不到 jar, 先 mvn clean package -DskipTests"
  exit 1
fi

echo "📦 打包 JAR: $LOCAL_JAR"
ls -lh "$LOCAL_JAR"

# 1. 远程准备目录
echo "🛠️  远程准备..."
ssh "$REMOTE" "mkdir -p $APP_DIR $LOG_DIR && chmod 755 $APP_DIR $LOG_DIR"

# 2. 上传 JAR
echo "⬆️  上传..."
scp "$LOCAL_JAR" "$REMOTE:$APP_DIR/"

# 3. 上传 systemd unit (首次)
if ! ssh "$REMOTE" "test -f /etc/systemd/system/discord-admin.service"; then
  echo "📄 安装 systemd service..."
  scp deploy/discord-admin.service "$REMOTE:/etc/systemd/system/"
  ssh "$REMOTE" "systemctl daemon-reload"
fi

# 4. 重启
echo "🔄 重启服务..."
ssh "$REMOTE" "systemctl restart discord-admin && sleep 3 && systemctl --no-pager -l status discord-admin"

# 5. 看日志
echo ""
echo "📜 最近 30 行日志:"
ssh "$REMOTE" "tail -30 $LOG_DIR/discord-admin.log"

echo ""
echo "✅ 部署完成! 监控: journalctl -u discord-admin -f"
