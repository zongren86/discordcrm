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
 * 检测浏览器 context 是否还活着（Playwright 原生 API）
 */
function isContextAlive(context) {
  if (!context) return false;
  try {
    if (typeof context.isClosed === 'function') return !context.isClosed();
    context.pages();
    return true;
  } catch { return false; }
}

/**
 * 检测页面是否在 Discord 域名且有 storage 可用
 */
async function pageReady(page) {
  try {
    const url = page.url();
    if (!url || url.startsWith('about:') || url.startsWith('chrome:')) return false;
    if (!url.includes('discord.com') && !url.includes('discordapp.com')) return false;
    return await page.evaluate(() => {
      return typeof localStorage !== 'undefined' && typeof sessionStorage !== 'undefined';
    }).catch(() => false);
  } catch { return false; }
}

async function checkCancelled(http, taskId) {
  try {
    const resp = await http.get('/agent-servers/tasks/' + taskId);
    return resp && resp.status === 'CANCELLED';
  } catch { return false; }
}

/**
 * 只唤起持久化浏览器（不采集）
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
  try {
    await page.goto('https://discord.com/channels/@me', { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
  } catch {}
  console.log('[Browser] ✅ 浏览器已打开，等待用户手动关闭...');
  return { context, page };
}

/**
 * Discord 账号采集
 */
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
    throw new Error('浏览器启动失败: ' + e.message.split('\n')[0]);
  }

  let page = context.pages()[0] || await context.newPage();
  await page.addInitScript(getInitScript());

  // 如果默认页在 about:blank，先打开 Discord
  try {
    await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 30000 });
  } catch (e) {
    // goto 可能超时但页面其实有内容，继续让用户手动操作
    console.log('[Browser] goto 超时或失败，等待 Discord 页面加载...');
  }

  // 轮询：等到页面真的加载好了（有 localStorage）
  console.log('[Browser] 等待页面就绪...');
  for (let i = 0; i < 20; i++) {
    await new Promise(r => setTimeout(r, 1000));
    if (await pageReady(page)) break;
    if (!isContextAlive(context)) throw new Error('用户关闭了浏览器');
    // 有时 page 被用户切了 tab，找回 discord tab
    const pages = context.pages();
    page = pages.find(p => (p.url() || '').includes('discord')) || page;
  }

  const result = { token: null, userId: null, username: null, email: null, avatarUrl: null };

  // scanToken：page.evaluate 里全程 try-catch，localStorage 不存在就返回 null
  const scanToken = async () => {
    try {
      return await page.evaluate(async () => {
        try {
          if (typeof localStorage === 'undefined' && typeof sessionStorage === 'undefined') return null;
          const scan = (storage) => {
            if (!storage) return false;
            try {
              for (const k of Object.keys(storage)) {
                const v = storage.getItem(k);
                if (v && v.split('.').length === 3 && v.length > 50) { token = v; return true; }
              }
            } catch {}
            return false;
          };
          let token = null;
          scan(localStorage) || scan(sessionStorage);
          // IndexedDB
          if (!token) {
            try {
              if (typeof indexedDB !== 'undefined') {
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
              }
            } catch {}
          }
          return token;
        } catch (e) {
          return null;
        }
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

    // 1. 取消检测
    if (taskId && http && await checkCancelled(http, taskId)) {
      console.log('[Browser] ❌ 任务已被取消');
      try { await context.close(); } catch {}
      const err = new Error('任务已被取消'); err.code = 'CANCELLED'; throw err;
    }

    // 2. 浏览器存活
    if (!isContextAlive(context)) {
      console.log('[Browser] ❌ context.isClosed() = true');
      const err = new Error('用户关闭了浏览器'); err.code = 'BROWSER_CLOSED'; throw err;
    }

    // 3. page 还在 discord 吗？
    if (!await pageReady(page)) {
      // 用户可能切了 tab，找回来
      const pages = context.pages();
      page = pages.find(p => (p.url() || '').includes('discord')) || page;
      if (scanCount <= 5 || scanCount % 15 === 0) {
        console.log(`[Browser] 扫描#${scanCount} 页面未就绪 (url=${page.url()})，跳过本轮`);
      }
      continue;
    }

    if (scanCount <= 3 || scanCount % 10 === 0) {
      console.log(`[Browser] 扫描#${scanCount}  hasToken=${!!result.token}  hasUser=${!!result.userId}`);
    }

    // 4. 扫 token
    const token = await scanToken();
    if (token && token !== result.token) {
      result.token = token;
      console.log(`[Browser] ✅ 找到 token (${token.length} chars)`);
    }

    // 5. 拿用户信息
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
      console.log('[Browser] 🎉 采集成功');
      return result;
    }
  }

  try { await context.close(); } catch {}
  throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
}

module.exports = { captureDiscordAccount, launchBrowserOnly };
