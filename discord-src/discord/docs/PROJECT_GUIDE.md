# Discord Admin — 项目完整指南

> 版本：v0.1.3 | 最后更新：2026-09-03\
> **目标读者**：接手本项目的 AI Agent / 开发人员

***

## 1. 项目概述

本项目是一套 **Discord CRM 聚合平台** 与 **MuMu 模拟器自动加好友系统** 的组合，核心目标：

- **商户隔离**：多商户（merchantId）共享一套部署，数据严格按商户隔离

- **设备隔离**：每个商户可管理多台 Windows 云电脑（运行 MuMu 模拟器 + mumu-agent），通过 `deviceId` 绑定，操作绝不串机

- **自动加好友**：从 Discord 服务器成员池中筛选用户，在模拟器中自动执行加好友动作

- **Discord CRM**：好友管理、会话管理、消息模板、自动回复等

### 项目拆分明细

| 子项目                | 技术栈                                            | 端口   | 运行位置            | 状态            |
| ------------------ | ---------------------------------------------- | ---- | --------------- | ------------- |
| **server-admin** ✅ | Spring Boot 3.3.4 + JPA + Security + WebSocket | 9090 | 应用服务器           | **当前主力**      |
| **client-admin** ✅ | Vue 3 + Vite 5 + Element Plus + Pinia          | 5175 | 应用服务器（Nginx 反代） | **当前主力**      |
| **mumu-agent** ✅   | Node.js + WebSocket + MuMu CLI                 | —    | Windows 云电脑     | **设备端 Agent** |
| server             | Spring Boot（旧）                                 | 8090 | 应用服务器           | 共存，非本轮开发范围    |
| client-vue         | Vue 2（旧）                                       | 5173 | 本地开发            | 共存，非本轮开发范围    |

### ⚠️ 重要：项目间的关系

| 子项目                       | 部署服务器         | 与 server-admin 的关系                                          |
| ------------------------- | ------------- | ----------------------------------------------------------- |
| **server-admin**（我负责）     | 101.47.41.151 | **当前主力后端**                                                  |
| **server**（旧子项目）          | 101.47.41.149 | 提供 DiscordUserClient（token 获取）+ CloudWebSocketService       |
| **crm-agent**（Playwright） | 独立机器（干净住宅 IP） | 通过 server 的 `/agent-servers` 管理，浏览器自动化获取 Discord USER token |
| **mumu-agent**            | Windows 云电脑   | 连 **server-admin** 的 `/ws/agent`，不连旧 server                 |

### IP 架构（⚠️ 风控关键）

```
crm-agent (Playwright + Chromium)
  所在机器 = 干净住宅 IP ✅
  做的事：打开 discord.com → 手动登录 → 提取 USER token
  出口 IP = 干净住宅 IP ✅ token 在这获取安全

旧 server 子项目 (101.47.41.149 机房 IP)
  做的事：DiscordUserClient 直连 Discord API
  出口 IP = 149 机房 IP

server-admin (101.47.41.151 机房 IP)
  做的事：GatewayMemberFetcher 用 token 连 Discord Gateway 采集成员
  出口 IP = 151 机房 IP ⚠️
  
  ⚠️ 跨服务器 IP 分裂风险：
     token 在干净 IP 获取 → 在机房 IP 使用 → Discord 识别为异地登录
     解决方案（待实施）：
     在 crm-agent 机器上装 tinyproxy（纯 HTTP CONNECT），让 151 的采集
     通过 149 的代理或 crm-agent 的代理转发，统一出口 IP
```

***

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
│  /var/www/discord-admin  │                           │               │
└──────────────────────────┼───────────────────────────┼───────────────┘
                           │                           │
                           │ WSS / WAN                 │ WAN
                           │                           │
