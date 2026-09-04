#!/bin/bash
# =============================================================================
# Discord CRM 一键构建脚本
# 用法: ./build.sh                # package.json 版本 +0.0.1
#       ./build.sh 1.7.5          # 直接指定版本号
#
# 做什么:
#   1. 版本号统一到所有位置（pom.xml / package.json / config.json / Java 2处）
#   2. crm_agent 打全量 zip（临时放干净 config.json，打完恢复用户的）
#   3. Maven 打后端 JAR（zip 内嵌进 JAR resources）
#   4. 验证完整性 + 版本号正确性
#   5. 输出部署指引
# =============================================================================
set -e
cd "$(dirname "$0")"
PROJECT_ROOT=$(pwd)

NEW_VER="${1:-}"
if [ -z "$NEW_VER" ]; then
  CUR=$(python3 -c "import json;print(json.load(open('crm_agent/package.json'))['version'])")
  NEW_VER=$(python3 -c "p='$CUR'.split('.');p[-1]=str(int(p[-1])+1);print('.'.join(p))")
fi

echo "=========================================="
echo " Discord CRM Build → v$NEW_VER"
echo "=========================================="

# ========== 1. Python 统一改版本号（6 处）==========
echo ""
echo "[1/4] 同步版本号..."

python3 -c "import sys; sys.setrecursionlimit(10000)"
python3 << PYEOF
# -*- coding: utf-8 -*-
import json, re, os

NEW_VER = "$NEW_VER"
PROJECT_ROOT = os.getcwd()

# 1a. pom.xml
p = os.path.join(PROJECT_ROOT, 'server/pom.xml')
with open(p) as f: c = f.read()
c = re.sub(r'(<artifactId>discord-admin-server</artifactId>\s*\n\s*)<version>.*?</version>', r'\1<version>'+NEW_VER+'</version>', c)
with open(p, 'w') as f: f.write(c)

# 1b. crm_agent/package.json
p = os.path.join(PROJECT_ROOT, 'crm_agent/package.json')
with open(p) as f: d = json.load(f)
d['version'] = NEW_VER
with open(p, 'w') as f: json.dump(d, f, indent=2); f.write('\n')

# 1c. crm_agent/config.json（用户运行时配置）
p = os.path.join(PROJECT_ROOT, 'crm_agent/config.json')
if os.path.exists(p):
    with open(p) as f: d = json.load(f)
    d['version'] = NEW_VER
    with open(p, 'w') as f: json.dump(d, f, indent=4, ensure_ascii=False); f.write('\n')

# 1d. Java AgentServerController：readAgentVersion 兜底 + configTemplate version
p = os.path.join(PROJECT_ROOT, 'server/src/main/java/com/discordadmin/controller/AgentServerController.java')
with open(p) as f: c = f.read()
# 兜底 return "xxx" → 新版本（处理 return "数字.数字.数字"）
c = re.sub(r'return "\d+\.\d+\.\d+"\s*;', f'return "{NEW_VER}";', c)
# configTemplate 里的 \\"version\\": \\"xxx\\"
c = re.sub(r'(\\\\\\"version\\\\\\": \\\\\\")([0-9]+\.[0-9]+\.[0-9]+)(\\\\\\")', lambda m: m.group(1)+NEW_VER+m.group(3), c)
# 字面值 $NEW_VER（以防万一）
c = c.replace('"version": "$NEW_VER"', f'"version": "{NEW_VER}"')
c = c.replace('\\"version\\": \\"$NEW_VER\\"', f'\\"version\\": \\"{NEW_VER}\\"')
with open(p, 'w') as f: f.write(c)

print(f"  ✅ 5 处版本号 → {NEW_VER}")
PYEOF

# ========== 2. 打 crm_agent 全量 zip ==========
echo ""
echo "[2/4] 打 crm_agent 全量 zip..."

# ⚠️ 用户 config.json 可能有 token → 先备份 → 放干净模板 → 打包 → 恢复
USER_CFG=""
if [ -f "crm_agent/config.json" ]; then
  cp crm_agent/config.json /tmp/crm_agent-config.bak
  USER_CFG="1"
fi

