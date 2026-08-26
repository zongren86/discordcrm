#!/bin/bash

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_DIR/logs"

kill $(cat "$LOG_DIR/backend.pid" 2>/dev/null) 2>/dev/null
kill $(cat "$LOG_DIR/frontend.pid" 2>/dev/null) 2>/dev/null
rm -f "$LOG_DIR/backend.pid" "$LOG_DIR/frontend.pid"

echo "已停止所有服务"