┌──────────────────────────▼──────┐  ┌─────────────────▼──────────────┐
│   Windows 云电脑                 │  │  DB 服务器 101.47.41.155       │
│                                  │  │  (2C/4G MySQL 8.0)             │
│  ┌──────────────┐               │  │                                │
│  │ mumu-agent   │──WebSocket───┘  │  discordadmin 数据库            │
│  │ agent.js     │  心跳 30s       │  (与旧 server 子项目共用)       │
│  └──────┬───────┘                 │                                │
│         │ mumu-cli / adb          │                                │
│  ┌──────▼───────┐                 │                                │
│  │ MuMu 模拟器   │×N              │                                │
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
    │─ 全部启动自动加好友 ───▶│                           │                      │
    │                         │─ EmuAutoAddDispatcher      │                      │
    │                         │  调度 candidates           │                      │
    │                         │── WebSocket: START_EMU ─▶│                      │
    │◀── 进度实时推送 ────────│◀── AUTOADD_EVENT/WS ─────│                      │
    │                         │                           │                      │
    │─ 全部停止自动加好友 ───▶│                           │                      │
    │                         │  清 nextAddAt=null        │                      │
    │                         │── WebSocket: BATCH_STOP ─▶│                      │
    │◀── 已停止并关闭模拟器 ──│                           │                      │
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

***

## 3. 技术栈

### 后端 server-admin

| 类别        | 技术                                   | 版本                      |
| --------- | ------------------------------------ | ----------------------- |
| 语言        | Java                                 | 17                      |
| 框架        | Spring Boot                          | 3.3.4                   |
| ORM       | Spring Data JPA (Hibernate)          | —                       |
| 安全        | Spring Security + JJWT               | 0.12.x                  |
| WebSocket | Spring WebSocket + STOMP over SockJS | —                       |
| 数据库       | MySQL                                | 8.0                     |
| 连接池       | HikariCP (Spring Boot 默认)            | prod max=15, dev max=20 |
| Excel     | Apache POI ooxml                     | 5.2.5                   |
| 构建        | Maven                                | —                       |

### 前端 client-admin

| 类别   | 技术                | 版本   |
| ---- | ----------------- | ---- |
| 语言   | JavaScript + Vite | —    |
| 框架   | Vue               | 3.4+ |
| 构建   | Vite              | 5.1  |
| UI   | Element Plus      | 2.6  |
| 状态管理 | Pinia             | 2.1  |
| 路由   | Vue Router        | 4.3  |
| HTTP | Axios             | 1.6  |

### 设备端 mumu-agent

| 类别    | 技术               | 说明                              |
| ----- | ---------------- | ------------------------------- |
| 语言    | Node.js          | 纯 JS 单文件 agent.js + config.json |
| 通信    | ws WebSocket 客户端 | 连后端 `/ws/agent`                 |
| 模拟器控制 | mumu-cli + adb   | 命令行创建/启动/停止/安装 APK              |
| 心跳    | 每 30 秒           | PING/PONG + 注册设备信息              |

***

## 4. 目录结构

