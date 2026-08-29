/**
 * Discord 用户采集 —— Playwright
 * 打开 Discord 登录页，等待用户登录，捕获 token + 用户信息
 */
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

/** 
 * 安全检测残留 Playwright Chromium（不杀用户自己的 Chrome！）
 * Playwright 启动的浏览器有以下特征：
 *   - 命令行包含 --playwright
 *   - 或命令行 user-data-dir 指向临时目录
 * 用户自己的 Chrome 绝对不碰
 */
function checkPlaywrightOrphans() {
  try {
    const { execSync } = require('child_process');
    const isWin = os.platform() === 'win32';
    let cmd;
    if (isWin) {
      // Windows: 只找带 playwright 特征的 chrome.exe，绝不杀
      cmd = 'wmic process where "name='chrome.exe' and commandline like '%--playwright%'" get processid 2>nul';
    } else {
      cmd = 'pgrep -f "chromium.*playwright" 2>/dev/null || true';
    }
    try {
      const out = execSync(cmd, { encoding: 'utf8', timeout: 3000 }).trim();
      if (out && out.split('
').filter(Boolean).length > 0) {
        console.warn('[Browser] 检测到残留的 Playwright Chromium，建议手动关闭后重试');
      }
    } catch {}
    // 只读检测，不杀任何进程！
  } catch {}
}

/**
 * 启动浏览器并采集一个 Discord 用户
 */
async function captureDiscordAccount(browserConfig = {}) {
  // 清理残留
  checkPlaywrightOrphans();
  await new Promise(r => setTimeout(r, 1000));

  // 每次用独立的临时 userDataDir，避免冲突
  let userDataDir;
  if (browserConfig.userDataDir) {
    userDataDir = path.resolve(browserConfig.userDataDir);
  } else {
    userDataDir = path.join(os.tmpdir(), `crm-agent-profile-${Date.now()}`);
  }
  
  // 确保目录存在且干净
  try {
    if (fs.existsSync(userDataDir)) {
      // 只清理临时目录，不清理用户指定的目录
      if (!browserConfig.userDataDir) {
        fs.rmSync(userDataDir, { recursive: true, force: true });
      }
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
          '--metrics-recording-only',
          '--password-store=basic',
          '--use-mock-keychain',
          '--disable-breakpad',
        ],
        ignoreHTTPSErrors: true,
      }
    );
  } catch (launchErr) {
    console.error('[Browser] 启动失败:', launchErr.message.split('\n')[0]);
    throw new Error(`浏览器启动失败: ${launchErr.message.split('\n')[0]}`);
  }

  const page = browser.pages()[0] || await browser.newPage();

  // 屏蔽所有自动化检测
  await page.addInitScript(() => {
    // 1. webdriver
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });

    // 2. chrome runtime
    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en'] });
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
    window.chrome = { runtime: {} };

    // 3. 覆盖 permissions
    const originalQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (parameters) => (
      parameters.name === 'notifications'
        ? Promise.resolve({ state: Notification.permission })
        : originalQuery(parameters)
    );

    // 4. 覆盖 WebGL vendor
    const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
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
    throw new Error(`无法打开 Discord 登录页（网络问题？）: ${navErr.message.split('\n')[0]}`);
  }

  const result = {
    token: null,
    userId: null,
    username: null,
    email: null,
    avatarUrl: null,
    raw: null,
  };

  const checkToken = async () => {
    try {
      const token = await page.evaluate(() => {
        const keys = Object.keys(localStorage);
        for (const k of keys) {
          const v = localStorage.getItem(k);
          if (!v) continue;
          if (v.length > 200 && (v.startsWith('MTIz') || v.startsWith('OTA') || v.startsWith('NzI'))) {
            return v;
          }
          if (k.toLowerCase() === 'token' || k.toLowerCase().includes('token')) {
            return v;
          }
        }
        for (const k of keys) {
          const v = localStorage.getItem(k);
          if (v && v.split('.').length === 3 && v.length > 50) return v;
        }
        return null;
      });
      if (token && token !== result.token) {
        result.token = token;
        console.log('[Browser] 捕获到 token (localStorage)');
      }
    } catch (e) { /* ignore */ }

    try {
      const userInfo = await page.evaluate(async () => {
        let token = null;
        const keys = Object.keys(localStorage);
        for (const k of keys) {
          const v = localStorage.getItem(k);
          if (!v) continue;
          if (v.split('.').length === 3 && v.length > 50) { token = v; break; }
        }
        if (!token) return null;
        try {
          const resp = await fetch('https://discord.com/api/users/@me', {
            headers: { Authorization: token }
          });
          if (resp.ok) return await resp.json();
        } catch (e) {}
        return null;
      });
      if (userInfo) {
        result.userId = userInfo.id;
        result.username = userInfo.username || userInfo.global_name;
        result.email = userInfo.email || null;
        if (userInfo.avatar) {
          result.avatarUrl = `https://cdn.discordapp.com/avatars/${userInfo.id}/${userInfo.avatar}.png`;
        }
        result.raw = userInfo;
        console.log(`[Browser] 捕获到用户: ${result.username} (${result.userId})`);
      }
    } catch (e) { /* ignore */ }
  };

  const intervalId = setInterval(checkToken, 2000);

  const timeoutMs = 5 * 60 * 1000;
  const deadline = Date.now() + timeoutMs;

  console.log('[Browser] 等待用户登录 Discord... (最多 5 分钟)');
  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 1000));
    if (result.token && result.userId && result.username) {
      break;
    }
  }

  clearInterval(intervalId);
  await checkToken();

  if (!result.token || !result.userId) {
    try { await browser.close(); } catch {}
    throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
  }

  console.log(`[Browser] 采集完成: ${result.username} (${result.userId})`);
  try { await browser.close(); } catch {}

  return result;
}

module.exports = { captureDiscordAccount };
