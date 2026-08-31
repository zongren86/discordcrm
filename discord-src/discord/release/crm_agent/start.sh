#!/usr/bin/env bash
set -e

echo "========================================"
echo "  Discord CRM Agent — 一键启动"
echo "========================================"
echo ""

# 1. 检查 Node.js
if ! command -v node &>/dev/null; then
    echo "[错误] 未检测到 Node.js，请先安装 Node.js 18+"
    echo "  macOS: brew install node@18"
    echo "  Linux: curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash - && sudo apt-get install -y nodejs"
    exit 1
fi
NODE_VER=$(node -v)
echo "[OK] Node.js: $NODE_VER"

# 2. 检查 config.json
if [ ! -f config.json ]; then
    echo "[错误] 未找到 config.json"
    echo "  请复制 config.example.json 为 config.json 并填写正确的 serverUrl 和 token"
    exit 1
fi
echo "[OK] config.json 已存在"

# 3. 安装依赖
if [ ! -d node_modules ]; then
    echo "[首次] 正在安装依赖，请稍候..."
    npm install --production
    echo "[OK] 依赖安装完成"
fi

# 4. 启动
echo ""
echo "[启动] crm-agent 启动中...  (Ctrl+C 停止)"
echo ""
node src/index.js