```
discord/                                 ← 仓库根目录
├── .git/                                ← Git 仓库 (branch: main-temp-2)
│
├── server-admin/              ✅ 后端（我负责）
│   ├── pom.xml                          ← version: 0.1.3, Spring Boot 3.3.4
│   ├── src/main/
│   │   ├── java/com/discordadmin/
│   │   │   ├── controller/
│   │   │   │   ├── AuthController            登录/JWT/权限
│   │   │   │   ├── EmuManagementController    ⭐ 模拟器+自动加好友接口
│   │   │   │   ├── GuildServerController      ⭐ 服务器列表（含平台管理员DB二次校验）
│   │   │   │   ├── GuildMembersController    服务器成员
│   │   │   │   ├── DiscordMemberController    ⭐ 采集任务启动
│   │   │   │   ├── FriendController          好友管理
│   │   │   │   ├── ExclusionController       排除配置
│   │   │   │   └── AgentDownloadHelper       mumu-agent 下载（动态生成带 config.json 的 zip）
│   │   │   ├── service/                 ⭐ 核心业务层都在这里
│   │   │   │   ├── CloudWebSocketService     Agent WebSocket 管理 + 心跳
│   │   │   │   ├── EmuInstanceService        ⭐ 模拟器操作 + 自动加好友启停
│   │   │   │   ├── EmuAutoAddDispatcher      ⭐ 自动加好友调度 + 候选过滤
│   │   │   │   ├── EmuServerBindingService   服务器绑定（已修复跨商户隔离）
│   │   │   │   ├── EmuAccountBindingService  账号绑定
│   │   │   │   ├── DiscordMemberService      ⭐ 采集进度管理 + 前缀序列清单
│   │   │   │   ├── GatewayMemberFetcher      ⭐ Discord Gateway WebSocket 采集
│   │   │   │   ├── EmuFriendPoolService      好友池构建
│   │   │   │   └── ...
│   │   │   ├── entity/                  ← JPA 实体
│   │   │   │   ├── FetchProgress.java         ⭐ 采集进度（含 prefixSequenceList）
│   │   │   │   ├── EmuInstance.java           ⭐ 模拟器（含 autoRunning, nextAddAt）
│   │   │   │   └── ... (47 张表)
│   │   │   ├── security/                ← JWT 过滤器 + 工具
│   │   │   └── config/                  ← Spring 配置类
│   │   └── resources/
│   │       ├── application.yml                通用配置
│   │       ├── application-dev.yml           本地开发 DB=127.0.0.1, ddl-auto=update
│   │       ├── application-prod.yml           生产（DB_HOST 等用 env 变量，ddl-auto=validate）
│   │       └── logback-spring.xml
│   └── target/                           ← 构建产物 discord-admin-server-admin-0.1.3.jar
│
├── client-admin/              ✅ 前端（我负责）
│   ├── package.json                     ← version: 1.0.1
│   ├── vite.config.js
│   ├── src/
│   │   ├── api/
│   │   ├── views/
│   │   │   ├── EmulatorView.vue              ⭐ 好友管理主页面（模拟器列表+自动加好友）
│   │   │   ├── Guilds.vue                    服务器列表（含采集进度显示）
│   │   │   └── ...
│   │   └── ...
│   └── dist/                            ← 构建产物（部署到 /var/www/discord-admin/）
│
├── mumu-agent/                ✅ 设备端（我负责）
│   ├── agent.js                         ← 单文件核心逻辑 (2900+ 行)
│   ├── config.json                      ← 运行时配置（含 serverUrl）
│   ├── package.json                     ← version: v2.13.8
│   ├── node_modules/                    ← 依赖 (ws, uuid)
│   ├── start_mac.command
│   ├── start_win.bat
│   └── install-service.js              ← 注册为 Windows 服务
│
├── server/                            ← 旧后端（非本轮开发）
├── client-vue/                        ← 旧前端（非本轮开发）
└── sql/                               ← 数据库脚本
```

***

## 5. 数据库设计

### 5.1 连接信息

| 环境   | 地址            | 端口   | 库名           | 账号   | 密码        |
| ---- | ------------- | ---- | ------------ | ---- | --------- |
| 本地开发 | 127.0.0.1     | 3306 | discordadmin | root | Len2026!  |
| 生产   | 101.47.41.155 | 3306 | discordadmin | root | Dsdb2026! |

### 5.2 连接池参数

| 参数                | dev    | prod                    |
| ----------------- | ------ | ----------------------- |
| maximum-pool-size | 20     | 15                      |
| keepalive-time    | —      | 60000ms                 |
| ddl-auto          | update | **validate**（⚠️ 生产严格校验） |

### 5.3 关键表结构变化（近期）

| 表                | 变化                                                     | 日期         |
| ---------------- | ------------------------------------------------------ | ---------- |
| `fetch_progress` | 新增 `prefix_sequence_list` LONGTEXT 字段（JSON 存前缀执行顺序+状态） | 2026-09-01 |

### 5.4 核心表

