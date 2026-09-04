#!/bin/bash
# crm_agent 停止脚本
pkill -f "node.*src/index.js" 2>/dev/null && echo "✅ agent 已停止" || echo "agent 未在运行"
