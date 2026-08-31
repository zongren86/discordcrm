@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0"

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found. Please install Node.js 18+ from https://nodejs.org
    exit /b 1
)

if not exist "config.json" (
    echo [ERROR] config.json not found. Please configure serverUrl, token, agentName first.
    exit /b 1
)

if not exist "node_modules" (
    echo [INFO] Installing dependencies...
    call npm install --production --no-audit --no-fund
    if errorlevel 1 (
        echo [ERROR] npm install failed
        exit /b 1
    )
) else (
    REM 每次启动都检查并更新代理相关依赖（确保 https-proxy-agent / socks-proxy-agent 存在）
    if not exist "node_modules\https-proxy-agent" (
        echo [INFO] Installing https-proxy-agent...
        call npm install https-proxy-agent socks-proxy-agent --save --no-audit --no-fund
    )
)

node src/index.js
