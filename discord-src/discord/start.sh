#!/bin/bash

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_DIR/logs"
mkdir -p "$LOG_DIR"

# 杀掉旧进程
kill $(cat "$LOG_DIR/backend.pid" 2>/dev/null) 2>/dev/null
kill $(cat "$LOG_DIR/frontend.pid" 2>/dev/null) 2>/dev/null

# 启动后端
cd "$PROJECT_DIR/server"
java -jar target/discord-admin-*.jar > "$LOG_DIR/backend.log" 2>&1 &
echo $! > "$LOG_DIR/backend.pid"

# 启动前端
cd "$PROJECT_DIR/client-vue"
node node_modules/.bin/vite > "$LOG_DIR/frontend.log" 2>&1 &
echo $! > "$LOG_DIR/frontend.pid"

echo "服务已启动"
