/**
 * Discord 用户采集 —— Playwright
 * 三层 token 捕获：网络拦截(最可靠) → localStorage → IndexedDB
 * v1.2.0: 超时/取消/异常都强制关闭浏览器
 */
const { chromium } = require('playwright');
const { execSync } = require('child_process');
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

function isContextAlive(context) {
  if (!context) return false;
  try {
    if (typeof context.isClosed === 'function') return !context.isClosed();
    context.pages(); return true;
  } catch { return false; }
}

async function pageReady(page) {
  try {
    const url = page.url();
    if (!url || url.startsWith('about:') || url.startsWith('chrome:')) return false;
    if (!url.includes('discord.com') && !url.includes('discordapp.com')) return false;
    return true;
  } catch { return false; }
}

async function checkCancelled(http, taskId) {
  try {
    const resp = await http.get('/agent-servers/tasks/' + taskId);
    return resp && resp.status === 'CANCELLED';
  } catch { return false; }
}

/**
 * 强制关闭浏览器 context
 * Playwright context.close() 在某些场景可能不真正关窗口，加进程 kill 兜底
 */
async function safeClose(context) {
  if (!context) return;
  try {
    if (typeof context.close === 'function') {
      await context.close();
      console.log('[Browser] context.close() 已执行');
    }
  } catch (e) {
    console.warn('[Browser] context.close() 异常:', String(e.message || e).split('\n')[0]);
  }
  try {
    const browser = context.browser && context.browser();
    if (browser && typeof browser.close === 'function') {
      await browser.close();
      console.log('[Browser] browser.close() 已执行');
    }
  } catch {}
  // 兜底：杀 chromium 进程
  try {
    await new Promise(r => setTimeout(r, 300));
    if (process.platform === 'win32') {
      try { execSync('taskkill /F /IM chromium.exe /T', { stdio: 'ignore' }); } catch {}
      try { execSync('taskkill /F /IM chrome.exe /T', { stdio: 'ignore' }); } catch {}
    } else {
      try { execSync('pkill -f "Chromium.*crm-agent"', { stdio: 'ignore' }); } catch {}
      try { execSync('pkill -f "chrome.*discord"', { stdio: 'ignore' }); } catch {}
    }
    console.log('[Browser] 兜底进程清理已执行');
  } catch {}
}

