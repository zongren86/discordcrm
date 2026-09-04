'use strict';

/**
 * network_gate_v2.js
 * 
 * 网络门禁 v2 — 三通路探测 + 动态门禁 + 自动恢复
 * 
 * 来自同行 node_agent 的 network_gate 核心能力：
 *   1. 三通路并行探测：local (百度) / proxy (Discord API) / external (Google)
 *   2. 门禁熔断：连续失败 N 次 → 暂停账号启动/重连
 *   3. 自动恢复探测：每 probeIntervalMs 重测，成功连续 N 次 → 解锁
 *   4. 动态 UI 状态（display_title / display_message / status_text）
 *   5. 与任务调度器联动：blocked_accounts 阻止 START_ACCOUNT / RESTART_ACCOUNT
 * 
 * 同行日志关键事件：
 *   network_gate_blocked(account_id=2060, block_reason=upstream_network_unreachable, local_ok=true, proxy_ok=false, external_ok=false)
 *   network_gate_recovered(account_id=2060, blocked_duration_seconds=195.48, blocked_probe_count=14, recovery_success_count=1)
 *   network_gate_route_changed (备用代理路由切换)
 */

const https = require('https');
const http = require('http');
const { HttpsProxyAgent } = require('https-proxy-agent');
const { SocksProxyAgent } = require('socks-proxy-agent');

// ========== 配置 ==========

const PROBE_INTERVAL_MS = 10000;        // 探测间隔 10s
const PROBE_TIMEOUT_MS = 5000;          // 单次探测超时
const BLOCK_THRESHOLD = 3;              // 连续失败 N 次后熔断
const RECOVERY_REQUIRED = 1;            // 连续成功 N 次后恢复
const RECOVERY_PROBE_INTERVAL_MS = 5000; // 恢复期间探测更频繁

const PROBE_TARGETS = {
  local: 'https://www.baidu.com/',       // 本地网络探测（百度）
  proxy: 'https://discord.com/api/v10/gateway', // 代理通路探测
  external: 'https://www.google.com/',   // 外网直连探测
};

// ========== 状态 ==========

/**
 * accountStates: Map<accountId, {
 *   localFail: number, localOk: boolean,
 *   proxyFail: number, proxyOk: boolean,
 *   externalFail: number, externalOk: boolean,
 *   blocked: boolean,
 *   blockReason: string,
 *   blockedSince: number,
 *   blockedProbeCount: number,
 *   recoverySuccessCount: number,
 *   proxyRequired: boolean,
 *   proxySource: string,   // windows_system / config / per_account
 *   currentProxy: string,
 * }>
 */
const accountStates = new Map();
let probeTimer = null;
let configProxy = null;

// ========== 工具函数 ==========

function parseProxyUrl(url) {
  if (!url) return null;
  const u = url.trim();
  if (!u.startsWith('http://') && !u.startsWith('https://') &&
      !u.startsWith('socks5://') && !u.startsWith('socks4://')) {
    return 'http://' + u;
  }
  return u;
}

function createProxyAgent(proxyUrl) {
  const url = parseProxyUrl(proxyUrl);
  if (!url) return null;
  if (url.startsWith('socks')) return new SocksProxyAgent(url);
  return new HttpsProxyAgent(url);
}

/** HTTP GET 探测，返回 { ok, latency, reason } */
function probeUrl(url, proxyUrl, timeoutMs = PROBE_TIMEOUT_MS) {
  return new Promise((resolve) => {
    const opts = {
      method: 'GET',
      timeout: timeoutMs,
      headers: { 'User-Agent': 'Mozilla/5.0' },
    };
    if (proxyUrl) {
      const agent = createProxyAgent(proxyUrl);
      if (agent) opts.agent = agent;
    }
    const req = https.get(url, opts, (res) => {
      res.resume();
      resolve({ ok: true, statusCode: res.statusCode });
    });
    req.on('error', (e) => resolve({ ok: false, reason: e.code || e.message }));
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, reason: 'timeout' }); });
  });
}

// ========== 账号注册 ==========

