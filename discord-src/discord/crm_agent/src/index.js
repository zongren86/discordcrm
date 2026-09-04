// 全局日志时间戳: [MM-DD HH:MM:SS]
(function(){
  const ts = () => {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    return `[${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}]`;
  };
  const _log = console.log;
  console.log = (...args) => _log(ts(), ...args);
  const _warn = console.warn;
  console.warn = (...args) => _warn(ts(), ...args);
  const _error = console.error;
  console.error = (...args) => _error(ts(), ...args);
})();

const { http, cfg } = require('./http');
const { discordHttp, fetchFriends, fetchDmChannels, sendMessageWithFiles, getProxyUrl } = require('./discord');
const { captureDiscordAccount, launchBrowserOnly, extractAccountFromContext } = require('./browser');
const fs = require('fs');
const path = require('path');

// ========== 反作弊核心模块（v1.9.0 集成）==========
const gatewayManager = require('./gateway/gateway_manager');
const networkGate = require('./network/network_gate_v2');
const maintenanceTrim = require('./maintenance_trim');
const { scheduler } = require('./scheduler/task_scheduler');
const outbox = require('./account_status_outbox');

const AGENT_VERSION = cfg.version || 'unknown';

console.log(`[启动] crm_agent v${AGENT_VERSION}`);
console.log(`[配置] serverUrl=${cfg.serverUrl}  agentName=${cfg.agentName || '(未命名)'}`);

let heartbeatOk = false;
let busy = false;
let currentTaskId = null;
const browserOpenAccounts = new Set();  // 浏览器唤起中的账号集合（仅状态标记，不再暂停轮询 — 同IP同通道不触发风控）

