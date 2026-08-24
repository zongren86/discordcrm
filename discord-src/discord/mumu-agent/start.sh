#!/bin/bash
# MuMu Agent 启动脚本

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f "config.json" ]; then
    echo "错误: 未找到 config.json"
    echo "请先复制 config.example.json 为 config.json 并编辑"
    exit 1
fi

echo "启动 MuMu Agent..."
node agent.js
