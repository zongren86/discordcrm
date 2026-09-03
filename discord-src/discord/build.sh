#!/bin/bash
# =============================================================================
# Discord CRM 一键构建脚本
# 用法: ./build.sh 1.7.5          # 指定新版本号
#       ./build.sh                # 自动在 package.json 基础上 +0.0.1
#
# 做什么:
#   1. 版本号统一（pom.xml / package.json / config.json / AgentServerController 硬编码 / configTemplate / config.example.json）
#   2. crm_agent 打全量 zip（完整目录树，排除 node_modules/data/.git/config.json）
#   3. 后端 Maven 打 JAR（zip 内嵌进 JAR 的 resources）
#   4. 验证 zip 完整性 + 版本号正确性
#   5. 输出部署到 149 的命令
# =============================================================================

set -e

cd "$(dirname "$0")"
PROJECT_ROOT=$(pwd)

# 1. 确定新版本号
if [ -n "$1" ]; then
  NEW_VER="$1"
else
  CUR_VER=$(python3 -c "import json; print(json.load(open('crm_agent/package.json'))['version'])")
  NEW_VER=$(python3 -c "
parts = '$CUR_VER'.split('.')
parts[-1] = str(int(parts[-1]) + 1)
print('.'.join(parts))
")
fi

echo "=========================================="
echo " Discord CRM Build → v$NEW_VER"
echo "=========================================="
echo ""

# 2. 统一版本号到所有文件
echo "[1/5] 同步版本号到 6 处..."

# 2a. pom.xml
python3 -c "
import re
with open('server/pom.xml') as f: c = f.read()
c = re.sub(r'(<artifactId>discord-admin-server</artifactId>\s*\n\s*)<version>.*?</version>', r'\1<version>$NEW_VER</version>', c)
with open('server/pom.xml','w') as f: f.write(c)
"

# 2b. crm_agent/package.json
python3 -c "import json; d=json.load(open('crm_agent/package.json')); d['version']='$NEW_VER'; json.dump(d, open('crm_agent/package.json','w'), indent=2)"

# 2c. crm_agent/config.json（用户的运行时配置）
python3 -c "
import json
d=json.load(open('crm_agent/config.json'))
d['version']='$NEW_VER'
with open('crm_agent/config.json','w') as f: json.dump(d, f, indent=4, ensure_ascii=False)
"

# 2d. config.example.json（干净模板）
python3 << PYEOF2
import json, os
path = 'crm_agent/config.example.json'
if os.path.exists(path):
    d = json.load(open(path))
else:
    d = {
        "serverUrl": "http://127.0.0.1:8090/api",
        "agentName": "crm-agent-01",
        "token": "",
        "heartbeatIntervalMs": 5000,
        "pollIntervalMs": 5000,
        "production": False,
        "browser": {
            "headless": False,
            "type": "chromium",
            "userDataDir": "./data/browser-profile",
            "viewport": {"width": 1280, "height": 800}
        }
    }
d["version"] = "$NEW_VER"
with open(path, "w") as f: json.dump(d, f, indent=4, ensure_ascii=False)
PYEOF2

# 2e. AgentServerController.java 硬编码
python3 << PYEOF2
import re
JAVA = "server/src/main/java/com/discordadmin/controller/AgentServerController.java"
with open(JAVA) as f: c = f.read()
c = re.sub(r'return "[0-9]+\.[0-9]+\.[0-9]+";', f'return "$NEW_VER";', c)
c = c.replace('return "$NEW_VER";', f'return "$NEW_VER";')
c = re.sub(r'(\\\\\"version\\\\\": \\\\")([0-9]+\.[0-9]+\.[0-9]+)(\\\\\")', lambda m: m.group(1)+"$NEW_VER"+m.group(3), c)
c = c.replace('\\\"version\\\": \\"$NEW_VER\\"', f'\\\"version\\\": \\"$NEW_VER\\"')
with open(JAVA, "w") as f: f.write(c)
PYEOF2

# 2f. AgentServerController.java configTemplate 里的 version 字段

# 验证版本号一致性
echo "  pom.xml:         $(grep -A1 discord-admin-server server/pom.xml | tail -1 | tr -d ' ' | sed 's/<version>//;s/<\/version>//')"
echo "  package.json:    $(python3 -c "import json;print(json.load(open('crm_agent/package.json'))['version'])")"
echo "  config.json:     $(python3 -c "import json;print(json.load(open('crm_agent/config.json'))['version'])")"
echo "  config.example:  $(python3 -c "import json;print(json.load(open('crm_agent/config.example.json'))['version'])")"
echo "  Java 硬编码:     $(grep 'return "[0-9]' server/src/main/java/com/discordadmin/controller/AgentServerController.java | tail -1 | tr -d ' ')"
echo "  configTemplate:  $(grep 'version.*[0-9]\.[0-9]\.[0-9]' server/src/main/java/com/discordadmin/controller/AgentServerController.java | head -1 | grep -o '[0-9]\.[0-9]\.[0-9]*')"

# 3. 打 crm_agent 全量 zip
echo ""
echo "[2/5] 打 crm_agent 全量 zip..."

cd "$PROJECT_ROOT"
rm -f server/src/main/resources/crm_agent-v*.zip server/src/main/resources/agent-package.zip
rm -f crm_agent/config.json.user crm_agent/config.json.bak crm_agent/*.bak crm_agent/src/*.bak 2>/dev/null

FULL_ZIP="server/src/main/resources/crm_agent-v$NEW_VER.zip"

# 全量打包：完整目录树 → 排除 node_modules / data / .git / config.json(防token) / 日志 / 锁文件
zip -r "$FULL_ZIP" crm_agent \
  -x "crm_agent/node_modules/*" \
     "crm_agent/data/*" \
     "crm_agent/.git/*" \
     "crm_agent/config.json" \
     "crm_agent/config.json.*" \
     "crm_agent/*.bak" \
     "crm_agent/src/*.bak" \
     "crm_agent/*.DS_Store" \
     "crm_agent/agent.log" \
     "crm_agent/package-lock.json" \
  > /dev/null 2>&1

cp "$FULL_ZIP" server/src/main/resources/agent-package.zip

ZIP_SIZE=$(ls -lh "$FULL_ZIP" | awk '{print $5}')
echo "  ✅ crm_agent-v$NEW_VER.zip ($ZIP_SIZE)"

# 4. 验证 zip 完整性
echo ""
echo "[3/5] 验证 zip 完整性..."

# 4a. 没有 config.json（防 token 泄露）
if unzip -l "$FULL_ZIP" | grep -qE "crm_agent/config\.json$"; then
  echo "  ❌ zip 里有 config.json！token 会泄露！"
  exit 1
fi
echo "  ✅ 无 config.json（安全）"

# 4b. 有 config.example.json
if ! unzip -l "$FULL_ZIP" | grep -q "config.example.json"; then
  echo "  ❌ zip 里没有 config.example.json！"
  exit 1
fi
echo "  ✅ 有 config.example.json"

# 4c. 有所有核心 .js 文件
CORE_FILES=("src/index.js" "src/discord.js" "src/browser.js" "src/http.js" "src/config.js"
            "src/agent/account_fingerprint.js" "src/gateway/gateway_manager.js"
            "src/network/network_gate.js" "src/scheduler/capacity_policy.js"
            "src/observability/runtime_trace.js" "package.json")
for f in "${CORE_FILES[@]}"; do
  if ! unzip -l "$FULL_ZIP" | grep -q "crm_agent/$f"; then
    echo "  ❌ 缺少 crm_agent/$f"
    exit 1
  fi
done
echo "  ✅ 11 个核心文件全在"

# 4d. config.example.json 里版本号正确
VER_IN_ZIP=$(unzip -p "$FULL_ZIP" crm_agent/config.example.json 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])")
if [ "$VER_IN_ZIP" != "$NEW_VER" ]; then
  echo "  ❌ config.example.json 版本号不对：$VER_IN_ZIP != $NEW_VER"
  exit 1
fi
echo "  ✅ config.example.json version=$VER_IN_ZIP"

# 5. 打后端 JAR（zip 内嵌进 JAR 的 resources）
echo ""
echo "[4/5] Maven 打后端 JAR..."
cd "$PROJECT_ROOT/server"
rm -rf target
mvn clean package -DskipTests -q 2>&1 | tail -3

JAR="target/discord-admin-server-$NEW_VER.jar"
if [ ! -f "$JAR" ]; then
  echo "  ❌ JAR 没打出来"
  ls target/discord-admin-server-*.jar 2>/dev/null || echo "  target 目录空的"
  exit 1
fi

JAR_SIZE=$(ls -lh "$JAR" | awk '{print $5}')
echo "  ✅ discord-admin-server-$NEW_VER.jar ($JAR_SIZE)"

# 6. 输出部署命令
echo ""
echo "[5/5] 构建完成！"
echo ""
echo "=========================================="
echo " 📦 v$NEW_VER 部署到生产 149:"
echo "=========================================="
echo ""
echo "  scp $PROJECT_ROOT/server/target/discord-admin-server-$NEW_VER.jar root@101.47.41.149:/opt/discord-admin/current/"
echo ""
echo "  ssh root@101.47.41.149:"
echo '    sed -i '"'"'s/discord-admin-server-.*\.jar/discord-admin-server-'"$NEW_VER"'.jar/'"'"' /etc/systemd/system/discord-admin.service'
echo "    systemctl daemon-reload"
echo "    systemctl restart discord-admin"
echo "    curl -s http://localhost:8090/api/agent-servers/package-info | python3 -c \"import sys,json;d=json.load(sys.stdin);print(d['version'],d['filename'])\""
echo ""
echo "=========================================="
echo " 📋 Windows agent 升级:"
echo "=========================================="
echo ""
echo "  Invoke-WebRequest -Uri http://101.47.41.149/api/agent-servers/package -OutFile agent.zip"
echo "  Expand-Archive agent.zip . -Force"
echo "  Copy-Item crm_agent\config.example.json config.json"
echo "  # 编辑 config.json → 填 token → production: true"
echo "  cd crm_agent; npm install; node src/index.js"
echo ""

cd "$PROJECT_ROOT"
