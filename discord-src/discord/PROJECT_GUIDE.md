# Discord Admin — 项目完整指南

> 版本：v0.1.3 | 最后更新：2026-08-28  
> **目标读者**：接手本项目的 AI Agent / 开发人员

---

## 1. 项目概述

本项目是一套 **Discord CRM 聚合平台** 与 **MuMu 模拟器自动加好友系统** 的组合，核心目标：

- **商户隔离**：多商户（merchantId）共享一套部署，数据严格按商户隔离
- **设备隔离**：每个商户可管理多台 Windows 云电脑（运行 MuMu 模拟器 + mumu-agent），通过 `deviceId` 绑定，操作绝不串机
- **自动加好友**：从 Discord 服务器成员池中筛选用户，在模拟器中自动执行加好友动作
- **Discord CRM**：好友管理、会话管理、消息模板、自动回复等（旧项目 server/client-vue，端口 8090/5173）

### ⚠️ Agent 分工边界（**最重要，别跨线**）

| 子项目 | 技术栈 | 端口 | 运行位置 | **归属 Agent** |
|--------|--------|------|----------|---------------|
| **server** ✅ | Spring Boot 3.3.4 + JPA + Security | 8090 | 101.47.41.149 | **当前 Agent 负责** ✅ |
| **client-vue** ✅ | Vue 3 + Vite 5 + Element Plus | 80 (Nginx) | 101.47.41.149 | **当前 Agent 负责** ✅ |
| **crm_agent** ✅ | Node.js + Playwright + Discord API | — | 用户 Windows 云电脑 | **当前 Agent 负责** ✅ |
| **mumu-agent** | Node.js + WebSocket + MuMu CLI | — | Windows 云电脑 173 | **另一个子项目 Agent 负责** ❌ 别碰 |
| **server-admin** | （另一个项目） | — | 101.47.41.151 | **另一个子项目 Agent 负责** ❌ 别碰 |
| **旧 client-vue (Vue2)** | Vue 2 + Vite | 5173 | 本地开发 | **废弃，别动** ❌ |

**当前 Agent 只碰 3 个目录**：`server/`、`client-vue/`、`crm_agent/`
**当前 Agent 绝不碰**：`mumu-agent/`、`server-admin/`、任何 151 服务器资源

### 项目拆分明细

---

## 2. 系统架构

### 2.1 拓扑图

```
┌────────────────────────────────────────────────────────────────────┐
│                    应用服务器  101.47.41.149 (4C/8G)                │
│  ┌──────────┐   ┌──────────────────┐   ┌──────────────────────┐   │
│  │  Nginx   │──▶│  server     │──▶│  MySQL Client (Hikari)│   │
│  │  :80/:443│   │  :8090 SpringBoot│   │  连接池 max=15       │   │
│  └──────────┘   └────────┬─────────┘   └──────────┬───────────┘   │
│        ↑                  │ WebSocket                │               │
│        │                  │ /ws/agent                │               │
│  client-vue            │                           │               │
│  (静态前端)               │                           │               │
│  或本地开发 5175          │                           │               │
└──────────────────────────┼───────────────────────────┼───────────────┘
                           │                           │
                           │ WSS / WAN                 │ WAN
                           │                           │
┌──────────────────────────▼──────┐  ┌─────────────────▼──────────────┐
│   Windows 云电脑                 │  │  DB 服务器 101.47.41.155       │
│                                  │  │  (2C/4G/50G MySQL 8.0)        │
│  ┌──────────────┐               │  │                                │
│  │ mumu-agent   │──WebSocket───┘  │  discordadmin 数据库            │
│  │ agent.js     │  心跳 30s       │  (与旧 server 子项目共用)       │
│  └──────┬───────┘                 │                                │
│         │ mumu-cli / adb          │                                │
│  ┌──────▼───────┐                 │                                │
│  │ MuMu 模拟器   │×N              │                                │
│  │ (index 0..N)  │                │                                │
│  └──────────────┘                 │                                │
└──────────────────────────────────┘  └────────────────────────────────┘
```

### 2.2 数据流（加好友场景）

```
client-vue               server                mumu-agent              MuMu模拟器
    │                         │                           │                      │
    │─ 创建模拟器 ───────────▶│                           │                      │
    │                         │─ 查找 deviceId 匹配的 Agent │                      │
    │                         │── WebSocket: CREATE_EMU ─▶│                      │
    │                         │                           │── mumu-cli create ─▶│
    │                         │◀── TASK_RESULT ──────────│◀── mumu-cli exit ────│
    │◀── 创建成功 ────────────│                           │                      │
    │                         │                           │                      │
    │─ 开始全部加好友 ───────▶│                           │                      │
    │                         │─ 读 friend_pool 取待添加用户│                      │
    │                         │── WebSocket: START_AUTOADD│                      │
    │                         │                           │── 启动 adb 脚本 ───▶│
    │◀── 进度实时推送 ────────│◀── WS push AUTOADD_EVENT ─│                      │
```

### 2.3 设备隔离核心机制

**EmuInstance.deviceId 字段**（`emu_instances.device_id`）是防串机的根：

```java
// 任何模拟器操作必须走这个链路：
// 1. 读 EmuInstance.deviceId
// 2. AgentServerService 中找 deviceId 对应的在线 Agent
// 3. 把命令发到那个 Agent 的 WebSocket 会话
// 绝对不能按 merchantId 或 "第一个在线 Agent" 选
```

---

## 3. 技术栈

### 后端 server

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.3.4 |
| ORM | Spring Data JPA (Hibernate) | — |
| 安全 | Spring Security + JJWT | 0.12.x |
| WebSocket | Spring WebSocket + STOMP over SockJS | — |
| 数据库 | MySQL | 8.0 |
| 连接池 | HikariCP (Spring Boot 默认) | — |
| Excel | Apache POI ooxml | 5.2.5 |
| Discord API | JDA (Java Discord API) | 5.x |
| 构建 | Maven | — |

