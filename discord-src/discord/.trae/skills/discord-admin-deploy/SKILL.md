---
name: "discord-admin-deploy"
description: "Discord Admin 生产部署：后端 jar + 前端静态文件分两步部署到 101.47.41.149。Invoke when user asks to deploy, 部署, 发布, 或更新生产环境。"
---

# Discord Admin 生产部署

## 基础设施

| 组件 | 值 |
|---|---|
| **应用服务器** | `101.47.41.149` (Ubuntu 22.04, Java 17, Nginx) |
| **SSH** | `root` / `laeC7ooC7eif#aih` |
| **数据库** | `101.47.41.155:3306` / `root` / `Dsdb2026!` / `discordadmin` |
| **本地开发库** | `localhost:3306` / `root` / `Len2066!` / `discordadmin` |
| **systemd 服务** | `discord-admin` |
| **后端端口** | 8090（Nginx 80 反代 `/api/` 和 `/ws`） |

## 项目源码根

```
discord-src/discord/
├── server/       # Spring Boot 后端 (mvn package → target/discord-admin-server-0.1.0.jar)
├── client-vue/   # Vue 3 + Vite 前端 (npm run build → dist/)
├── client-admin/ # 另一个前端（如有需要单独部署）
├── server-admin/  # 另一个后端（如有需要单独部署）
├── crm_agent/    # 独立模块，不在本 skill 部署范围
└── sql/          # SQL 脚本
```

## 关键部署目录（两套独立！不要搞混！）

| 组件 | 服务器路径 | 说明 |
|---|---|---|
| **后端 jar** | `/opt/discord-admin/current/discord-admin-server-0.1.0.jar` | systemd 直接启动此 jar |
| **后端版本备份** | `/opt/discord-admin/releases/YYYYMMDD-HHMMSS/` | 每次部署建议 cp 一份旧 jar |
| **前端静态** | `/var/www/discord-admin/current/` | **Nginx 直接读取！不读 jar 里的 static！** |
| **前端 assets** | `/var/www/discord-admin/current/assets/` | Vite 懒加载 chunk 都在这里 |
| **日志** | `/var/log/discord-admin/discord-admin.log` | 业务日志 |
| **Nginx 配置** | `/etc/nginx/sites-available/discord-admin` | 已启用 |

## systemd 环境变量

```
SPRING_PROFILES_ACTIVE=prod
DB_HOST=101.47.41.155       ← 注意不是 149！
DB_PORT=3306
DB_NAME=discordadmin
DB_USERNAME=root
DB_PASSWORD=Dsdb2026!
JWT_SECRET=discord-admin-prod-jwt-secret-2026-change-me-32chars!!
APP_BASE_URL=http://101.47.41.149:8090
JAVA_OPTS=-Xms512m -Xmx2048m -XX:+UseG1GC ...
```

## 部署流程（**后端改了 + 前端改了 = 两步都要做**）

### Step 1: 后端 jar 更新

```bash
# 本地：编译打包（必须 clean，防止旧 class/资源残留）
cd <项目根>/server
mvn clean package -DskipTests

# 服务器备份旧版本（可选但推荐）
ssh root@101.47.41.149 "cp /opt/discord-admin/current/discord-admin-server-0.1.0.jar /opt/discord-admin/releases/\$(date +%Y%m%d-%H%M%S).jar"

# 上传新 jar
scp target/discord-admin-server-0.1.0.jar root@101.47.41.149:/opt/discord-admin/current/

# 重启（先杀旧进程防端口冲突）
ssh root@101.47.41.149 "pkill -f 'discord-admin.*jar' 2>/dev/null; sleep 2; systemctl start discord-admin"

# 等待启动 + 验证
sleep 15
ssh root@101.47.41.149 "systemctl is-active discord-admin"
curl -s http://101.47.41.149:8090/api/auth/ping    # 期望 {"ok":true}
```

### Step 2: 前端静态文件更新

