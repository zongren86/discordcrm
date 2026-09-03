#!/bin/bash
# =============================================================================
# crm_agent 一键启动（macOS / Linux）
# 首次运行自动: npm install + playwright install chromium
# =============================================================================
set -e
cd "$(dirname "$0")"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN} crm_agent 启动中...${NC}"
echo -e "${CYAN}========================================${NC}"

# 1. 检查 Node.js >= 18
echo ""
echo -ne "  🔍 检查 Node.js..."
NODE_V=$(node -v 2>/dev/null || echo "")
if [ -z "$NODE_V" ]; then
  echo -e " ${RED}未安装${NC}"
  echo -e "  ${YELLOW}请安装 Node.js >= 18: https://nodejs.org/${NC}"
  exit 1
fi
NODE_MAJOR=$(echo "$NODE_V" | sed 's/v//' | cut -d. -f1)
if [ "$NODE_MAJOR" -lt 18 ]; then
  echo -e " ${RED}版本过低 $NODE_V${NC}"
  exit 1
fi
echo -e " ${GREEN}$NODE_V ✅${NC}"

# 2. 检查 config.json
echo -ne "  🔍 检查 config.json..."
if [ ! -f "config.json" ]; then
  echo -e " ${RED}不存在${NC}"
  echo -e "  ${YELLOW}请编辑 config.json 填写 token${NC}"
  exit 1
fi
TOKEN=$(python3 -c "import json;print(json.load(open('config.json')).get('token',''))" 2>/dev/null || echo "")
if [ -z "$TOKEN" ]; then
  echo -e " ${RED}token 为空${NC}"
  echo -e "  ${YELLOW}请编辑 config.json 填写 token${NC}"
  exit 1
fi
AGENT_NAME=$(python3 -c "import json;print(json.load(open('config.json')).get('agentName',''))" 2>/dev/null)
echo -e " ${GREEN}agentName=$AGENT_NAME ✅${NC}"

# 3. npm install（如果 node_modules 不存在）
if [ ! -d "node_modules" ]; then
  echo ""
  echo -e "  📦 首次运行，安装依赖..."
  npm install --registry=https://registry.npmmirror.com 2>&1 | tail -3
  echo -e "  ${GREEN}依赖安装完成 ✅${NC}"
fi

# 4. 检查 Playwright Chromium
echo -ne "  🔍 检查 Chromium..."
if ! npx playwright install --dry-run chromium 2>/dev/null | grep -q "chromium.*installed"; then
  echo -e " ${YELLOW}未安装，正在下载...${NC}"
  npx playwright install chromium 2>&1 | tail -3
  echo -e "  ${GREEN}Chromium 安装完成 ✅${NC}"
else
  echo -e " ${GREEN}已安装 ✅${NC}"
fi

# 5. 启动
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN} 启动 agent（Ctrl+C 停止）${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

node src/index.js
