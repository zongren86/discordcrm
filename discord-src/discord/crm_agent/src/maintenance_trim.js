'use strict';

/**
 * maintenance_trim.js
 * 
 * 定期维护清理（来自同行 node_agent 的 MAINTENANCE_TRIM 任务）
 * 
 * 清理内容：
 *   1. 僵尸 Chrome 进程（启动时可能残留）
 *   2. 超时的 Playwright 连接
 *   3. 临时文件（Playwright 下载残留）
 *   4. 过期的 seenMessageIds 缓存
 *   5. 过期的 tokenInvalidReportedAt 记录
 * 
 * 同行日志事件：
 *   task_enqueued(type=MAINTENANCE_TRIM, reason=periodic_trim)
 *   task_started(type=MAINTENANCE_TRIM, active_accounts=1, active_task_count=1)
 *   trim_completed(trimmed_process_count=6)
 *   trim_skipped(reason=resource_gate, resource_state=yellow) — 资源紧张时让道
 */

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const TRIM_INTERVAL_MS = 30 * 60 * 1000; // 每 30 分钟一次
const TRIM_ENABLED = true;

/**
 * 执行一次完整清理
 * @returns {{ trimmedProcesses: number, cleanedFiles: number, clearedEntries: number, skipped: boolean, skipReason?: string }}
 */
async function runTrim() {
  // 检查资源状态：red 状态不 trim，让道给核心任务
  try {
    const { scheduler } = require('./scheduler/task_scheduler');
    const status = scheduler.getStatus();
    if (status.resource_state === 'red') {
      console.log('[Trim] ⏭️ 资源紧张 (red)，跳过维护清理');
      try {
        const trace = require('./observability/runtime_trace');
        trace.logEvent('trim_skipped', { reason: 'resource_gate', resource_state: 'red' });
      } catch {}
      return { trimmedProcesses: 0, cleanedFiles: 0, clearedEntries: 0, skipped: true, skipReason: 'resource_gate_red' };
    }
  } catch {}

  console.log('[Trim] 🔧 开始定期维护清理...');
  let trimmedProcesses = 0, cleanedFiles = 0, clearedEntries = 0;

  // 1. 清理僵尸 Chrome 进程
  try {
    const chromeProcesses = _findZombieChrome();
    if (chromeProcesses.length > 0) {
      console.log(`[Trim] 发现 ${chromeProcesses.length} 个僵尸 Chrome 进程`);
      for (const pid of chromeProcesses) {
        try {
          process.kill(pid, 'SIGKILL');
          trimmedProcesses++;
        } catch {}
      }
    }
  } catch (err) {
    console.warn('[Trim] Chrome 清理失败:', err.message);
  }

  // 2. 清理 Playwright 临时下载文件
  try {
    const tmpDirs = [
      path.join(os.tmpdir(), 'playwright'),
      path.join(os.tmpdir(), 'playwright-downloads'),
    ];
    for (const dir of tmpDirs) {
      if (fs.existsSync(dir)) {
        const files = fs.readdirSync(dir);
        const cutoff = Date.now() - 30 * 60 * 1000; // 超过 30 分钟
        for (const f of files) {
          const fp = path.join(dir, f);
          try {
            const stat = fs.statSync(fp);
            if (stat.isFile() && stat.mtimeMs < cutoff) {
              fs.unlinkSync(fp);
              cleanedFiles++;
            }
          } catch {}
        }
      }
    }
  } catch (err) {
    console.warn('[Trim] 临时文件清理失败:', err.message);
  }

  // 3. 清理过期 tokenInvalidReportedAt（24h 前的记录可以清掉）
  try {
    // 这部分在 index.js 里，通过事件通知
    const { scheduler } = require('./scheduler/task_scheduler');
    scheduler.emit('trim_cleanup_request', { type: 'token_invalid_cooldown' });
  } catch {}

  // 4. 清理旧的 runtime_trace 文件（保留最近 24h）
  try {
    const traceFile = path.join(__dirname, '..', 'data', 'runtime_trace.jsonl');
    if (fs.existsSync(traceFile)) {
      const stat = fs.statSync(traceFile);
      // 超过 50MB 就截断
      if (stat.size > 50 * 1024 * 1024) {
        const content = fs.readFileSync(traceFile, 'utf8');
        const lines = content.split('\n');
        // 保留后 5000 行
        const keepLines = lines.slice(-5000).filter(Boolean);
        fs.writeFileSync(traceFile, keepLines.join('\n') + '\n');
        clearedEntries = lines.length - keepLines.length;
        console.log(`[Trim] runtime_trace 截断: 保留 ${keepLines.length} 行，删除 ${clearedEntries}`);
      }
    }
  } catch (err) {
    console.warn('[Trim] trace 清理失败:', err.message);
  }

  console.log(`[Trim] ✅ 清理完成: 僵尸进程=${trimmedProcesses} 临时文件=${cleanedFiles} trace条目=${clearedEntries}`);

  try {
    const trace = require('./observability/runtime_trace');
    trace.logEvent('trim_completed', {
      trimmed_process_count: trimmedProcesses,
      cleaned_files: cleanedFiles,
      cleared_entries: clearedEntries,
    });
  } catch {}

  return { trimmedProcesses, cleanedFiles, clearedEntries, skipped: false };
}

function _findZombieChrome() {
  if (process.platform === 'win32') {
    try {
      const output = execSync('tasklist /FI "IMAGENAME eq chrome.exe" /FO CSV', { encoding: 'utf8', timeout: 5000 });
      const lines = output.trim().split('\n').slice(2); // 跳过表头
      return lines.map(l => {
        const m = l.match(/"(\d+)"/g);
        return m ? parseInt(m[m.length - 1].replace(/"/g, ''), 10) : null;
      }).filter(Boolean);
    } catch { return []; }
  } else {
    try {
      const output = execSync('pgrep -f "chrome.*--headless|Chromium.*--headless" || true', { encoding: 'utf8', timeout: 5000 });
      return output.trim().split('\n').filter(Boolean).map(Number);
    } catch { return []; }
  }
}

// ========== 定时运行 ==========

let trimTimer = null;

function start() {
  if (trimTimer) return;
  if (!TRIM_ENABLED) return;
  trimTimer = setInterval(() => {
    runTrim().catch(err => console.error('[Trim] 异常:', err.message));
  }, TRIM_INTERVAL_MS);
  console.log(`[Trim] 定期维护启动 interval=${TRIM_INTERVAL_MS / 60000}min`);
}

function stop() {
  if (trimTimer) { clearInterval(trimTimer); trimTimer = null; }
  console.log('[Trim] 定期维护停止');
}

module.exports = { runTrim, start, stop };
