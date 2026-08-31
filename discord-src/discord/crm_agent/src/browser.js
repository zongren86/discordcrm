/**
 * Discord 用户采集 —— Playwright
 * 三层 token 捕获：网络拦截(最可靠) → localStorage → IndexedDB
 * v1.2.0: 超时/取消/异常都强制关闭浏览器
 */
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

/**
 * 查找系统真实 Chrome（反检测比 Playwright 自带 Chromium 强 10 倍）
 * Windows: C:\Program Files\Google\Chrome\Application\chrome.exe
 *          C:\Program Files (x86)\Google\Chrome\Application\chrome.exe
 *          ~\AppData\Local\Google\Chrome\Application\chrome.exe
 * macOS:   /Applications/Google Chrome.app/Contents/MacOS/Google Chrome
 * Linux:   /usr/bin/google-chrome
 * 返回路径字符串（找到真实 Chrome）或 null（用 Playwright 自带 Chromium）
 */

function findSystemChrome() {
  const fs = require('fs');
  const path = require('path');
  const { execSync } = require('child_process');
  const platform = process.platform;
  const cfg = require('./config').loadConfig();

  // 优先级 0: config.json 显式配置（最可靠）
  if (cfg.browser && cfg.browser.executablePath && cfg.browser.executablePath.trim()) {
    const p = cfg.browser.executablePath.trim();
    if (fs.existsSync(p)) {
      console.log(`[Browser] ✅ 使用 config.json 配置的浏览器: ${p}`);
      return p;
    } else {
      console.warn(`[Browser] ⚠️ config.json executablePath 不存在: ${p}`);
    }
  }

  const candidates = [];

  if (platform === 'win32') {
    // 用 path.join 构建，避免反斜杠转义 bug
    const pf = process.env['ProgramFiles'] || 'C:\\Program Files';
    const pf86 = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
    const localAppData = process.env.LOCALAPPDATA || '';

    // Chrome 全版本
    candidates.push(
      path.join(pf, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      path.join(pf86, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      path.join(localAppData, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      // Beta / Dev / Canary
      path.join(localAppData, 'Google', 'Chrome Beta', 'Application', 'chrome.exe'),
      path.join(localAppData, 'Google', 'Chrome Dev', 'Application', 'chrome.exe'),
      path.join(localAppData, 'Google', 'Chrome SxS', 'Application', 'chrome.exe'),
      // Edge 全版本
      path.join(pf, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      path.join(pf86, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      path.join(localAppData, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      path.join(localAppData, 'Microsoft', 'Edge Beta', 'Application', 'msedge.exe'),
      path.join(localAppData, 'Microsoft', 'Edge Dev', 'Application', 'msedge.exe'),
      path.join(localAppData, 'Microsoft', 'Edge SxS', 'Application', 'msedge.exe'),
      // 便携版常见位置
      path.join(pf, 'Chromium', 'Application', 'chrome.exe'),
      path.join(pf86, 'Chromium', 'Application', 'chrome.exe'),
    );

    // PowerShell where.exe 动态查找（最靠谱）
    try {
      const out1 = execSync('where chrome 2>nul', { encoding: 'utf8' });
      out1.split('\n').filter(Boolean).forEach(l => candidates.push(l.trim()));
    } catch {}
    try {
      const out2 = execSync('where msedge 2>nul', { encoding: 'utf8' });
      out2.split('\n').filter(Boolean).forEach(l => candidates.push(l.trim()));
    } catch {}

  } else if (platform === 'darwin') {
    candidates.push(
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/Applications/Google Chrome Beta.app/Contents/MacOS/Google Chrome Beta',
      '/Applications/Google Chrome Dev.app/Contents/MacOS/Google Chrome Dev',
      '/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary',
      '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
      '/Applications/Microsoft Edge Beta.app/Contents/MacOS/Microsoft Edge Beta',
      '/Applications/Microsoft Edge Dev.app/Contents/MacOS/Microsoft Edge Dev',
      '/Applications/Microsoft Edge Canary.app/Contents/MacOS/Microsoft Edge Canary',
      '/Applications/Chromium.app/Contents/MacOS/Chromium',
    );
    try {
      const out = execSync('mdfind "kMDItemCFBundleIdentifier == com.google.Chrome" | head -1', { encoding: 'utf8' });
      if (out.trim()) candidates.push(out.trim() + '/Contents/MacOS/Google Chrome');
    } catch {}

  } else {
    candidates.push(
      '/usr/bin/google-chrome',
      '/usr/bin/google-chrome-stable',
      '/usr/bin/chromium',
      '/usr/bin/chromium-browser',
      '/snap/bin/chromium',
    );
  }

  // 去重 + 存在性检查
  const seen = new Set();
  for (const p of candidates) {
    if (!p || seen.has(p)) continue;
    seen.add(p);
    try {
      if (fs.existsSync(p)) {
        const name = p.toLowerCase().includes('edge') ? 'Edge' 
                   : p.toLowerCase().includes('beta') ? 'Chrome Beta'
                   : p.toLowerCase().includes('dev') ? 'Chrome Dev'
                   : p.toLowerCase().includes('sx') ? 'Chrome Canary'
                   : p.includes('Chromium') ? 'Chromium' : 'Chrome';
        console.log(`[Browser] ✅ 发现系统 ${name}: ${p}`);
        return p;
      }
    } catch {}
  }

  // Playwright channel 兜底（Playwright 自己也有一套查找逻辑）
  try {
    const { chromium } = require('playwright');
    const channels = ['chrome', 'msedge', 'chrome-beta', 'msedge-beta'];
    for (const ch of channels) {
      try {
        const p = chromium.executablePath(ch);
        if (p && fs.existsSync(p)) {
          console.log(`[Browser] ✅ Playwright channel 发现: ${ch} → ${p}`);
          return p;
        }
      } catch {}
    }
  } catch {}

  console.log('[Browser] ⚠️ 未找到系统 Chrome/Edge，使用 Playwright 自带 Chromium（风控风险更高）');
  console.log('[Browser] 💡 解决方法 A: 安装 Chrome 浏览器');
  console.log('[Browser] 💡 解决方法 B: config.json 加 "browser": { "executablePath": "C:\\\\path\\\\to\\\\chrome.exe" }');
  return null;
}

const SYSTEM_CHROME = findSystemChrome();


function getLaunchArgs() {
  // 注意：绝对不允许 --no-sandbox —— 这会导致 Chrome 弹出警告
  // Playwright 会在 chromiumSandbox !== true 时自动加 --no-sandbox，
  // 所以 launchOpts 必须设 chromiumSandbox: true（下面两处已设）
  return [
    '--disable-dev-shm-usage',
    '--disable-gpu',
    '--disable-infobars',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-sync',
    '--disable-breakpad',
    '--no-title-update',
    '--no-window-animation',
  ].filter(a => !a.includes('--no-sandbox'));  // 双重保险：过滤掉任何误加的 --no-sandbox
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
    // 只调 Playwright 原生 API，绝不碰系统进程
    // Playwright 内部会自动管理 chromium 进程的生命周期
    if (typeof context.close === 'function') {
      await context.close();
      console.log('[Browser] context.close() ✅');
    }
  } catch (e) {
    console.warn('[Browser] context.close() 异常:', String(e.message || e).split('\n')[0]);
  }
  try {
    const browser = context.browser && context.browser();
    if (browser && typeof browser.close === 'function') {
      await browser.close();
      console.log('[Browser] browser.close() ✅');
    }
  } catch {}
}

/** 只唤起浏览器，不采集 */
async function launchBrowserOnly(browserProfilePath, browserConfig = {}) {
  if (!browserProfilePath) throw new Error('缺少 browserProfilePath');
  const userDataDir = path.resolve(browserProfilePath);
  if (!fs.existsSync(userDataDir)) throw new Error('浏览器 profile 不存在: ' + userDataDir);

  console.log('[Browser] 唤起持久化浏览器 profile...');
  const launchOpts = {
    headless: false,
    chromiumSandbox: true,  // 强制 Playwright 不自动追加 --no-sandbox
    args: getLaunchArgs().filter(a => !a.includes('--no-sandbox')),  // 双重保险
    ignoreHTTPSErrors: true,
    // 唤起浏览器时设 null 禁用 Playwright 默认 1280x720 viewport，让 Chrome 窗口自然展开
    viewport: null,  // 唤起浏览器禁用 Playwright viewport，让 Chrome 窗口自然展开可拖拽
  };
  if (SYSTEM_CHROME) launchOpts.executablePath = SYSTEM_CHROME;
  const context = await chromium.launchPersistentContext(userDataDir, launchOpts);

  const page = context.pages()[0] || await context.newPage();
  await page.addInitScript(getInitScript());
  try {
    await page.goto('https://discord.com/', { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => {});
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
    const launchOpts = {
      headless: browserConfig.headless ?? false,
      chromiumSandbox: true,  // 强制 Playwright 不自动追加 --no-sandbox
      viewport: browserConfig.viewport || { width: 1280, height: 800 },
      args: getLaunchArgs().filter(a => !a.includes('--no-sandbox')),  // 双重保险
      ignoreHTTPSErrors: true,
    };
    if (SYSTEM_CHROME) launchOpts.executablePath = SYSTEM_CHROME;
    else launchOpts.channel = 'chrome';
    context = await chromium.launchPersistentContext(userDataDir, launchOpts);

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
    await page.goto('https://discord.com/', { waitUntil: 'domcontentloaded', timeout: 30000 });
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
          if (!resp.ok) return { ok: false };
          // 关键: 用 resp.text() 拿原始 JSON 文本, 不用 resp.json()
          // 因为浏览器 JSON.parse 会把 19 位 Snowflake ID 当成 Number → 精度丢失!
          const text = await resp.text();
          // 用正则从 JSON 文本里安全提取字段 (Snowflake ID 保持字符串)
          const extract = (key) => {
            const re = new RegExp('"' + key + '"\\s*:\\s*(\\d+|"[^"]*")');
            const m = text.match(re);
            if (!m) return null;
            let v = m[1];
            if (v.startsWith('"') && v.endsWith('"')) v = v.slice(1, -1);
            return v;
          };
          return {
            ok: true,
            id: extract('id'),
            username: extract('username') || '',
            global_name: extract('global_name') || '',
            email: extract('email'),
            avatar: extract('avatar'),
          };
        } catch {}
        return { ok: false };
      }, token);
      if (r && r.ok) {
        return {
          id: r.id, username: r.username,
          global_name: r.global_name, email: r.email, avatar: r.avatar,
        };
      }
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
      console.log('[Browser] 3 秒后自动关闭浏览器...');
      await new Promise(r => setTimeout(r, 3000));
      await safeClose(context);
      return result;
    }
  }

  // 超时：强制关浏览器
  console.log('[Browser] ⏰ 采集超时，强制关闭浏览器...');
  await safeClose(context);
  throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
}

module.exports = { captureDiscordAccount, launchBrowserOnly };
