'use strict';

/**
 * task_scheduler.js
 * 
 * 任务调度器（来自同行 node_agent 的 capacity_policy + scheduler + lifecycle 体系）
 * 
 * 核心能力：
 *   1. 5 种任务类型：START_ACCOUNT, RESTART_ACCOUNT, GATEWAY_RECONNECT, BROWSER_INIT, MAINTENANCE_TRIM
 *   2. 每种类型独立槽位限制（capacity）
 *   3. per-account 串行：同一账号同时只跑一个任务
 *   4. 优先级 + 容量溢出自动降级 resource_state（green/yellow/red）
 *   5. 完整生命周期事件：enqueued → started → finished
 */

const EventEmitter = require('events');

const TASK_TYPES = {
  START_ACCOUNT: 'START_ACCOUNT',
  RESTART_ACCOUNT: 'RESTART_ACCOUNT',
  GATEWAY_RECONNECT: 'GATEWAY_RECONNECT',
  BROWSER_INIT: 'BROWSER_INIT',
  MAINTENANCE_TRIM: 'MAINTENANCE_TRIM',
};

const DEFAULT_CAPACITY = {
  [TASK_TYPES.START_ACCOUNT]: 2,
  [TASK_TYPES.RESTART_ACCOUNT]: 2,
  [TASK_TYPES.GATEWAY_RECONNECT]: 16,
  [TASK_TYPES.BROWSER_INIT]: 5,
  [TASK_TYPES.MAINTENANCE_TRIM]: 1,
};

class TaskScheduler extends EventEmitter {
  constructor() {
    super();
    this.capacity = { ...DEFAULT_CAPACITY };
    this.active = new Map();
    this.pending = [];
    this.accountLocks = new Set();
    this.counter = 0;
    this._running = false;
    this._tickTimer = null;
    this._tickIntervalMs = 100;
  }

  setCapacity(taskType, max) {
    if (!this.capacity[taskType]) return;
    this.capacity[taskType] = max;
    console.log(`[Scheduler] capacity ${taskType}=${max}`);
    this._emitCapacityPolicy();
  }

  getResourceState() {
    let totalUsed = 0, totalMax = 0;
    const slotUsage = {};
    for (const [type, max] of Object.entries(this.capacity)) {
      const activeCount = [...this.active.values()].filter(t => t.type === type).length;
      totalUsed += activeCount;
      totalMax += max;
      slotUsage[type] = { used: activeCount, max };
    }
    const ratio = totalMax > 0 ? totalUsed / totalMax : 0;
    const state = ratio >= 0.9 ? 'red' : ratio >= 0.7 ? 'yellow' : 'green';
    return { state, ratio, totalUsed, totalMax, slotUsage };
  }

  _emitCapacityPolicy() {
    const res = this.getResourceState();
    const taskTypeSlots = {};
    for (const [type, max] of Object.entries(this.capacity)) {
      taskTypeSlots[type.toLowerCase() + '_slots'] = max;
    }
    this.emit('capacity_policy_updated', {
      resource_state: res.state,
      task_type_slots: taskTypeSlots,
      account_start_slots: this.capacity[TASK_TYPES.START_ACCOUNT],
      gateway_reconnect_slots: this.capacity[TASK_TYPES.GATEWAY_RECONNECT],
    });
    try {
      const trace = require('../observability/runtime_trace');
      trace.logEvent('capacity_policy_updated', {
        resource_state: res.state,
        ...taskTypeSlots,
      });
    } catch {}
  }

  enqueue(type, accountId, handler, priority = 10) {
    if (!this.capacity[type]) {
      console.warn(`[Scheduler] 未知任务类型: ${type}`);
      return null;
    }
    this.counter++;
    const taskId = `task_${Date.now()}_${this.counter}`;
    const entry = { taskId, type, accountId, handler, priority, enqueuedAt: Date.now() };
    this.pending.push(entry);
    this.pending.sort((a, b) => {
      if (b.priority !== a.priority) return b.priority - a.priority;
      return a.enqueuedAt - b.enqueuedAt;
    });
    console.log(`[Scheduler] task_enqueued ${type} id=${taskId} account=${accountId}`);
    this.emit('task_enqueued', entry);
    try {
      const trace = require('../observability/runtime_trace');
      trace.logEvent('task_enqueued', { task_id: taskId, task_type: type, account_id: accountId });
    } catch {}
    this._emitCapacityPolicy();
    this._tick();
    return taskId;
  }

  async _tick() {
    if (this._running) return;
    this._running = true;
    try {
      while (true) {
        let picked = null;
        for (let i = 0; i < this.pending.length; i++) {
          const t = this.pending[i];
          const activeCount = [...this.active.values()].filter(a => a.type === t.type).length;
          if (activeCount >= this.capacity[t.type]) continue;
          if (t.accountId != null && this.accountLocks.has(t.accountId)) continue;
          picked = t;
          this.pending.splice(i, 1);
          break;
        }
        if (!picked) break;
        await this._execute(picked);
      }
    } finally {
      this._running = false;
    }
  }

  async _execute(task) {
    const taskId = task.taskId;
    this.active.set(taskId, { type: task.type, accountId: task.accountId, startedAt: Date.now() });
    if (task.accountId != null) this.accountLocks.add(task.accountId);
    console.log(`[Scheduler] task_started ${task.type} id=${taskId} account=${task.accountId}`);
    this.emit('task_started', task);
    try {
      const trace = require('../observability/runtime_trace');
      trace.logEvent('task_started', { task_id: taskId, task_type: task.type, account_id: task.accountId });
    } catch {}
    this._emitCapacityPolicy();
    try {
      const result = await Promise.resolve().then(() => task.handler(taskId));
      console.log(`[Scheduler] task_finished ${task.type} id=${taskId} account=${task.accountId}`);
      this.emit('task_finished', { ...task, result });
      try {
        const trace = require('../observability/runtime_trace');
        trace.logEvent('task_finished', { task_id: taskId, task_type: task.type, account_id: task.accountId });
      } catch {}
    } catch (err) {
      console.error(`[Scheduler] task_failed ${task.type} id=${taskId}:`, err.message);
      this.emit('task_failed', { ...task, error: err.message });
    } finally {
      this.active.delete(taskId);
      if (task.accountId != null) this.accountLocks.delete(task.accountId);
      this._emitCapacityPolicy();
      setImmediate(() => this._tick());
    }
  }

  getStatus() {
    const res = this.getResourceState();
    return {
      resource_state: res.state,
      pending_count: this.pending.length,
      active_count: this.active.size,
      active_by_type: this._countByType(this.active),
      pending_by_type: this._countByType(this.pending),
      background_task_count: this.active.size + this.pending.length,
    };
  }

  _countByType(mapOrArr) {
    const result = {};
    const items = mapOrArr instanceof Map ? [...mapOrArr.values()] : mapOrArr;
    for (const t of items) result[t.type] = (result[t.type] || 0) + 1;
    return result;
  }

  start() {
    if (this._tickTimer) return;
    this._tickTimer = setInterval(() => this._tick(), this._tickIntervalMs);
    console.log('[Scheduler] started');
  }

  stop() {
    if (this._tickTimer) { clearInterval(this._tickTimer); this._tickTimer = null; }
    console.log('[Scheduler] stopped');
  }
}

const scheduler = new TaskScheduler();

module.exports = { TASK_TYPES, scheduler, TaskScheduler };
