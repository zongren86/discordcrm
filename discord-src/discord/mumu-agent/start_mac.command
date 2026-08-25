#!/bin/bash
# ============================================
#  MuMu Agent macOS 双击启动脚本
# ============================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================"
echo "  MuMu Agent (macOS) v2.0.0"
echo "============================================"
echo ""

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "❌ 错误: 未找到 Node.js"
    echo ""
    echo "请先安装 Node.js 18+:"
    echo "  官网: https://nodejs.org/"
    echo "  或使用 Homebrew: brew install node"
    echo ""
    read -p "按回车键退出..."
    exit 1
fi

NODE_VERSION=$(node -v | sed 's/v//' | cut -d. -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "❌ 错误: Node.js 版本过低 (需要 >= 18)"
    echo "   当前版本: $(node -v)"
    echo ""
    echo "请升级 Node.js: https://nodejs.org/"
    echo ""
    read -p "按回车键退出..."
    exit 1
fi

echo "✅ Node.js 版本: $(node -v)"

# 检查配置文件
CONFIG_FILE="config.json"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ 错误: 未找到配置文件 (config.json)"
    echo ""
    echo "请在管理后台重新下载 Agent 包"
    echo ""
    read -p "按回车键退出..."
    exit 1
fi

echo "✅ 配置文件: $CONFIG_FILE"

# 检查配置文件是否有效
if ! node -e "JSON.parse(require('fs').readFileSync('$CONFIG_FILE', 'utf8'))" 2>/dev/null; then
    echo "❌ 错误: 配置文件格式错误 (JSON 解析失败)"
    echo ""
    echo "请在管理后台重新下载 Agent 包"
    echo ""
    read -p "按回车键退出..."
    exit 1
fi

echo "✅ 配置文件格式有效"

# 检查依赖
if [ ! -d "node_modules" ]; then
    echo ""
    echo "📦 首次运行，正在安装依赖..."
    npm install
    if [ $? -ne 0 ]; then
        echo ""
        echo "❌ 依赖安装失败"
        echo "请检查网络连接后重试"
        echo ""
        read -p "按回车键退出..."
        exit 1
    fi
    echo "✅ 依赖安装完成"
fi

# 显示配置信息
echo ""
echo "📋 配置信息:"
node -e "
    const config = JSON.parse(require('fs').readFileSync('$CONFIG_FILE', 'utf8'));
    console.log('   用户ID:', config.userId || '-');
    console.log('   商户ID:', config.merchantId || '-');
    console.log('   服务器:', config.serverUrl || '-');
    const platform = config.platforms && config.platforms.darwin;
    if (platform) {
        console.log('   MuMu路径:', platform.mumuPath || '-');
    }
"

echo ""
echo "🚀 启动 Agent..."
echo ""

# 启动 agent
node agent.js

# 如果出错，显示提示
EXIT_CODE=$?
if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo "❌ Agent 异常退出"
    echo ""
    echo "可能的原因:"
    echo "  1. MuMu 模拟器未安装或路径错误"
    echo "  2. 服务器地址无法访问"
    echo "  3. 配置文件错误"
    echo ""
    echo "请检查上方日志排查问题"
    echo ""
fi

echo ""
read -p "按回车键关闭窗口..."
exit $EXIT_CODE
