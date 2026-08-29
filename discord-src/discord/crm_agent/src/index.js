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