| 表名                    | 用途          | 关键字段                                                                                                                   |
| --------------------- | ----------- | ---------------------------------------------------------------------------------------------------------------------- |
| `agents`              | 系统用户        | id, username, password, merchant\_id, user\_id, account\_type(0=admin,1=user)                                          |
| `agent_registrations` | Agent 设备注册  | id, merchant\_id, user\_id, **device\_id**, status(ONLINE/OFFLINE)                                                     |
| `emu_instances`       | 模拟器实例       | id, merchant\_id, user\_id, **device\_id**, instance\_index, status(RUNNING/STOPPED), auto\_running, **next\_add\_at** |
| `discord_accounts`    | Discord 账号  | id, merchant\_id, name, token, **token\_valid**, **token\_expires\_at**                                                |
| `guild_servers`       | Discord 服务器 | id, merchant\_id, discord\_account\_id, guild\_id, name, member\_count                                                 |
| `guild_members`       | 服务器成员       | id, guild\_server\_id, discord\_username, friend\_status                                                               |
| `fetch_progress`      | 采集进度        | id, guild\_server\_id, completed\_prefixes, resume\_frontier, **prefix\_sequence\_list**                               |

### 5.5 实体字段命名规范

- `merchant_id`：商户归属，**所有查询必须带此条件（除非平台管理员）**

- `user_id`：当前登录用户

- `device_id`：Agent 设备唯一标识（UUID），**模拟器操作的关键隔离字段**

- `instance_index`：模拟器编号（DB 存 1-based，MuMu CLI 用 0-based）

***

## 6. 生产环境信息汇总（⚠️ 换账号开发必读）

### 6.1 服务器清单

| 服务器     | IP              | OS           | SSH                       | 用途                                        |
| ------- | --------------- | ------------ | ------------------------- | ----------------------------------------- |
| **151** | `101.47.41.151` | Ubuntu 22.04 | root / `laeC7ooC7eif#aih` | server-admin 后端 + client-admin 前端 + Nginx |
| **155** | `101.47.41.155` | Ubuntu 22.04 | root / `laeC7ooC7eif#aih` | MySQL 8.0 discordadmin 数据库                |
| **149** | `101.47.41.149` | Ubuntu 22.04 | root / `laeC7ooC7eif#aih` | 旧 server 子项目 (port 8090)，crm-agent 代理管理页面 |

### 6.2 151 服务器部署结构

```
101.47.41.151
│
├── systemd
│   └── /etc/systemd/system/discord-admin.service
│       └── server-admin 后端进程，监听 :9090
│
├── 后端 jar
│   └── /opt/discord-admin/backend/
│       ├── discord-admin-server-admin-0.1.3.jar    ← pom.xml version 同步
│       └── mumu-agent/                              ← 遗留副本，不运行
│
├── 前端静态资源（nginx root）
│   └── /var/www/discord-admin/                       ← ⚠️ 不是 /opt/discord-admin/frontend/
│       ├── index.html
│       ├── assets/
│       └── workers/
│
├── Nginx
│   └── /etc/nginx/sites-enabled/discord-admin
│       ├── :80 → /var/www/discord-admin/ (SPA try_files)
│       ├── /api/ → 127.0.0.1:9090/api/
│       ├── /emu-api/ → 127.0.0.1:9090/api/emu/
│       ├── /ws/ → 127.0.0.1:9090/ws/
│       │     └── /ws/agent 是 mumu-agent 反向连入端口
│       └── /emu-ws/ → 127.0.0.1:9090/ws/emu/
│
├── 日志
│   └── /var/log/discord-admin/
│       ├── app.log                                    ← Spring Boot stdout
│       └── error.log                                  ← Spring Boot stderr
│
└── 上传
    └── /opt/discord-admin/uploads/
```

### 6.3 systemd 服务完整配置

```ini
[Unit]
Description=Discord Admin Backend (server-admin)
After=network.target mysql.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/discord-admin/backend

# ============ 环境变量 ============
Environment="SPRING_PROFILES_ACTIVE=prod"
Environment="DB_HOST=101.47.41.155"
Environment="DB_USER=root"
Environment="DB_PASSWORD=Dsdb2026!"
Environment="JWT_SECRET=discord-admin-prod-secret-2026-change-me-abcdef0123456789"
Environment="APP_BASE_URL=http://101.47.41.151:9090"
Environment="APP_UPLOAD_PATH=/opt/discord-admin/uploads"

# ============ JVM ============
ExecStart=/usr/bin/java \
  -Xms512m -Xmx1024m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/discord-admin/ \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -XX:+ParallelRefProcEnabled \
  -jar /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar

Restart=on-failure
RestartSec=5
TimeoutStartSec=180
LimitNOFILE=65536
StandardOutput=append:/var/log/discord-admin/app.log
StandardError=append:/var/log/discord-admin/error.log

[Install]
WantedBy=multi-user.target
```

