/**
 * Discord 用户采集 —— Playwright
 * 打开 Discord 登录页，等待用户登录，捕获 token + 用户信息
 */
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

/** 清理残留 Chromium 进程（Windows/Linux/Mac） */
function killOrphanChromium() {
  try {
    const { execSync } = require('child_process');
    const isWin = os.platform() === 'win32';
    if (isWin) {
      // Windows 上只杀 playwright 启动的残留（用 --remote-debugging-pipe 标记的）
      // 避免杀到用户自己的 Chrome
      try { execSync('taskkill /F /IM chrome.exe /T 2>nul', { stdio: 'ignore' }); } catch {}
      try { execSync('taskkill /F /IM chromium.exe /T 2>nul', { stdio: 'ignore' }); } catch {}
    } else {
      // Mac/Linux
      try { execSync('pkill -f "chrome.exe.*remote-debugging-pipe" 2>/dev/null', { stdio: 'ignore' }); } catch {}
      try { execSync('pkill -f "chromium.*remote-debugging-pipe" 2>/dev/null', { stdio: 'ignore' }); } catch {}
    }
    console.log('[Browser] 已清理残留浏览器进程');
  } catch {}
}

/**
 * 启动浏览器并采集一个 Discord 用户
 */
async function captureDiscordAccount(browserConfig = {}) {
  // 清理残留
  killOrphanChromium();
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
        ],
        ignoreHTTPSErrors: true,
      }
    );
  } catch (launchErr) {
    console.error('[Browser] 启动失败:', launchErr.message.split('\n')[0]);
    throw new Error(`浏览器启动失败: ${launchErr.message.split('\n')[0]}`);
  }

  const page = browser.pages()[0] || await browser.newPage();

  // 屏蔽 webdriver 检测
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
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
