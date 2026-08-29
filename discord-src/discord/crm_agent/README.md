# crm_agent — 代理服务器节点

负责在本机启动浏览器，自动打开 Discord 登录页，用户登录后自动捕获 token、用户信息并回传到主服务器。

## 快速开始

```bash
cd crm_agent
cp config.example.json config.json
# 编辑 config.json —— 填入 serverUrl / agentName / token
npm install
npm start
```

## 流程

1. 启动后立即向主服务器上报心跳 → 节点状态变 ONLINE
2. 定期轮询 `/api/agent-servers/tasks/poll` 拉取待执行任务
3. 收到 CAPTURE_DISCORD_ACCOUNT 任务 → 启动 Playwright 浏览器打开 Discord 登录页
4. 用户在浏览器中登录 Discord
5. Agent 自动从 localStorage / discord 内部 API 捕获 token + userId + username + email + avatar
6. 回传到主服务器 → 主服务器保存为新的 Discord 账号
