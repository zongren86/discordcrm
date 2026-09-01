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
    // ⚠️ 绝对不能用 --no-sandbox、--disable-blink-features=AutomationControlled 等反风控的参数
    // 真正的反检测靠下面这些 + initScript + 使用系统 Chrome
    return [
      '--disable-dev-shm-usage',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-infobars',
      '--no-pings',                      // 不发导航通知
      
    ];
  }

function getInitScript() {
  return `
    // 1. 核心: 消除 webdriver 标记（Discord 重点检测项）
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });

    // 2. 伪造 Chrome 真实指纹
    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
    Object.defineProperty(navigator, 'plugins', { get: () => [
      { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer' },
      { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai' },
      { name: 'Native Client', filename: 'internal-nacl-plugin' }
    ] });
    Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
    Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });
    Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });

    // 3. chrome 对象完整伪造（Discord 探测 chrome.runtime.sendMessage）
    window.chrome = {
      runtime: { id: undefined, connect: function(){}, sendMessage: function(){}, onMessage: { addListener: function(){} } },
      loadTimes: function(){ return { firstPaintTime: 0, firstPaintAfterLoadTime: 0 }; },
      csi: function(){ return { onloadT: Date.now(), startE: Date.now(), pageT: Date.now() }; }
    };

    // 4. permissions 拦截
    const origQ = window.navigator.permissions.query;
    window.navigator.permissions.query = (p) => {
      if (p.name === 'notifications') return Promise.resolve({ state: 'granted' });
      if (p.name === 'mediaDevices') return Promise.resolve({ state: 'granted' });
      return origQ(p);
    };

    // 5. WebGL vendor/renderer 伪造
    try {
      const origGetParam = WebGLRenderingContext.prototype.getParameter;
      WebGLRenderingContext.prototype.getParameter = function(param) {
        if (param === 0x1F00) return 'Google Inc.';
        if (param === 0x1F01) return 'ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)';
        return origGetParam.apply(this, arguments);
      };
    } catch {}

    // 6. 屏蔽 CDP 调试痕迹
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Object; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Function; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Proxy; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Map; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Set; } catch {}
    try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Error; } catch {}

    // 7. 移除 Chrome Automation 标题标记
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
    chromiumSandbox: false,
    args: getLaunchArgs().filter(a => !a.includes('--no-sandbox')),  // 双重保险
    ignoreHTTPSErrors: true,
    // 唤起浏览器时设 null 禁用 Playwright 默认 1280x720 viewport，让 Chrome 窗口自然展开
    viewport: undefined,
  };
  if (SYSTEM_CHROME) launchOpts.executablePath = SYSTEM_CHROME;
  // 注入真实 Windows Chrome UA + 真实视口
    launchOpts.userAgent = launchOpts.userAgent || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
    launchOpts.locale = 'zh-CN';
    launchOpts.timezoneId = 'Asia/Shanghai';
    let context = await chromium.launchPersistentContext(userDataDir, launchOpts);

  const page = context.pages()[0] || await context.newPage();
  await page.addInitScript(getInitScript());
  try {
    await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {});
  } catch {}
  console.log('[Browser] ✅ 浏览器已打开');
  return { context, page };
}

async function captureDiscordAccount(browserConfig = {}, { taskId, http, agentName } = {}) {
  // 每个账号独立 profile 目录 —— 防风控 + 后续唤起热启动
  // 路径: ./data/browser-profiles/<agentName>-task<taskId>
  const profileName = `${agentName || 'agent'}-task${taskId || Date.now()}`;
  const userDataDir = path.resolve(`./data/browser-profiles/${profileName}`);
  try { fs.mkdirSync(userDataDir, { recursive: true }); } catch {}
  const isHot = fs.existsSync(path.join(userDataDir, 'Preferences'));
  console.log(`[Browser] 启动 Chrome (profile=${userDataDir})${isHot ? ' ⚡热启动' : ' 🧊冷启动 — 新账号独立 profile'}`);

  let context;
  try {
    const launchOpts = {
      headless: browserConfig.headless ?? false,
      chromiumSandbox: false,
      viewport: browserConfig.viewport || { width: 1280, height: 800 },
      args: getLaunchArgs().filter(a => !a.includes('--no-sandbox')),  // 双重保险
      ignoreHTTPSErrors: true,
    };
    if (SYSTEM_CHROME) launchOpts.executablePath = SYSTEM_CHROME;
    else launchOpts.channel = 'chrome';
        // 注入真实 Windows Chrome UA + 真实视口
    launchOpts.userAgent = launchOpts.userAgent || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
    launchOpts.locale = 'zh-CN';
    launchOpts.timezoneId = 'Asia/Shanghai';
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
    await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 10000 });
  } catch (e) {
    console.warn('[Browser] Discord 登录页加载较慢，继续在后台等待...');
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
      console.log('[Browser] 🎉 采集成功！浏览器保持打开，请手动关闭窗口后再进行下一步（如修改头像）');
      return result;
    }
  }

  // 超时：强制关浏览器
  console.log('[Browser] ⏰ 采集超时，强制关闭浏览器...');
  await safeClose(context);
  throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
}


/**
 * 从已打开的浏览器 context 中提取账号信息（token + 用户资料）
 * 用于 LAUNCH_BROWSER 场景：唤起已有 profile 后，扫描当前登录状态
 */
async function extractAccountFromContext(context) {
  const pages = context.pages();
  const page = pages.find(p => (p.url() || '').includes('discord')) || pages[0];
  if (!page) return null;

  // ⭐ 方案1: 网络拦截（最可靠 —— Discord 前端自动刷新 token 后会从内存带出来）
  let capturedToken = null;
  const reqHandler = (request) => {
    try {
      const auth = request.headers()['authorization'] || request.headers()['Authorization'];
      if (auth && !auth.startsWith('Bot ') && auth.length > 50) {
        capturedToken = auth;
      }
    } catch {}
  };
  context.on('request', reqHandler);

  // 给 Discord 一点时间发 API 请求（打开页面它会自动请求 /users/@me /gateway 等）
  console.log('[Browser] 网络拦截已就绪，等待 Discord API 请求抓 token...');
  // 强制触发一次 API 调用 —— 让页面 goto 当前URL触发 Discords SDK 请求
  try {
    const curUrl = page.url() || 'https://discord.com/app';
    await page.goto(curUrl, { waitUntil: 'domcontentloaded', timeout: 8000 }).catch(() => {});
  } catch {}
  // 等待 4 秒让网络请求飞回来
  for (let i = 0; i < 8; i++) {
    await new Promise(r => setTimeout(r, 500));
    if (capturedToken) {
      console.log(`[Browser] 🎯 网络拦截抓到 token (${capturedToken.length} chars)`);
      break;
    }
  }
  // 移除监听避免污染后续流程
  context.off('request', reqHandler);

  // 先尝试从 localStorage/sessionStorage/IndexedDB 扫 token（补充：万一 storage 里也有呢）
  let token = null;
  try {
    token = await page.evaluate(async () => {
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
      } catch { return null; }
      return null;
    });
  } catch {}

  // 优先用网络拦截抓到的（更可靠）
  if (capturedToken && !token) {
    token = capturedToken;
    console.log(`[Browser] 用网络拦截 token (${token.length} chars)`);
  } else if (capturedToken && token && capturedToken !== token) {
    // 两边都有但不一致 → 用网络拦截的（Discord 可能已用 cookie 刷新了）
    console.log('[Browser] ⚠️ storage token vs 网络拦截 token 不一致 → 用网络拦截的（可能已刷新）');
    token = capturedToken;
  }

  if (!token) {
    console.log('[Browser] 网络拦截 + storage 均未找到 token');
    return null;
  }

  console.log(`[Browser] 扫描到 token (${token.length} chars)，验证中...`);
  // 用 token 调 /users/@me 验证有效并拿用户信息
  const userInfo = await page.evaluate(async (t) => {
    try {
      const resp = await fetch('https://discord.com/api/users/@me', { headers: { Authorization: t } });
      if (!resp.ok) return { ok: false };
      const text = await resp.text();
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
    } catch { return { ok: false }; }
  }, token);

  if (!userInfo || !userInfo.ok) {
    console.log(`[Browser] token 验证失败 (可能已过期): ${userInfo?.ok === false ? 'API返回非200' : '无用户信息'}`);
    return { token, tokenValid: false };
  }

  console.log(`[Browser] ✅ token有效! 用户: ${userInfo.username} (${userInfo.id})`);
  return {
    token,
    tokenValid: true,
    discordId: userInfo.id,
    username: userInfo.username,
    discordName: userInfo.global_name || userInfo.username,
    email: userInfo.email,
    avatarUrl: userInfo.avatar ? `https://cdn.discordapp.com/avatars/${userInfo.id}/${userInfo.avatar}.png` : null,
  };
}

module.exports = { captureDiscordAccount, launchBrowserOnly, extractAccountFromContext };
