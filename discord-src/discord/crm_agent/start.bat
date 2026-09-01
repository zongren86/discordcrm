@echo off
cd /d "%~dp0"

where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found. Please install Node.js 18+ from https://nodejs.org
    pause
    exit /b 1
)

if not exist "config.json" (
    echo [ERROR] config.json not found. Please configure serverUrl, token, agentName first.
    pause
    exit /b 1
)

if not exist "node_modules" (
    echo [INFO] Installing dependencies...
    REM 先试官方源（国内走 npmmirror，海外走官方）
    call npm install --no-audit --no-fund --registry=https://registry.npmjs.org
    if errorlevel 1 (
        echo [WARN] 官方源失败，重试 npmmirror...
        call npm install --no-audit --no-fund --registry=https://registry.npmmirror.com
        if errorlevel 1 (
            echo [ERROR] npm install failed. Try: npm config set registry https://registry.npmmirror.com
            pause
            exit /b 1
        )
    )
    REM 注意：不需要 playwright install chromium，我们用系统 Chrome
)

node src/index.js
