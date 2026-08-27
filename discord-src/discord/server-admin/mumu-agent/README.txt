MuMu Agent v2.0.0 使用说明
========================================

## 快速开始

### macOS 用户:
    双击 start_mac.command

### Windows 用户:
    双击 start_win.bat

## 前置条件
- Node.js 18+ (https://nodejs.org/)
- MuMu 模拟器已安装

## 首次使用
1. 解压下载的 zip 包
2. 双击启动脚本:
   - macOS: start_mac.command
   - Windows: start_win.bat
3. 脚本会自动:
   - 检查 Node.js 环境
   - 安装依赖 (npm install)
   - 加载配置文件
   - 启动 Agent

## 配置说明
- config.json: 唯一配置文件
  - 通用配置: userId, serverUrl 等
  - 平台配置: platforms.darwin/win32/linux
- 启动时自动根据系统选择平台配置

## 注意事项
- 一个账号只能在一台服务器上运行
- 请确保 MuMu 模拟器版本兼容
- 如遇问题请查看命令行日志
