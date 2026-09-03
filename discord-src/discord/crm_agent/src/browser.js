'use strict';

/**
 * browser.js —— Discord 浏览器自动化核心
 * 
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  v2.0 — 集成同行全部反检测策略                                 ║
 * ║                                                                ║
 * ║  ✅ account_fingerprint   每账号独立指纹 + 持久化             ║
 * ║  ✅ 清除 Playwright automation 默认参数                       ║
 * ║  ✅ network_gate          per-account 代理 + 熔断            ║
 * ║  ✅ capacity_policy        并发槽位控制                        ║
 * ║  ✅ runtime_trace          运行时追踪                          ║
 * ║  ✅ gateway_manager       Gateway 会话（可后接）              ║
 * ║                                                                ║
 * ║  核心修复:                                                     ║
 * ║  🔴 UA 版本跟随系统 Chrome（不再硬编码 131）                 ║
 * ║  🔴 每账号独立 WebGL/CPU/内存/分辨率指纹                      ║
 * ║  🔴 清除 --enable-automation 等 Playwright 默认痕迹           ║
 * ║  🔴 所有账号共用 profile → 改为独立 profile                  ║
 * ╚══════════════════════════════════════════════════════════════╝
 */

const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const os = require('os');

// ============ 新模块引入 ============
const fingerprint = require('./agent/account_fingerprint');
const networkGate = require('./network/network_gate');
const capacity = require('./scheduler/capacity_policy');
const trace = require('./observability/runtime_trace');

// ============ 定位系统 Chrome ============

function findSystemChrome() {
  const plat = os.platform();
  try {
    const { execSync } = require('child_process');
    if (plat === 'win32') {
      const candidates = [
        'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
        'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
        process.env.LOCALAPPDATA + '\\Google\\Chrome\\Application\\chrome.exe',
      ];
      for (const p of candidates) if (fs.existsSync(p)) return p;
    } else if (plat === 'darwin') {
      const p = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
      if (fs.existsSync(p)) return p;
    } else if (plat === 'linux') {
      const out = execSync('which google-chrome 2>/dev/null || which chromium-browser 2>/dev/null || which chromium 2>/dev/null', { encoding: 'utf8', timeout: 3000 });
      const p = out.trim();
      if (p) return p;
    }
  } catch {}
  return null;
}

const SYSTEM_CHROME = findSystemChrome();

// ============ Chrome 启动参数 ============

function getLaunchArgs() {
  // ⭐ 对齐同行策略：不用激进反风控参数（--disable-blink-features / --no-sandbox / --disable-enable-automation）
  // 真正的反检测靠：系统 Chrome + initScript 伪造 + chromiumSandbox:false（Playwright 选项）
  return [
    // 基础清理（同行同款）
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-infobars',
    '--disable-sync',
    '--metrics-recording-only',
    '--no-pings',
    '--disable-extensions',
    '--disable-background-networking',

    // 服务器加速（同行没加但安全，服务器确实没 GPU/共享内存问题）
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--disable-breakpad',
    '--disable-component-update',
    '--disable-domain-reliability',

    // 窗口
    '--window-size=1280,800',
    '--start-maximized',
  ];
}

// ============ 动态 initScript（由 account_fingerprint 生成）============
// 保留 getInitScript 名称用于兼容，但实际内部调用 fingerprint.buildInitScript

function getInitScript(fp) {
  if (fp) return fingerprint.buildInitScript(fp);
  return fingerprint.getDefaultInitScript();
}

// ============ 工具函数 ============

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