### 前端 client-vue

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | JavaScript + Vite | — |
| 框架 | Vue | 3.4+ |
| 构建 | Vite | 5.1 |
| UI | Element Plus | 2.6 |
| 状态管理 | Pinia | 2.1 |
| 路由 | Vue Router | 4.3 |
| HTTP | Axios | 1.6 |
| 图表 | ECharts + vue-echarts | 6.1 |
| WebSocket | STOMP over SockJS | — |

### 设备端 mumu-agent

| 类别 | 技术 | 说明 |
|------|------|------|
| 语言 | Node.js | 纯 JS 单文件 agent.js + config.json |
| 通信 | ws WebSocket 客户端 | 连后端 `/ws/agent` |
| 模拟器控制 | mumu-cli + adb | 命令行创建/启动/停止/安装 APK |
| 心跳 | 每 30 秒 | PING/PONG + 注册设备信息 |

---

## 4. 目录结构

```
discord/                                 ← 仓库根目录
├── .git/                                ← Git 仓库 (branch: main-temp-2)
│
├── server/              ✅ 后端
│   ├── pom.xml                          ← 当前 version: 0.1.3
│   ├── src/main/
│   │   ├── java/com/discordadmin/
│   │   │   ├── controller/              ← REST API 控制器
│   │   │   │   ├── AuthController            登录/JWT/权限
│   │   │   │   ├── EmuManagementController    模拟器实例管理
│   │   │   │   ├── GuildServerController      服务器列表
│   │   │   │   ├── GuildMembersController    服务器成员
│   │   │   │   ├── DiscordMemberController    Discord 成员抓取
│   │   │   │   ├── FriendController          好友管理
│   │   │   │   └── ExclusionController       排除配置（新增）
│   │   │   ├── service/                 ← 业务层（核心逻辑都在这里）
│   │   │   │   ├── AgentServerService     ⭐ Agent WebSocket 管理 + 心跳
│   │   │   │   ├── DiscordAccountService        ⭐ 模拟器实例操作（start/stop/create）
│   │   │   │   ├── AgentTaskService      ⭐ 自动加好友调度
│   │   │   │   ├── AgentServerService   服务器绑定（含跨商户隔离逻辑）
│   │   │   │   ├── DiscordAccountService  账号绑定（含跨商户隔离逻辑）
│   │   │   │   ├── ExclusionService          排除配置管理
│   │   │   │   ├── DiscordMemberService      Discord 成员抓取
│   │   │   │   └── ...
│   │   │   ├── repository/              ← Spring Data JPA 接口
│   │   │   │   ├── DiscordAccountRepository   ⚠️ 大量 findByMerchantIdOrNull 查询
│   │   │   │   ├── EmuInstanceRepository       含 findByDeviceIdAndInstanceIndex
│   │   │   │   ├── GuildServerRepository       含 findByMerchantIdAndDiscordAccountIdIn
│   │   │   │   └── ...
│   │   │   ├── entity/                  ← JPA 实体（与 DB 表一一对应）
│   │   │   ├── security/                ← JWT 过滤器、工具、SecurityUtils
│   │   │   │   ├── JwtAuthFilter              ⭐ MDC traceId + userId/merchantId 注入
│   │   │   │   ├── JwtUtil                   JWT 生成/解析
│   │   │   │   └── SecurityUtils             角色/商户判断工具
│   │   │   └── config/                  ← Spring 配置类
│   │   │       ├── MdcTraceFilter             ⭐ 每请求 traceId
│   │   │       ├── AccessLogFilter            ⭐ HTTP 请求访问日志
│   │   │       └── WebSocketConfig            Agent WS 端点 /ws/agent
│   │   └── resources/
│   │       ├── application.yml                通用配置（不含 DB 密码）
│   │       ├── application-dev.yml           本地开发（DB=127.0.0.1）
│   │       ├── application-prod.yml           生产（DB_HOST 等用 env 变量）
│   │       └── logback-spring.xml             ⭐ 含 traceId/userId/merchantId 日志格式
│   ├── scripts/discord-admin.service     ← systemd 服务模板
│   └── target/                           ← 编译产物 *.jar
│
├── client-vue/              ✅ 前端
│   ├── package.json                     ← 当前 version: 1.0.1
│   ├── vite.config.js                   ← dev proxy /assets → /api
│   ├── src/
│   │   ├── api/                         ← axios 封装
│   │   ├── views/
│   │   │   ├── EmulatorView.vue              ⭐ 好友管理主页面
│   │   │   ├── Guilds.vue                    服务器列表
│   │   │   ├── GuildMembers.vue              服务器成员
│   │   │   └── ...
│   │   ├── router/index.js              ← 路由（含权限过滤）
│   │   ├── stores/                      ← Pinia stores
│   │   └── services/websocket.js        ← 前端 WS（监控后端状态）
│   └── dist/                            ← 构建产物
│
├── mumu-agent/                ✅ Windows Agent
│   ├── agent.js                         ← 单文件核心逻辑 (2700+ 行)
│   ├── config.json                      ← 运行时配置
│   ├── node_modules/
│   └── 打包脚本（zip 整目录）
│
├── server/                            ← 旧后端（非本轮开发）
├── client-vue/                        ← 旧前端（非本轮开发）
├── scripts/                           ← 部署脚本
├── sql/                               ← 数据库脚本
└── logs/                              ← 共享日志目录
```

---

## 5. 数据库设计

### 5.1 数据库连接

| 环境 | 地址 | 端口 | 库名 | 说明 |
|------|------|------|------|------|
| 本地开发 | 127.0.0.1 | 3306 | discordadmin | 独立库 |
| 生产 | 101.47.41.155 | 3306 | discordadmin | 与旧 server 子项目共用 |

### 5.2 连接池参数

