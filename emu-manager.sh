#!/bin/bash
# 模拟器后端管理脚本
# 用法: ./emu-manager.sh [start|stop|status|restart|logs]

EMU_DIR="/Users/ren/CodeBuddy/20260807093456/backend"
PID_FILE="/tmp/mumu-backend.pid"
LOG_FILE="/tmp/mumu-backend.log"
PORT=8088

start() {
    if is_running; then
        echo "✅ 模拟器后端已在运行 (端口: $PORT)"
        return 0
    fi

    echo "🚀 启动模拟器后端..."
    cd "$EMU_DIR"
    nohup mvn spring-boot:run > "$LOG_FILE" 2>&1 &
    MVN_PID=$!
    echo "$MVN_PID" > "$PID_FILE"
    
    echo "⏳ 等待服务启动..."
    for i in {1..30}; do
        sleep 1
        if curl -s http://localhost:$PORT/api/emulators > /dev/null 2>&1; then
            JAVA_PID=$(lsof -ti:$PORT 2>/dev/null | head -1)
            if [ -n "$JAVA_PID" ]; then
                echo "$JAVA_PID" > "$PID_FILE"
            fi
            echo "✅ 模拟器后端启动成功 (PID: $JAVA_PID)"
            return 0
        fi
    done
    echo "❌ 启动超时，请检查日志: $LOG_FILE"
    return 1
}

stop() {
    echo "🛑 停止模拟器后端..."
    
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 $PID 2>/dev/null; then
            kill $PID 2>/dev/null
            sleep 2
            kill -9 $PID 2>/dev/null
        fi
    fi
    
    PORT_PID=$(lsof -ti:$PORT 2>/dev/null)
    if [ -n "$PORT_PID" ]; then
        echo "   清理端口 $PORT 上的进程: $PORT_PID"
        echo "$PORT_PID" | xargs kill -9 2>/dev/null
    fi
    
    MVN_PIDS=$(ps aux | grep "mumu-manager-backend" | grep -v grep | awk '{print $2}')
    if [ -n "$MVN_PIDS" ]; then
        echo "   清理残留Maven进程"
        echo "$MVN_PIDS" | xargs kill -9 2>/dev/null
    fi
    
    rm -f "$PID_FILE"
    sleep 1
    
    if lsof -ti:$PORT > /dev/null 2>&1; then
        echo "⚠️ 端口 $PORT 仍被占用"
    else
        echo "✅ 已停止"
    fi
}

is_running() {
    curl -s http://localhost:$PORT/api/emulators > /dev/null 2>&1
}

status() {
    if is_running; then
        COUNT=$(curl -s http://localhost:$PORT/api/emulators 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
        PID=$(lsof -ti:$PORT 2>/dev/null | head -1)
        echo "✅ 模拟器后端运行中 (PID: $PID, 端口: $PORT, 模拟器数量: $COUNT)"
    else
        echo "❌ 模拟器后端未运行 (端口 $PORT 未监听)"
        return 1
    fi
}

logs() {
    if [ -f "$LOG_FILE" ]; then
        tail -50 "$LOG_FILE"
    else
        echo "日志文件不存在: $LOG_FILE"
    fi
}

restart() {
    stop
    sleep 2
    start
}

case "${1:-}" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    restart)
        restart
        ;;
    *)
        echo "用法: $0 [start|stop|status|restart|logs]"
        echo ""
        echo "  start    - 启动模拟器后端"
        echo "  stop     - 停止模拟器后端"
        echo "  status   - 查看运行状态"
        echo "  logs     - 查看最近日志"
        echo "  restart  - 重启模拟器后端"
        exit 1
        ;;
esac
