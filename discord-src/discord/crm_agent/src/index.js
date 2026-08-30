const { http, cfg } = require('./http');
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
          console.log('[任务] 浏览器已打开，等待用户关闭...');
          // 保持浏览器开着直到用户主动关闭
          await new Promise((resolve) => {
            context.on('close', resolve);
          });
          console.log('[Browser] 浏览器已关闭');
          await reportTask(task.id, 'SUCCESS');
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
        const { token, channelId, content } = params;
        if (!token || !channelId) {
          await reportTask(task.id, 'FAILED', { error: '缺少 token 或 channelId' });
          break;
        }
        await reportTask(task.id, 'RUNNING');
        try {
          // 从 agent 机器发 Discord API 请求 —— IP 是用户家庭宽带，不会触发风控
          const resp = await require('axios').post(
            `https://discord.com/api/v10/channels/${channelId}/messages`,
            { content: content || '' },
            {
              headers: {
                'Authorization': token,
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
                'Content-Type': 'application/json',
              },
              timeout: 15000,
            }
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
  setInterval(pollTask, cfg.pollIntervalMs || 5000);

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
const MSG_POLL_MS = 2000;

async function loadManagedAccounts() {
  try {
    const accounts = await http.post('/agent-servers/accounts', { token: cfg.token });
    managedAccounts = accounts || [];
    if (managedAccounts.length > 0) {
      console.log(`[消息轮询] 负责 ${managedAccounts.length} 个 AGENT 采集账号`);
    }
  } catch (e) {} // 静默
}

async function pollMessages() {
  if (managedAccounts.length === 0) return;
  const axios = require('axios');

  for (const acc of managedAccounts) {
    try {
      // 1. 拉 DM channels
      const chResp = await axios.get('https://discord.com/api/v10/users/@me/channels', {
        headers: { 'Authorization': acc.token }, timeout: 8000,
      });
      const channels = chResp.data || [];

      const newMessages = [];
      const seen = seenMessageIds.get(acc.id) || new Set();

      for (const ch of channels) {
        // 2. 拉最新 10 条消息
        const mResp = await axios.get(`https://discord.com/api/v10/channels/${ch.id}/messages`, {
          params: { limit: 10 },
          headers: { 'Authorization': acc.token }, timeout: 5000,
        });
        for (const m of (mResp.data || [])) {
          if (seen.has(m.id)) continue;
          seen.add(m.id);
          // 防止 seen 无限增长
          if (seen.size > 2000) {
            const arr = [...seen]; arr.splice(0, arr.length - 2000);
            seenMessageIds.set(acc.id, new Set(arr));
          } else {
            seenMessageIds.set(acc.id, seen);
          }
          newMessages.push({
            accountId: acc.id,
            channelId: ch.id,
            channelType: ch.type,
            discordMessageId: m.id,
            authorId: m.author?.id,
            authorName: m.author?.username,
            content: m.content || '',
            timestamp: m.timestamp,
            isFromMe: m.author?.id === acc.discordId,
          });
        }
      }

      // 3. 批量上报
      if (newMessages.length > 0) {
        await http.post('/agent-servers/messages/report', {
          token: cfg.token, messages: newMessages,
        });
        console.log(`[消息轮询] ${acc.name}: ${newMessages.length} 条新消息`);
      }
    } catch (err) {
      const st = err.response?.status;
      if (st === 401) console.warn(`[消息轮询] ${acc.name}: Token 已失效`);
      // 429 限流 或 其它错误 → 静默
    }
  }
}