# 生成干净 config.json（token 空，production false，版本同步）
python3 -c "import sys; sys.setrecursionlimit(10000)"
python3 << PYEOF_CFG
# -*- coding: utf-8 -*-
import json
v = "$NEW_VER"
tpl = {
    "serverUrl": "http://127.0.0.1:8090/api",
    "agentName": "crm-agent-01",
    "token": "",
    "heartbeatIntervalMs": 5000,
    "pollIntervalMs": 5000,
    "production": False,
    "version": v,
    "browser": {
        "headless": False,
        "type": "chromium",
        "userDataDir": "./data/browser-profile",
        "viewport": {"width": 1280, "height": 800}
    }
}
with open("crm_agent/config.json", "w") as f:
    json.dump(tpl, f, indent=4, ensure_ascii=False)
    f.write("\n")
PYEOF_CFG

rm -f server/src/main/resources/crm_agent-v*.zip server/src/main/resources/agent-package.zip
rm -f crm_agent/*.bak crm_agent/src/*.bak crm_agent/.DS_Store 2>/dev/null

FULL_ZIP="server/src/main/resources/crm_agent-v$NEW_VER.zip"
zip -r "$FULL_ZIP" crm_agent \
  -x "crm_agent/node_modules/*" \
     "crm_agent/data/*" \
     "crm_agent/.git/*" \
     "crm_agent/*.bak" \
     "crm_agent/src/*.bak" \
     "crm_agent/.DS_Store" \
     "crm_agent/agent.log" \
     "crm_agent/package-lock.json" \
  > /dev/null 2>&1

# 恢复用户 config.json
if [ -n "$USER_CFG" ]; then
  mv /tmp/crm_agent-config.bak crm_agent/config.json
  echo "  🔄 已恢复用户 config.json"
fi

cp "$FULL_ZIP" server/src/main/resources/agent-package.zip
echo "  ✅ crm_agent-v$NEW_VER.zip ($(ls -lh "$FULL_ZIP" | awk '{print $5}'))"

# ========== 3. 验证 zip 完整性 ==========
echo ""
echo "[3/4] 验证 zip..."

# 3a. 有 config.json 且版本正确（干净模板，token 空）
CFG_TMP=$(unzip -p "$FULL_ZIP" crm_agent/config.json 2>/dev/null)
if [ -z "$CFG_TMP" ]; then echo "  ❌ 缺少 config.json"; exit 1; fi
CFG_VER=$(echo "$CFG_TMP" | python3 -c "import sys,json;print(json.load(sys.stdin)['version'])" 2>/dev/null)
[ "$CFG_VER" = "$NEW_VER" ] || { echo "  ❌ config.json version $CFG_VER != $NEW_VER"; exit 1; }
echo "  ✅ config.json (version=$CFG_VER, token 空, production=false)"

# 3b. 有所有核心文件
CORE=("src/index.js" "src/discord.js" "src/browser.js" "src/http.js" "src/config.js"
      "src/agent/account_fingerprint.js" "src/gateway/gateway_manager.js"
      "src/network/network_gate.js" "src/scheduler/capacity_policy.js"
      "src/observability/runtime_trace.js" "package.json")
for f in "${CORE[@]}"; do
  unzip -l "$FULL_ZIP" | grep -q "crm_agent/$f" || { echo "  ❌ 缺少 $f"; exit 1; }
done
echo "  ✅ 11 个核心文件全在"

# 3c. package.json 版本号正确
PKG_VER=$(unzip -p "$FULL_ZIP" crm_agent/package.json 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin)['version'])")
[ "$PKG_VER" = "$NEW_VER" ] || { echo "  ❌ package.json $PKG_VER != $NEW_VER"; exit 1; }
echo "  ✅ package.json version=$PKG_VER"

# ========== 4. Maven 打后端 JAR ==========
echo ""
echo "[4/4] Maven 打后端 JAR..."
cd server && rm -rf target && mvn clean package -DskipTests -q 2>&1 | tail -3
JAR="target/discord-admin-server-$NEW_VER.jar"
[ -f "$JAR" ] || { echo "  ❌ JAR 没打出来"; exit 1; }
echo "  ✅ discord-admin-server-$NEW_VER.jar ($(ls -lh "$JAR" | awk '{print $5}'))"
cd ..

# ========== 输出部署指引 ==========
echo ""
echo "=========================================="
echo " ✅ v$NEW_VER 构建完成！"
echo "=========================================="
echo ""
echo " 📦 部署生产 149:"
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
echo "   # config.json 已内嵌（干净模板，token 空），填 token + 改 production:true 即可"
echo "   cd crm_agent && npm install && node src/index.js"
echo ""
