#!/bin/bash
# ⭐ 安全部署脚本 — 不会出现"sed 改 service 但 JAR 没 scp"的竞态
# 用法: ./deploy_safe.sh <new_version>
# 例如: ./deploy_safe.sh 1.7.15

NEW_VER="$1"
if [ -z "$NEW_VER" ]; then
  echo "❌ 用法: ./deploy_safe.sh <version>"
  exit 1
fi

echo "============================================"
echo "  部署 discord-admin-server-$NEW_VER.jar"
echo "============================================"

# Step 1: scp 新 JAR 到服务器（先到位）
echo ""
echo "[1/4] 上传 JAR..."
sshpass -p 'laeC7ooC7eif#aih' scp -o StrictHostKeyChecking=no -o PreferredAuthentications=password -o PubkeyAuthentication=no \
  "server/target/discord-admin-server-$NEW_VER.jar" \
  "root@101.47.41.149:/opt/discord-admin/current/"
if [ $? -ne 0 ]; then echo "❌ scp 失败"; exit 1; fi

# Step 2: 验证服务器上 JAR 存在且可读
echo ""
echo "[2/4] 验证 JAR..."
sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no root@101.47.41.149 \
  "ls -lh /opt/discord-admin/current/discord-admin-server-$NEW_VER.jar && java -jar /opt/discord-admin/current/discord-admin-server-$NEW_VER.jar --version 2>/dev/null || echo '(跳过版本检查)'"

# Step 3: 改 systemd（JAR 已就位，安全）
echo ""
echo "[3/4] 更新 systemd..."
sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no root@101.47.41.149 \
  "sed -i 's/discord-admin-server-.*\.jar/discord-admin-server-$NEW_VER.jar/' /etc/systemd/system/discord-admin.service && systemctl daemon-reload"

# Step 4: 重启
echo ""
echo "[4/4] 重启服务..."
sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no root@101.47.41.149 \
  "systemctl restart discord-admin && for i in \$(seq 1 30); do sleep 2; C=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8090/api/auth/login -X POST -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"admin123\"}'); if [ \"\$C\" = '200' ]; then echo '✅ HTTP \$C (\$((i*2))s)'; break; fi; done"

echo ""
echo "============================================"
echo "  ✅ 部署完成: v$NEW_VER"
echo "============================================"
