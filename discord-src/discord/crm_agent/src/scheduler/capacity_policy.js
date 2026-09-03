'use strict';

/**
 * scheduler/capacity_policy.js
 * 
 * 并发槽位控制（来自同行 node_agent 的 capacity_policy）
 * 
 * 策略：
 *   - 同时最多 N 个浏览器实例（START_ACCOUNT）
 *   - 同时最多 M 个 Gateway 重连（GATEWAY_RECONNECT）
 *   - 避免瞬间资源耗尽导致系统不稳定
 */

const _slots = {
  START_ACCOUNT: { max: 2, used: 0 },
  GATEWAY_RECONNECT: { max: 16, used: 0 },
  LAUNCH_BROWSER: { max: 4, used: 0 },
};

const _waiters = new Map(); // slotType -> [{ resolve, timeout }]

function setCapacity(slotType, max) {
  if (!_slots[slotType]) _slots[slotType] = { max: 0, used: 0 };
  _slots[slotType].max = max;
  console.log('[Capacity] ' + slotType + ' max=' + max);
}

async function acquire(slotType, timeoutMs = 30000) {
  const slot = _slots[slotType];
  if (!slot) throw new Error('unknown slot: ' + slotType);
  
  if (slot.used < slot.max) {
    slot.used++;
    return true;
  }
  
  // 排队等
  return await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      resolve(false);
      const list = _waiters.get(slotType) || [];
      const idx = list.findIndex(w => w.resolve === resolve);
      if (idx >= 0) list.splice(idx, 1);
    }, timeoutMs);
    
    if (!_waiters.has(slotType)) _waiters.set(slotType, []);
    _waiters.get(slotType).push({ resolve: () => { clearTimeout(timeout); resolve(true); }, timeout });
  });
}

function release(slotType) {
  const slot = _slots[slotType];
  if (!slot || slot.used <= 0) return;
  slot.used--;
  
  // 唤醒等待队列
  const waiters = _waiters.get(slotType);
  if (waiters && waiters.length > 0) {
    const next = waiters.shift();
    slot.used++;
    next.resolve();
  }
}

function getStatus() {
  return Object.fromEntries(Object.entries(_slots).map(([k, v]) => [k, { ...v }]));
}

module.exports = { acquire, release, setCapacity, getStatus };
