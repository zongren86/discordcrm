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
    call npm install --production --silent
)

node src/index.js