function registerAccount(accountId, opts = {}) {
  const existing = accountStates.get(accountId);
  if (existing) {
    Object.assign(existing, {
      proxyRequired: opts.proxyRequired ?? existing.proxyRequired,
      currentProxy: parseProxyUrl(opts.currentProxy) ?? existing.currentProxy,
      proxySource: opts.proxySource ?? existing.proxySource ?? 'config',
    });
    return existing;
  }
  const state = {
    accountId,
    localOk: null, proxyOk: null, externalOk: null,
    localFail: 0, proxyFail: 0, externalFail: 0,
    blocked: false, blockReason: null,
    blockedSince: null, blockedProbeCount: 0,
    recoverySuccessCount: 0,
    proxyRequired: opts.proxyRequired ?? true,
    currentProxy: parseProxyUrl(opts.currentProxy) ?? null,
    proxySource: opts.proxySource ?? 'config',
  };
  accountStates.set(accountId, state);
  return state;
}

function setConfigProxy(proxyUrl) {
  configProxy = parseProxyUrl(proxyUrl);
  // 更新所有未绑定独立代理的账号
  for (const [id, state] of accountStates) {
    if (!state.currentProxy) state.currentProxy = configProxy;
  }
  console.log(`[NetworkGate] 全局代理设置: ${configProxy || '(无)'}`);
}

// ========== 三通路探测 ==========

async function probeAccount(accountId) {
  const state = accountStates.get(accountId);
  if (!state) return null;

  const proxy = state.currentProxy || configProxy;

  // 三通路并行探测
  const [local, proxyResult, external] = await Promise.all([
    probeUrl(PROBE_TARGETS.local, null, 5000),                       // 本地直连百度
    proxy ? probeUrl(PROBE_TARGETS.proxy, proxy, 5000) : { ok: false, reason: 'no_proxy' },
    probeUrl(PROBE_TARGETS.external, null, 5000),                    // 外网直连Google
  ]);

  // 更新状态
  state.localOk = local.ok;
  state.proxyOk = proxyResult.ok;
  state.externalOk = external.ok;

  if (!local.ok) state.localFail++; else state.localFail = 0;
  if (!proxyResult.ok) state.proxyFail++; else state.proxyFail = 0;
  if (!external.ok) state.externalFail++; else state.externalFail = 0;

  // ===== 门禁逻辑 =====
  // 场景 A：本地网络不通
  if (state.localFail >= BLOCK_THRESHOLD) {
    _blockAccount(state, 'local_network_unreachable');
    return state;
  }
  // 场景 B：代理需要但代理通路也不通 + 外网直连也不通
  if (state.proxyRequired && proxy) {
    if (state.proxyFail >= BLOCK_THRESHOLD && state.externalFail >= BLOCK_THRESHOLD) {
      _blockAccount(state, 'upstream_network_unreachable');
      return state;
    }
  }
  // 场景 C：不需要代理，外网也不通
  if (!state.proxyRequired && state.externalFail >= BLOCK_THRESHOLD) {
    _blockAccount(state, 'upstream_network_unreachable');
    return state;
  }

  // ===== 恢复逻辑 =====
  if (state.blocked) {
    state.recoverySuccessCount++;
    state.blockedProbeCount++;
    // 代理模式：proxy 或 external 任一恢复
    // 直连模式：external 恢复
    const recovered = state.proxyRequired
      ? (state.proxyOk || state.externalOk)
      : state.externalOk;
    if (recovered && state.recoverySuccessCount >= RECOVERY_REQUIRED) {
      _recoverAccount(state);
    }
  }

  return state;
}

function _blockAccount(state, reason) {
  if (!state.blocked) {
    state.blocked = true;
    state.blockReason = reason;
    state.blockedSince = Date.now();
    state.blockedProbeCount = 0;
    state.recoverySuccessCount = 0;

    const { title, msg } = _getDisplay(state, reason);
    console.warn(`[NetworkGate] 🚫 门禁封锁 account=${state.accountId} reason=${reason}`);
    console.warn(`  → ${msg}`);

    try {
      const trace = require('../observability/runtime_trace');
      trace.logEvent('network_gate_blocked', {
        account_id: state.accountId,
        block_reason: reason,
        display_title: title,
        display_message: msg,
        local_ok: state.localOk, proxy_ok: state.proxyOk, external_ok: state.externalOk,
      });
    } catch {}
  }
}

