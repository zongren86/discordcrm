#!/bin/bash
# =============================================================================
# Discord CRM 一键构建脚本（版本号只改一个地方，自动同步全部文件）
# 用法: ./build.sh                # 自动 package.json 版本 +0.0.1
#       ./build.sh 1.7.5          # 直接指定版本号
# =============================================================================
set -e
cd "$(dirname "$0")"

NEW_VER="${1:-}"
if [ -z "$NEW_VER" ]; then
  CUR=$(python3 -c "import json;print(json.load(open('crm_agent/package.json'))['version'])")
  NEW_VER=$(python3 -c "p='$CUR'.split('.');p[-1]=str(int(p[-1])+1);print('.'.join(p))")
fi

echo "=========================================="
echo " Discord CRM Build → v$NEW_VER"
echo "=========================================="

# ========== 1. Python 统一改版本号（pom.xml / package.json / config.json / config.example.json / Java 硬编码 / configTemplate）==========
echo ""
echo "[1/5] 同步版本号到所有文件（6 处）..."

python3 << PYEOF
import json, re, os

NEW_VER = "$NEW_VER"

# 1a. pom.xml
with open('server/pom.xml') as f: c = f.read()
c = re.sub(r'(<artifactId>discord-admin-server</artifactId>\s*\n\s*)<version>.*?</version>', r'\1<version>'+NEW_VER+'</version>', c)
with open('server/pom.xml', 'w') as f: f.write(c)
print(f"  ✅ pom.xml → {NEW_VER}")

# 1b. crm_agent/package.json
with open('crm_agent/package.json') as f: d = json.load(f)
d['version'] = NEW_VER
with open('crm_agent/package.json', 'w') as f: json.dump(d, f, indent=2); f.write('\n')
print(f"  ✅ package.json → {NEW_VER}")

# 1c. crm_agent/config.json（用户运行时配置）
with open('crm_agent/config.json') as f: d = json.load(f)
d['version'] = NEW_VER
with open('crm_agent/config.json', 'w') as f: json.dump(d, f, indent=4, ensure_ascii=False); f.write('\n')
print(f"  ✅ config.json → {NEW_VER}")

# 1d. crm_agent/config.example.json（干净模板）
if os.path.exists('crm_agent/config.example.json'):
    with open('crm_agent/config.example.json') as f: d = json.load(f)
else:
    d = {"serverUrl":"http://127.0.0.1:8090/api","agentName":"crm-agent-01","token":"","heartbeatIntervalMs":5000,"pollIntervalMs":5000,"production":False,"browser":{"headless":False,"type":"chromium","userDataDir":"./data/browser-profile","viewport":{"width":1280,"height":800}}}
d['version'] = NEW_VER
with open('crm_agent/config.example.json', 'w') as f: json.dump(d, f, indent=4, ensure_ascii=False); f.write('\n')
print(f"  ✅ config.example.json → {NEW_VER}")

# 1e. Java readAgentVersion 兜底硬编码 + configTemplate 里的 version 字段
JAVA = 'server/src/main/java/com/discordadmin/controller/AgentServerController.java'
with open(JAVA) as f: c = f.read()
# 兜底 return "1.x.x" → 新版本
c = re.sub(r'return "[0-9]+\.[0-9]+\.[0-9]+";', f'return "{NEW_VER}";', c)
# configTemplate 里的 "version": "1.x.x" → 新版本（注意 Java 字符串转义 \\\" 变成 \"）
c = re.sub(r'(\\\\\\"version\\\\\\": \\\\\\")([0-9]+\.[0-9]+\.[0-9]+)(\\\\\\")', rf'\1{NEW_VER}\3', c)
# 也处理可能的字面值 $NEW_VER
c = c.replace('"version": "$NEW_VER"', f'"version": "{NEW_VER}"')
c = c.replace('\\"version\\": \\"$NEW_VER\\"', f'\\"version\\": \\"{NEW_VER}\\"')
with open(JAVA, 'w') as f: f.write(c)
print(f"  ✅ Java readAgentVersion 兜底硬编码 → {NEW_VER}")
print(f"  ✅ Java configTemplate version → {NEW_VER}")
PYEOF

