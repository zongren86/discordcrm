/**
 * Discord 用户采集 —— Playwright
 * 支持：新账号采集、持久化 profile 唤起、取消/浏览器关闭检测
 */
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

function getLaunchArgs() {
  return [
    '--no-sandbox',
    '--disable-blink-features=AutomationControlled',
    '--disable-dev-shm-usage',
    '--disable-gpu',
    '--disable-infobars',
    '--disable-features=AutomationControlled',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-sync',
    '--disable-breakpad',
    '--no-title-update',
    '--no-window-animation',
  ];
}

function getInitScript() {
  return `
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en'] });
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
    window.chrome = { runtime: {} };
    const origQ = window.navigator.permissions.query;
    window.navigator.permissions.query = (p) => (
      p.name === 'notifications' ? Promise.resolve({ state: Notification.permission }) : origQ(p)
    );
    try { document.title = document.title.replace(/[—-]\\s*Chrome.*Automation.*$/i, ''); } catch {}
  `;
}

/**
 * 检测浏览器是否还活着（用 SingletonLock 文件）
 */
function isBrowserAlive(userDataDir) {
  const lockFile = path.join(userDataDir, 'SingletonLock');
  if (!fs.existsSync(lockFile)) return false;
  // Windows 上 SingletonLock 是 socket 文件，存在且被占用 = 活着
  try {
    const stat = fs.statSync(lockFile);
    return stat.size > 0 || Date.now() - stat.atimeMs < 30000;
  } catch {
    return false;
  }
}

/**
 * 检测后端任务状态（是否已被取消）
 */
async function checkCancelled(http, taskId) {
  try {
    const resp = await http.get('/agent-servers/tasks/' + taskId);
    return resp && resp.status === 'CANCELLED';
  } catch {
    return false;
  }
}

/**
 * 打开持久化 profile 浏览器（只展示不采集）
 */
async function launchBrowserOnly(browserProfilePath, browserConfig = {}) {
  if (!browserProfilePath) throw new Error('缺少 browserProfilePath');
  const userDataDir = path.resolve(browserProfilePath);
  if (!fs.existsSync(userDataDir)) throw new Error('浏览器 profile 不存在: ' + userDataDir);

  console.log('[Browser] 唤起持久化浏览器 profile...');
  const context = await chromium.launchPersistentContext(userDataDir, {
    headless: false,
    viewport: browserConfig.viewport || { width: 1280, height: 800 },
    args: getLaunchArgs(),
    ignoreHTTPSErrors: true,
  });

  const page = context.pages()[0] || await context.newPage();
  await page.addInitScript(getInitScript());

  // 打开 Discord（如果还没登录会跳到登录页）
  try {
    await page.goto('https://discord.com/channels/@me', { waitUntil: 'domcontentloaded', timeout: 15000 });
  } catch {
    try { await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 15000 }); } catch {}
  }

  console.log('[Browser] ✅ 浏览器已打开，等待用户手动关闭...');
  return { context, page };
}

/**
 * Discord 账号采集
 */