***

## 7. 部署流程（⚠️ 实测可靠方式）

### 7.1 后端 server-admin

```bash
# === 1. 构建 ===
cd server-admin
mvn clean package -DskipTests
# 产物: target/discord-admin-server-admin-0.1.3.jar

# === 2. 备份生产 ===
ssh root@101.47.41.151
BAK=$(date +%Y%m%d%H%M%S)
cp /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar \
   /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar.bak$BAK

# === 3. 上传 ⚠️ 必须用 rsync 不能用 scp ===
# 因为 macOS scp 传 88MB+ jar 会随机截断损坏（MD5 不一致）
rsync -avz --progress \
  -e "/opt/homebrew/bin/sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no" \
  server-admin/target/discord-admin-server-admin-0.1.3.jar \
  root@101.47.41.151:/opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar

# === 4. 校验 MD5（必须） ===
# 本地
md5 server-admin/target/discord-admin-server-admin-0.1.3.jar
# 服务器
ssh root@101.47.41.151 "md5sum /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar"
# 两边必须完全一致

# === 5. 重启 ===
ssh root@101.47.41.151
chown root:root /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar
systemctl restart discord-admin
sleep 15
systemctl status discord-admin --no-pager
grep -iE "started|error|exception" /var/log/discord-admin/app.log | tail -5
# 期望看到: "Started DiscordAdminApplication in X.XXX seconds"

# === 6. 快速验证 ===
curl -s http://101.47.41.151/                 # 前端 HTTP 200
```

### 7.2 前端 client-admin

```bash
# 构建
cd client-admin
npm install
npm run build

# 上传（rsync + delete 清旧文件）
rsync -avz --delete \
  -e "/opt/homebrew/bin/sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no" \
  client-admin/dist/ \
  root@101.47.41.151:/var/www/discord-admin/
# 前端纯静态，不用重启 nginx
```

### 7.3 回滚

```bash
# 找最近的备份
ssh root@101.47.41.151
ls -lt /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar.bak* | head -1
# 回滚
cp /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar.bakYYYYMMDDHHMMSS \
   /opt/discord-admin/backend/discord-admin-server-admin-0.1.3.jar
systemctl restart discord-admin
```

### 7.4 ⚠️ 部署关键注意

| # | 注意                                                                                                                 |
| - | ------------------------------------------------------------------------------------------------------------------ |
| 1 | **pom.xml 版本号** 与 systemd 里 jar 文件名必须同步，改版本两处都要改                                                                   |
| 2 | **jar 命名**：server-admin 是 `discord-admin-server-admin-*.jar`，旧 server 子项目是 `discord-admin-server-*.jar`，**绝对不能搞混** |
| 3 | **ddl-auto: validate**（prod），DB schema 变更必须先 `ALTER TABLE` 再部署                                                     |
| 4 | **前端 nginx root** = `/var/www/discord-admin/`，**不是** `/opt/discord-admin/frontend/`（后者是旧冗余目录）                      |
| 5 | **大文件上传必须用 rsync**，scp 在 macOS → Ubuntu 传 88MB+ jar 会截断损坏                                                          |
| 6 | **JWT\_SECRET** 硬编码在 service 文件，改 secret 后所有前端需重新登录                                                                |
| 7 | **代理当前未启用**：`DISCORD_PROXY_ENABLED=false`，待 crm-agent 机器上装 tinyproxy 后开启                                           |

***

## 8. 近期修复汇总（接手必读）

### 8.1 数据隔离修复（2026-08-28 \~ 08-30）

