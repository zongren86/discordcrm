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

### 项目拆分明细

| 子项目 | 技术栈 | 端口 | 运行位置 | 状态 |
|--------|--------|------|----------|------|
| **server-admin** ✅ | Spring Boot 3.3.4 + JPA + Security + WebSocket | 9090 | 应用服务器 | **当前主力** |
| **client-admin** ✅ | Vue 3 + Vite 5 + Element Plus + Pinia | 5175 | 应用服务器（Nginx 反代） | **当前主力** |
| **mumu-agent** ✅ | Node.js + WebSocket + MuMu CLI | — | Windows 云电脑（173/其它） | **设备端 Agent** |
| server | Spring Boot（旧） | 8090 | 应用服务器 | 共存，非本轮开发范围 |
| client-vue | Vue 2（旧） | 5173 | 本地开发 | 共存，非本轮开发范围 |

---

## 2. 系统架构

### 2.1 拓扑图

```
┌────────────────────────────────────────────────────────────────────┐
│                    应用服务器  101.47.41.151 (4C/8G)                │
│  ┌──────────┐   ┌──────────────────┐   ┌──────────────────────┐   │
│  │  Nginx   │──▶│  server-admin     │──▶│  MySQL Client (Hikari)│   │
│  │  :80/:443│   │  :9090 SpringBoot│   │  连接池 max=15       │   │
│  └──────────┘   └────────┬─────────┘   └──────────┬───────────┘   │
│        ↑                  │ WebSocket                │               │
│        │                  │ /ws/agent                │               │
│  client-admin            │                           │               │
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
client-admin               server-admin                mumu-agent              MuMu模拟器
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
// 2. CloudWebSocketService 中找 deviceId 对应的在线 Agent
// 3. 把命令发到那个 Agent 的 WebSocket 会话
// 绝对不能按 merchantId 或 "第一个在线 Agent" 选
```

---

## 3. 技术栈

### 后端 server-admin

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

### 前端 client-admin

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
├── server-admin/              ✅ 后端
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
│   │   │   │   ├── CloudWebSocketService     ⭐ Agent WebSocket 管理 + 心跳
│   │   │   │   ├── EmuInstanceService        ⭐ 模拟器实例操作（start/stop/create）
│   │   │   │   ├── EmuAutoAddDispatcher      ⭐ 自动加好友调度
│   │   │   │   ├── EmuServerBindingService   服务器绑定（含跨商户隔离逻辑）
│   │   │   │   ├── EmuAccountBindingService  账号绑定（含跨商户隔离逻辑）
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
├── client-admin/              ✅ 前端
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

### 6.1 后端 API（server-admin 9090）

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
| POST `/instances/{id}/start` | 启动（走 CloudWebSocket → deviceId 匹配的 Agent） |
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

### 6.2 前端页面（client-admin 5175）

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
- EmuServerBindingService.getAvailableServers：原来用 findAll() → 跨商户泄漏 → 改为 findByMerchantId()
- EmuAccountBindingService.getAvailableAccounts：controller fallback merchantId=1L → 查不到账号 → 改为 SecurityUtils.currentMerchantId()

### 7.3 设备隔离（deviceId）⭐ 最关键

```java
// 错误示范：
// 按 merchantId 找第一个在线 Agent → 串机！
agent = findFirstOnlineAgent(merchantId);

// 正确做法：
// 从 EmuInstance 读 deviceId → 在 CloudWebSocketService 的 session Map 里精确匹配
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
cd server-admin
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 监听 :9090

# 2. 前端（dev 模式自动代理 /api → :9090）
cd client-admin
npm install     # 首次
npm run dev
# 监听 :5175

# 3. mumu-agent（Windows 电脑上跑）
# config.json 里 serverUrl 改成 ws://[你的mac本地IP]:9090/ws/agent
# 然后 node agent.js
```

### 8.3 本地 DB 初始化

```sql
CREATE DATABASE IF NOT EXISTS discordadmin DEFAULT CHARSET utf8mb4;
USE discordadmin;
-- JPA 自动建表（ddl-auto=update 在 dev profile）
```

### 8.4 开发验证规范（用户要求）

1. 改完代码必须**重启前后端**（9090 / 5175）
2. 用 **curl** 验证后端 API（不用浏览器）
3. 只在**最终验证**时用浏览器
4. 每次改完**批量改几处再统一编译**，不要一行一编译
5. 改动后立即 `git add + git commit` 保持工作区干净
6. **绝对不要自己执行 git push**（等用户通知）

---

## 9. 生产部署

### 9.1 服务器清单

| 用途 | IP | 配置 | OS | 备注 |
|------|-----|------|----|------|
| 应用服务器（server-admin + Nginx） | 101.47.41.151 | 4C/8G/50G | Ubuntu 22.04 | SSH user=root |
| DB 服务器（MySQL 8.0） | 101.47.41.155 | 2C/4G/50G | Ubuntu 22.04 | SSH user=root |
| Windows 云电脑（mumu-agent + MuMu） | — | — | Windows | mumu-agent 主动连后端 |