```bash
# 本地：clean 构建（必须 rm -rf dist，Vite 会生成带 hash 的新 chunk，旧的要清理）
cd <项目根>/client-vue
rm -rf dist
npm run build

# 上传整个 dist 内容
scp -r dist/* root@101.47.41.149:/var/www/discord-admin/current/

# 清理服务器上旧的 orphan chunk（重要！否则浏览器可能继续引用旧 chunk）
ssh root@101.47.41.149 "
  cd /var/www/discord-admin/current
  ls assets/*.js
"

# 验证：Nginx 返回的 index.html 引用了新 chunk
curl -s http://101.47.41.149/ | grep 'assets/index-.*\.js'
```

### Step 3: 端到端验证

```bash
# 1. 后端健康检查
curl http://101.47.41.149:8090/api/auth/ping

# 2. Nginx 反代正常
curl http://101.47.41.149/api/auth/ping   # 应该和上面一样

# 3. 前端页面能打开
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://101.47.41.149/

# 4. 看实时日志（部署后立刻 tail，看有没有报错）
ssh root@101.47.41.149 "tail -f /var/log/discord-admin/discord-admin.log"
```

## 操作数据库（生产环境）

```bash
# 连接生产 DB
mysql -h 101.47.41.155 -P 3306 -u root -p'Dsdb2026!' discordadmin

# 常用查询
mysql -h 101.47.41.155 -u root -p'Dsdb2026!' discordadmin -e "SELECT id,username,accountType,merchantId FROM user;"
```

## 应急操作

```bash
# 重启后端
ssh root@101.47.41.149 "systemctl restart discord-admin"

# 查看实时日志
ssh root@101.47.41.149 "tail -f /var/log/discord-admin/discord-admin.log"

# 查看最近错误
ssh root@101.47.41.149 "tail -100 /var/log/discord-admin/discord-admin-error.log"

# 回滚后端（用 releases 里的旧 jar）
ssh root@101.47.41.149 "cp /opt/discord-admin/releases/YYYYMMDD-HHMMSS/xxx.jar /opt/discord-admin/current/discord-admin-server-0.1.0.jar && systemctl restart discord-admin"

# Nginx 重载（改了 nginx 配置后）
ssh root@101.47.41.149 "nginx -t && systemctl reload nginx"

# 临时关闭前端缓存（调试用）
ssh root@101.47.41.149 "sed -i 's/add_header Cache-Control \"public, immutable\";/add_header Cache-Control \"no-cache\";/' /etc/nginx/sites-available/discord-admin && nginx -t && systemctl reload nginx"
```

## 踩过的坑（必须记住）

| # | 坑 | 现象 | 正确做法 |
|---|---|---|---|
| 1 | **只更新 jar 不更新前端** | 改了 Vue 代码生产不生效 | 前端改了必须单独 `scp dist/*` 到 `/var/www/discord-admin/current/` |
| 2 | **旧 chunk 残留** | Vite 每次打包生成新 hash 的 js，但服务器上旧的还在；浏览器缓存命中旧 chunk | 前端构建前 `rm -rf dist`，部署后清理服务器上所有旧 chunk |
| 3 | **浏览器缓存** | Cmd+R 刷新还是老代码 | `Cmd+Shift+R` 硬刷新；或临时把 nginx 的 `Cache-Control` 改成 `no-cache` |
| 4 | **DB_HOST 写错** | systemd 里是 `101.47.41.155`，不是 149 | 本地开发用 localhost，生产用 155 |
| 5 | **端口冲突** | 重启 systemd 时旧进程还占着 8090 | 先 `pkill -f 'discord-admin.*jar' && sleep 2` 再 start |
| 6 | **mvn 没 clean** | 旧 class 文件混进新 jar | 每次打包必须 `mvn clean package` |
| 7 | **systemd 环境变量不生效** | 改了 service 文件但没 daemon-reload | `systemctl daemon-reload && systemctl restart discord-admin` |

## 使用 sshpass

本项目用 sshpass 做非交互式 SSH/SCP。命令前加 `sshpass -p '密码'`：

```bash
sshpass -p 'laeC7ooC7eif#aih' ssh -o StrictHostKeyChecking=no root@101.47.41.149 "command"
sshpass -p 'laeC7ooC7eif#aih' scp file root@101.47.41.149:/path/
```