| 问题                                             | 根因                              | 修复                           |
| ---------------------------------------------- | ------------------------------- | ---------------------------- |
| yfyadmin 账号看到其他商户服务器                           | JWT 中 merchantId 丢失 → 被误判为平台管理员 | 各 Controller 增加平台管理员 DB 二次校验 |
| saveServer 用 payload merchantId 兜底             | 商户管理员应严格用当前用户 merchantId        | 移除 fallback 逻辑               |
| listAccountOptions merchantId=null 兜底          | 查不到账号显示空列表才对                    | 移除兜底                         |
| GuildMembersController resolveVisibleServerIds | 平台管理员未正确豁免                      | 增加平台管理员分支查全部                 |

### 8.2 采集任务修复（2026-09-01）

| 问题             | 根因                                       | 修复                                                                               |
| -------------- | ---------------------------------------- | -------------------------------------------------------------------------------- |
| 一点采集就提示完成      | 上次失败但 completedPrefixes 被存为全部 38 个前缀     | 增加 `completedPrefixes.size() >= 38` 或 `requestCount == 0` 视为假完成，清空进度             |
| 采集窗口"总采集数"显示错误 | 前端绑定 maxMembers（已采集数）而非 maxRequests（请求数） | 改前端绑定为 maxRequests                                                               |
| 采集间隔太频繁        | 默认 10 秒太激进                               | 改默认 60 秒，最小 10 秒，UI 隐藏                                                           |
| 前缀序列清单（新功能）    | 无                                        | FetchProgress 新增 `prefix_sequence_list` 字段，GatewayMemberFetcher 构建有序前缀清单，续传按清单顺序 |

### 8.3 模拟器 / 自动加好友修复（2026-08-30 \~ 09-02）

| 问题                            | 根因                                                     | 修复                                                    |
| ----------------------------- | ------------------------------------------------------ | ----------------------------------------------------- |
| 模拟器启动 merchantId/userId=null  | 后台线程无 SecurityContext                                  | startInstance 增加 deviceId+instanceIndex 作为补充查询条件      |
| 好友管理删除服务器报错                   | Hibernate DeleteEvent 类加载失败 NoClassDefFoundError       | EmuServerBindingService.removeServer 改用原生 SQL DELETE  |
| "下次添加时间"不显示                   | auto\_running 在任务失败/号池空时被错误重置为 0                       | 移除所有调用 stopAutoRunning 的错误逻辑                          |
| formatCountdown 过期显示 00:00:00 | 前端对过期时间显示错误                                            | 过期改为显示"即将开始"                                          |
| **全部停止后下次添加时间不清空 + 模拟器不关**    | stopAllAutoAdd 只改 autoRunning=false，未清 nextAddAt、未停模拟器 | 后端改：清空 nextAddAt、设 status=STOPPED、Agent 发 BATCH\_STOP |

### 8.4 最近部署内容（2026-09-02）

**git commit 历史（与本项目相关）：**

```
adb009c  fix: start/stop/restart 不再错误重置 autoRunning=false
5d32d4e  fix: start/stop/restart 不再错误重置 autoRunning=false
369f7b4  fix: Enter 发送重复 — 删除全局 handleGlobalKeydown 中的 Enter 发送
b688440  fix: 登录页去掉默认账号密码（client-vue + client-admin）
ed4688f  feat: 服务器列表所属账号列显示 token 有效状态
```

***

## 9. 本地开发环境

### 9.1 前置要求

| 依赖      | 版本   | 安装                                             |
| ------- | ---- | ---------------------------------------------- |
| JDK     | 17+  | `brew install openjdk@17`                      |
| Node.js | 18+  | `brew install node`                            |
| Maven   | 3.8+ | 随 Spring Boot 推荐版                              |
| MySQL   | 8.0  | 本地 127.0.0.1:3306，库 discordadmin，root/Len2026! |

### 9.2 启动顺序

```bash
# 1. 后端
cd server-admin
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 监听 :9090
# dev profile: ddl-auto=update, DB=127.0.0.1:3306/discordadmin

# 2. 前端
cd client-admin
npm install     # 首次
npm run dev
# 监听 :5175（Vite 配置里 proxy /api → :9090）

# 3. mumu-agent（Windows 云电脑）
# config.json 里 serverUrl 改成 ws://[你的mac本地IP]:9090/ws/agent
# 然后 node agent.js
```

### 9.3 开发验证规范

