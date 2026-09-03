'use strict';

/**
 * network_gate.js
 * 
 * 代理探测 + 熔断 + per-account 代理管理
 * 
 * 策略（来自同行 node_agent 的 network_gate）：
 *   1. 每个账号可绑定独立代理（proxy 从配置或账号级别传入）
 *   2. 启动时探测代理可用性 → 标记 available / blocked
 *   3. 代理连续失败 N 次 → 熔断（blocked），自动恢复探测
 *   4. fallback: 代理不可用时可选裸连（但警告风险）
 */

const http = require('http');
const https = require('https');
const { HttpsProxyAgent } = require('https-proxy-agent');
const { SocksProxyAgent } = require('socks-proxy-agent');

// ========== 状态 ==========

const _proxyStates = new Map(); // proxyUrl -> { status, failCount, lastCheck, lastFail }

const CIRCUIT_BREAKER_THRESHOLD = 3;    // 连续失败 3 次熔断
const CIRCUIT_BREAKER_RECOVER_MS = 60000; // 熔断后 60 秒探测一次
const PROXY_CHECK_TIMEOUT = 5000;

// ========== 工具 ==========

function parseProxyUrl(url) {
  if (!url) return null;
  const u = url.trim();
  if (!u.startsWith('http://') && !u.startsWith('https://') && !u.startsWith('socks5://') && !u.startsWith('socks4://')) {
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

async function checkProxy(proxyUrl) {
  const url = parseProxyUrl(proxyUrl);
  if (!url) return { ok: false, reason: 'no_proxy' };
  
  return await new Promise((resolve) => {
    const agent = createProxyAgent(url);
    if (!agent) return resolve({ ok: false, reason: 'bad_proxy_url' });
    
    const start = Date.now();
    const req = https.get('https://discord.com/api/v10/gateway', {
      agent,
      timeout: PROXY_CHECK_TIMEOUT,
      headers: { 'User-Agent': 'Mozilla/5.0' },
    }, (res) => {
      res.resume();
      const latency = Date.now() - start;
      resolve({ ok: true, latency, statusCode: res.statusCode });
    });
    
    req.on('error', (e) => resolve({ ok: false, reason: e.message, latency: Date.now() - start }));
    req.on('timeout', () => { req.destroy(); resolve({ ok: false, reason: 'timeout' }); });
  });
}

// ========== 熔断逻辑 ==========

function getProxyState(proxyUrl) {
  if (!proxyUrl) return { status: 'direct' };
  return _proxyStates.get(parseProxyUrl(proxyUrl)) || { status: 'unknown', failCount: 0 };
}

function recordProxyResult(proxyUrl, ok) {
  const url = parseProxyUrl(proxyUrl);
  if (!url) return;
  
  let state = _proxyStates.get(url);
  if (!state) {
    state = { status: 'unknown', failCount: 0, lastCheck: null, lastFail: null };
    _proxyStates.set(url, state);
  }
  
  state.lastCheck = Date.now();
  if (ok) {
    state.failCount = 0;
    state.status = 'available';
  } else {
    state.failCount++;
    state.lastFail = Date.now();
    if (state.failCount >= CIRCUIT_BREAKER_THRESHOLD) {
      state.status = 'blocked';
      console.warn('[NetworkGate] 代理熔断:', url, '(连续失败 ' + state.failCount + ' 次)');
    }
  }
}

async function isProxyUsable(proxyUrl) {
  const state = getProxyState(proxyUrl);
  if (state.status !== 'blocked') return true;
  
  // 熔断后过了恢复时间 → 探测一次
  if (state.lastFail && Date.now() - state.lastFail >= CIRCUIT_BREAKER_RECOVER_MS) {
    console.log('[NetworkGate] 探测恢复:', proxyUrl);
    const result = await checkProxy(proxyUrl);
    recordProxyResult(proxyUrl, result.ok);
    return result.ok;
  }
  return false;
}

// ========== per-account 代理绑定 ==========

const _accountProxies = new Map(); // agentName -> proxyUrl

function bindAccountProxy(agentName, proxyUrl) {
  if (!proxyUrl) return;
  _accountProxies.set(agentName, parseProxyUrl(proxyUrl));
  console.log('[NetworkGate] ' + agentName + ' 绑定代理:', parseProxyUrl(proxyUrl));
}

function getAccountProxy(agentName) {
  return _accountProxies.get(agentName) || null;
}

async function getUsableProxyForAccount(agentName, fallbackDirect = true) {
  const proxy = getAccountProxy(agentName);
  if (!proxy) return fallbackDirect ? null : null;
  
  const usable = await isProxyUsable(proxy);
  if (usable) return proxy;
  
  // 尝试全局 fallback（如果有配置）
  const globalProxy = process.env.DISCORD_PROXY || null;
  if (globalProxy && globalProxy !== proxy) {
    const globalUsable = await isProxyUsable(globalProxy);
    if (globalUsable) {
      console.warn('[NetworkGate] ' + agentName + ' 的代理不可用，fallback 到全局代理');
      return globalProxy;
    }
  }
  
  if (fallbackDirect) {
    console.warn('[NetworkGate] ' + agentName + ' 无可用代理，裸连（有账号 IP 切换风险）');
    return null;
  }
  return null;
}

// ========== 批量探测 ==========

async function probeAllProxies() {
  const proxies = [...new Set([..._proxyStates.keys(), ..._accountProxies.values()].filter(Boolean))];
  console.log('[NetworkGate] 探测 ' + proxies.length + ' 个代理...');
  
  const results = [];
  for (const p of proxies) {
    const r = await checkProxy(p);
    recordProxyResult(p, r.ok);
    results.push({ proxy: p, ...r });
    console.log('[NetworkGate] ' + (r.ok ? '✅' : '❌') + ' ' + p + (r.ok ? ' (' + r.latency + 'ms)' : ' - ' + r.reason));
  }
  return results;
}

function getStatusSummary() {
  const proxies = [..._proxyStates.entries()].map(([url, state]) => ({ proxy: url, ...state }));
  const blocked = proxies.filter(p => p.status === 'blocked').length;
  const available = proxies.filter(p => p.status === 'available').length;
  return { total: proxies.length, available, blocked, proxies };
}

module.exports = {
  bindAccountProxy, getAccountProxy, getUsableProxyForAccount,
  createProxyAgent, checkProxy, isProxyUsable, recordProxyResult,
  probeAllProxies, getStatusSummary, parseProxyUrl,
};
