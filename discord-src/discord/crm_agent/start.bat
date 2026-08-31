@echo off
chcp 65001 >nul
title crm-agent

echo ========================================
echo   Discord CRM Agent — 一键启动
echo ========================================
echo.

:: 1. 检查 Node.js
where node >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Node.js，请先安装 Node.js 18+
    echo 下载: https://nodejs.org/
    pause
    exit /b 1
)
for /f "tokens=1" %%v in ('node -v') do set NODE_VER=%%v
echo [OK] Node.js: %NODE_VER%

:: 2. 检查 config.json
if not exist config.json (
    echo [错误] 未找到 config.json
    echo 请复制 config.example.json 为 config.json 并填写正确的 serverUrl 和 token
    pause
    exit /b 1
)
echo [OK] config.json 已存在

:: 3. 安装依赖（如果 node_modules 不存在）
if not exist node_modules (
    echo [首次] 正在安装依赖，请稍候...
    call npm install --production
    if errorlevel 1 (
        echo [错误] npm install 失败
        pause
        exit /b 1
    )
    echo [OK] 依赖安装完成
)

:: 4. 启动
echo.
echo [启动] crm-agent 启动中...  (Ctrl+C 停止)
echo.
node src/index.js
pause