| 参数 | dev | prod | 说明 |
|------|-----|------|------|
| maximum-pool-size | 20 | 15 | prod DB 2C/4G 还要分旧项目 |
| keepalive-time | — | 60000 (60s) | 防 DB 断连 |
| validation-timeout | — | 1000 | 验证超时 |
| wait_timeout | — | 28800 (8h) | MySQL 侧 |

### 5.3 核心表（47 张中挑关键的）

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `merchants` | 商户 | id, name, contact |
| `agents` | 系统用户 | id, username, password, merchant_id, user_id, account_type(0=admin,1=user) |
| `roles` / `sys_features` / `role_feature` | RBAC 权限 | role_feature 为中间表 |
| `agent_registrations` | Agent 设备注册 | id, merchant_id, user_id, **device_id**, os |
| `emu_instances` | 模拟器实例 | id, merchant_id, user_id, **device_id**, instance_index, name, status |
| `discord_accounts` | Discord 账号 | id, merchant_id, name, token, status, account_type |
| `discord_account_numbers` | 账号编号 | 连接 DiscordAccount |
| `emu_account_bindings` | 商户-账号绑定 | id, merchant_id, discord_account_id, status |
| `guild_servers` | Discord 服务器 | id, merchant_id, discord_account_id, guild_id, name, member_count |
| `guild_members` | 服务器成员 | id, guild_server_id, discord_username, friend_status |
| `emu_server_bindings` | 商户-服务器绑定 | id, merchant_id, server_id, last_sync_at |
| `emu_friend_pool` | 好友池（待加用户） | id, merchant_id, user_id, discord_username, guild_server_id, status |
| `friend_exclusion_config` | 排除配置 | id, merchant_id, user_id, exclude_all_friends, use_custom_list |
| `friend_exclusion_users` | 排除用户清单 | id, merchant_id, user_id, username, source（唯一约束 三列） |
| `friends` | 已添加好友 | id, discord_account_id, username |
| `auto_add_tasks` / `auto_add_task_items` | 自动加好友任务 | 任务调度 |
| `apk_versions` | APK 版本 | 上传的 Discord APK |
| `audit_logs` | 审计日志 | 用户操作记录 |

### 5.4 实体字段命名规范

- `merchant_id`：商户归属，**跨商户查询必须带此条件**
- `user_id`：当前登录用户（同一商户下可多个用户）
- `device_id`：Agent 设备唯一标识（UUID 风格字符串）
- `discord_account_id`：关联 `discord_accounts.id`
- `server_id` / `guild_server_id`：关联 `guild_servers.id`
- `instance_index`：模拟器编号（0-based MuMu CLI 索引 +1，DB 存 1-based）

---

## 6. 功能清单

### 6.1 后端 API（server 8090）

#### 认证 `/api/auth`
| 接口 | 说明 |
|------|------|
| POST `/login` | 用户名密码登录，返回 JWT + 权限列表 |
| GET `/me` | 当前用户信息（含 roleIds + featureCodes） |
| POST `/refresh` | 刷新 token |

#### 模拟器管理 `/api/emu`
| 接口 | 说明 |
|------|------|
| GET `/instances` | 模拟器实例列表（按 merchantId/userId 过滤） |
| POST `/instances` | 批量创建实例（**需指定 deviceId**） |
| POST `/instances/{id}/start` | 启动（走 Agent Server HTTP Poll → deviceId 匹配的 Agent） |
| POST `/instances/{id}/stop` | 停止 |
| POST `/instances/{id}/restart` | 重启 |
| DELETE `/instances/{id}` | 删除（同步调用 mumu-agent 删除物理实例） |
| GET `/servers/available` | 可添加服务器（**已修复跨商户**） |
| POST `/servers/add` | 添加服务器 |
| GET `/servers/added` | 已添加服务器 |
| GET `/accounts/available` | 可用账号（**已修复筛选下拉**） |
| GET `/accounts/added` | 已绑定账号 |
| POST `/accounts/add` | 绑定账号 |
| POST `/discord/apk-upload` | 上传 Discord APK |
| GET `/discord/apk-download` | 下载 APK |
| POST `/auto-add/{id}/start` | 启动全部加好友 |
| POST `/auto-add/{id}/stop` | 停止加好友 |

#### 服务器管理 `/api/guild-servers`、成员 `/api/guild-members`、排除配置 `/api/exclusion`

#### Discord 成员抓取 `/api/discord-members`

### 6.2 前端页面（client-vue 5175）

| 页面 | 路由 | 说明 |
|------|------|------|
| 登录 | `/login` | — |
| 好友管理 | `/emulator` | ⭐ 主力页面：模拟器列表 + 服务器卡片 + 排除配置 TAB |
| 服务器列表 | `/guilds` | — |
| 服务器成员 | `/guild-members` | — |
| 账号管理 | `/accounts` | — |
| 角色权限 | `/roles` | — |
| 审计日志 | `/audit` | — |

### 6.3 mumu-agent 命令集

Agent 收到的 WebSocket 消息格式：`{type: "CMD", command: "...", params: {...}}`

| 命令 | 说明 |
|------|------|
| `CREATE_EMU` | `mumutool create --index N --cpu C --memory M` |
| `START_EMU` | `mumutool launch --index N` |
| `STOP_EMU` | `mumutool quit --index N` |
| `RESTART_EMU` | quit + launch |
| `DELETE_EMU` | 物理删除 |
| `GET_EMULATORS` | 返回当前实例列表（后端 30s 防抖） |
| `INSTALL_APK` | `adb install` |
| `START_AUTOADD` | 启动加好友脚本 |
| `STOP_AUTOADD` | 停止 |
| `PING` / `PONG` | 心跳 |

---

## 7. 关键设计

### 7.1 RBAC 权限体系

