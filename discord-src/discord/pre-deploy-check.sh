#!/bin/bash
# 部署前检查 — 本地 JAR 存在即可，版本号每次递增，不管生产
set -e
MODE="${1:-all}"

echo "============================================"
echo "  🔍 部署前检查"
echo "============================================"

LOCAL_VER=$(awk '/<artifactId>discord-admin-server<\/artifactId>/ {getline; print}' server/pom.xml | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
echo ""
echo "本地版本: $LOCAL_VER"

if [ "$MODE" != "frontend" ]; then
  [ ! -f "server/target/discord-admin-server-$LOCAL_VER.jar" ] && { echo "❌ 本地 JAR 不存在！先 mvn package"; exit 1; }
  echo "✅ JAR 就绪: server/target/discord-admin-server-$LOCAL_VER.jar ($(ls -lh server/target/discord-admin-server-$LOCAL_VER.jar | awk '{print $5}'))"
fi

echo ""
echo "✅ 可以部署"
