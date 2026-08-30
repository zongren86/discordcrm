---
name: "backend-dev-guidelines"
description: "Backend development error prevention checklist. Invoke before writing/editing Java Spring Boot code, after fixing bugs, and before restarting backend services."
---

# 后端开发错误预防指南

本技能记录项目开发中反复犯的错误、根因和解决方案。每次涉及后端代码编写、修改、编译或服务启动时，**必须逐条检查**。

---

## 一、Java 代码硬规则（违反必编译失败）

### 1. 字符串必须用双引号，内部 JSON 要转义
❌ 错误：`task.setResult("{'error':'x'}")`  ← Python 式单引号
❌ 错误：`task.setResult("{\"error\":\"x\"}")`  ← 在 Python 字符串替换里写的，反斜杠被 Python 吞了变成 `"{"error":"x"}"`，Java 编译器看到双引号就报错
✅ 正确：`task.setResult("{\"error\":\"x\"}");`  ← 直接在 Java 文件里写，或用 Python 时写四层反斜杠 `\\\\\"`

**安全做法**：用 `ObjectMapper.writeValueAsString()` 序列化 Map，不要手写 JSON 字符串。

### 2. Python 脚本编辑 Java 文件时的陷阱
- Python 的 `\"` 在 triple-quoted string 里就是字面上的 `\` + `"`
- Python 的 `"{"error":"x"}"` 里的双引号会让 Java 编译器认为字符串提前结束
- 写 Python 脚本时，Java 里的双引号 → Python 字符串里用 `\\\"` 表示（Java 看到 `\"`，Python 实际输出 `\"`）
- **更安全**：直接用 sed/perl/awk，不要用 Python 字符串拼接 Java 代码

### 3. Spring Data JPA Repository 方法加 @Query 的位置
❌ 错误：把新方法插在 `@Query` 和原方法之间
```java
// 错误！@Query 贴到了新方法上
@Query("SELECT ...")
List<DiscordAccount> findByXxx(...);    // ← 新方法插在这里，@Query 被它"截留"
List<DiscordAccount> findForTokenHealthCheck();  // ← 原方法失去 @Query，被当派生查询解析 → 崩溃
```
✅ 正确：**新方法一律加在文件末尾**，不要插在已有 `@Query` + 方法对之间。

**检查手段**：改完 Repository 末尾后，手动 `tail -20 file.java` 确认每个 `@Query` 紧贴对应方法。

---

## 二、事务陷阱（静默失败，最难排查）

### 4. 异步任务创建方法必须用 REQUIRES_NEW
**场景**：Service A 有 `@Transactional`，内部调用 `createTask()` 创建一个 agent task，然后 `waitForTaskResult(timeoutMs)` 等待。如果超时抛异常，外层事务回滚 → **task 也被回滚删除** → agent poll 永远找不到任务。

❌ 错误：
```java
// MessageService.sendReply() 有 @Transactional
agentTaskService.createTask(serverId, "SEND_MESSAGE", params);  // 默认 REQUIRED，加入外层事务
agentTaskService.waitForTaskResult(taskId, 15000);  // 超时 → 外层回滚 → task 被删除！
```

✅ 正确：
```java
// AgentTaskService.createTask()
@Transactional(propagation = Propagation.REQUIRES_NEW)  // 独立事务，不受外层回滚影响
public AgentTask createTask(...) { ... }

// 同时：waitForTaskResult 超时后把 task 标记为 CANCELLED
// 防止 agent 后来 poll 到这个"孤儿 task"又执行一遍
```

**识别特征**：
- 后端日志打印了"创建 task id=XX"
- 但 MySQL 里 `SELECT * FROM agent_tasks WHERE id=XX` 返回空
- 时间线吻合：task 创建时间 == 外层方法开始时间 → 外层异常时间 == task 消失时间

### 5. 默认 `@Transactional(rollbackFor = Exception.class)`
Spring 默认只回滚 RuntimeException，检查型异常不回滚。本项目所有 Service 的 `@Transactional` 都应该加 `rollbackFor = Exception.class`。

---

## 三、后端启动验证标准

