@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================
REM  MuMu Agent Windows 双击启动脚本
REM ============================================

cd /d "%~dp0"

echo ============================================
echo   MuMu Agent (Windows) v2.7.0
echo ============================================
echo.

REM 检查 Node.js
where node >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 未找到 Node.js
    echo.
    echo 请先安装 Node.js 18+:
    echo   官网: https://nodejs.org/
    echo   或使用 nvm-windows: https://github.com/coreybutler/nvm-windows
    echo.
    pause
    exit /b 1
)

for /f "tokens=1 delims=Vv" %%v in ('node -v') do set NODE_MAJOR=%%v
for /f "tokens=1 delims=." %%a in ("%NODE_MAJOR%") do set NODE_MAJOR=%%a

if %NODE_MAJOR% lss 18 (
    echo [错误] Node.js 版本过低 (需要 >= 18)
    echo   当前版本: v%NODE_MAJOR%.x
    echo.
    echo 请升级 Node.js: https://nodejs.org/
    echo.
    pause
    exit /b 1
)

echo [成功] Node.js 版本: v%NODE_MAJOR%.x

REM 检查配置文件
if not exist "config.json" (
    echo [错误] 未找到配置文件 (config.json)
    echo.
    echo 请在管理后台重新下载 Agent 包
    echo.
    pause
    exit /b 1
)

echo [成功] 配置文件: config.json

REM 检查配置文件是否有效
node -e "JSON.parse(require('fs').readFileSync('config.json', 'utf8'))" >nul 2>nul
if %errorlevel% neq 0 (
    echo [错误] 配置文件格式错误 (JSON 解析失败)
    echo.
    echo 请在管理后台重新下载 Agent 包
    echo.
    pause
    exit /b 1
)

echo [成功] 配置文件格式有效

REM 检查依赖
if not exist "node_modules" (
    echo.
    echo [信息] 首次运行，正在安装依赖...
    call npm install
    if %errorlevel% neq 0 (
        echo [错误] 依赖安装失败
        echo.
        echo 请检查网络连接后重试
        echo.
        pause
        exit /b 1
    )
    echo [成功] 依赖安装完成
)

REM 显示配置信息
echo.
echo [信息] 配置信息:
node -e "const c=JSON.parse(require('fs').readFileSync('config.json','utf8'));console.log('   用户ID:',c.userId||'-');console.log('   商户ID:',c.merchantId||'-');console.log('   服务器:',c.serverUrl||'-');if(c.platforms&&c.platforms.win32)console.log('   MuMu路径:',c.platforms.win32.mumuPath||'-');"

echo.
echo [启动] 正在启动 Agent...
echo.

REM 启动 agent
node agent.js

if %errorlevel% neq 0 (
    echo.
    echo [错误] Agent 异常退出
    echo.
    echo 可能的原因:
    echo   1. MuMu 模拟器未安装或路径错误
    echo   2. 服务器地址无法访问
    echo   3. 配置文件错误
    echo.
    echo 请检查上方日志排查问题
    echo.
    pause
)