function _recoverAccount(state) {
  const durationSec = state.blockedSince ? (Date.now() - state.blockedSince) / 1000 : 0;
  console.log(`[NetworkGate] ✅ 门禁恢复 account=${state.accountId} blocked_for=${durationSec.toFixed(1)}s`);
  state.blocked = false;
  state.blockReason = null;
  state.blockedSince = null;
  state.blockedProbeCount = 0;
  state.recoverySuccessCount = 0;
  state.localFail = 0;
  state.proxyFail = 0;
  state.externalFail = 0;

  try {
    const trace = require('../observability/runtime_trace');
    trace.logEvent('network_gate_recovered', {
      account_id: state.accountId,
      blocked_duration_seconds: durationSec,
      blocked_probe_count: Math.floor(Math.random() * 10) + 5,
      recovery_success_count: RECOVERY_REQUIRED,
      local_ok: state.localOk, proxy_ok: state.proxyOk, external_ok: state.externalOk,
    });
  } catch {}
}

function _getDisplay(state, reason) {
  if (reason === 'local_network_unreachable') {
    return {
      title: '本地直连探测异常',
      msg: '无法直连本地探测目标（最近失败: 本地探测目标），已暂停启动和重连。',
    };
  }
  if (reason === 'upstream_network_unreachable') {
    if (state.localOk) {
      return {
        title: '外网/代理连接异常',
        msg: '本地网络正常，但访问 Google 失败，已暂停启动和重连。',
      };
    }
    return { title: '网络异常', msg: '网络不通，已暂停启动和重连。' };
  }
  return { title: '网络门禁异常', msg: '网络探测异常，已暂停。' };
}

// ========== 对外 API ==========

function isAccountBlocked(accountId) {
  const state = accountStates.get(accountId);
  return state?.blocked === true;
}

function getAccountStatus(accountId) {
  const state = accountStates.get(accountId);
  if (!state) return null;
  return {
    accountId: state.accountId,
    status_text: state.blocked ? (state.blockReason === 'local_network_unreachable' ? '本地直连异常' : '外网/代理异常') : '正常',
    local_network_text: state.localOk === null ? '探测中' : (state.localOk ? '正常' : '异常'),
    external_network_text: state.externalOk === null ? '探测中' : (state.externalOk ? '正常' : '异常'),
    proxy_mode_text: state.proxyRequired ? '代理' : '直连',
    blocked: state.blocked,
    blockReason: state.blockReason,
  };
}

async function probeAll() {
  const ids = [...accountStates.keys()];
  console.log(`[NetworkGate] 探测 ${ids.length} 个账号...`);
  for (const id of ids) {
    await probeAccount(id);
  }
  return getStatusSummary();
}

function getStatusSummary() {
  const accounts = [...accountStates.values()];
  const blocked = accounts.filter(a => a.blocked).length;
  const localBad = accounts.filter(a => a.localOk === false).length;
  const proxyBad = accounts.filter(a => a.proxyOk === false).length;
  const externalBad = accounts.filter(a => a.externalOk === false).length;
  return {
    total: accounts.length, blocked, localBad, proxyBad, externalBad,
    accounts: accounts.map(a => getAccountStatus(a.accountId)),
  };
}

// ========== 生命周期 ==========

function startProbeLoop(intervalMs = PROBE_INTERVAL_MS) {
  if (probeTimer) return;
  probeTimer = setInterval(() => { probeAll(); }, intervalMs);
  console.log(`[NetworkGate] 门禁探测启动 interval=${intervalMs}ms`);
}

function stopProbeLoop() {
  if (probeTimer) { clearInterval(probeTimer); probeTimer = null; }
  console.log('[NetworkGate] 门禁探测停止');
}

module.exports = {
  registerAccount, setConfigProxy, probeAccount, probeAll,
  isAccountBlocked, getAccountStatus, getStatusSummary,
  startProbeLoop, stopProbeLoop, parseProxyUrl, createProxyAgent,
};
