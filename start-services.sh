#!/bin/zsh

# 配置
PROJECT_DIR="/Users/ren/Library/Application Support/TRAE SOLO CN/ModularData/ai-agent/work-mode-projects/6a6d8114a6113204564fb48e/discord-src/discord"
SERVER_DIR="$PROJECT_DIR/server"
CLIENT_DIR="$PROJECT_DIR/client-vue"
BACKEND_PORT=8090
FRONTEND_PORT=5173
LOG_DIR="/tmp"

echo "=== 启动服务脚本 ==="
echo ""

# 1. 停止旧服务
echo "[1/4] 停止旧服务..."
if lsof -ti:$BACKEND_PORT > /dev/null 2>&1; then
    lsof -ti:$BACKEND_PORT | xargs kill -9 2>/dev/null
    echo "  ✓ 后端进程已停止"
else
    echo "  - 后端未运行"
fi

if lsof -ti:$FRONTEND_PORT > /dev/null 2>&1; then
    lsof -ti:$FRONTEND_PORT | xargs kill -9 2>/dev/null
    echo "  ✓ 前端进程已停止"
else
    echo "  - 前端未运行"
fi

sleep 1

# 2. 启动后端
echo ""
echo "[2/4] 启动后端服务..."
cd "$SERVER_DIR" || exit 1
nohup mvn spring-boot:run > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
echo "  后端启动中 (PID: $BACKEND_PID)..."

# 等待后端启动
echo "  等待后端就绪..."
for i in $(seq 1 30); do
    sleep 1
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$BACKEND_PORT/api/emu/autoadd/status" 2>/dev/null)
    if [ "$STATUS" = "200" ]; then
        echo "  ✓ 后端已启动 (端口 $BACKEND_PORT)"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "  ✗ 后端启动超时，查看日志: $LOG_DIR/backend.log"
        tail -20 "$LOG_DIR/backend.log"
        exit 1
    fi
done

# 3. 启动前端
echo ""
echo "[3/4] 启动前端服务..."
cd "$CLIENT_DIR" || exit 1
nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
echo "  前端启动中 (PID: $FRONTEND_PID)..."

# 等待前端启动
echo "  等待前端就绪..."
for i in $(seq 1 30); do
    sleep 1
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$FRONTEND_PORT" 2>/dev/null)
    if [ "$STATUS" = "200" ]; then
        echo "  ✓ 前端已启动 (端口 $FRONTEND_PORT)"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "  ✗ 前端启动超时，查看日志: $LOG_DIR/frontend.log"
        tail -20 "$LOG_DIR/frontend.log"
        exit 1
    fi
done

# 4. 最终状态
echo ""
echo "[4/4] 服务状态:"
BACKEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$BACKEND_PORT/api/emu/autoadd/status" 2>/dev/null)
FRONTEND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$FRONTEND_PORT" 2>/dev/null)
echo "  后端 (http://localhost:$BACKEND_PORT): $BACKEND_STATUS"
echo "  前端 (http://localhost:$FRONTEND_PORT): $FRONTEND_STATUS"
echo ""
echo "日志文件:"
echo "  后端: $LOG_DIR/backend.log"
echo "  前端: $LOG_DIR/frontend.log"
echo ""
echo "=== 启动完成 ==="
