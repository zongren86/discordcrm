---
name: "restart-services-before-notify"
description: "Ensures frontend and backend services are running before notifying user. Invoke when completing development tasks and before sending completion notification to user."
---

# 开发完成后自动启动前后端服务

## 触发条件

在以下场景中，**必须在通知用户之前**调用此技能：
- 完成任何后端 Java 代码修改后
- 完成任何前端 Vue/JS 代码修改后
- 用户说"开发完了"、"修改好了"等暗示任务完成的时刻
- 准备使用 `NotifyUser` 或直接口头告知用户"已完成"之前

## 执行流程（严格按顺序执行）

### 第一步：清理旧进程

```bash
# 清理后端端口
lsof -ti:8090 | xargs kill -9 2>/dev/null

# 清理前端端口
lsof -ti:5173 | xargs kill -9 2>/dev/null

# 等待端口释放
sleep 2
```

**验证**: 确认端口已释放：`lsof -ti:8090` 和 `lsof -ti:5173` 应无输出。

### 第二步：编译后端（如果修改了 Java 代码）

```bash
cd discord-src/discord/server && mvn clean compile -DskipTests
```

等待输出 `BUILD SUCCESS`。

### 第三步：启动后端服务

```bash
cd discord-src/discord/server && mvn spring-boot:run -DskipTests
```

**参数**: `blocking: false`, `wait_ms_before_async: 20000`

**验证标准**（满足任一即可视为启动成功）：
- 日志出现 `Started DiscordAdminApplication in X seconds`
- 日志出现 `Tomcat started on port 8090`
- `lsof -ti:8090` 有进程ID返回

### 第四步：启动前端服务

```bash
cd discord-src/discord/client-vue && npm run dev
```

**参数**: `blocking: false`, `wait_ms_before_async: 8000`

**验证标准**（满足任一即可视为启动成功）：
- 日志出现 `VITE v5.x ready`
- 日志出现 `Local: http://localhost:5173/`
- `curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/` 返回 200

### 第五步：最终验证

```bash
# 验证后端
curl -s -o /dev/null -w "后端: HTTP %{http_code}\n" http://localhost:8090/api/auth/login

# 验证前端
curl -s -o /dev/null -w "前端: HTTP %{http_code}\n" http://localhost:5173/
```

**前端必须返回 200，后端返回 500/401 等均可（只要不是连接失败）。**

### 第六步：向用户报告

在最终回复中**必须**包含：
```
✅ 后端已启动 (端口 8090)
✅ 前端已启动 (端口 5173)
```

## 项目配置

| 项目 | 路径 | 端口 | 启动命令 |
|------|------|------|----------|
| 后端 | `discord-src/discord/server` | 8090 | `mvn spring-boot:run -DskipTests` |
| 前端 | `discord-src/discord/client-vue` | 5173 | `npm run dev` |

## 严禁事项

1. ❌ **禁止口头宣称启动成功而不实际执行命令**
2. ❌ **禁止跳过验证步骤** - 必须用 `curl` 或 `lsof` 验证服务状态
3. ❌ **禁止先通知用户再启动服务**
4. ❌ **禁止只启动一个服务** - 前后端必须同时启动

## 故障排查

| 问题 | 解决方案 |
|------|----------|
| 端口被占用 | `lsof -ti:PORT \| xargs kill -9` 后重试 |
| 后端编译失败 | 检查 Java 代码语法错误，修复后重试 |
| 前端依赖缺失 | 运行 `npm install` 后重试 |
| 启动超时 | 增加 `wait_ms_before_async` 时间，再检查日志 |
