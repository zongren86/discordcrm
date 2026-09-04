#!/bin/bash
# ⚠️ 部署前强制检查 — 防止旧版本 JAR 覆盖生产
set -e
MODE="${1:-all}"

echo "============================================"
echo "  🔍 部署前强制检查"
echo "============================================"

# 本地版本
LOCAL_VER=$(awk '/<artifactId>discord-admin-server<\/artifactId>/ {getline; print}' server/pom.xml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
echo ""
echo "[1/3] 本地 pom.xml 版本: $LOCAL_VER"

# 生产版本（重试 3 次）
echo "[2/3] 生产状态:"
PROD_VER=""
for i in 1 2 3; do
  OUT=$(sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no -o PreferredAuthentications=password -o PubkeyAuthentication=no root@101.47.41.149 "grep ExecStart /etc/systemd/system/discord-admin.service" 2>/dev/null || true)
  PROD_VER=$(echo "$OUT" | grep -oE 'discord-admin-server-[0-9.]+' | head -1 | sed 's/discord-admin-server-//')
  [ -n "$PROD_VER" ] && break
  sleep 1
done
PROD_ACTIVE=$(sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no -o PreferredAuthentications=password -o PubkeyAuthentication=no root@101.47.41.149 "systemctl is-active discord-admin" 2>/dev/null || echo "unknown")
echo "  运行 JAR 版本: $PROD_VER"
echo "  服务状态: $PROD_ACTIVE"

# 版本对比
echo "[3/3] 版本对比:"
if [ -z "$LOCAL_VER" ] || [ -z "$PROD_VER" ]; then
  echo "❌ 版本号读取失败（本地=$LOCAL_VER 生产=$PROD_VER）"
  exit 1
fi

if [ "$LOCAL_VER" != "$PROD_VER" ]; then
  echo ""
  echo "❌ 本地 pom.xml ($LOCAL_VER) ≠ 生产运行版本 ($PROD_VER)"
  echo ""
  echo "   选项 a) 版本号同步（本地功能代码已包含生产所有改动）:"
  echo "     python3 build.sh $PROD_VER"
  echo ""
  echo "   选项 b) 这次只改了前端 → 跳过后端，直接部署 frontend"
  echo ""
  echo "   选项 c) 先看差异再决定:"
  echo "     ssh root@101.47.41.149 'ls -lh /opt/discord-admin/current/'"
  exit 1
else
  echo "✅ 版本一致: $LOCAL_VER"
fi

if [ "$MODE" != "frontend" ]; then
  if [ ! -f "server/target/discord-admin-server-$LOCAL_VER.jar" ]; then
    echo ""
    echo "❌ 本地 JAR 不存在！cd server && mvn clean package -DskipTests"
    exit 1
  fi
  echo "✅ 本地 JAR 就绪: $(ls -lh server/target/discord-admin-server-$LOCAL_VER.jar | awk '{print $5}')"
fi

echo ""
echo "============================================"
echo "  ✅ 检查通过，可以部署"
echo "============================================"
