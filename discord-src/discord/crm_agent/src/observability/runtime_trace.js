'use strict';

/**
 * observability/runtime_trace.js
 * 
 * 运行时追踪（来自同行 node_agent 的 node_runtime_trace.jsonl）
 * 
 * 记录：browser_context_present, chrome_pid_count, gateway_present 等
 * 写入 JSONL 文件供后续诊断
 */

const fs = require('fs');
const path = require('path');

let _logStream = null;
let _traceFile = null;
let _started = false;

function start(logDir) {
  if (_started) return;
  try {
    const dir = logDir || path.join(process.cwd(), 'data', 'logs');
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    const ts = new Date().toISOString().replace(/[:.]/g, '-');
    _traceFile = path.join(dir, 'runtime-trace-' + ts + '.jsonl');
    _logStream = fs.createWriteStream(_traceFile, { flags: 'a' });
    _started = true;
    console.log('[Trace] 📝 运行时追踪写入:', _traceFile);
  } catch (e) {
    console.warn('[Trace] 启动失败:', e.message);
  }
}

function event(type, data = {}) {
  if (!_started || !_logStream) return;
  try {
    const entry = {
      ts: new Date().toISOString(),
      event: type,
      ...data,
    };
    _logStream.write(JSON.stringify(entry) + '\n');
  } catch {}
}

function browserWorkflow(action, data = {}) {
  event('browser_workflow_' + action, { source: 'browser', ...data });
}

function gatewayEvent(data = {}) {
  event('gateway_event', { source: 'gateway', ...data });
}

function networkEvent(data = {}) {
  event('network_event', { source: 'network', ...data });
}

function taskEvent(action, data = {}) {
  event('task_' + action, { source: 'task', ...data });
}

function stop() {
  if (_logStream) {
    _logStream.end();
    _logStream = null;
  }
  _started = false;
}

module.exports = { start, stop, event, browserWorkflow, gatewayEvent, networkEvent, taskEvent };