### 9.2 systemd 服务配置

位置：`/etc/systemd/system/discord-admin.service`

```ini
[Unit]
Description=Discord Admin Server
After=network.target

[Service]
User=root
WorkingDirectory=/opt/discord-admin/backend
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="DB_HOST=101.47.41.155"
Environment="DB_USER=root"
Environment="DB_PASSWORD=Dsdb2026!"
Environment="JWT_SECRET=<你的密钥>"
ExecStart=/usr/bin/java \
  -Xms1g -Xmx2g \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -Dfile.encoding=UTF-8 \
  -jar discord-admin-server-admin-0.1.3.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### 9.3 一键部署脚本

```bash
#!/bin/bash
# deploy.sh — 部署 server-admin 到 101.47.41.151

APP_HOST="root@101.47.41.151"
JAR="server-admin/target/discord-admin-server-admin-0.1.3.jar"

# 1. 打包
cd server-admin && mvn clean package -DskipTests

# 2. 上传 jar
scp "$JAR" "$APP_HOST:/opt/discord-admin/backend/"

# 3. 远程重启
ssh "$APP_HOST" "
  sed -i 's/discord-admin-server-admin-0.1.2.jar/discord-admin-server-admin-0.1.3.jar/' /etc/systemd/system/discord-admin.service
  systemctl daemon-reload
  systemctl restart discord-admin
  sleep 15
  systemctl is-active discord-admin
  ss -tlnp | grep 9090
"
```

### 9.4 Nginx 反代配置

```nginx
server {
    listen 80;
    server_name 101.47.41.151;

    # 前端静态
    root /var/www/discord-admin;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }

    # 后端 API
    location /api/ {
        proxy_pass http://127.0.0.1:9090/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # Agent WebSocket 端点
    location /ws/ {
        proxy_pass http://127.0.0.1:9090/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

### 9.5 mumu-agent 生产 config.json

```json
{
  "version": "v2.13.7",
  "serverUrl": "ws://101.47.41.151/ws/agent",
  "heartbeatInterval": 30000,
  "mumuCliPath": "C:\\Program Files\\Netease\\MuMu\\shell\\mumutool.exe",
  "adbPath": "C:\\Program Files\\Netease\\MuMu\\shell\\adb.exe"
}
```

### 9.6 常用运维命令

```bash
# 服务状态
systemctl status discord-admin
journalctl -u discord-admin -f --since "10 min ago"

# 日志
tail -f /var/log/discord-admin/app.log
tail -f /var/log/discord-admin/access.log    # AccessLogFilter

# 端口
ss -tlnp | grep 9090

# DB 连接数（生产 DB 服务器上跑）
mysql -uroot -p'Dsdb2026!' -e "SHOW PROCESSLIST;"
```

---

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
| 8 | mumu-agent 串机 | 按 merchantId 选 Agent 而非 deviceId | **CloudWebSocket 里 session Map 用 deviceId 做 key** |

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
- [ ] `cd server-admin && mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- [ ] `cd client-admin && npm install && npm run dev`
- [ ] 用 admin / admin123 登录 http://localhost:5175
- [ ] curl -X POST http://localhost:9090/api/auth/login -H "Content-Type: application/json" -d '{"username":"merchantadmin","password":"admin123"}'
- [ ] 查 server-admin/src/main/resources/application-prod.yml 里的环境变量，在 101.47.41.151 检查 /etc/systemd/system/discord-admin.service
- [ ] 改完代码 → mvn package → scp 到 151 → systemctl restart → 验证

---

## 附：关键文件速查表

| 你想做… | 去这里看 |
|--------|---------|
| 改登录/JWT | server-admin/.../security/JwtUtil.java、JwtAuthFilter.java |
| 改权限/RBAC | server-admin/.../controller/AuthController.java、RoleRepository.java |
| 改模拟器操作 | server-admin/.../service/EmuInstanceService.java |
| 改自动加好友 | server-admin/.../service/EmuAutoAddDispatcher.java |
| 改 Agent 通信 | server-admin/.../service/CloudWebSocketService.java、mumu-agent/agent.js |
| 改跨商户查询 | server-admin/.../service/EmuServerBindingService.java、EmuAccountBindingService.java |
| 改心跳/超时 | server-admin/.../service/CloudWebSocketService.java（HEARTBEAT_TIMEOUT_SECONDS） |
| 改排除配置 | server-admin/.../service/ExclusionService.java、controller/ExclusionController.java |
| 改日志 | server-admin/src/main/resources/logback-spring.xml |
| 改前端页面 | client-admin/src/views/EmulatorView.vue（好友管理） |
| 改前端轮询 | 在 EmulatorView.vue 搜 setInterval |
| 查实体字段 | server-admin/.../entity/*.java（全部 @Column 都有含义） |
