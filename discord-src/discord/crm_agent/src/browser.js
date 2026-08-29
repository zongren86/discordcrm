/**
 * Discord 用户采集 —— Playwright
 * 打开 Discord 登录页，等待用户登录，捕获 token + 用户信息
 */
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

function checkPlaywrightOrphans() {
  try {
    const { execSync } = require('child_process');
    const isWin = os.platform() === 'win32';
    let cmd;
    if (isWin) {
      cmd = 'tasklist /FI "IMAGENAME eq chrome.exe" /FO CSV 2>nul';
    } else {
      cmd = "pgrep -f 'chromium.*playwright' 2>/dev/null || true";
    }
    try {
      const out = execSync(cmd, { encoding: 'utf8', timeout: 3000 }).trim();
      const lines = out.split('\n').filter(Boolean);
      if (lines.length > 1) {
        console.log('[Browser] chrome.exe 运行中（不会杀用户浏览器）');
      }
    } catch {}
  } catch {}
}

async function captureDiscordAccount(browserConfig = {}) {
  checkPlaywrightOrphans();
  await new Promise(r => setTimeout(r, 1000));

  let userDataDir;
  if (browserConfig.userDataDir) {
    userDataDir = path.resolve(browserConfig.userDataDir);
  } else {
    userDataDir = path.join(os.tmpdir(), `crm-agent-profile-${Date.now()}`);
  }
  try {
    if (fs.existsSync(userDataDir)) {
      fs.rmSync(userDataDir, { recursive: true, force: true });
    }
    fs.mkdirSync(userDataDir, { recursive: true });
  } catch (e) {
    console.warn('[Browser] userDataDir 清理失败，继续尝试:', e.message);
  }

  console.log(`[Browser] 启动 Chromium... (profile=${userDataDir})`);

  let browser;
  try {
    browser = await chromium.launchPersistentContext(
      userDataDir,
      {
        headless: browserConfig.headless ?? false,
        viewport: browserConfig.viewport || { width: 1280, height: 800 },
        args: [
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
        ],
        ignoreHTTPSErrors: true,
      }
    );
  } catch (launchErr) {
    console.error('[Browser] 启动失败:', launchErr.message.split('\n')[0]);
    throw new Error('浏览器启动失败: ' + launchErr.message.split('\n')[0]);
  }

  const page = browser.pages()[0] || await browser.newPage();

  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en'] });
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
    window.chrome = { runtime: {} };
    const originalQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (parameters) => (
      parameters.name === 'notifications'
        ? Promise.resolve({ state: Notification.permission })
        : originalQuery(parameters)
    );
    // 去掉标题里的 "— 由 Chrome 自动测试软件控制"
    try { document.title = document.title.replace(/[—-]\s*Chrome.*Automation.*$/i, ''); } catch {}
  });

  console.log('[Browser] 打开 Discord 登录页...');
  try {
    await page.goto('https://discord.com/login', {
      waitUntil: 'domcontentloaded',
      timeout: 30000
    });
  } catch (navErr) {
    console.error('[Browser] 导航失败:', navErr.message.split('\n')[0]);
    try { await browser.close(); } catch {}
    throw new Error('无法打开 Discord 登录页（网络问题？）: ' + navErr.message.split('\n')[0]);
  }

  const result = {
    token: null,
    userId: null,
    username: null,
    email: null,
    avatarUrl: null,
    raw: null,
  };

  // === 核心改进：多层 token 检测 ===
  const findTokenInStorage = async () => {
    return await page.evaluate(async () => {
      // 1. localStorage
      let token = null;
      const scan = (storage) => {
        const keys = Object.keys(storage);
        for (const k of keys) {
          const v = storage.getItem(k);
          if (!v || v.length < 20) continue;
          // Discord token: 3段 base64 JWT 或很长的字符串
          if (v.split('.').length === 3 && v.length > 50) {
            token = v;
            return true;
          }
          if (k.toLowerCase().includes('token') || k.toLowerCase().includes('auth')) {
            if (v.length > 20) { token = v; return true; }
          }
        }
        return false;
      };
      scan(localStorage) || scan(sessionStorage);

      // 2. IndexedDB（Discord 新版可能存这里）
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
                    const tx = req.result.transaction(req.result.objectStoreNames[0], 'readonly');
                    const store = tx.objectStore(req.result.objectStoreNames[0]);
                    const all = store.getAll();
                    all.onsuccess = () => {
                      const items = all.result || [];
                      for (const item of items) {
                        const str = typeof item === 'string' ? item : JSON.stringify(item);
                        // 找 JWT 格式的 token
                        const match = str.match(/[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{20,}/);
                        if (match) { token = match[0]; break; }
                      }
                    };
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

  const fetchUserInfo = async (token) => {
    try {
      const resp = await page.evaluate(async (t) => {
        try {
          const r = await fetch('https://discord.com/api/users/@me', {
            headers: { Authorization: t }
          });
          if (r.ok) return { ok: true, data: await r.json() };
          return { ok: false, status: r.status };
        } catch (e) {
          return { ok: false, error: e.message };
        }
      }, token);
      if (resp && resp.ok) return resp.data;
    } catch {}
    return null;
  };

  let scanCount = 0;
  const checkAll = async () => {
    scanCount++;
    const token = await findTokenInStorage();
    if (token && token !== result.token) {
      result.token = token;
      console.log(`[Browser] 扫描#${scanCount} 找到 token (${token.length} chars)`);
    }
    if (result.token && !result.userId) {
      const user = await fetchUserInfo(result.token);
      if (user) {
        result.userId = user.id;
        result.username = user.username || user.global_name || user.display_name;
        result.email = user.email || null;
        if (user.avatar) {
          result.avatarUrl = `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png`;
        }
        result.raw = user;
        console.log(`[Browser] ✅ 捕获到用户: ${result.username} (${result.userId})`);
      } else {
        console.log(`[Browser] 扫描#${scanCount} token 存在但 fetch @me 失败`);
      }
    }
  };

  console.log('[Browser] 等待用户登录 Discord... (最多 5 分钟)');
  const timeoutMs = 5 * 60 * 1000;
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 2000));
    await checkAll();
    if (result.token && result.userId && result.username) {
      console.log('[Browser] ✅ 采集条件满足，退出等待');
      break;
    }
  }

  // 最终再检一次
  await checkAll();

  if (!result.token || !result.userId) {
    console.error('[Browser] ❌ 采集失败');
    console.error(`  token: ${result.token ? '有 (' + result.token.length + ' chars)' : '无'}`);
    console.error(`  userId: ${result.userId || '无'}`);
    console.error(`  username: ${result.username || '无'}`);
    try { await browser.close(); } catch {}
    throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
  }

  console.log(`[Browser] 🎉 采集完成: ${result.username} (${result.userId})`);
  try { await browser.close(); } catch {}
  return result;
}

module.exports = { captureDiscordAccount };
