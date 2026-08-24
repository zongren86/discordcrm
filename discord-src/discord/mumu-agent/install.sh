#!/bin/bash
# MuMu Agent 安装脚本

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================="
echo "  MuMu Agent 安装程序"
echo "========================================="
echo ""

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "错误: 未找到 Node.js"
    echo "请先安装 Node.js: https://nodejs.org/"
    exit 1
fi

NODE_VERSION=$(node -v | sed 's/v//' | cut -d. -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "错误: Node.js 版本过低 (需要 >= 18)"
    echo "当前版本: $(node -v)"
    exit 1
fi

echo "✓ Node.js 版本: $(node -v)"

# 安装依赖
echo ""
echo "正在安装依赖..."
npm install

echo ""
echo "========================================="
echo "  安装完成！"
echo "========================================="
echo ""
echo "下一步操作："
echo "1. 复制 config.example.json 为 config.json"
echo "2. 编辑 config.json，设置正确的参数"
echo "3. 运行: node agent.js"
echo ""
echo "或者使用启动脚本:"
echo "  ./start.sh"
echo ""
