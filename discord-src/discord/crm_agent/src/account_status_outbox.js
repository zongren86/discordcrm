'use strict';

/**
 * account_status_outbox.js
 * 
 * 账号状态上报 outbox（来自同行 node_agent 的 account_status_report_outbox）
 * 
 * 特性：
 *   1. 批量收集账号状态变更
 *   2. 定时上报到后端（避免频繁 HTTP 请求）
 *   3. 失败自动重试（指数退避）
 *   4. 离线缓存到本地 JSONL（启动丢失不丢数据）
 * 
 * 同行日志关键行为：
 *   账号状态批次发送失败，将保留重试: All connection attempts failed
 */

const fs = require('fs');
const path = require('path');

const BATCH_INTERVAL_MS = 15000;   // 每 15s 批量上报一次
const BATCH_MAX_SIZE = 100;        // 单次最多 100 条
const MAX_RETRY = 5;               // 最大重试次数
const RETRY_BASE_MS = 2000;        // 初始退避 2s

let http = null;
let cfg = null;
let pending = [];          // [{ type, accountId, data, createdAt }]
let retryCount = 0;
let flushTimer = null;
let outboxFile = null;

function init(httpModule, cfgModule) {
  http = httpModule;
  cfg = cfgModule;
  outboxFile = path.join(__dirname, '..', 'data', 'status_outbox.jsonl');
  // 加载离线缓存
  _loadFromDisk();
}

function _loadFromDisk() {
  if (!fs.existsSync(outboxFile)) return;
  try {
    const content = fs.readFileSync(outboxFile, 'utf8');
    const lines = content.split('\n').filter(Boolean);
    for (const line of lines) {
      try { pending.push(JSON.parse(line)); } catch {}
    }
    if (pending.length > 0) {
      console.log(`[Outbox] 加载离线缓存 ${pending.length} 条`);
    }
  } catch (err) {
    console.warn('[Outbox] 离线缓存加载失败:', err.message);
  }
}

function _persist() {
  if (!outboxFile) return;
  try {
    fs.mkdirSync(path.dirname(outboxFile), { recursive: true });
    const data = pending.map(p => JSON.stringify(p)).join('\n') + '\n';
    fs.writeFileSync(outboxFile, data);
  } catch {}
}

/**
 * 记录账号状态变更
 */
function record(type, accountId, data = {}) {
  const entry = { type, accountId, data, createdAt: Date.now() };
  pending.push(entry);
  if (pending.length >= BATCH_MAX_SIZE) {
    _flush().catch(() => {});
  } else {
    _persist(); // 增量持久化
  }
}

async function _flush() {
  if (pending.length === 0) return;
  if (!http || !cfg) { console.warn('[Outbox] 未初始化'); return; }

  const batch = pending.splice(0, BATCH_MAX_SIZE);
  console.log(`[Outbox] 批量上报 ${batch.length} 条状态变更`);

  try {
    await http.post('/agent-servers/accounts/status-batch', {
      token: cfg.token,
      agentName: cfg.agentName,
      items: batch,
    });
    retryCount = 0;
    // 成功后清掉磁盘缓存
    _persist();
    console.log(`[Outbox] ✅ 上报成功 ${batch.length} 条`);
  } catch (err) {
    // 放回队列，等待重试
    pending = [...batch, ...pending];
    retryCount++;
    const delay = Math.min(RETRY_BASE_MS * Math.pow(2, Math.min(retryCount, MAX_RETRY)), 60000);
    console.warn(`[Outbox] ❌ 上报失败，${delay / 1000}s 后重试 (attempt=${retryCount}/∞): ${err.message}`);
    _persist();
    setTimeout(() => _flush(), delay);
  }
}

function start() {
  if (flushTimer) return;
  flushTimer = setInterval(() => { _flush().catch(() => {}); }, BATCH_INTERVAL_MS);
  console.log(`[Outbox] 状态上报启动 interval=${BATCH_INTERVAL_MS / 1000}s`);
  // 启动时立即 flush 一次（可能有离线缓存）
  setTimeout(() => _flush().catch(() => {}), 2000);
}

function stop() {
  if (flushTimer) { clearInterval(flushTimer); flushTimer = null; }
  // 停止前强制 flush
  _flush().catch(() => {});
  _persist();
  console.log('[Outbox] 状态上报停止');
}

function getStatus() {
  return {
    pendingCount: pending.length,
    retryCount,
    outboxFile,
  };
}

module.exports = { init, record, start, stop, _flush, getStatus };
