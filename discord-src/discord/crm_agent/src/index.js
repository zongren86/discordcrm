const { http, cfg } = require('./http');
const { discordHttp } = require('./discord');
const { captureDiscordAccount, launchBrowserOnly } = require('./browser');
const fs = require('fs');
const path = require('path');

const AGENT_VERSION = cfg.version || 'unknown';

console.log(`[启动] crm_agent v${AGENT_VERSION}`);
console.log(`[配置] serverUrl=${cfg.serverUrl}  agentName=${cfg.agentName || '(未命名)'}`);

let heartbeatOk = false;
let busy = false;
let currentTaskId = null;

async function heartbeat() {
  try {
    await http.post('/agent-servers/heartbeat', {
      token: cfg.token,
      name: cfg.agentName,
      serverAddress: cfg.publicAddress || '',
      nodeVersion: process.version,
      browserType: (cfg.browser && cfg.browser.type) || 'chromium',
    });
    if (!heartbeatOk) {
      heartbeatOk = true;
      console.log('[心跳] ✅ 注册成功，节点已上线');
    }
  } catch (e) {
    const status = e.response?.status;
    if (status === 401) console.error('[心跳] ❌ Token 无效！');
    else console.error('[心跳] ❌ 失败:', status || e.message);
  }
}

async function pollTask() {
  if (busy) return;
  try {
    const task = await http.post('/agent-servers/tasks/poll', {
      token: cfg.token,
      agentName: cfg.agentName,
    });
    if (task && task.id) {
      console.log(`[任务] 收到任务 id=${task.id}  type=${task.type}`);
      busy = true;
      currentTaskId = task.id;
      await executeTask(task);
      busy = false;
      currentTaskId = null;
    }
  } catch (e) {
    const status = e.response?.status;
    if (status === 404) {} // 无任务，静默
    else if (status === 401) console.error('[轮询] ❌ Token 无效');
    else console.error('[轮询] 异常:', status || e.message);
  }
}

async function reportTask(taskId, status, result = null) {
  try {
    await http.post('/agent-servers/tasks/report', {
      token: cfg.token,
      taskId, status, result,
    });
  } catch (e) {
    console.error('[回传] 失败:', e.response?.status || e.message);
  }
}