```
JWT payload:
{
  sub: "merchantadmin",
  agentId: 4,
  userId: 4,
  merchantId: 2,
  roleIds: [2],
  accountType: 0
}

角色判断（SecurityUtils.currentRole()）：
- accountType=0 + merchantId=null  → PLATFORM_ADMIN（平台管理员，看全部）
- accountType=0 + merchantId!=null  → MERCHANT_ADMIN（商户管理员，本商户）
- accountType=1                     → USER（普通用户）

权限加载流程（AuthController.login）：
1. AgentRepository.findByIdWithRoleIds  ← JOIN FETCH roleIds 解决 LAZY 问题
2. RoleRepository.findFeatureCodesByRoleIds  ← 原生 SQL 查 role_feature
3. 返回给前端 + 写入 JWT 的 roleIds
```

### 7.2 商户隔离（merchantId）

**核心原则**：所有查询必须带 `merchantId = currentMerchantId`，除非是 PLATFORM_ADMIN。

最近修复的两个典型错误：
- AgentServerService.getAvailableServers：原来用 findAll() → 跨商户泄漏 → 改为 findByMerchantId()
- DiscordAccountService.getAvailableAccounts：controller fallback merchantId=1L → 查不到账号 → 改为 SecurityUtils.currentMerchantId()

### 7.3 设备隔离（deviceId）⭐ 最关键

```java
// 错误示范：
// 按 merchantId 找第一个在线 Agent → 串机！
agent = findFirstOnlineAgent(merchantId);

// 正确做法：
// 从 EmuInstance 读 deviceId → 在 AgentServerService 的 session Map 里精确匹配
String deviceId = instance.getDeviceId();
AgentSession session = cloudWsService.findSessionByDeviceId(deviceId);
session.send(command);
```

**三个地方必须确保 deviceId 有值**：
1. `setInstanceCount` — 创建模拟器时
2. `syncInstanceDatabase` / `syncInstanceDatabaseIncremental` — DB 同步时
3. `startInstance` — 启动操作时的 fallback 查询

### 7.4 心跳与连接稳定性

历史踩坑记录：

| 问题 | 根因 | 修复 |
|------|------|------|
| Agent 频繁断开 | HikariCP 连接池耗尽（默认 10）→ 心跳线程阻塞 | 调大到 15（prod）/ 20（dev） |
| 心跳超时误杀 | 90s 超时太短 | 延长到 180s + 注释关闭会话逻辑 |
| TASK_RESULT 每秒一条 | 前端定时器高频轮询 /api/emu/emulators | 降频到 30s + 后端 30s 防抖缓存 |
| 心跳 DB 写入阻塞 | 每次心跳都 UPDATE agent_registrations | 降频到 60s 一次 |

**心跳间隔**：Agent 每 30s 发一次；后端 180s 超时。

### 7.5 好友排除配置

```
friend_exclusion_config (每商户1行):
  exclude_all_friends=true  → 把本商户所有已加好友都标记 excluded
  use_custom_list=true      → 使用 friend_exclusion_users 里的清单

好友池构建时:
  1. 读 guild_members 里未被添加的用户（friend_status != 已添加）
  2. 检查是否在 exclusion_users 中
  3. 检查是否在本商户的 friend 表中（exclude_all_friends）
  4. 命中任一 → friend_status = 已排除（新增状态值 4）
  5. 未命中 → 加入 emu_friend_pool 号池
```

### 7.6 日志体系

```
logback-spring.xml 配置了三个过滤器：
├── MdcTraceFilter       每请求生成 traceId 放入 MDC
├── JwtAuthFilter        解析 JWT 后 setUserContext(userId, merchantId)
└── AccessLogFilter      记录每次 HTTP 请求

日志格式关键字段:
%d{HH:mm:ss.SSS} [%thread] %-5level %X{traceId} %X{merchantId} %X{userId} - %msg
```

---

## 8. 本地开发环境

### 8.1 前置要求

| 依赖 | 版本 | 安装 |
|------|------|------|
| JDK | 17+ | `brew install openjdk@17` |
| Node.js | 18+ | `brew install node` |
| Maven | 3.8+ | 随 Spring Boot 推荐版 |
| MySQL | 8.0 | 本地 127.0.0.1:3306，库 discordadmin |

### 8.2 启动顺序

```bash
# 1. 后端（dev profile 连本地 DB）
cd server
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 监听 :8090

# 2. 前端（dev 模式自动代理 /api → :8090）
cd client-vue
npm install     # 首次
npm run dev
# 监听 :5175

# 3. mumu-agent（Windows 电脑上跑）
# config.json 里 serverUrl 改成 ws://[你的mac本地IP]:8090/ws/agent
# 然后 node agent.js
```

### 8.3 本地 DB 初始化

```sql
CREATE DATABASE IF NOT EXISTS discordadmin DEFAULT CHARSET utf8mb4;
USE discordadmin;
-- JPA 自动建表（ddl-auto=update 在 dev profile）
```

### 8.4 开发验证规范（用户要求）

1. 改完代码必须**重启前后端**（8090 / 5175）
2. 用 **curl** 验证后端 API（不用浏览器）
3. 只在**最终验证**时用浏览器
4. 每次改完**批量改几处再统一编译**，不要一行一编译
5. 改动后立即 `git add + git commit` 保持工作区干净
6. **绝对不要自己执行 git push**（等用户通知）

---

## 9. 生产部署

### 9.1 服务器清单（v1.7.2 更新）

| 用途 | IP | 配置 | OS | 备注 |
|------|-----|------|----|------|
| **应用服务器**（server v1.7.2 + Nginx + systemd） | **101.47.41.149** | 4C/8G/50G | Ubuntu 22.04 | SSH root/laeC7ooC7eif#aih |
| **DB 服务器**（MySQL 8.0，discordadmin 库） | **101.47.41.155** | 2C/4G/50G | Ubuntu 22.04 | 独立部署，HikariCP 30 连接池 |
| Discord CRM Agent（Mac/Windows） | — | — | macOS/Windows | **主动连后端** poll 任务，不走系统端口 |