# ========== 2. 打 crm_agent 全量 zip ==========
echo ""
echo "[2/5] 打 crm_agent 全量 zip..."
rm -f server/src/main/resources/crm_agent-v*.zip server/src/main/resources/agent-package.zip
rm -f crm_agent/config.json.user crm_agent/*.bak crm_agent/src/*.bak crm_agent/.DS_Store 2>/dev/null

FULL_ZIP="server/src/main/resources/crm_agent-v$NEW_VER.zip"
zip -r "$FULL_ZIP" crm_agent \
  -x "crm_agent/node_modules/*" \
     "crm_agent/data/*" \
     "crm_agent/.git/*" \
     "crm_agent/config.json" \
     "crm_agent/config.json.*" \
     "crm_agent/*.bak" \
     "crm_agent/src/*.bak" \
     "crm_agent/.DS_Store" \
     "crm_agent/agent.log" \
     "crm_agent/package-lock.json" \
  > /dev/null 2>&1
cp "$FULL_ZIP" server/src/main/resources/agent-package.zip
echo "  ✅ crm_agent-v$NEW_VER.zip ($(ls -lh "$FULL_ZIP" | awk '{print $5}'))"

# ========== 3. 验证 zip 完整性 ==========
echo ""
echo "[3/5] 验证 zip 完整性..."

# 3a. 无 config.json（防 token 泄露）
unzip -l "$FULL_ZIP" | grep -qE "crm_agent/config\.json$" && { echo "  ❌ zip 里有 config.json！"; exit 1; }
echo "  ✅ 无 config.json（安全）"

# 3b. 有 config.example.json
unzip -l "$FULL_ZIP" | grep -q "config.example.json" || { echo "  ❌ 缺少 config.example.json"; exit 1; }
echo "  ✅ 有 config.example.json"

# 3c. 有所有核心文件
CORE=("src/index.js" "src/discord.js" "src/browser.js" "src/http.js" "src/config.js"
      "src/agent/account_fingerprint.js" "src/gateway/gateway_manager.js"
      "src/network/network_gate.js" "src/scheduler/capacity_policy.js"
      "src/observability/runtime_trace.js" "package.json")
for f in "${CORE[@]}"; do
  unzip -l "$FULL_ZIP" | grep -q "crm_agent/$f" || { echo "  ❌ 缺少 $f"; exit 1; }
done
echo "  ✅ 11 个核心文件全在"

# 3d. zip 里 package.json 版本号 = NEW_VER
VER_IN_ZIP=$(unzip -p "$FULL_ZIP" crm_agent/package.json 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['version'])")
[ "$VER_IN_ZIP" = "$NEW_VER" ] || { echo "  ❌ zip 内版本 $VER_IN_ZIP != $NEW_VER"; exit 1; }
echo "  ✅ zip 内 package.json version=$VER_IN_ZIP"

# ========== 4. Maven 打后端 JAR ==========
echo ""
echo "[4/5] Maven 打后端 JAR..."
cd server && rm -rf target && mvn clean package -DskipTests -q 2>&1 | tail -3
JAR="target/discord-admin-server-$NEW_VER.jar"
[ -f "$JAR" ] || { echo "  ❌ JAR 没打出来"; exit 1; }
echo "  ✅ discord-admin-server-$NEW_VER.jar ($(ls -lh "$JAR" | awk '{print $5}'))"
cd ..

# ========== 5. 输出部署指引 ==========
echo ""
echo "=========================================="
echo " ✅ v$NEW_VER 构建完成！"
echo "=========================================="
echo ""
echo " 📦 部署到生产 149:"
echo ""
echo "   scp server/target/discord-admin-server-$NEW_VER.jar root@101.47.41.149:/opt/discord-admin/current/"
echo "   ssh root@101.47.41.149:"
echo "     sed -i 's/discord-admin-server-.*\.jar/discord-admin-server-$NEW_VER.jar/' /etc/systemd/system/discord-admin.service"
echo "     systemctl daemon-reload && systemctl restart discord-admin"
echo ""
echo " 📋 Windows agent 升级:"
echo ""
echo "   Invoke-WebRequest -Uri http://101.47.41.149/api/agent-servers/package -OutFile agent.zip"
echo "   Expand-Archive agent.zip . -Force"
echo "   Copy-Item crm_agent\\config.example.json config.json"
echo "   # 编辑 config.json → 填 token → production: true"
echo "   cd crm_agent && npm install && node src/index.js"
echo ""