async function executeTask(task) {
  try {
    switch (task.type) {
      case 'CAPTURE_DISCORD_ACCOUNT': {
        await reportTask(task.id, 'RUNNING');
        try {
          const result = await captureDiscordAccount(cfg.browser || {}, {
            taskId: task.id,
            http: http,
            agentName: cfg.agentName,
          });
          // 带 browserProfilePath 回传给后端
          const payload = {
            discordId: result.userId,
            username: result.username,
            email: result.email,
            token: result.token,
            avatarUrl: result.avatarUrl,
            browserProfilePath: result.browserProfilePath,
          };
          await reportTask(task.id, 'SUCCESS', payload);
          console.log(`[任务] ✅ 完成 — 已保存 ${result.username}`);
          // 保存确认后再关浏览器
          console.log('[Browser] 浏览器将在 3 秒后自动关闭...');
        } catch (err) {
          if (err.code === 'CANCELLED') {
            await reportTask(task.id, 'CANCELLED');
            console.log('[任务] 已取消');
          } else if (err.code === 'BROWSER_CLOSED') {
            await reportTask(task.id, 'FAILED', { error: '用户关闭了浏览器' });
            console.log('[任务] 用户主动关闭浏览器');
          } else {
            await reportTask(task.id, 'FAILED', { error: err.message });
            console.error('[任务] 执行失败:', err.message);
          }
        }
        break;
      }

      case 'LAUNCH_BROWSER': {
        const params = typeof task.params === 'string'
          ? (() => { try { return JSON.parse(task.params); } catch { return {}; } })()
          : (task.params || {});
        const profilePath = params.browserProfilePath || params.profilePath ||
          (params.accountId ? (await getAccountProfilePath(params.accountId)) : null);

        if (!profilePath) {
          await reportTask(task.id, 'FAILED', { error: '缺少浏览器 profile 路径' });
          break;
        }
        await reportTask(task.id, 'RUNNING');
        try {
          const { context } = await launchBrowserOnly(profilePath, cfg.browser || {});
          console.log('[任务] ✅ 浏览器已打开');
          // 立即报告 SUCCESS + 释放 busy，让 pollTask 能继续 poll 新任务（如 SEND_MESSAGE）
          // 浏览器关闭监听放后台异步跑，不阻塞主循环
          reportTask(task.id, 'SUCCESS').catch(() => {});
          (async () => {
            try {
              await new Promise(resolve => context.on('close', resolve));
              console.log('[Browser] 浏览器已关闭');
            } catch {}
          })();
          break;
        } catch (err) {
          await reportTask(task.id, 'FAILED', { error: err.message });
          console.error('[任务] 唤起浏览器失败:', err.message);
        }
        break;
      }

      case 'SEND_MESSAGE': {
        const params = typeof task.params === 'string'
          ? (() => { try { return JSON.parse(task.params); } catch { return {}; } })()
          : (task.params || {});
        const { token, channelId, content, stickerIds, sticker_id } = params;
        if (!token || !channelId) {
          await reportTask(task.id, 'FAILED', { error: '缺少 token 或 channelId' });
          break;
        }
        await reportTask(task.id, 'RUNNING');
        try {
          // 构建请求体：支持纯文本、Sticker、混合发送
          const body = {};
          if (stickerIds && Array.isArray(stickerIds) && stickerIds.length > 0) {
            body.sticker_ids = stickerIds;
            if (content) body.content = content;
            console.log(`[任务] 发送 Sticker: sticker_ids=[${stickerIds.join(',')}]`);
          } else if (sticker_id) {
            body.sticker_ids = [sticker_id];
            if (content) body.content = content;
            console.log(`[任务] 发送 Sticker: sticker_id=${sticker_id}`);
          } else {
            body.content = content || '';
          }
          // 从 agent 机器发 Discord API 请求 —— IP 是用户家庭宽带，不会触发风控
          const resp = await discordHttp.post(
            `/channels/${channelId}/messages`,
            body,
            { headers: { 'Authorization': token } }
          );
          const discordMessageId = resp.data?.id;
          console.log(`[任务] ✅ 消息已发送 discordMsgId=${discordMessageId}`);
          await reportTask(task.id, 'SUCCESS', {
            discordMessageId,
            channelId,
          });
        } catch (err) {
          const status = err.response?.status;
          const msg = status === 401 ? 'Token 已失效（401 Unauthorized）'
                    : status === 403 ? '没有权限在此频道发消息（403 Forbidden）'
                    : status === 429 ? 'Discord 限流（429 Too Many Requests）'
                    : (err.response?.data?.message || err.message);
          console.error(`[任务] 发消息失败: ${msg}`);
          await reportTask(task.id, 'FAILED', { error: msg, status });
        }
        break;
      }

      default:
        console.warn(`[任务] 未知类型: ${task.type}`);
        await reportTask(task.id, 'FAILED', { error: '未知任务类型: ' + task.type });
    }
  } catch (err) {
    console.error('[任务] 严重错误:', err.message);
    try { await reportTask(task.id, 'FAILED', { error: err.message }); } catch {}
  }
}

/** 临时方案：通过 accountId 查 DiscordAccount 拿 profilePath */
async function getAccountProfilePath(accountId) {
  try {
    const resp = await http.get('/discord-accounts/' + accountId);
    return resp?.browserProfilePath || resp?.browser_profile_path || null;
  } catch { return null; }
}

async function main() {
  await heartbeat();
  setInterval(heartbeat, cfg.heartbeatIntervalMs || 5000);
  setInterval(pollTask, cfg.pollIntervalMs || 2000);

  // 消息轮询 (方案 C: HTTP REST, 2s 间隔)
  await loadManagedAccounts();
  setInterval(loadManagedAccounts, ACCOUNT_REFRESH_MS);
  setInterval(pollMessages, MSG_POLL_MS);

  console.log('[就绪] 等待任务...');

  process.on('SIGINT', () => {
    console.log('[退出] 收到信号');
    process.exit(0);
  });
}

main().catch(err => {
  console.error('[启动] 致命错误:', err.message);
  process.exit(1);
});

// ============ AGENT PULL MESSAGES (方案 C: HTTP REST 轮询) ============

// agent 负责的 AGENT 采集账号列表
let managedAccounts = [];
// 每个账号已见消息 ID 集合: Map<accountId, Set<discordMessageId>>
const seenMessageIds = new Map();
const ACCOUNT_REFRESH_MS = 30000;
const MSG_POLL_MS = 5000;   // 消息轮询 5s，分层采样 + 并发 25 支撑 ~450 账号

// 已经执行过首次历史补拉的账号 ID 集合
const backfilledAccounts = new Set();