> 📌 **服务器归属说明（重要，别搞混）**：
> | IP | 归属 | 用途 |
> |----|------|------|
> | **101.47.41.149** | ✅ **本子项目（Discord CRM）** | **应用服务器**：server v1.7.2 + Nginx + systemd |
> | **101.47.41.151** | 📦 **其他子项目** | `server-admin/` 是其他子项目的主服务目录，**同仓库但独立维护** |
> | **101.47.41.155** | 📊 共用 | **DB 服务器**（MySQL 8.0），本项目和其他子项目**共用同一个 discordadmin 库** |
>
> ⚠️ **本项目旧架构已废弃**：曾短暂在 149 上跑过 server-admin 0.1.3（端口 9090），已停止使用。
> 旧 mumu-agent（WebSocket 模式）也停了，换成新 **crm_agent**（HTTP poll 模式，端口 8090）。

### 9.2 生产架构图（v1.7.2）

```
                    ┌─────────────────────────────────────┐
                    │   101.47.41.149 (应用 4C/8G)        │
                    │                                     │
  ┌──────────┐      │  ┌──────────────────────────┐      │
  │ 外网     │:80   │  │  nginx (active, since 8/26)│      │
  │ 用户浏览器│─────▶│  │   proxy_pass 8090 (api)   │      │
  │          │      │  │   proxy_pass 5273 (emu)   │      │
  └──────────┘      │  └──────────────────────────┘      │
                    │           │                         │
                    │           ▼                         │
                    │  ┌──────────────────────────┐      │
                    │  │ systemd: discord-admin   │      │
                    │  │  enabled (开机自启)       │      │
                    │  │                          │      │
                    │  │ java -jar                 │      │
                    │  │ discord-admin-server-1.7.2│      │
                    │  │ --spring.profiles.active  │      │
                    │  │   =prod                   │      │
                    │  │ --server.port=8090       │      │
                    │  │ Xms512m Xmx2048m          │      │
                    │  └──────┬───────────────────┘      │
                    │         │ HikariCP 30连接             │
                    └─────────┼────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────────────────┐
                    │ DB: 101.47.41.155:3306      │
                    │ MySQL 8.0                   │
                    │ discordadmin (utf8mb4)      │
                    │ root / Dsdb2026!            │
                    │ max_connections ≈ 200       │
                    └─────────────────────────────┘

  Agent 节点（macOS/Windows）:
  ┌──────────────┐       HTTP poll (每 5s)
  │ crm_agent    │ ──────────────────────▶ 后端 8090
  │ v1.7.2       │ ◀────────────────────── AgentTask
  │ Playwright   │                         (CAPTURE/LAUNCH/SYNC)
  └──────────────┘
       │
       ├── 系统 Chrome（非 Playwright 内置）
       ├── 代理自动探测 7890（本机）
       └── proxy=http://127.0.0.1:7890
```

### 9.3 systemd 完整配置（生产真实文件）

位置：`/etc/systemd/system/discord-admin.service` — **systemctl enable 已生效**

```ini
[Unit]
Description=Discord Admin Backend (server) v1.7.2
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/discord-admin/current

# ⚠️ 所有 DB/JWT 配置必须通过环境变量传入（application-prod.yml 默认值为空）
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="DB_HOST=101.47.41.155"
Environment="DB_PORT=3306"
Environment="DB_NAME=discordadmin"
Environment="DB_USERNAME=root"
Environment="DB_PASSWORD=Dsdb2026!"
Environment="JWT_SECRET=discord-admin-prod-secret-2026-strong-key-at-least-256-bits"
Environment="APP_BASE_URL=http://101.47.41.149:8090"

Environment="JAVA_OPTS=-Xms512m -Xmx2048m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/discord-admin/ \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Dfile.encoding=UTF-8"

ExecStart=/usr/bin/java $JAVA_OPTS \
  -jar /opt/discord-admin/current/discord-admin-server-1.7.2.jar

Restart=on-failure
RestartSec=5
StartLimitIntervalSec=300
StartLimitBurst=10
TimeoutStartSec=180
LimitNOFILE=65536

StandardOutput=append:/var/log/discord-admin/app.log
StandardError=append:/var/log/discord-admin/error.log

[Install]
WantedBy=multi-user.target
```

**常用命令**：
```bash
systemctl daemon-reload              # 改 service 后必须 reload
systemctl restart discord-admin      # 重启（会自动拉新环境变量）
systemctl status discord-admin       # 查看状态
journalctl -u discord-admin -f       # 实时日志
```

### 9.4 Nginx 反代配置

```nginx
server {
    listen 80;
    server_name 101.47.41.149;

    # 前端静态（Vite 打包产物）
    root /var/www/discord-admin/current;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }

    # 后端 API
    location /api/ {
        proxy_pass http://127.0.0.1:8090/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # Agent WebSocket（CAPTURE 进度推送）
    location /ws/ {
        proxy_pass http://127.0.0.1:8090/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

### 9.5 一键部署 SOP

```bash
# ===== 本地 Mac 执行 =====
BASE="discord-src/discord"
REMOTE="root@101.47.41.149"
REMOTE_DIR="/opt/discord-admin/current"

# 1. 前端打包
cd $BASE/client-vue
rm -rf node_modules/.vite dist
npm run build

