/**
 * crm_agent 主入口
 * 1. 加载配置
 * 2. 心跳上报 → 让主服务器知道我 ONLINE
 * 3. 定期轮询任务队列
 * 4. 收到 CAPTURE_DISCORD_ACCOUNT 任务 → 启动浏览器 → 回传结果
 */
const { http, cfg } = require('./http');
const { captureDiscordAccount } = require('./browser');

const fs = require('fs');
const path = require('path');

let AGENT_VERSION = 'unknown';
try { AGENT_VERSION = fs.readFileSync(path.join(__dirname, '..', 'VERSION'), 'utf8').trim(); } catch {}

console.log(`[启动] crm_agent v${AGENT_VERSION}`);
console.log(`[配置] serverUrl=${cfg.serverUrl}  agentName=${cfg.agentName || '(未命名)'}`);

let running = true;
let busy = false;

// ==== 心跳 ====
let heartbeatOk = false;
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
    if (status === 401) {
      console.error('[心跳] ❌ Token 无效！请在前端「配置→代理管理」复制正确的 token 到 config.json');
    } else {
      console.error('[心跳] ❌ 失败，请检查 serverUrl 和 token');
    }
  }
}

// ==== 任务轮询 ====
async function pollTask() {
  if (busy) return;
  try {
    const task = await http.post('/agent-servers/tasks/poll', {
      token: cfg.token,
      agentName: cfg.agentName,
    });
    if (task && task.id) {
      console.log(`[任务] 收到任务 id=${task.id}  type=${task.type}`);
      await executeTask(task);
    }
  } catch (e) {
    const status = e.response?.status;
    if (status === 404) {
      // 无任务，正常静默
    } else if (status === 401) {
      console.error('[轮询] ❌ Token 无效，请检查 config.json');
    } else {
      console.error('[轮询] 异常:', status, e.message);
    }
  }
}

// ==== 执行任务 ====
async function executeTask(task) {
  busy = true;
  try {
    switch (task.type) {
      case 'CAPTURE_DISCORD_ACCOUNT': {
        await reportTask(task.id, 'RUNNING');
        const result = await captureDiscordAccount(cfg.browser || {});
        await reportTask(task.id, 'SUCCESS', {
          discordId: result.userId,
          username: result.username,
          email: result.email,
          token: result.token,
          avatarUrl: result.avatarUrl,
        });
        console.log(`[任务] 完成 — 捕获用户 ${result.username}`);
        break;
      }
      default:
        console.warn(`[任务] 未知任务类型: ${task.type}`);
        await reportTask(task.id, 'FAILED', { error: `未知任务类型: ${task.type}` });
    }
  } catch (err) {
    console.error('[任务] 执行失败:', err.message);
    try {
      await reportTask(task.id, 'FAILED', { error: err.message });
    } catch (e) { /* ignore */ }
  } finally {
    busy = false;
  }
}

// ==== 回传任务状态 ====
async function reportTask(taskId, status, result = null) {
  await http.post('/agent-servers/tasks/report', {
    token: cfg.token,
    taskId,
    status,
    result,
  });
}

// ==== 主循环 ====
async function main() {
  // 立即心跳一次
  await heartbeat();

  // 定时器
  setInterval(heartbeat, cfg.heartbeatIntervalMs || 5000);
  setInterval(pollTask, cfg.pollIntervalMs || 5000);

  console.log('[就绪] 等待任务...');

  // 优雅退出
  const shutdown = () => {
    running = false;
    console.log('[退出] 收到信号，停止运行');
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch(err => {
  console.error('[启动] 致命错误:', err);
  process.exit(1);
});