async function safeClose(context) {
  if (!context) return;
  try {
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

// ============ 第 4 层防线：强制 Chrome profile 语言偏好 ============
// 根因：Chrome profile/Default/Preferences 里的 intl.* 字段会覆盖
// --lang / --accept-language / Playwright locale 等启动参数。
// 每次 launchPersistentContext 前必须确保 Preferences 里的语言是对的。
function ensureProfileIntl(userDataDir, fp) {
  try {
    const acceptLanguages = fp.languages;
    const languagesArr = fp.languages.split(',');

    // ============ 文件 1: Default/Preferences ============
    const prefsDir = path.join(userDataDir, 'Default');
    if (!fs.existsSync(prefsDir)) fs.mkdirSync(prefsDir, { recursive: true });
    const prefsFile = path.join(prefsDir, 'Preferences');
    let prefs = {};
    if (fs.existsSync(prefsFile)) {
      try { prefs = JSON.parse(fs.readFileSync(prefsFile, 'utf8')); }
      catch { try { fs.copyFileSync(prefsFile, prefsFile + '.bak.' + Date.now()); } catch {} prefs = {}; }
    }
    prefs.intl = prefs.intl || {};
    prefs.intl.accept_languages = acceptLanguages;
    prefs.intl.selected_languages = languagesArr;
    prefs.spellcheck = prefs.spellcheck || {};
    prefs.spellcheck.dictionaries = [fp.locale.split('-')[0]];
    prefs.translate = prefs.translate || {};
    prefs.translate.enabled = false;
    // 关键：content_settings.default.language —— Chrome 语言偏好的核心键！
    prefs.profile = prefs.profile || {};
    prefs.profile.default_content_setting_values = prefs.profile.default_content_setting_values || {};
    prefs.profile.default_content_setting_values.language = fp.locale;
    // 还有一个：download.default_directory 无关，不用管
    fs.writeFileSync(prefsFile, JSON.stringify(prefs));
    console.log('[Browser] 📝 Default/Preferences.intl.accept_languages =', acceptLanguages);
    console.log('[Browser] 📝 Default/Preferences.profile.default_content_setting_values.language =', fp.locale);

    // ============ 文件 2: Local State（比 Preferences 更全局）============
    const localStateFile = path.join(userDataDir, 'Local State');
    let ls = {};
    if (fs.existsSync(localStateFile)) {
      try { ls = JSON.parse(fs.readFileSync(localStateFile, 'utf8')); } catch { ls = {}; }
    }
    ls.intl = ls.intl || {};
    ls.intl.accept_languages = acceptLanguages;
    ls.intl.selected_languages = languagesArr;
    ls.browser = ls.browser || {};
    ls.browser.enabled_labs_experiments = ls.browser.enabled_labs_experiments || [];
    fs.writeFileSync(localStateFile, JSON.stringify(ls));
    console.log('[Browser] 📝 Local State.intl.accept_languages =', acceptLanguages);

    return true;
  } catch (e) {
    console.warn('[Browser] ⚠️ 写入 profile 语言偏好失败:', e.message);
    return false;
  }
}

// ============ 构建 launchPersistentContext 选项 ============

/**
 * 根据指纹构建 Playwright launch 选项
 * @param {object} fp - account_fingerprint 生成的指纹
 * @param {object} extra - 额外覆盖选项
 */
function buildLaunchOpts(fp, extra = {}) {
  const opts = {
    headless: extra.headless ?? false,
    chromiumSandbox: false,
    args: getLaunchArgs(),
    ignoreHTTPSErrors: true,
  };
  
  // ⭐ 使用系统 Chrome（避免 Playwright 内置 Chromium 的特征）
  // ⭐ 强制用系统 Chrome（不用 Playwright 自带 Chromium，后者 TLS/UA 全被标记）
  if (SYSTEM_CHROME) opts.executablePath = SYSTEM_CHROME;
  
  // ⭐ 从指纹注入 UA + 语言 + 时区
  if (fp) {
    opts.userAgent = fp.userAgent;
    opts.locale = fp.locale;
    opts.timezoneId = fp.timezone;
    opts.viewport = fp.viewport;
    opts.deviceScaleFactor = fp.devicePixelRatio;
    // ⭐ 关键：Chromium 启动时强制 --lang，覆盖系统 Chrome 默认语言
    // 避免 Windows 系统 Chrome 自带韩文语言包被 Chromium 优先使用
    opts.args.push('--lang=' + fp.locale);
    // ⭐ 最关键：--accept-language 命令行参数直接覆盖所有 HTTP 请求的 Accept-Language
    // 这个参数比 Playwright locale 更底层，hCaptcha / Discord / DevTools 都读这个
    opts.args.push('--accept-language=' + fp.languages);
    // Playwright locale 参数本身会设置 navigator.language，但 Chromium
    // 内部一些组件（包括 hCaptcha）看的是 --lang 参数
  } else {
    // 指纹模块没加载 → 用默认配对（Asia/Shanghai + zh-CN，地理自洽）
    opts.userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + fingerprint.getSystemChromeVersion() + ' Safari/537.36';
    opts.locale = 'zh-CN';
    opts.timezoneId = 'Asia/Shanghai';
    opts.args.push('--lang=zh-CN');
    opts.args.push('--accept-language=zh-CN,zh,en-US,en');
  }
  
  // 代理（如果账号绑定了）
  if (extra.proxyUrl) {
    opts.proxy = { server: extra.proxyUrl };
  }
  
  return opts;
}

// ============ 核心 API ============

/**
 * captureDiscordAccount — 启动浏览器让用户手动登录，捕获 token
 */
async function captureDiscordAccount(browserConfig = {}, { taskId, http, agentName, proxyUrl } = {}) {
  // 并发槽位控制
  const acquired = await capacity.acquire('START_ACCOUNT', 120000);
  if (!acquired) throw new Error('并发槽位已满，无法启动新账号');
  
  // 初始化追踪
  trace.start();
  
  // ⭐ 获取账号独立指纹（地理感知：通过代理探测出口 IP）
  const fp = await fingerprint.getOrCreateFingerprint(agentName, { proxyUrl });
  
  // ⭐ 如果有代理，绑定并检测
  if (proxyUrl) {
    networkGate.bindAccountProxy(agentName, proxyUrl);
    const usable = await networkGate.isProxyUsable(proxyUrl);
    if (!usable) {
      console.warn('[Browser] 代理不可用，尝试裸连');
    }
  }
  
  // 每个账号独立 profile 目录
  const profileName = `${agentName || 'agent'}-task${taskId || Date.now()}`;
  const userDataDir = path.resolve(`./data/browser-profiles/${profileName}`);
  try { fs.mkdirSync(userDataDir, { recursive: true }); } catch {}
  const isHot = fs.existsSync(path.join(userDataDir, 'Preferences'));
  
  trace.browserWorkflow('capture_start', { agentName, taskId, fingerprintId: fp.fingerprintId, isHot });
  console.log(`[Browser] 启动 Chrome (profile=${userDataDir})${isHot ? ' ⚡热启动' : ' 🧊冷启动'}  UA=${fp.chromeVersion} GPU=${fp.webglRenderer.split(',')[1]?.trim()}`);
  
  let context;
  try {
    const launchOpts = buildLaunchOpts(fp, {
      headless: browserConfig.headless ?? false,
      viewport: browserConfig.viewport || fp.viewport,
      proxyUrl,
    });
    console.log('[Browser] launch opts:', JSON.stringify({
      userAgent: launchOpts.userAgent.slice(0, 50) + '...',
      locale: launchOpts.locale,
      timezoneId: launchOpts.timezoneId,
      viewport: launchOpts.viewport,
      proxy: launchOpts.proxy?.server || '(none)',
      executablePath: launchOpts.executablePath ? 'SYSTEM_CHROME' : '(playwright)',
    }));
    
    // ⭐ 第 4 层防线：强制 profile/Default/Preferences 语言
    ensureProfileIntl(userDataDir, fp);
    context = await chromium.launchPersistentContext(userDataDir, launchOpts);
    trace.browserWorkflow('context_created', { pages: context.pages().length });
    // ⭐ 第 2 层防线：强制 HTTP Accept-Language header（Discord/hCaptcha 优先读这个）
    await context.setExtraHTTPHeaders({ 'Accept-Language': fp.languages });

    // ⚡⚡⚡ 资源拦截：Discord 登录页只需要 HTML + JS + CSS，拦截非必要资源
    // 这让页面加载从 3-8s 降到 1-2s，而且不影响 token 捕获
    try {
      // 1. 图片/字体/media — 登录页 UI 能显示就行
      // ⚠️ 不拦图片！Discord 头像/表情/hCaptcha 验证都需要 png/jpg/svg
   // 只拦追踪脚本、广告、Discord 自己的遥测
      // 2. 远端字体 CDN
      await context.route(/fonts\.(googleapis|gstatic|adobe|cloudflare)\.com/, route => route.abort());
      // 3. 追踪/遥测脚本 — 完全不影响登录功能
      await context.route(/(segment|hotjar|fullstory|mixpanel|amplitude|datadog|posthog|google-analytics|analytics)\.com/, route => route.abort());
      await context.route(/(googletagmanager|facebook|fbcdn|tiktok)\.com/, route => route.abort());
      await context.route(/(clarity|userpilot|pendo|heap\.io|logrocket)\.(com|io)/, route => route.abort());
      await context.route(/capture\.discordapp\.com/, route => route.abort());  // Discord 自己的遥测!
      // 4. 广告/CDN（非必要的）
      await context.route(/doubleclick\.net|googlesyndication\.com/, route => route.abort());
    } catch { /* route 可能已经被注册，忽略 */ }

  } catch (e) {
    capacity.release('START_ACCOUNT');
    trace.browserWorkflow('capture_fail', { reason: String(e.message).slice(0, 200) });
    throw new Error('浏览器启动失败: ' + String(e.message || e).split('\n')[0]);
  }

  let page = context.pages()[0] || await context.newPage();
  
  // ⭐ 注入动态反检测脚本（从指纹生成）
  await page.addInitScript(getInitScript(fp));

  // 网络拦截抓 token（双向：request 的 Authorization header + response 的）
  let capturedToken = null;
  let tokenReady = false;  // 🆕 标志位：token 一抓到就触发，跳过 2 秒等待
  const responseHandler = (response) => {
    try {
      const headers = response.headers();
      const auth = headers['authorization'] || headers['Authorization'];
      if (auth && !auth.startsWith('Bot ') && auth.length > 50) {
        if (!capturedToken || capturedToken !== auth) {
          capturedToken = auth;
          tokenReady = true;
          console.log(`[Browser] 🎯 response 拦截捕获 token (${auth.length} chars)`);
          trace.event('token_captured', { source: 'response', len: auth.length });
        }
      }
    } catch {}
  };
  context.on('response', responseHandler);
  // request 方向也抓（更可靠，Discord 每次 API 调用都带 Authorization）
  const requestHandler = (request) => {
    try {
      const url = request.url();
      if (!url.includes('discord.com/api')) return;
      const headers = request.headers();
      const auth = headers['authorization'] || headers['Authorization'];
      if (auth && !auth.startsWith('Bot ') && auth.length > 50) {
        if (!capturedToken || capturedToken !== auth) {
          capturedToken = auth;
          tokenReady = true;
          console.log(`[Browser] 🎯 request 拦截捕获 token (${auth.length} chars) url=${url.slice(0,60)}`);
          trace.event('token_captured', { source: 'request', len: auth.length });
        }
      }
    } catch {}
  };
  context.on('request', requestHandler);

  // 🆕 JS 层终极保险：hook fetch/XHR 抓 Authorization header
  // 这一层在 Discord 的 JS 运行时直接拦截，最可靠
  const jsHook = `
(() => {
    const origFetch = window.fetch;
    window.fetch = async function(input, init) {
        try {
            const url = typeof input === 'string' ? input : input?.url || '';
            if (url.includes('discord.com/api') && init?.headers) {
                const h = init.headers;
                let auth = typeof h.get === 'function' ? h.get('authorization') || h.get('Authorization')
                        : (h['authorization'] || h['Authorization']);
                if (auth && !auth.startsWith('Bot ') && auth.length > 50) {
                    window.__capturedDiscordToken = auth;
                    console.log('[TokenHook] fetch captured token');
                }
            }
        } catch {}
        return origFetch.apply(this, arguments);
    };
    const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function(name, val) {
        try {
            if ((name.toLowerCase() === 'authorization') && val && !val.startsWith('Bot ') && val.length > 50) {
                window.__capturedDiscordToken = val;
                console.log('[TokenHook] XHR captured token');
            }
        } catch {}
        return origSetHeader.apply(this, arguments);
    };
})();`;
  await context.addInitScript(jsHook);
  console.log('[Browser] JS fetch/XHR token hook injected');

  // 🆕 CDP: 监听 WebSocket Gateway 帧（Discord 2025+ token 在这里）
  try {
    const cdp = await context.newCDPSession(await context.pages()[0] || await context.newPage());
    cdp.on('Network.webSocketFrameSent', (frame) => {
      try {
        const payload = JSON.parse(frame.payload);
        // op=2 IDENTIFY / op=4 RESUME / op=12 HEARTBEAT 都带 token
        if (payload.op === 2 || payload.op === 4) {
          const t = payload.d?.token;
          if (t && t.length > 50 && (!capturedToken || capturedToken !== t)) {
            capturedToken = t;
            tokenReady = true;
            console.log(`[Browser] 🎯 CDP WebSocket 捕获 token (${t.length} chars) op=${payload.op}`);
            trace.event('token_captured', { source: 'websocket', len: t.length, op: payload.op });
          }
        }
      } catch {}
    });
    await cdp.send('Network.enable');
  } catch (e) {
    console.warn('[Browser] CDP 初始化失败（不影响 HTTP 拦截）:', e.message?.slice(0, 80));
  }

  console.log('[Browser] 打开 Discord 登录页...');
  let gotoOk = false;
  try {
    await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded', timeout: 10000 });
    gotoOk = true;
    console.log('[Browser] ✅ Discord 登录页已加载');
  } catch (e) {
    console.warn('[Browser] Discord 登录页加载较慢，快速等待...');
  }

  if (!gotoOk) {
    console.log('[Browser] 快速等待页面就绪...');
    for (let i = 0; i < 25; i++) {
      await new Promise(r => setTimeout(r, 200));
      if (await pageReady(page)) {
        console.log(`[Browser] ✅ 页面就绪（${(i+1)*200}ms）`);
        break;
      }
      if (!isContextAlive(context)) {
        await safeClose(context);
        capacity.release('START_ACCOUNT');
        trace.browserWorkflow('capture_aborted', { reason: 'user_closed' });
        throw new Error('用户关闭了浏览器');
      }
      const pages = context.pages();
      page = pages.find(p => (p.url() || '').includes('discord')) || page;
    }
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
          // 优先扫 Discord 已知 indexedDB（2024+ 用这些存 token）
          const DISCORD_DBS = ['discord_store', 'discord_cache', 'KeyDBCache', 'discordMeta'];
          const scanDB = (dbName) => new Promise((resolve) => {
            let found = null;
            try {
              const req = indexedDB.open(dbName);
              req.onsuccess = async () => {
                try {
                  const tx = req.result.transaction(req.result.objectStoreNames, 'readonly');
                  for (const n of req.result.objectStoreNames) {
                    try {
                      const store = tx.objectStore(n);
                      const all = store.getAll();
                      await new Promise(r2 => {
                        all.onsuccess = () => {
                          for (const item of all.result || []) {
                            const s = typeof item === 'string' ? item : JSON.stringify(item);
                            const m = s.match(tokenPattern);
                            if (m && !found) { found = m[0]; }
                          }
                          r2();
                        };
                        all.onerror = () => r2();
                      });
                      if (found) break;
                    } catch {}
                  }
                } catch {}
                resolve(found);
              };
              req.onerror = () => resolve(null);
              req.onupgradeneeded = () => resolve(null);
            } catch { resolve(null); }
          });
          for (const dbName of DISCORD_DBS) {
            const t = await scanDB(dbName);
            if (t) return t;
          }
          try {
            const dbs = await indexedDB.databases();
            for (const db of dbs) {
              if (!db.name || DISCORD_DBS.includes(db.name)) continue;
              const t = await scanDB(db.name);
              if (t) return t;
            }
          } catch {}
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
          if (!resp.ok) return { ok: false, status: resp.status };
          try {
            const data = await resp.json();
            return {
              ok: true,
              id: String(data.id || ''),
              username: data.username || '',
              global_name: data.global_name || data.username || '',
              email: data.email || null,
              avatar: data.avatar || null,
              discriminator: data.discriminator || null,
            };
          } catch (e) { return { ok: false, parseError: String(e.message).slice(0,50) }; }
        } catch {}
        return { ok: false };
      }, token);
      if (r && r.ok) {
        return {
          id: r.id, username: r.username || r.global_name,
          global_name: r.global_name || r.username, email: r.email, avatar: r.avatar,
        };
      }
      // Fallback: 从 Discord /app 页面 DOM 提取用户名
      try {
        const dom = await page.evaluate(() => {
          const els = document.querySelectorAll('[aria-label*="user"], [aria-label*="User"]');
          for (const el of els) {
            const label = (el.getAttribute('aria-label') || '').trim();
            const m = label.match(/^(.+?)(?:#\d+)?$/);
            if (m && m[1] && m[1].length > 1 && m[1].length < 64) {
              return { id: null, username: m[1], domFallback: true };
            }
          }
          return null;
        });
        if (dom) return dom;
      } catch {}
    } catch {}
    return null;
  };

  console.log('[Browser] 等待用户登录 Discord... (最多 5 分钟)');
  const deadline = Date.now() + 5 * 60 * 1000;
  let scanCount = 0;

  while (Date.now() < deadline) {
    // 如果 token 已抓到，跳过等待立即处理
    if (!tokenReady) await new Promise(r => setTimeout(r, 2000));
    scanCount++;

    if (taskId && http && await checkCancelled(http, taskId)) {
      console.log('[Browser] ❌ 任务已被取消');
      context.off('response', responseHandler);
      await safeClose(context);
      capacity.release('START_ACCOUNT');
      trace.taskEvent('cancelled', { taskId });
      const err = new Error('任务已被取消'); err.code = 'CANCELLED'; throw err;
    }

    if (!isContextAlive(context)) {
      console.log('[Browser] ❌ 用户关闭了浏览器');
      capacity.release('START_ACCOUNT');
      trace.browserWorkflow('capture_aborted', { reason: 'user_closed' });
      const err = new Error('用户关闭了浏览器'); err.code = 'BROWSER_CLOSED'; throw err;
    }

    if (!await pageReady(page)) {
      const pages = context.pages();
      page = pages.find(p => (p.url() || '').includes('discord')) || page;
      if (scanCount <= 5 || scanCount % 15 === 0) {
        console.log(`[Browser] 扫描#${scanCount} 页面未就绪 (url=${page.url()})`);
      }
      continue;
    }

    // 🆕 检测登录成功：URL 从 /login 变成 /app 或 /channels
    const curUrl = page.url() || '';
    const loggedIn = curUrl.includes('/app') || curUrl.includes('/channels') || curUrl.includes('/library');
    if (loggedIn && !result.token) {
      if (scanCount <= 3 || scanCount % 10 === 0) {
        console.log(`[Browser] ✅ 检测到登录成功！URL=${curUrl.slice(0,60)}，立即扫描 token...`);
      }
    }

    let token = capturedToken;
    // 🆕 先查 JS hook 捕获的 token（最快最可靠）
    if (!token) {
      try {
        const jsToken = await page.evaluate(() => window.__capturedDiscordToken);
        if (jsToken && jsToken.length > 50) {
          token = jsToken;
          console.log(`[Browser] token from JS hook (${token.length} chars)`);
          tokenReady = true;
        }
      } catch {}
    }
    if (!token) {
      token = await scanStorage();
      if (token) {
        console.log(`[Browser] 🎯 storage 扫描捕获 token (${token.length} chars)`);
        tokenReady = true;
      }
    }
    if (token && token !== result.token) result.token = token;

    if (scanCount <= 3 || scanCount % 10 === 0) {
      console.log(`[Browser] 扫描#${scanCount}  token=${result.token ? result.token.slice(0,20)+'...' : '(无)'}  user=${result.username || '(无)'}`);
    }

    if (result.token && !result.userId) {
      const user = await fetchUser(result.token);
      if (user) {
        result.userId = user.id;
        result.username = user.username || user.global_name || user.display_name;
        result.email = user.email || null;
        if (user.avatar) result.avatarUrl = `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png`;
        console.log(`[Browser] ✅ 捕获用户: ${result.username} (${result.userId})`);
        trace.browserWorkflow('user_captured', { userId: result.userId, username: result.username });
      }
    }

    if (result.token && result.userId && result.username) {
      result.browserProfilePath = userDataDir;
      result.fingerprintId = fp.fingerprintId;
      capacity.release('START_ACCOUNT');
      trace.browserWorkflow('capture_done', { userId: result.userId });
      console.log('[Browser] 🎉 采集成功！', result.username, result.userId);
      // ⚠️ 关键：成功后立即关闭 context，让 Chrome 把 Cookie/session/localStorage 正确写盘
      // 不关的话 profile 是半成品 → 后续 LAUNCH_BROWSER 打开时 Discord 会要求重新验证/登录
      context.off('response', responseHandler);
      context.off('request', requestHandler);
      await safeClose(context);
      console.log('[Browser] ✅ profile 已持久化到磁盘，后续唤起会从干净状态启动');
      return result;
    }
  }

  console.log('[Browser] ⏰ 采集超时，强制关闭浏览器...');
  context.off('response', responseHandler);
  await safeClose(context);
  capacity.release('START_ACCOUNT');
  trace.browserWorkflow('capture_timeout', { scanCount });
  throw new Error('采集超时或用户信息不完整');
}