async function heartbeat() {
  try {
    await http.post('/agent-servers/heartbeat', {
      token: cfg.token,
      name: cfg.agentName,
      serverAddress: cfg.publicAddress || '',
      nodeVersion: process.version,
      agentVersion: AGENT_VERSION,
      browserType: (cfg.browser && cfg.browser.type) || 'chromium',
      // 🛡️ 反作弊: 上报系统状态（JSON.stringify 成字符串 — 后端接收 Map<String,String>）
      gatewayStatus: JSON.stringify(gatewayManager.getAllStatus()),
      networkStatus: JSON.stringify(networkGate.getStatusSummary()),
      schedulerStatus: JSON.stringify(scheduler.getStatus()),
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
          // ⭐ 把 discordProxy 传进去 → 地理探测才能匹配代理出口 IP
          const result = await captureDiscordAccount(cfg.browser || {}, {
            taskId: task.id,
            http: http,
            agentName: cfg.agentName,
            proxyUrl: getProxyUrl() || cfg.discordProxy || null,
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
          // 🛡️ 反作弊关键: CAPTURE 成功后立即建立 Gateway 会话
          // 同行日志证明：有 Gateway 会话的账号封号率显著降低
          try {
            gatewayManager.connectAgent(cfg.agentName + '_' + (result.discordId || 'unknown'), result.token, { intents: 0 });
            console.log(`[Gateway] 🛡️ ${result.username} 已建立 Gateway 会话`);
          } catch (gwErr) {
            console.warn('[Gateway] 建立失败(不影响主流程):', (gwErr.message || '').slice(0, 80));
          }
          // 🔧 反作弊: 通知 network_gate 注册这个账号
          networkGate.registerAccount(result.discordId || result.username, {
            proxyRequired: !!(getProxyUrl() || cfg.discordProxy),
            currentProxy: getProxyUrl() || cfg.discordProxy || null,
          });
          // ⚡ 性能关键: 立即刷新账号列表（不等到 30s 的 ACCOUNT_REFRESH_MS）
          // 确保新账号 ≤ 2s 内进入 managedAccounts 并被消息轮询覆盖
          setTimeout(async () => {
            try {
              const before = (managedAccounts || []).length;
              await loadManagedAccounts();
              const after = (managedAccounts || []).length;
              if (after > before) {
                console.log('[性能] ⚡ CAPTURE 后即时刷新: managedAccounts ' + before + ' → ' + after + ' (+' + (after - before) + ')');
              }
            } catch {}
          }, 1500);
          // 已移除 3-4 分钟强制间隔：
          // 1. CAPTURE 成功后 safeClose 让 Chrome 正确写盘 profile，session 状态稳定
          // 2. 用户需要快速连续新增账号，agent 立即 ready 领下一个任务
          // 3. 反作弊靠 browser.js 层的 initScript/指纹/资源拦截，不靠人为延迟

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
        const accountId = params.accountId;
        const profilePath = params.browserProfilePath || params.profilePath ||
          (accountId ? (await getAccountProfilePath(accountId)) : null);

        if (!profilePath) {
          await reportTask(task.id, 'FAILED', { error: '缺少浏览器 profile 路径' });
          break;
        }
        await reportTask(task.id, 'RUNNING');
        try {
          // ⭐ 把 agentName + discordProxy 传进去 → 地理探测才能匹配代理出口 IP
          const { context } = await launchBrowserOnly(profilePath, cfg.browser || {}, {
            agentName: cfg.agentName,
            proxyUrl: getProxyUrl() || cfg.discordProxy || null,
          });
          console.log('[任务] ✅ 浏览器已打开');

          // 💡 状态标记: 记录哪些账号的 Chrome 被 LAUNCH_BROWSER 唤起了
          // 注意: 不再暂停消息轮询 — Discord 官方客户端也是 WebSocket + REST 同时用
          // 真正触发风控的是多 IP 同时用、异常行为模式、自动化痕迹
          if (accountId) {
            browserOpenAccounts.add(accountId);
            console.log(`[状态] 账号 ${accountId} Chrome 已唤起（不暂停消息轮询）`);
          }

          // ⚡ 关键: 唤起后自动扫描当前 token，上报后端更新
          await new Promise(r => setTimeout(r, 3000));
          const extracted = await extractAccountFromContext(context);

          if (extracted && extracted.tokenValid) {
            // token 有效 → 上报完整 payload，后端 upsert 更新 DB
            const payload = {
              discordId: extracted.discordId,
              username: extracted.username,
              discordName: extracted.discordName,
              email: extracted.email,
              token: extracted.token,
              avatarUrl: extracted.avatarUrl,
              browserProfilePath: profilePath,
            };
            await reportTask(task.id, 'SUCCESS', payload);
            console.log(`[任务] ✅ Token已更新! ${extracted.username} (${extracted.discordId})`);

            // 同步更新本地缓存: 恢复该账号轮询
            if (accountId) {
              const localAcc = managedAccounts.find(a => String(a.id) === String(accountId));
              if (localAcc) {
                localAcc.token = extracted.token;
                tokenInvalidAccounts.delete(localAcc.id);
                console.log(`[本地] 账号 ${localAcc.name} token已刷新 → 恢复轮询`);
              }
            }
          } else if (extracted && !extracted.tokenValid) {
            // token存在但API返回401，需要用户手动登录
            await reportTask(task.id, 'SUCCESS', {
              notice: 'token_expired',
              hint: '浏览器已打开，请手动重新登录 Discord，然后再次点击"唤起"即可更新 token',
            });
            console.warn('[任务] Token已过期，请在打开的浏览器中重新登录');
          } else {
            // 完全没扫到 token → session 过期
            await reportTask(task.id, 'SUCCESS', {
              notice: 'session_expired',
              hint: '浏览器已打开，请登录 Discord，然后再次点击"唤起"更新 token',
            });
            console.warn('[任务] 未扫描到 token，浏览器等待用户登录...');
          }

          // 浏览器保持打开，监听关闭事件 → 恢复轮询
          (async () => {
            try {
              await new Promise(resolve => context.on('close', resolve));
              console.log('[Browser] 浏览器已关闭');
            } catch {}
            if (accountId) {
              browserOpenAccounts.delete(accountId);
              console.log(`[状态] 账号 ${accountId} Chrome 已关闭`);
            }
          })();
          break;
        } catch (err) {
          await reportTask(task.id, 'FAILED', { error: err.message });
          console.error('[任务] 唤起浏览器失败:', err.message);
          break;
        }
      }

      case 'SEND_MESSAGE': {
        const params = typeof task.params === 'string'
          ? (() => { try { return JSON.parse(task.params); } catch { return {}; } })()
          : (task.params || {});
        const { token, channelId, content, stickerIds, sticker_id, files } = params;
        if (!token || !channelId) {
          await reportTask(task.id, 'FAILED', { error: '缺少 token 或 channelId' });
          break;
        }
        await reportTask(task.id, 'RUNNING');
        try {
          // 有 files → multipart 同时发 content + files（合并为一条消息）
          if (files && Array.isArray(files) && files.length > 0) {
            console.log(`[任务] 统一发送 content="${content || ''}", files=${files.length}`);
            const result = await sendMessageWithFiles(token, channelId, content || '', files);
            const discordMessageId = result?.id;
            const firstCdnUrl = result?.attachments?.[0]?.url || null;
            console.log(`[任务] ✅ 统一发送成功 discordMsgId=${discordMessageId} cdnUrl=${firstCdnUrl || '(无)'}`);
            await reportTask(task.id, 'SUCCESS', {
              discordMessageId,
              cdnUrl: firstCdnUrl,
              channelId,
            });
            break;
          }
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
          if (status === 401 && params.accountId) {
            reportTokenInvalid(params.accountId, `accountId=${params.accountId}`);
          }
          await reportTask(task.id, 'FAILED', { error: msg, status });
        }
        break;
      }

      case 'FULL_SYNC_FRIENDS': {
        const params = typeof task.params === 'string'
          ? (() => { try { return JSON.parse(task.params); } catch { return {}; } })()
          : (task.params || {});
        const { token, accountId } = params;
        if (!token) {
          await reportTask(task.id, 'FAILED', { error: '缺少 token' });
          break;
        }
        await reportTask(task.id, 'RUNNING');
        // 防风控: 执行前随机延迟 2~8 秒
        const fDelay = 2000 + Math.random() * 6000;
        console.log(`[任务] FULL_SYNC_FRIENDS 延迟 ${Math.round(fDelay/1000)}s 后开始 (accountId=${accountId})`);
        await new Promise(r => setTimeout(r, fDelay));
        try {
          console.log(`[任务] 拉取好友列表 (accountId=${accountId})...`);
          const friends = await fetchFriends(token);
          // 过滤: type=1 是已接受好友，type=2/3 是待处理
          const accepted = friends.filter(f => f.type === 1).length;
          const pending  = friends.filter(f => f.type === 2).length;
          const blocked  = friends.filter(f => f.type === 4).length;
          console.log(`[任务] 好友列表拉取完成: 已接受=${accepted} 待处理=${pending} 阻止=${blocked} 总计=${friends.length}`);

          // 上报到后端
          // Discord /users/@me/relationships 返回的好友结构:
          //   { id, type, nickname, since, user: { id, username, global_name, avatar, ... } }
          // 注意: 用户资料在 user 子对象里，顶级 username/global_name/avatar 字段不存在
          const payload = {
            token: cfg.token,
            accountId,
            friends: friends.map(f => {
              const u = f.user || {};
              return {
                friendDiscordUserId: f.id,
                username: u.username || "",
                globalName: u.global_name || u.username || "",
                avatar: u.avatar || null,
                relationshipType: f.type,  // 1=好友 2=入站待请求 3=出站待请求 4=阻止
              };
            }),
          };
          await http.post('/agent-servers/friends/report', payload);
          console.log(`[任务] ✅ 好友数据已上报 ${friends.length} 条`);

          // 第二步: 拉取 DM 频道, 上报到服务器创建 Conversation
          let dmCount = 0;
          try {
            console.log(`[任务] 拉取 DM 频道...`);
            const channels = await fetchDmChannels(token);
            // 1:1 DM(type=1): 只有1个recipient; 群组DM(type=3): 多个recipients
            const dms = (channels || []).map(ch => ({
              channelId: ch.id,
              channelType: ch.type,
              recipients: (ch.recipients || []).map(r => ({
                id: r.id, username: r.username, globalName: r.global_name || r.username, avatar: r.avatar,
              })),
            }));
            await http.post('/agent-servers/dm-channels/report', {
              token: cfg.token, accountId, dms,
            });
            dmCount = dms.filter(d => d.channelType === 1).length;
            console.log(`[任务] ✅ DM 频道已上报: ${dms.length} 个 (其中 1:1=${dmCount})`);
          } catch (dmErr) {
            console.warn(`[任务] DM 频道拉取失败(不影响好友同步): ${dmErr.message}`);
          }

          await reportTask(task.id, 'SUCCESS', { friendCount: friends.length, accepted, pending, dmCount });
        } catch (err) {
          console.error(`[任务] 拉好友失败: ${err.message}`);
          await reportTask(task.id, 'FAILED', { error: err.message });
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

  // ========== 🛡️ 反作弊基础设施启动 ==========
  scheduler.start();
  outbox.init(http, cfg);
  outbox.start();
  maintenanceTrim.start();
  networkGate.setConfigProxy(getProxyUrl() || cfg.discordProxy || null);
  networkGate.startProbeLoop();
  console.log('🛡️ 反作弊模块已启动: scheduler + gateway_manager + network_gate + maintenance_trim + outbox');

  // 消息轮询 (方案 C: HTTP REST, 2s 间隔)
  await loadManagedAccounts();
  setInterval(loadManagedAccounts, ACCOUNT_REFRESH_MS);
  setInterval(pollMessages, MSG_POLL_MS);
  setInterval(tokenHeartbeat, TOKEN_HEARTBEAT_MS);
  // 启动后 2 分钟先跑一次 token 心跳（快速发现失效）
  setTimeout(tokenHeartbeat, 120000);

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
// Token 失效账号集合：检测到一次 401 后从轮询中移除，避免重复请求
const tokenInvalidAccounts = new Set();
// 24小时后自动重新尝试 token（可能用户已重新登录）
const TOKEN_INVALID_COOLDOWN_MS = 24 * 60 * 60 * 1000; // 24h
// Token 失效上报节流: Map<accountId, lastReportTimestamp>，5分钟内不重复上报
const tokenInvalidReportedAt = new Map();
function reportTokenInvalid(accountId, accountName) {
  const now = Date.now();
  const last = tokenInvalidReportedAt.get(accountId) || 0;
  if (now - last < 5 * 60 * 1000) return;  // 5分钟节流
  tokenInvalidReportedAt.set(accountId, now);
  // 🚨 标记账号失效，从轮询中移除！避免持续请求失效 token
  tokenInvalidAccounts.add(accountId);
  http.post('/agent-servers/accounts/token-status', {
    token: cfg.token, accountId, valid: false, reason: '401 Unauthorized (消息轮询/API调用返回401)',
  }).then(() => {
    console.warn(`[Token上报] ${accountName}(${accountId}) Token失效已同步 → 暂停该账号轮询`);
  }).catch(e => {
    console.warn(`[Token上报] ${accountName} 上报失败: ${e.message}`);
  });
}
// 每个账号已见消息 ID 集合: Map<accountId, Set<discordMessageId>>
const seenMessageIds = new Map();
// ============================================
// Token 心跳: 每 30 分钟检测所有托管账号 token 是否有效
// GET https://discord.com/api/v10/users/@me → 401=失效, 上报服务器
// ============================================
const TOKEN_HEARTBEAT_MS = 30 * 60 * 1000;
async function tokenHeartbeat() {
  try {
    const accounts = await loadManagedAccounts();
    if (!accounts || accounts.length === 0) return;
    console.log(`[Token心跳] 检测 ${accounts.length} 个账号的 token 有效性...`);
    let invalidCount = 0, validCount = 0;
    const invalidList = [];
    for (const acc of accounts) {
      try {
        const resp = await fetch('https://discord.com/api/v10/users/@me', {
          headers: { Authorization: acc.token },
          method: 'GET',
        });
        if (resp.status === 401 || resp.status === 403) {
          invalidCount++;
          invalidList.push({ accountId: acc.id, username: acc.username, status: resp.status });
          console.warn(`[Token心跳] ❌ ${acc.username} (id=${acc.id}) token 已失效 (HTTP ${resp.status})`);
        } else if (resp.ok) {
          validCount++;
        }
        await new Promise(r => setTimeout(r, 2000 + Math.random() * 3000));
      } catch (e) { /* 静默网络错误 */ }
    }
    console.log(`[Token心跳] ✅ 有效=${validCount} 失效=${invalidCount}`);
    if (invalidList.length > 0) {
      try {
        await http.post('/agent-servers/accounts/token-status', {
          token: cfg.token,
          results: invalidList.map(i => ({ accountId: i.accountId, tokenValid: false, lastCheckAt: new Date().toISOString() })),
        });
        console.log(`[Token心跳] 📡 已上报 ${invalidList.length} 个失效账号`);
      } catch {}
    }
  } catch {}
}

const ACCOUNT_REFRESH_MS = 30000;
const MSG_POLL_MS = 5000;   // 消息轮询 5s，分层采样 + 并发 5 防风控

// 已经执行过首次历史补拉的账号 ID 集合
const backfilledAccounts = new Set();

async function loadManagedAccounts() {
  try {
    const accounts = await http.post('/agent-servers/accounts', { token: cfg.token });
    managedAccounts = accounts || [];
    // 冷却过期后恢复 token 失效账号（24h 后再试一次）
    const now = Date.now();
    for (const acc of managedAccounts) {
      const lastReport = tokenInvalidReportedAt.get(acc.id) || 0;
      if (tokenInvalidAccounts.has(acc.id) && (now - lastReport > TOKEN_INVALID_COOLDOWN_MS)) {
        tokenInvalidAccounts.delete(acc.id);
        console.log(`[冷却恢复] ${acc.name}(${acc.id}) Token失效已超24h，重新尝试`);
      }
    }
    if (managedAccounts.length > 0) {
      console.log(`[消息轮询] 负责 ${managedAccounts.length} 个 AGENT 采集账号${tokenInvalidAccounts.size ? ` (${tokenInvalidAccounts.size}个失效已跳过)` : ''}`);
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
        const stickers = (m.sticker_items || m.stickers || []).map(s => {
          const fmt = s.format_type || s.formatType || 0;
          const isLottie = Number(fmt) === 3;
          const asset = s.asset || '';  // Discord API 里 asset 是文件名（可能为空字符串）
          const ext = Number(fmt) === 2 ? 'png' : (Number(fmt) === 4 ? 'webp' : 'png');
          let assetUrl;
          if (isLottie) {
            // Lottie sticker CDN: https://cdn.discordapp.com/stickers/{id}.json 或带 asset
            assetUrl = asset
              ? `https://cdn.discordapp.com/stickers/${s.id}/${asset}.json`
              : `https://cdn.discordapp.com/stickers/${s.id}.json`;
          } else {
            assetUrl = asset
              ? `https://cdn.discordapp.com/stickers/${s.id}/${asset}.${ext}`
              : `https://cdn.discordapp.com/stickers/${s.id}.${ext}`;
          }
          return { id: s.id, name: s.name, formatType: fmt, asset, assetUrl };
        });
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
    // 账号内 channel 请求加随机间隔 (20-80ms)，避免瞬间并发打 Discord
    const fetchTasks = channels.map(async (ch, idx) => {
      try {
        await new Promise(r => setTimeout(r, 20 + Math.random() * 20));  // 20~40ms 随机间隔，防风控又快
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
        const stickers = (m.sticker_items || m.stickers || []).map(s => {
          const fmt = s.format_type || s.formatType || 0;
          const isLottie = Number(fmt) === 3;
          const asset = s.asset || '';  // Discord API 里 asset 是文件名（可能为空字符串）
          const ext = Number(fmt) === 2 ? 'png' : (Number(fmt) === 4 ? 'webp' : 'png');
          let assetUrl;
          if (isLottie) {
            // Lottie sticker CDN: https://cdn.discordapp.com/stickers/{id}.json 或带 asset
            assetUrl = asset
              ? `https://cdn.discordapp.com/stickers/${s.id}/${asset}.json`
              : `https://cdn.discordapp.com/stickers/${s.id}.json`;
          } else {
            assetUrl = asset
              ? `https://cdn.discordapp.com/stickers/${s.id}/${asset}.${ext}`
              : `https://cdn.discordapp.com/stickers/${s.id}.${ext}`;
          }
          return { id: s.id, name: s.name, formatType: fmt, asset, assetUrl };
        });
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
      const sentCount = newMessages.filter(m => m.isFromMe).length;
      const recvCount = newMessages.length - sentCount;
      const parts = [];
      if (recvCount > 0) parts.push(`收 ${recvCount}`);
      if (sentCount > 0) parts.push(`发 ${sentCount}`);
      console.log(`[消息轮询] ${acc.name}: ${newMessages.length} 条新消息 (${parts.join(', ')})`);
    }
  } catch (err) {
    const st = err.response?.status;
    if (st === 401) {
      console.warn(`[消息轮询] ${acc.name}: Token 已失效`);
      reportTokenInvalid(acc.id, acc.name);
    } else if (st === 429) {
      console.debug(`[消息轮询] ${acc.name}: 被限流`);
    }
  }
}

async function pollMessages() {
  if (managedAccounts.length === 0) return;
  if (pollInProgress) return;  // 防重入：上一轮还没跑完就跳过本轮
  pollInProgress = true;
  try {
    // 过滤掉 token 失效账号（标记后 24h 自动冷却重试）
    const validAccounts = managedAccounts.filter(acc => !tokenInvalidAccounts.has(acc.id));  // browserOpenAccounts 不再过滤 — 浏览器打开时继续轮询
    if (validAccounts.length === 0) return;
    // 并发 10（防风控又保证速度，Discord rate limit 50/channel，10账号×20channel=200<阈值）
    await pool(validAccounts, 10, pollOneAccount);
  } finally {
    pollInProgress = false;
  }
}
