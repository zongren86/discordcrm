# 项目开发记忆

## 关键规则（必须严格遵守）
1. **禁止随意 `git checkout` 覆盖本地文件**：未经用户明确确认，不得执行 `git checkout HEAD -- <file>` 等操作覆盖工作目录的修改。这会导致未提交的功能丢失。
2. **Git 提交前必须确认**：所有功能修改完成并验证通过后，才能提交 git。
3. **功能修改必须记录**：每次修改哪些文件、增加什么功能，要在修改前明确。

## 已实现的功能清单（前端 Chat.vue）
1. **GIF 统一入口**：左侧 GIF 按钮保留，右侧 Sticker 按钮已移除
2. **Sticker/Lottie 渲染**：支持 Lottie 动画（formatType=3）和普通 Sticker 图片
3. **GIF/Sticker 收藏分离**：后端 GifFavorite 实体添加 type 字段（gif/sticker），前端分别存储
4. **文件多选发送**：支持图片、视频、文档等多文件上传
5. **翻译预览**：发送前预览翻译结果，调用真实翻译 API
6. **好友状态筛选**：在线/闲置/请勿打扰/离线
7. **账号失效标记**：token 失效账号显示"(失效)"
8. **图片附件渲染**：消息中的图片附件直接显示
9. **Lottie 收藏功能**：Sticker 消息支持收藏/取消收藏/下载

## 已实现的后端功能
1. **GifFavorite 实体**：添加 type 字段区分 GIF 和 Sticker
2. **GifFavoriteController**：listFavorites/addFavorite 支持 type 参数
3. **MessageService**：sendGifMessage 使用 DTO 而非原始实体广播
4. **ConversationController**：新增 /translate-text 接口
5. **Message 实体**：conversation、senderAgent 添加 @JsonIgnore
6. **Conversation 实体**：discordUser、discordAccount、assignedAgent 添加 @JsonIgnore

## 数据库表结构
- `gif_favorites` 表：已添加 `type` 列（varchar(32)，默认 'gif'）
- `message` 表：已添加 `sticker_items_json` 列

## 服务信息
- 后端：Spring Boot，端口 8090
- 前端：Vue 3 + Vite + Element Plus，端口 5173
- 数据库：MySQL（jdbc:mysql://127.0.0.1:3306/discordadmin）

## 历史教训
- 2026-08-23：执行 `git checkout HEAD -- Chat.vue` 导致所有未提交功能丢失，花费大量时间重新实现
- **绝对禁止**在未确认的情况下用 git 命令覆盖工作目录文件
- 正确做法：使用 git stash 保存工作，或手动备份后再操作

## Agent 职责边界（2026-09-04 明确）

### 本 Agent 负责（main-temp-2 分支）
- `discord-src/discord/server/` — 后端核心 Spring Boot
- `discord-src/discord/client-vue/` — 聊天前端 Vue 3
- `discord-src/discord/crm_agent/` — 模拟器代理 Node.js

### 打包输出路径
- 统一输出到 `discord-src/discord/deploy-package/crm/`
- 目录尚不存在，首次打包时创建

### 其他 agent 负责
- `discord-src/discord/server-admin/` — 管理后端（另一 agent）
- `discord-src/discord/client-admin/` — 管理前端（另一 agent）
- `discord-src/discord/mumu-agent/` — MuMu 模拟器对接（独立）

### Git 分支策略
- 本 agent 工作分支：`main-temp-2`
- 远程主分支：`main`
- 禁止自动 git push；危险操作先输出 git status 让用户确认
- 每次改动完成立即 git add + git commit，保持工作区干净