### 6. 不要只看端口监听
❌ 错误标准：`lsof -i :8090` 有 LISTEN → 认为后端启动成功
✅ 正确标准：**三者必须同时满足**：
1. `curl -s -o /dev/null -w "%{http_code}" /api/...` 返回非 000（可以是 401/404 但不能是 Connection refused）
2. `tail -30 /tmp/server.log` 最后没有 `BeanCreationException` / `QueryCreationException` / `UnsatisfiedDependencyException`
3. Hibernate SQL 日志正常输出（说明 EntityManagerFactory 初始化成功）

Spring Boot DevTools 崩溃后会自动重启，端口可能短暂监听但 Bean 初始化失败——**必须查日志**。

### 7. IPv4 绑定
macOS + Node.js 默认解析 `localhost` 为 IPv6（::1），局域网 IPv4 访问会失败。
- Vite dev 脚本加 `--host 0.0.0.0`
- Spring Boot `application.yml` 加 `server.address: 0.0.0.0`

---

## 四、功能闭环 Checklist

每实现一个新功能，**必须逐条确认**：

- [ ] 函数/方法写了 → 在 main()/init()/controller 里**注册调用**了吗？（不要当死代码）
- [ ] 后端改了 → 前端页面/按钮/弹窗**对接**了吗？
- [ ] crm_agent 源码改了 → **版本号 +1**（config.json）→ **重新 zip** 覆盖 `server/src/main/resources/agent-package.zip`
- [ ] 数据库 schema 改了 → 实体类加了新字段/注解吗？`mvn compile` 通过吗？
- [ ] Entity 加了字段 → Repository 加了查询方法吗？Service 里用到了吗？
- [ ] agent 改了 → **通知用户更新 Windows agent 包**

### 8. 不要假设第三方实现
Discord、Telegram 等产品迭代快：
- 不要假设 token 存在 localStorage → 实际上新版用 IndexedDB + localStorage + sessionStorage
- 不要假设某个 REST API 路径不变 → 先 curl 验证
- 涉及第三方存储/API 时，先 Playwright/HTTP 验证实际行为，再写代码

---

## 五、网络 & 连接问题

### 9. 全局代理劫持
用户电脑开了 Clash/Surge 等全局代理，内网 IP 请求被劫持到代理服务器 → 局域网访问 502。
**排查**：ping 内网 IP 正常但 HTTP 不通 → 让用户关代理或加直连规则。

### 10. JWT 过滤器路径配置
agent 心跳/poll/report 请求带 token 走自定义 header（不是 JWT），需要在 SecurityConfig 里放行 `/api/agent-servers/**`，或者在 filter 里对这些路径做特殊处理。

---

## 六、Bug 排查方法论

### 11. 消息收不到/发不出，全链路排查
```
前端发消息 → 后端 Controller → Service 路由条件（source == "AGENT"？agentServerId != null？）
  → createTask（事务？REQUIRES_NEW？task 在 DB 吗？）
  → agent poll（token 对吗？pollNext 查到 task 了吗？）
  → agent execute（SEND_MESSAGE case 写了吗？axios 请求发了吗？）
  → reportTask（回传了 discordMessageId 吗？）
  → 后端 waitForTaskResult（拿到 result 了吗？超时了吗？）
  → 消息入库 → 前端 WebSocket 推送
```

### 12. agent 收消息不工作
检查 `main()` 里有没有注册 `setInterval(pollMessages, ...)`——**函数写了但没注册 = 死代码**。

---

## 七、Agent 版本管理

### 13. crm_agent 版本号规则
- 每次修改 crm_agent 源码（哪怕一行），config.json 里的 `version` 必须 +0.0.1
- 打包命令：`cd crm_agent && rm -f ../server/src/main/resources/agent-package.zip && zip -r ../server/src/main/resources/agent-package.zip . -x "node_modules/*" -x ".git/*"`
- 后端会自动把 zip 打包到 jar 里，前端下载接口直接返回这个 zip
- 通知用户时必须说明"请更新 agent 包到 vX.X.X"

---

## 触发条件

在以下场景 **必须调用本技能**：

1. 编写或修改 Java Spring Boot 后端代码时
2. 修改 Spring Data JPA Repository 文件时
3. 修改 @Transactional 相关代码时
4. 编写 Python/shell 脚本修改 Java 文件时
5. 后端启动前 / 启动后验证时
6. 新增 agent 功能（发消息、收消息、唤起浏览器等）时
7. 修复 bug 后自查时
