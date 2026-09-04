'use strict';

/**
 * scheduler/capacity_policy.js v2
 * 
 * 并发槽位控制 — 与同行 node_agent 完整对齐
 * 
 * 5 种任务类型槽位：
 *   - START_ACCOUNT (max=2) 启动账号
 *   - RESTART_ACCOUNT (max=2) 重启账号
 *   - GATEWAY_RECONNECT (max=16) Gateway 重连
 *   - BROWSER_INIT (max=5) 浏览器初始化
 *   - MAINTENANCE_TRIM (max=1) 维护清理
 * 
 * resource_state 三色：
 *   green  — 使用率 < 70%
 *   yellow — 70% ≤ 使用率 < 90%
 *   red    — 使用率 ≥ 90%
 * 
 * 导出给 index.js 用，同时 task_scheduler.js 内部也维护自己的 capacity
 */

const _slots = {
  START_ACCOUNT: { max: 2, used: 0 },
  RESTART_ACCOUNT: { max: 2, used: 0 },
  GATEWAY_RECONNECT: { max: 16, used: 0 },
  BROWSER_INIT: { max: 5, used: 0 },
  MAINTENANCE_TRIM: { max: 1, used: 0 },
};

const _waiters = new Map();

function setCapacity(slotType, max) {
  if (!_slots[slotType]) _slots[slotType] = { max: 0, used: 0 };
  _slots[slotType].max = max;
  console.log('[Capacity] ' + slotType + ' max=' + max);
}

async function acquire(slotType, timeoutMs = 30000) {
  const slot = _slots[slotType];
  if (!slot) throw new Error('unknown slot: ' + slotType);
  if (slot.used < slot.max) { slot.used++; return true; }
  return await new Promise((resolve) => {
    const timeout = setTimeout(() => { resolve(false); }, timeoutMs);
    if (!_waiters.has(slotType)) _waiters.set(slotType, []);
    _waiters.get(slotType).push({ resolve: () => { clearTimeout(timeout); resolve(true); } });
  });
}

function release(slotType) {
  const slot = _slots[slotType];
  if (!slot || slot.used <= 0) return;
  slot.used--;
  const waiters = _waiters.get(slotType);
  if (waiters && waiters.length > 0) {
    const next = waiters.shift();
    slot.used++;
    next.resolve();
  }
}

function getResourceState() {
  let totalUsed = 0, totalMax = 0;
  for (const slot of Object.values(_slots)) {
    totalUsed += slot.used;
    totalMax += slot.max;
  }
  const ratio = totalMax > 0 ? totalUsed / totalMax : 0;
  const state = ratio >= 0.9 ? 'red' : ratio >= 0.7 ? 'yellow' : 'green';
  return { state, ratio, totalUsed, totalMax };
}

function getStatus() {
  const slots = Object.fromEntries(Object.entries(_slots).map(([k, v]) => [k, { ...v }]));
  const state = getResourceState();
  return { ...state, slots };
}

module.exports = { acquire, release, setCapacity, getStatus, getResourceState };