1. 改完代码必须**重启后端**（Spring Boot devtools 可能自动重载，但生产级改动建议手动重启）
2. 用 **curl** 验证后端 API
3. 每次改完批量改几处再统一编译
4. 改动后立即 `git add + git commit` 保持工作区干净
5. **绝对不要自己执行 git push**（等用户通知）

***

## 10. 常见问题速查

| 你想做…                   | 去这里看                                                                               |
| ---------------------- | ---------------------------------------------------------------------------------- |
| 改登录/JWT                | `server-admin/.../security/JwtUtil.java`, `JwtAuthFilter.java`                     |
| 改 RBAC 权限              | `server-admin/.../controller/AuthController.java`                                  |
| 改模拟器 start/stop/create | `server-admin/.../service/EmuInstanceService.java`                                 |
| 改自动加好友调度/候选过滤          | `server-admin/.../service/EmuAutoAddDispatcher.java`                               |
| 改采集进度/前缀清单             | `server-admin/.../service/DiscordMemberService.java`, `GatewayMemberFetcher.java`  |
| 改 agent WebSocket 通信   | `server-admin/.../service/CloudWebSocketService.java`, `mumu-agent/agent.js`       |
| 改服务器列表隔离逻辑             | `server-admin/.../service/EmuServerBindingService.java`                            |
| 改前端模拟器列表/下次添加时间显示      | `client-admin/src/views/EmulatorView.vue`                                          |
| 改采集进度显示                | `client-admin/src/views/Guilds.vue`                                                |
| 改心跳超时                  | `server-admin/.../service/CloudWebSocketService.java`（HEARTBEAT\_TIMEOUT\_SECONDS） |
| 查实体字段                  | `server-admin/.../entity/*.java`（全部 @Column 都有含义）                                  |
| 部署后端                   | 见第 7 节                                                                             |

***

## 11. 已知问题 & 待办

| 状态          | 问题                                       | 说明                                                                     |
| ----------- | ---------------------------------------- | ---------------------------------------------------------------------- |
| 🟡 **待实施**  | crm-agent 代理：让 151 采集流量走 crm-agent 机器    | 在 crm-agent 机器上装 tinyproxy，server-admin 加 DISCORD\_PROXY\_ENABLED=true |
| 🟡 **待实施**  | Discord 风控：149/151 机房 IP 容易触发冻结          | 同上，统一出口 IP                                                             |
| 🟡 **待验证**  | 采集任务修复端到端验证                              | 需要有效的 Discord USER token 才能测试 Gateway 连接                               |
| 🟡 **待验证**  | 前缀序列清单功能端到端                              | 需实际跑一轮采集                                                               |
| 🔵 **TODO** | server-admin 的 GatewayMemberFetcher 代理支持 | 代码已实现 HTTP CONNECT 探测，配置 `DISCORD_PROXY_HOST/PORT` 即可启用                |
| 🔵 TODO     | Docker 化部署                               | systemd 运维够用                                                           |
| 🔵 TODO     | 接口文档自动生成                                 | springdoc-openapi 未接入                                                  |

***

## 12. 快速上手 Checklist

换账号接手，请按此清单：

- [ ] 读本文档（你正在做的事 ✅）

- [ ] 确认 JDK 17 / Node 18+ / MySQL 8.0 环境

- [ ] 本地创建数据库 `discordadmin`（dev profile ddl-auto=update 自动建表）

- [ ] `cd server-admin && mvn spring-boot:run -Dspring-boot.run.profiles=dev`

- [ ] `cd client-admin && npm install && npm run dev`

- [ ] 访问 <http://localhost:5175> 登录（默认账号 merchantadmin/admin123）

- [ ] curl 验证后端：`curl -X POST http://localhost:9090/api/auth/login -H "Content-Type: application/json" -d '{"username":"merchantadmin","password":"admin123"}'`

- [ ] 确认生产环境 101.47.41.151 的 systemd 服务还在正常运行

- [ ] 部署后端用 **rsync**（不是 scp）+ MD5 校验

- [ ] 前端部署到 `/var/www/discord-admin/`（不是 `/opt/discord-admin/frontend/`）

