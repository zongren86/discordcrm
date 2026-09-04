@echo off
REM ============================================================================
REM crm_agent 一键启动（Windows）
REM 首次运行自动: npm install + playwright install chromium
REM ============================================================================
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo  crm_agent 启动中...
echo ========================================

REM 1. 检查 Node.js >= 18
echo.
echo | set /p=  检查 Node.js... 
for /f "tokens=1 delims=v" %%v in ('node -v 2^>nul') do set NODE_V=%%v
if "%NODE_V%"=="" (
  echo [错误] 未安装 Node.js
  echo 请安装 Node.js ^>= 18: https://nodejs.org/
  pause
  exit /b 1
)
for /f "tokens=1 delims=." %%m in ("%NODE_V%") do set NODE_MAJOR=%%m
if %NODE_MAJOR% LSS 18 (
  echo [错误] Node.js 版本过低 v%NODE_V%
  pause
  exit /b 1
)
echo v%NODE_V% OK

REM 2. 检查 config.json
echo | set /p=  检查 config.json... 
if not exist config.json (
  echo [错误] config.json 不存在
  echo 请编辑 config.json 填写 token
  pause
  exit /b 1
)
echo OK

REM 3. npm install
if not exist node_modules (
  echo.
  echo  首次运行，安装依赖...
  call npm install --registry=https://registry.npmmirror.com
  echo  依赖安装完成
)

REM 4. 检查 Playwright Chromium
echo | set /p=  检查 Chromium... 
call npx playwright install chromium 2>nul
echo OK

REM 5. 启动
echo.
echo ========================================
echo  启动 agent（Ctrl+C 停止）
echo ========================================
echo.

node src\index.js
pause