async function loadManagedAccounts() {
  try {
    const accounts = await http.post('/agent-servers/accounts', { token: cfg.token });
    managedAccounts = accounts || [];
    if (managedAccounts.length > 0) {
      console.log(`[消息轮询] 负责 ${managedAccounts.length} 个 AGENT 采集账号`);
      // 对新账号执行首次历史补拉
      for (const acc of managedAccounts) {
        if (!backfilledAccounts.has(acc.id)) {
          backfilledAccounts.add(acc.id);
          // 异步执行，不阻塞主流程
          setTimeout(() => backfillHistoryForAccount(acc), 3000);
        }
      }
    }
  } catch (e) {} // 静默
}

/**
 * 首次启动时对每个账号补拉历史消息（最多 500 条），确保不丢消息。
 * 已经在数据库里的消息后端会自动去重。
 */
async function backfillHistoryForAccount(acc) {
  try {
    console.log(`[历史补拉] 开始为 ${acc.name} 拉取历史消息...`);
    const chResp = await discordHttp.get('/users/@me/channels', {
      headers: { 'Authorization': acc.token }, timeout: 10000,
    });
    const channels = chResp.data || [];
    let totalNew = 0;
    for (const ch of channels) {
      // 拉最新 100 条（Discord 单次最大）
      const mResp = await discordHttp.get(`/channels/${ch.id}/messages`, {
        params: { limit: 100 },
        headers: { 'Authorization': acc.token }, timeout: 8000,
      });
      const msgs = mResp.data || [];
      const seen = seenMessageIds.get(acc.id) || new Set();
      const newMsgs = [];
      for (const m of msgs) {
        if (seen.has(m.id)) continue;
        seen.add(m.id);
        const atts = (m.attachments || []).map(a => ({
          url: a.url, contentType: a.content_type, filename: a.filename, size: a.size
        }));
        const stickers = (m.sticker_items || m.stickers || []).map(s => ({
          id: s.id, name: s.name, formatType: s.format_type || s.formatType
        }));
        let gifUrl = null;
        const trimmed = (m.content || '').trim();
        if (/^https?:\/\/\S+$/i.test(trimmed) && /\.(gif|webp|mp4|webm)(\?|#|$)/i.test(trimmed)) {
          gifUrl = trimmed;
        } else if (/\.gif/i.test(trimmed) || /gif|giphy|klipy/i.test(trimmed)) {
          gifUrl = trimmed;
        }
        const gifAtt = atts.find(a => a.contentType === 'image/gif' || /\.gif$/i.test(a.filename || ''));
        if (gifAtt && !gifUrl) gifUrl = gifAtt.url;
        newMsgs.push({
          accountId: acc.id, channelId: ch.id, channelType: ch.type,
          discordMessageId: m.id, authorId: m.author?.id, authorName: m.author?.username,
          authorGlobalName: m.author?.global_name,
          content: m.content || '', timestamp: m.timestamp,
          isFromMe: m.author?.id === acc.discordId,
          messageType: stickers.length > 0 ? 'sticker' : gifUrl ? 'gif' : atts.length > 0 ? 'image' : 'text',
          gifUrl, attachments: atts, stickers,
        });
      }
      seenMessageIds.set(acc.id, seen);
      if (newMsgs.length > 0) {
        await http.post('/agent-servers/messages/report', {
          token: cfg.token, messages: newMsgs,
        });
        totalNew += newMsgs.length;
      }
    }
    console.log(`[历史补拉] ${acc.name}: 完成，共上报 ${totalNew} 条历史消息（后端会自动去重）`);
  } catch (err) {
    console.warn(`[历史补拉] ${acc.name} 失败:`, err.message);
  }
}

/** 有限并发池 — 控制同时执行的账号数 */
async function pool(items, concurrency, worker) {
  let idx = 0;
  async function run() {
    while (idx < items.length) {
      const i = idx++;
      try { await worker(items[i]); } catch {}
    }
  }
  await Promise.all(Array(concurrency).fill(0).map(run));
}

/** 每个 (accountId, channelId) 上次看到的最新消息 ID — 用 Discord after 参数增量拉 */
const channelLastMsgId = new Map();

/** 轮次计数器 — 用于分层采样 */
let pollRound = 0;
let pollInProgress = false;

/**
 * 处理单个账号的消息轮询
 * @param {object} acc 账号
 * @param {number} channelLimit 本轮最多拉多少个 DM channel（Discord 返回按最近消息时间倒序）
 */
async function pollOneAccount(acc, channelLimit) {
  try {
    const chResp = await discordHttp.get('/users/@me/channels', {
      headers: { 'Authorization': acc.token }, timeout: 8000,
      params: { limit: Math.min(channelLimit, 200) },
    });
    const allChannels = chResp.data || [];
    // 分层采样：只取前 channelLimit 个（Discord 按最近消息时间倒序返回）
    const channels = allChannels.slice(0, channelLimit);
    const seen = seenMessageIds.get(acc.id) || new Set();
    const newMessages = [];

    // ===== 账号内 messages API 并发 — 关键优化 =====
    // 之前串行: 20 channel × 50ms = 1000ms
    // 现在并行: max 100ms（取决于最慢的那 1-2 个请求）
    const fetchTasks = channels.map(async (ch) => {
      try {
        const key = `${acc.id}:${ch.id}`;
        const lastId = channelLastMsgId.get(key);
        const params = { limit: 50 };
        if (lastId) params.after = lastId;

        const mResp = await discordHttp.get(`/channels/${ch.id}/messages`, {
          params,
          headers: { 'Authorization': acc.token }, timeout: 5000,
        });
        const msgs = mResp.data || [];
        if (msgs.length === 0) return null;

        // Discord messages 数组按时间倒序 — index 0 是最新
        const latestMsgId = msgs[0]?.id;
        if (latestMsgId) channelLastMsgId.set(key, latestMsgId);
        return { ch, msgs };
      } catch {
        return null;  // 单个 channel 失败不影响其他
      }
    });

    const results = await Promise.all(fetchTasks);

    for (const res of results) {
      if (!res) continue;
      const { ch, msgs } = res;

      for (const m of msgs) {
        if (seen.has(m.id)) continue;
        seen.add(m.id);
        if (seen.size > 2000) {
          const arr = [...seen]; arr.splice(0, arr.length - 2000);
          seenMessageIds.set(acc.id, new Set(arr));
        } else {
          seenMessageIds.set(acc.id, seen);
        }
        const atts = (m.attachments || []).map(a => ({
          url: a.url, contentType: a.content_type, filename: a.filename, size: a.size
        }));
        const stickers = (m.sticker_items || m.stickers || []).map(s => ({
          id: s.id, name: s.name, formatType: s.format_type || s.formatType
        }));
        let gifUrl = null;
        const trimmed = (m.content || '').trim();
        if (/^https?:\/\/\S+$/i.test(trimmed) && /\.(gif|webp|mp4|webm)(\?|#|$)/i.test(trimmed)) {
          gifUrl = trimmed;
        } else if (/\.gif/i.test(trimmed) || /gif|giphy|klipy/i.test(trimmed)) {
          gifUrl = trimmed;
        }
        const gifAtt = atts.find(a => a.contentType === 'image/gif' || /\.gif$/i.test(a.filename || ''));
        if (gifAtt && !gifUrl) gifUrl = gifAtt.url;

        newMessages.push({
          accountId: acc.id,
          channelId: ch.id,
          channelType: ch.type,
          discordMessageId: m.id,
          authorId: m.author?.id,
          authorName: m.author?.username,
          authorGlobalName: m.author?.global_name,
          authorAvatar: m.author?.avatar
            ? `https://cdn.discordapp.com/avatars/${m.author.id}/${m.author.avatar}.png`
            : null,
          content: m.content || '',
          timestamp: m.timestamp,
          isFromMe: m.author?.id === acc.discordId,
          messageType: stickers.length > 0 ? 'sticker'
            : gifUrl ? 'gif'
            : atts.length > 0 && atts.some(a => (a.contentType || '').startsWith('image/')) ? 'image'
            : atts.length > 0 && atts.some(a => (a.contentType || '').startsWith('video/')) ? 'gif'
            : 'text',
          gifUrl,
          attachments: atts,
          stickers,
        });
      }
    }

    if (newMessages.length > 0) {
      await http.post('/agent-servers/messages/report', {
        token: cfg.token, messages: newMessages,
      });
      console.log(`[消息轮询] ${acc.name}: ${newMessages.length} 条新消息`);
    }
  } catch (err) {
    const st = err.response?.status;
    if (st === 401) console.warn(`[消息轮询] ${acc.name}: Token 已失效`);
    else if (st === 429) console.debug(`[消息轮询] ${acc.name}: 被限流`);
  }
}

async function pollMessages() {
  if (managedAccounts.length === 0) return;
  if (pollInProgress) return;  // 防重入：上一轮还没跑完就跳过本轮
  pollInProgress = true;
  try {
    // 并发 25 — 三层并发（账号间 25 + 账号内 Promise.all + 分层采样 20/50/200 channel）
    await pool(managedAccounts, 25, pollOneAccount);
  } finally {
    pollInProgress = false;
  }
}