# 2. 后端打包（内嵌前端 dist）
cd $BASE/server
mvn clean package -DskipTests
JAR=$(ls -t target/*.jar | head -1)
echo "JAR: $JAR"

# ===== 上传 =====
# 3. JAR 上传
sshpass -p 'laeC7ooC7eif#aih' scp -o StrictHostKeyChecking=no \
  "$JAR" $REMOTE:$REMOTE_DIR/

# 4. 前端静态上传
sshpass -p 'laeC7ooC7eif#aih' rsync -az --delete \
  "$BASE/client-vue/dist/" \
  $REMOTE:/var/www/discord-admin/current/

# ===== 远程重启 =====
# 5. 修改 systemd ExecStart 指向新 JAR（版本号变了的话）
sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no $REMOTE << REMOTE_EOF
systemctl daemon-reload
systemctl restart discord-admin

# 等待启动（最多 30s）
for i in $(seq 1 30); do
  sleep 2
  CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8090/api/auth/login \
    -X POST -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}')
  if [ "$CODE" = "200" ] || [ "$CODE" = "401" ]; then
    echo "✅ 启动成功 HTTP $CODE (${i}x2秒)"
    break
  fi
  [ $i -eq 30 ] && { echo "❌ 超时"; tail -20 /var/log/discord-admin/discord-admin.log; }
done
REMOTE_EOF

# 6. 外网验证
curl -s -o /dev/null -w "前端: HTTP %{http_code}\n" http://101.47.41.149/
curl -s -o /dev/null -w "API : HTTP %{http_code}\n" http://101.47.41.149/api/auth/login \
  -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### 9.6 生产环境变量速查（**全部在 systemd service 里**）

| 变量 | 值 | 说明 |
|------|-----|------|
| `DB_HOST` | `101.47.41.155` | ⚠️ **独立 DB 服务器，不是 149 本机** |
| `DB_PORT` | `3306` | 默认 |
| `DB_NAME` | `discordadmin` | — |
| `DB_USERNAME` | `root` | — |
| `DB_PASSWORD` | `Dsdb2026!` | ⚠️ 不是本地的 `Len2066!` |
| `JWT_SECRET` | `discord-admin-prod-secret-2026-strong-key-at-least-256-bits` | 必须 ≥ 32 chars，否则 JwtUtil 启动崩溃 |
| `APP_BASE_URL` | `http://101.47.41.149:8090` | 邮件回调、WebSocket 地址拼接 |

### 9.7 生产日志路径

| 文件 | 路径 | 内容 |
|------|------|------|
| app.log | `/var/log/discord-admin/app.log` | systemd stdout，主日志 |
| error.log | `/var/log/discord-admin/error.log` | systemd stderr，异常堆栈 |
| discord-admin.log | `/var/log/discord-admin/discord-admin.log` | 旧 logback-spring.xml 输出（可能已废弃） |

### 9.8 2026-09-03 生产部署踩坑记录（必读）

本次从 0.1.0 升级到 **v1.7.2**，踩了 4 个致命坑：

| # | 坑 | 现象 | 根因 | 修复 |
|---|-----|------|------|------|
| **1** | **JWT 启动崩溃** | `WeakKeyException: 0 bits not secure enough` | `application-prod.yml` 的 `jwt.secret: ${JWT_SECRET:}` **默认值是空字符串**，不是有意义的 fallback！之前老版本可能在 application.yml 里有默认值，但 prod 没设 | 在 systemd Environment 里加 `JWT_SECRET=xxxx(≥32 chars)` |
| **2** | **DB 连不上（Connection refused）** | HikariCP 一直 `Communications link failure` | 以为 MySQL 在 149 本机，但 **DB 独立部署在 155**！注释写的 `149 应用 + 155 DB` 是对的，我忽略了 | 设 `DB_HOST=101.47.41.155`，`nc -zv 101.47.41.155 3306` 验证连通 |
| **3** | **DB 密码错（Access denied）** | `Access denied for user 'root'@'101.47.41.149'` | 用了本地密码 `Len2066!`，但生产实际密码是 `Dsdb2026!` | 从 `/opt/discord-admin/releases/1.0.0/config/application.yml` 里找回旧明文密码 |
| **4** | **启动后外网 502 Bad Gateway** | nginx 连不上 8090 | 上面 3 个坑叠加导致后端没起来，nginx proxy_pass 8090 自然超时 | 修好 1+2+3，后端 8 秒内就绪 → 502 消失 |

**教训**：
- application-prod.yml 里 `${VAR:}` 空默认值是**陷阱**——Spring Boot 不是 fallback 到 application.yml，就是空值
- 独立 DB 架构：`DB_HOST` 不是 localhost，必须显式设
- 换版本前**先 grep 旧 JAR 里的明文密码**，不要假设和本地一样
- 部署 SOP 第一步：`nc -zv $DB_HOST 3306` 验证 DB 通，再启动 Java

### 9.9 crm_agent v1.7.2 生产 config.json（每台 Agent 节点独立）

```json
{
  "serverUrl": "http://101.47.41.149:8090/api",
  "agentName": "<唯一节点名，如 crm-agent-win-1>",
  "token": "<前端代理管理 → 重置 token 拿>",
  "heartbeatIntervalMs": 5000,
  "pollIntervalMs": 5000,
  "browser": {
    "headless": false,
    "userDataDir": "./data/browser-profile"
  },
  "version": "1.7.2"
}
```

**上线步骤**：
```bash
# Mac/Windows agent 机器
cd crm_agent
npm install
# 改 config.json 的 token 和 agentName
nohup node src/index.js > agent.log 2>&1 &

# 验证：前端 → 代理管理页面 → 看到节点 ONLINE
```

### 9.10 常用运维命令速查

```bash
# ===== 149 应用服务器 =====
systemctl status discord-admin              # 服务状态
systemctl restart discord-admin             # 重启（加载新 env）
journalctl -u discord-admin -f              # 实时日志
journalctl -u discord-admin --since "1h ago"
tail -f /var/log/discord-admin/app.log      # 旧 logback 日志
ss -tlnp | grep 8090                        # 端口监听
ps aux | grep discord-admin-server          # Java 进程

# ===== 155 DB 服务器 =====
mysql -uroot -p'Dsdb2026!' discordadmin \
  -e "SHOW PROCESSLIST;"                    # 查看连接
mysql -uroot -p'Dsdb2026!' discordadmin \
  -e "SHOW TABLES;"
mysql -uroot -p'Dsdb2026!' discordadmin \
  -e "SELECT COUNT(*) FROM agent_servers;"

# ===== Agent 节点（Mac/Windows）=====
tail -f crm_agent/agent.log
pgrep -fa "crm_agent"                       # 进程检查
pgrep -fa "browser-profiles"                # Chrome 子进程检查

# ===== 外网验证 =====
curl -s http://101.47.41.149/api/auth/login \
  -X POST -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```


### 9.11 crm_agent 打包机制（**2026-09-03 重写，严禁回退**）

#### 核心原则

> **后端下载接口只认源码目录，JAR 内嵌兜底已删除。没有源码就报错。**

#### 下载链路

```
GET /api/agent-servers/package
    │
    ├─ resolveAgentSourceDir() 找源码目录
    │   ├─ agentSourceDir 配置项（显式）
    │   └─ 自动推断：从 user.dir 向上找 crm_agent/package.json
    │
    ├─ 源码目录存在 → buildZip(sourceDir) 动态打包 ✅ 最新代码
    │
    └─ 源码目录不存在 → HTTP 500 报错 ⚠️ （之前读 JAR 内嵌 zip 是 bug，已删）
```

#### 服务器必须有的目录

```
/opt/discord-admin/current/
├── discord-admin-server-1.8.4.jar          ← systemd ExecStart 用
├── crm_agent/                               ← 源码目录！buildZip() 从这里读
│   ├── package.json                         ← version 字段必须和 JAR 一致
│   ├── config.json                          ← 干净模板（token="在此粘贴token"）
│   ├── src/
│   │   ├── browser.js                       ← 核心 CAPTURE 逻辑
│   │   ├── index.js                         ← heartbeat 上报 agentVersion
│   │   └── ...
│   └── .DS_Store（会被 buildZip 自动排除）
└── systemd service 文件
```

#### buildZip() 排除规则（Java AgentServerController.java 硬编码）

**排除目录**：node_modules, data, .git, .idea, .vscode, __pycache__

**排除文件**：README.md, agent.log, server.js, .DS_Store, *.bak, *.bak2（任意层级）, .开头的隐藏文件

**会被打包**：config.json ✅, package-lock.json ✅, package.json ✅, src/** ✅, start.sh/bat ✅, README_INSTALL.txt（后端动态生成）

#### 版本号管理规范（**4 处必须一致，任何代码改动后必须 +1**）

| 位置 | 文件 | 示例 |
|------|------|------|
| 1 | crm_agent/package.json | `"version": "1.8.4"` |
| 2 | crm_agent/config.json | `"version": "1.8.4"` |
| 3 | server/pom.xml（项目 version，不是 Spring Boot parent） | `<version>1.8.4</version>` |
| 4 | AgentServerController.java（两处：configTemplate 字符串 + return 常量） | `"1.8.4"` |

#### 一键严谨打包流程

```bash
# Step 1: 清理所有缓存
rm -rf server/target
rm -rf server/src/main/resources/crm_agent-v*.zip
rm -rf server/src/main/resources/agent-package.zip
rm -f crm_agent/*.zip crm_agent/config.json.bak

# Step 2: 确认 4 处版本号一致（手动改或用 sed）

# Step 3: Maven clean + 打 JAR
cd server && mvn clean package -DskipTests && cd ..

# Step 4: 同步 crm_agent 源码到服务器（--delete 确保删掉旧文件）
rsync -avz --delete \
  --exclude node_modules --exclude data --exclude .git --exclude '*.log' --exclude '*.zip' \
  crm_agent/ root@101.47.41.149:/opt/discord-admin/current/crm_agent/

# Step 5: 传 JAR + 重启
scp server/target/discord-admin-server-{VER}.jar root@101.47.41.149:/opt/discord-admin/current/
ssh root@101.47.41.149 "
  sed -i 's/discord-admin-server-[0-9.]*\.jar/discord-admin-server-{VER}.jar/' /etc/systemd/system/discord-admin.service
  systemctl daemon-reload && systemctl restart discord-admin
"

# Step 6: 验证（必须做，不能靠猜）
curl -s http://101.47.41.149/api/agent-servers/package -o /tmp/test.zip
unzip -l /tmp/test.zip | grep config.json                          # 应有
unzip -l /tmp/test.zip | grep browser.js                          # 大小 >= 35000 是新版
unzip -p /tmp/test.zip crm_agent/config.json | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"
unzip -p /tmp/test.zip crm_agent/src/browser.js | grep -c "__capturedDiscordToken"  # >= 3
```

#### 下载接口常见错误

| 现象 | 原因 | 解决 |
|------|------|------|
| HTTP 500 + "CRM_AGENT_SOURCE_NOT_FOUND" | 服务器没有 `/opt/discord-admin/current/crm_agent/` | rsync 同步源码 |
| zip 里 browser.js 很小（~30000 bytes） | 源码是旧版 | git pull 或重新同步 |
| zip 里没有 config.json | buildZip() 排除列表里有 config.json | 改 Java skipFiles |

### 9.12 crm_agent v1.8.0~v1.8.4 反作弊 + CAPTURE 核心改动

#### 4 层 Token 抓取（按优先级）

| # | 层级 | 位置 | 说明 |
|---|------|------|------|
| 1 | **JS fetch/XHR Hook** | page 内 initScript | 在 Discord JS 运行时直接拦截 Authorization header，设置 window.__capturedDiscordToken |
| 2 | **Playwright request** | Node 层 | 拦截发往 discord.com/api 的请求头 |
| 3 | **Playwright response** | Node 层 | 拦截 Discord 返回的响应头 |
| 4 | **CDP WebSocket** | 浏览器底层 | CDP Network 监听 Gateway op=2 IDENTIFY / op=4 RESUME 帧里的 token |

**关键代码**：`crm_agent/src/browser.js` 第 330-395 行

#### CAPTURE 成功条件（严格）

```
必须同时满足：
  result.token 非空（长度 > 50，不以 "Bot " 开头）
  result.userId 非空
  result.username 非空
```

#### heartbeat 必须上报 agentVersion

```javascript
// crm_agent/src/index.js heartbeat()
await http.post('/agent-servers/heartbeat', {
  token: cfg.token,
  name: cfg.agentName,
  nodeVersion: process.version,
  agentVersion: AGENT_VERSION,   // ⚠️ 缺了后端看到 NOT_SET
  browserType: (cfg.browser && cfg.browser.type) || 'chromium',
});
```

#### 反作弊核心措施

| 措施 | 说明 |
|------|------|
| SYSTEM_CHROME | 用系统 Chrome，不用 Playwright Chromium |
| chromiumSandbox: false | 减少自动化痕迹 |
| initScript 反检测 | 伪装 navigator.webdriver, window.chrome, chrome.debugger |
| account_fingerprint.js | 每账号独立指纹：UA/WebGL/时区/语言/硬件 |
| 地理-语言 1:1 映射 | Japan→ja-JP, SG→zh-CN |
| 代理穿透 | Chrome --proxy-server + axios 同出口 |
| 资源拦截 | 只拦追踪/广告，不碰 hCaptcha/Discord API |
| CAPTURE 后延迟 3-4 分钟 | 防风控 |
| capacity_policy | 并发槽位控制 |

## 10. 开发规范与历史踩坑

### 10.1 最容易犯的错误（按出现频率排序）

| # | 问题 | 根因 | 修复要点 |
|---|------|------|----------|
| 1 | 跨商户数据泄漏 | 用了 findAll() 而不是 findByMerchantId() | **所有查询先想：是否需要加 merchantId 条件？** |
| 2 | 模拟器启动报 deviceId/merchantId/userId 为空 | 创建时没设 deviceId；异步线程无 SecurityContext | setInstanceCount 显式设三字段；startInstance 里补查 |
| 3 | JPA @ElementCollection LAZY 加载导致空 | agent.getRoleIds() 在非事务上下文 | 用 JOIN FETCH 或 EntityGraph |
| 4 | @Transactional 长事务死锁 | setInstanceCount 里 for 循环 INSERT | 移除 @Transactional 或缩小事务范围 |
| 5 | 前端定时器高频轮询打爆后端 | 多个 setInterval 叠加 | 统一 10-30s + 后端加防抖缓存 |
| 6 | AccessLogFilter 日志重复打印 | 同时 @Component + addFilterBefore | 只保留 @Component 自动注册 |
| 7 | HikariCP 连接耗尽 | 默认 10，心跳阻塞 | prod=15, dev=20，DB 2C/4G 撑不住太多 |
| 8 | mumu-agent 串机 | 按 merchantId 选 Agent 而非 deviceId | **Agent Server HTTP Poll 里 session Map 用 deviceId 做 key** |

### 10.2 代码修改原则

1. **不改无关代码** — 修 Bug 时只改 Bug 相关的函数，不顺手优化
2. **先理解再动手** — 读完整调用链再改，尤其是 WebSocket 消息链路
3. **一次改多处再统一编译** — 不要改一处编译一处
4. **生产 config 用 env 变量** — 开发可以写死，生产不能

### 10.3 git 流程（用户明确要求）

```bash
git add -A && git commit -m "描述"    # 改完立即提交，保持工作区干净
# 不自动 push，等用户明确通知
# 禁止自动 pull / reset / checkout / restore
```

---

## 11. 已知问题 & TODO

| 状态 | 问题 | 说明 |
|------|------|------|
| 🟡 WIP | 模拟器实例创建 CPU/内存配置 | 已改 agent.js 加 --cpu --memory 参数，未端到端验证 |
| 🟡 WIP | APK 上传/下载 | 后端接口已实现，前端需验证 |
| 🟡 WIP | 前端账号筛选下拉 | 后端已修复，但需生产验证 Element Plus Select 的 remote-search |
| 🔵 TODO | Docker 化部署 | systemd 运维够用，但后续可以 Docker Compose |
| 🔵 TODO | 接口文档自动生成 | springdoc-openapi 未接入 |
| 🔵 TODO | Grafana/Prometheus 监控 | 未接入 micrometer |
| 🔵 TODO | Redis 缓存 | 全走 DB，高频查询可以加一级缓存 |

---

## 12. 快速上手 Checklist

接手本项目的 Agent，请按此清单依次执行：

- [ ] 读本文档
- [ ] 确认 JDK 17 / Node 18+ / MySQL 8.0 环境
- [ ] 本地创建数据库 discordadmin
- [ ] `cd server && mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- [ ] `cd client-vue && npm install && npm run dev`
- [ ] 用 admin / admin123 登录 http://localhost:5175
- [ ] curl -X POST http://localhost:8090/api/auth/login -H "Content-Type: application/json" -d '{"username":"merchantadmin","password":"admin123"}'
- [ ] 查 server/src/main/resources/application-prod.yml 里的环境变量，在 101.47.41.149 检查 /etc/systemd/system/discord-admin.service
- [ ] 改完代码 → mvn package → scp 到 151 → systemctl restart → 验证

---

## 附：关键文件速查表

| 你想做… | 去这里看 |
|--------|---------|
| 改登录/JWT | server/.../security/JwtUtil.java、JwtAuthFilter.java |
| 改权限/RBAC | server/.../controller/AuthController.java、RoleRepository.java |
| 改模拟器操作 | server/.../service/DiscordAccountService.java |
| 改自动加好友 | server/.../service/AgentTaskService.java |
| 改 Agent 通信 | server/.../service/AgentServerService.java、mumu-agent/agent.js |
| 改跨商户查询 | server/.../service/AgentServerService.java、DiscordAccountService.java |
| 改心跳/超时 | server/.../service/AgentServerService.java（HEARTBEAT_TIMEOUT_SECONDS） |
| 改排除配置 | server/.../service/ExclusionService.java、controller/ExclusionController.java |
| 改日志 | server/src/main/resources/logback-spring.xml |
| 改前端页面 | client-vue/src/views/EmulatorView.vue（好友管理） |
| 改前端轮询 | 在 EmulatorView.vue 搜 setInterval |
| 查实体字段 | server/.../entity/*.java（全部 @Column 都有含义） |