/** 只唤起浏览器，不采集 */
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
  try {
    await page.goto('https://discord.com/channels/@me', { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
  } catch {}
  console.log('[Browser] ✅ 浏览器已打开');
  return { context, page };
}

async function captureDiscordAccount(browserConfig = {}, { taskId, http, agentName } = {}) {
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
    throw new Error('浏览器启动失败: ' + String(e.message || e).split('\n')[0]);
  }

  let page = context.pages()[0] || await context.newPage();
  await page.addInitScript(getInitScript());

  // 网络拦截抓 Authorization header（最可靠）
  let capturedToken = null;
  context.on('request', (request) => {
    try {
      const headers = request.headers();
      const auth = headers['authorization'] || headers['Authorization'];
      if (auth && !auth.startsWith('Bot ') && auth.length > 50) {
        if (!capturedToken || capturedToken !== auth) {
          capturedToken = auth;
          console.log(`[Browser] 🎯 网络拦截捕获 token (${auth.length} chars)`);
        }
      }
    } catch {}
  });

  console.log('[Browser] 打开 Discord 登录页...');
  try {
    await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 30000 });
  } catch (e) {
    console.log('[Browser] goto 超时，继续等待...');
  }

  console.log('[Browser] 等待 Discord 页面就绪...');
  for (let i = 0; i < 15; i++) {
    await new Promise(r => setTimeout(r, 1000));
    if (await pageReady(page)) break;
    if (!isContextAlive(context)) {
      await safeClose(context);
      throw new Error('用户关闭了浏览器');
    }
    const pages = context.pages();
    page = pages.find(p => (p.url() || '').includes('discord')) || page;
  }

  const result = { token: null, userId: null, username: null, email: null, avatarUrl: null };

  const scanStorage = async () => {
    try {
      return await page.evaluate(async () => {
        try {
          const tokenPattern = /[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{20,}/;
          if (typeof localStorage !== 'undefined') {
            for (let i = 0; i < localStorage.length; i++) {
              const v = localStorage.getItem(localStorage.key(i));
              if (v && tokenPattern.test(v)) return v;
            }
          }
          if (typeof sessionStorage !== 'undefined') {
            for (let i = 0; i < sessionStorage.length; i++) {
              const v = sessionStorage.getItem(sessionStorage.key(i));
              if (v && tokenPattern.test(v)) return v;
            }
          }
          if (typeof indexedDB !== 'undefined') {
            try {
              const dbs = await indexedDB.databases();
              for (const db of dbs) {
                if (!db.name) continue;
                let t = null;
                await new Promise((resolve) => {
                  const req = indexedDB.open(db.name);
                  req.onsuccess = async () => {
                    try {
                      const names = req.result.objectStoreNames;
                      for (const n of names) {
                        const all = req.result.transaction(n, 'readonly').objectStore(n).getAll();
                        await new Promise(r2 => {
                          all.onsuccess = () => {
                            for (const item of all.result || []) {
                              const s = typeof item === 'string' ? item : JSON.stringify(item);
                              const m = s.match(tokenPattern);
                              if (m) { t = m[0]; break; }
                            }
                            r2();
                          };
                          all.onerror = () => r2();
                        });
                        if (t) break;
                      }
                    } catch {}
                    resolve();
                  };
                  req.onerror = () => resolve();
                });
                if (t) return t;
              }
            } catch {}
          }
          return null;
        } catch { return null; }
      });
    } catch { return null; }
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

    // 1. 取消
    if (taskId && http && await checkCancelled(http, taskId)) {
      console.log('[Browser] ❌ 任务已被取消');
      await safeClose(context);
      const err = new Error('任务已被取消'); err.code = 'CANCELLED'; throw err;
    }

    // 2. 浏览器存活
    if (!isContextAlive(context)) {
      console.log('[Browser] ❌ 用户关闭了浏览器');
      const err = new Error('用户关闭了浏览器'); err.code = 'BROWSER_CLOSED'; throw err;
    }

    // 3. page 还在 discord 吗
    if (!await pageReady(page)) {
      const pages = context.pages();
      page = pages.find(p => (p.url() || '').includes('discord')) || page;
      if (scanCount <= 5 || scanCount % 15 === 0) {
        console.log(`[Browser] 扫描#${scanCount} 页面未就绪 (url=${page.url()})`);
      }
      continue;
    }

    // 4. 拿 token
    let token = capturedToken;
    if (!token) {
      token = await scanStorage();
      if (token) console.log(`[Browser] 🎯 storage 扫描捕获 token (${token.length} chars)`);
    }
    if (token && token !== result.token) result.token = token;

    if (scanCount <= 3 || scanCount % 10 === 0) {
      console.log(`[Browser] 扫描#${scanCount}  token=${result.token ? result.token.slice(0,20)+'...' : '(无)'}  user=${result.username || '(无)'}`);
    }

    // 5. 有 token 就拿用户信息
    if (result.token && !result.userId) {
      const user = await fetchUser(result.token);
      if (user) {
        result.userId = user.id;
        result.username = user.username || user.global_name || user.display_name;
        result.email = user.email || null;
        if (user.avatar) result.avatarUrl = `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png`;
        console.log(`[Browser] ✅ 捕获用户: ${result.username} (${result.userId})`);
      }
    }

    if (result.token && result.userId) {
      result.browserProfilePath = userDataDir;
      console.log('[Browser] 🎉 采集成功！');
      return result;
    }
  }

  // 超时：强制关浏览器
  console.log('[Browser] ⏰ 采集超时，强制关闭浏览器...');
  await safeClose(context);
  throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
}

module.exports = { captureDiscordAccount, launchBrowserOnly };
