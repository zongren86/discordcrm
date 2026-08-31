#!/usr/bin/env bash
# =============================================================================
# 一键诊断脚本 — Discord CRM 全链路状态
# 用法: bash tools/debug.sh
# =============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()  { echo -e "${GREEN}  ✅${NC} $*"; }
bad() { echo -e "${RED}  ❌${NC} $*"; }
warn(){ echo -e "${YELLOW}  ⚠️${NC} $*"; }
hdr() { echo -e "\n${CYAN}━━━ $* ━━━${NC}"; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── 1. 端口状态 ──
hdr "端口状态"
check_port() {
  local port=$1 name=$2
  if lsof -i :$port -sTCP:LISTEN -t 2>/dev/null | head -1 | grep -q .; then
    ok "$name 端口 $port 监听中 (PID=$(lsof -i :$port -sTCP:LISTEN -t 2>/dev/null | head -1))"
  else
    bad "$name 端口 $port 未监听"
  fi
}
check_port 8090 "后端"
check_port 5173 "前端"

# ── 2. 接口健康 ──
hdr "接口健康"
if curl -sf http://localhost:8090/api/auth/ping >/dev/null 2>&1; then
  ok "后端 /auth/ping 正常"
else
  bad "后端 /auth/ping 无响应"
fi
if curl -sf -o /dev/null http://localhost:5173/ 2>&1; then
  ok "前端 Vite 正常"
else
  bad "前端 Vite 无响应"
fi

# ── 3. agent 包版本 ──
hdr "Agent 包版本"
curl -sf http://localhost:8090/api/agent-servers/package-info 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'  version = {d.get(\"version\",\"?\")}')
print(f'  file    = {d.get(\"filename\",\"?\")}')
print(f'  url     = {d.get(\"downloadUrl\",\"?\")}')
" 2>/dev/null || bad "无法获取 package-info"

# ── 4. 数据库 ──
hdr "数据库 (discordadmin)"
python3 -c "
import mysql.connector, sys
try:
    c = mysql.connector.connect(host='localhost', port=3306, user='root', password='Len2026!', database='discordadmin')
    cur = c.cursor()
    cur.execute('SELECT MAX(id), COUNT(*), MAX(created_at) FROM messages')
    r = cur.fetchone()
    print(f'  最新消息 id={r[0]}  总数={r[1]}  最新时间={r[2]}')
    cur.execute(\"SELECT direction, COUNT(*) FROM messages GROUP BY direction\")
    for r in cur.fetchall(): print(f'  {r[0]}: {r[1]}')
    cur.execute('SELECT COUNT(*) FROM discord_accounts')
    print(f'  账号总数: {cur.fetchone()[0]}')
    cur.execute(\"SELECT source, COUNT(*) FROM discord_accounts GROUP BY source\")
    for r in cur.fetchall(): print(f'  账号类型 {r[0]}: {r[1]}')
    cur.execute('SELECT id, name, status FROM agent_servers LIMIT 5')
    rows = cur.fetchall()
    if rows: print(f'  代理服务器:')
    for r in rows: print(f'    #{r[0]} {r[1]} [{r[2]}]')
    c.close()
except Exception as e:
    print(f'  ❌ 连接失败: {e}')
" 2>&1 | sed 's/^/  /'

# ── 5. 后端最新错误日志 ──
hdr "后端最新错误 (最近 10 条)"
tail -200 /tmp/backend.log 2>/dev/null | grep -E "ERROR|WARN.*失败|Exception" | tail -10 | sed 's/^/  /' || warn "无错误日志或后端未启动"

echo ""
echo -e "${GREEN}诊断完成${NC}"