/**
 * launchBrowserOnly —— 只唤起浏览器，不采集（v2: 独立 profile + 指纹）
 */
async function launchBrowserOnly(browserProfilePath, browserConfig = {}, { agentName, proxyUrl } = {}) {
  if (!browserProfilePath) throw new Error('缺少 browserProfilePath');
  const userDataDir = path.resolve(browserProfilePath);
  if (!fs.existsSync(userDataDir)) throw new Error('浏览器 profile 不存在: ' + userDataDir);

  // ⭐ 指纹（地理感知：通过代理探测出口 IP）
  const fp = await fingerprint.getOrCreateFingerprint(agentName || path.basename(userDataDir), { proxyUrl });
  
  // ⭐ 代理
  if (proxyUrl) networkGate.bindAccountProxy(agentName || path.basename(userDataDir), proxyUrl);

  // 检测 profile 是否已存在（热启动），热启动时跳过 ensureProfileIntl 避免 Discord 检测到配置篡改
  const isHot = fs.existsSync(path.join(userDataDir, 'Default', 'Preferences'));
  if (isHot) {
    console.log('[Browser] 热启动 profile，跳过 ensureProfileIntl（保留原始 profile 状态）');
  } else {
    console.log('[Browser] 冷启动 profile，写入初始语言偏好...');
    ensureProfileIntl(userDataDir, fp);
  }

  console.log('[Browser] 唤起持久化浏览器 profile...');
  const launchOpts = buildLaunchOpts(fp, {
    headless: false,
    proxyUrl,
  });
  
  let context = await chromium.launchPersistentContext(userDataDir, launchOpts);
  // ⭐ 第 2 层防线：强制 HTTP Accept-Language header（Discord/hCaptcha 优先读这个）
  await context.setExtraHTTPHeaders({ 'Accept-Language': fp.languages });

  // ⚡ 资源拦截：同 captureDiscordAccount
  try {
    // ⚠️ 不拦图片！Discord 头像/表情/hCaptcha 验证都需要 png/jpg/svg
   // 只拦追踪脚本、广告、Discord 自己的遥测
    await context.route(/fonts\.(googleapis|gstatic|adobe|cloudflare)\.com/, route => route.abort());
    await context.route(/(segment|hotjar|fullstory|mixpanel|amplitude|datadog|posthog|google-analytics|analytics)\.com/, route => route.abort());
    await context.route(/(googletagmanager|facebook|fbcdn|tiktok)\.com/, route => route.abort());
    await context.route(/(clarity|userpilot|pendo|heap\.io|logrocket)\.(com|io)/, route => route.abort());
    await context.route(/capture\.discordapp\.com/, route => route.abort());
    await context.route(/doubleclick\.net|googlesyndication\.com/, route => route.abort());
  } catch {}


  const page = context.pages()[0] || await context.newPage();
  // ⭐ 注入动态反检测脚本
  await page.addInitScript(getInitScript(fp));
  
  try {
    await page.goto('https://discord.com/app', { waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {});
  } catch {}
  
  console.log('[Browser] ✅ 浏览器已打开 (fingerprint=' + fp.fingerprintId + ')');
  return { context, page };
}

/**
 * extractAccountFromContext —— 从已打开浏览器提取账号信息（v2: 增加 fingerprint 记录）
 */
async function extractAccountFromContext(context) {
  const pages = context.pages();
  const page = pages.find(p => (p.url() || '').includes('discord')) || pages[0];
  if (!page) return null;

  let capturedToken = null;
  const reqHandler = (response) => {
    try {
      const auth = response.headers()['authorization'] || response.headers()['Authorization'];
      if (auth && !auth.startsWith('Bot ') && auth.length > 50) capturedToken = auth;
    } catch {}
  };
  context.on('response', reqHandler);

  console.log('[Browser] 网络拦截已就绪，等待 Discord API 请求抓 token...');
  for (let i = 0; i < 8; i++) {
    await new Promise(r => setTimeout(r, 500));
    if (capturedToken) {
      console.log(`[Browser] 🎯 网络拦截抓到 token (${capturedToken.length} chars)`);
      break;
    }
  }
  context.off('response', reqHandler);

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

  if (capturedToken && !token) {
    token = capturedToken;
    console.log(`[Browser] 用网络拦截 token (${token.length} chars)`);
  } else if (capturedToken && token && capturedToken !== token) {
    console.log('[Browser] ⚠️ storage token vs 网络拦截 token 不一致 → 用网络拦截的');
    token = capturedToken;
  }

  if (!token) {
    console.log('[Browser] 网络拦截 + storage 均未找到 token');
    return null;
  }

  console.log(`[Browser] 扫描到 token (${token.length} chars)，验证中...`);
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
    console.log(`[Browser] token 验证失败 (可能已过期)`);
    return { token, tokenValid: false };
  }

  console.log(`[Browser] ✅ token有效! 用户: ${userInfo.username} (${userInfo.id})`);
  trace.browserWorkflow('extract_done', { userId: userInfo.id });
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

module.exports = {
  captureDiscordAccount,
  launchBrowserOnly,
  extractAccountFromContext,
  // 新模块（供外部调用）
  fingerprint,
  networkGate,
  capacity,
  trace,
  SYSTEM_CHROME,
};