async function captureDiscordAccount(browserConfig = {}, { taskId, http, agentName } = {}) {
  // 用 agentName 生成固定的 profile 路径（采集成功后持久化）
  const userDataDir = path.join(
    os.tmpdir(),
    `crm-agent-${agentName || 'default'}-${Date.now()}`
  );

  try { fs.mkdirSync(userDataDir, { recursive: true }); } catch {}

  console.log(`[Browser] 启动 Chromium... (profile=${userDataDir})`);

  let context;
  try {
    context = await chromium.launchPersistentContext(userDataDir, {
      headless: browserConfig.headless ?? false,
      viewport: browserConfig.viewport || { width: 1280, height: 800 },
      args: getLaunchArgs(),
      ignoreHTTPSErrors: true,
    });
  } catch (e) {
    throw new Error('浏览器启动失败: ' + e.message.split('\n')[0]);
  }

  const page = context.pages()[0] || await context.newPage();
  await page.addInitScript(getInitScript());

  console.log('[Browser] 打开 Discord 登录页...');
  try {
    await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 30000 });
  } catch (e) {
    try { await context.close(); } catch {}
    throw new Error('无法打开 Discord 登录页（网络问题？）');
  }

  const result = { token: null, userId: null, username: null, email: null, avatarUrl: null };

  const scanToken = async () => {
    return await page.evaluate(async () => {
      let token = null;
      const scan = (storage) => {
        for (const k of Object.keys(storage)) {
          const v = storage.getItem(k);
          if (v && v.split('.').length === 3 && v.length > 50) { token = v; return true; }
        }
        return false;
      };
      scan(localStorage) || scan(sessionStorage);
      // IndexedDB fallback
      if (!token) {
        try {
          const dbs = await indexedDB.databases();
          for (const db of dbs) {
            if (!db.name) continue;
            try {
              await new Promise((resolve) => {
                const req = indexedDB.open(db.name);
                req.onsuccess = () => {
                  try {
                    const names = req.result.objectStoreNames;
                    if (names.length > 0) {
                      const tx = req.result.transaction(names[0], 'readonly');
                      const all = tx.objectStore(names[0]).getAll();
                      all.onsuccess = () => {
                        for (const item of all.result || []) {
                          const str = typeof item === 'string' ? item : JSON.stringify(item);
                          const m = str.match(/[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{20,}/);
                          if (m) { token = m[0]; break; }
                        }
                      };
                    }
                  } catch {}
                  resolve();
                };
                req.onerror = () => resolve();
              });
            } catch {}
            if (token) break;
          }
        } catch {}
      }
      return token;
    });
  };

  const fetchUser = async (token) => {
    try {
      const r = await page.evaluate(async (t) => {
        try {
          const resp = await fetch('https://discord.com/api/users/@me', { headers: { Authorization: t } });
          if (resp.ok) return { ok: true, data: await resp.json() };
        } catch {}
        return { ok: false };
      }, token);
      if (r && r.ok) return r.data;
    } catch {}
    return null;
  };

  console.log('[Browser] 等待用户登录 Discord... (最多 5 分钟)');
  const deadline = Date.now() + 5 * 60 * 1000;
  let scanCount = 0;

  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 2000));
    scanCount++;

    // 1. 检测取消
    if (taskId && http && await checkCancelled(http, taskId)) {
      console.log('[Browser] ❌ 任务已被取消');
      try { await context.close(); } catch {}
      const err = new Error('任务已被取消');
      err.code = 'CANCELLED';
      throw err;
    }

    // 2. 检测浏览器是否还活着（用户可能主动关了）
    if (!isBrowserAlive(userDataDir)) {
      console.log('[Browser] ❌ 浏览器已关闭');
      const err = new Error('用户关闭了浏览器');
      err.code = 'BROWSER_CLOSED';
      throw err;
    }

    // 3. 扫描 token
    const token = await scanToken();
    if (token && token !== result.token) {
      result.token = token;
      console.log(`[Browser] 扫描#${scanCount} 找到 token (${token.length} chars)`);
    }

    if (result.token && !result.userId) {
      const user = await fetchUser(result.token);
      if (user) {
        result.userId = user.id;
        result.username = user.username || user.global_name || user.display_name;
        result.email = user.email || null;
        if (user.avatar) {
          result.avatarUrl = `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png`;
        }
        console.log(`[Browser] ✅ 捕获到用户: ${result.username} (${result.userId})`);
      }
    }

    if (result.token && result.userId) {
      // 登录成功，不自动关浏览器（让用户看到"保存中"提示）
      result.browserProfilePath = userDataDir;
      console.log('[Browser] 🎉 采集成功，profile 已持久化，等待后端保存确认...');
      return result;
    }
  }

  // 超时
  try { await context.close(); } catch {}
  throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
}

module.exports = { captureDiscordAccount, launchBrowserOnly };
