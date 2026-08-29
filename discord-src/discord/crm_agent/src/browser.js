/**
 * Discord 用户采集 —— Playwright
 * 打开 Discord 登录页，等待用户登录，捕获 token + 用户信息
 */
const { chromium } = require('playwright');
const path = require('path');

/**
 * 启动浏览器并采集一个 Discord 用户
 * @returns {Promise<{token:string, userId:string, username:string, email:string|null, avatarUrl:string|null, raw:object}>}
 */
async function captureDiscordAccount(browserConfig = {}) {
  const userDataDir = browserConfig.userDataDir
    ? path.resolve(browserConfig.userDataDir)
    : null;

  console.log('[Browser] 启动 Chromium...');
  const browser = await chromium.launchPersistentContext(
    userDataDir || path.join(__dirname, '..', 'data', 'browser-temp'),
    {
      headless: browserConfig.headless ?? false,
      viewport: browserConfig.viewport || { width: 1280, height: 800 },
      args: ['--no-sandbox', '--disable-blink-features=AutomationControlled'],
      ignoreHTTPSErrors: true,
    }
  );

  const page = browser.pages()[0] || await browser.newPage();

  // 屏蔽 webdriver 检测
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
  });

  console.log('[Browser] 打开 Discord 登录页...');
  await page.goto('https://discord.com/login', { waitUntil: 'domcontentloaded' });

  // 采集结果容器
  const result = {
    token: null,
    userId: null,
    username: null,
    email: null,
    avatarUrl: null,
    raw: null,
  };

  // 方法1：监听 localStorage 里的 token
  const checkToken = async () => {
    try {
      const token = await page.evaluate(() => {
        // Discord 把 token 存在 localStorage.token 或用加密形式
        // 简化处理：遍历所有 key 找包含 MFA/DTOKEN/TOKEN/Authorization 的
        const keys = Object.keys(localStorage);
        for (const k of keys) {
          const v = localStorage.getItem(k);
          if (!v) continue;
          if (v.length > 200 && (v.startsWith('MTIz') || v.startsWith('OTA') || v.startsWith('NzI'))) {
            return v;
          }
          // 有的版本存在 token 字段
          if (k.toLowerCase() === 'token' || k.toLowerCase().includes('token')) {
            return v;
          }
        }
        // 新版可能在 window.__token 之类的地方
        // 尝试从 localStorage 找任何看起来像 JWT 的东西
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
      // 方法2：直接调用 /users/@me API（需要先设置 Authorization）
      const userInfo = await page.evaluate(async () => {
        // 从 localStorage 找 token
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

  // 定期检查（登录后几秒钟 token 就会出现）
  const intervalId = setInterval(checkToken, 2000);

  // 等待直到同时有 token 和 userId，或者超时
  const timeoutMs = 5 * 60 * 1000; // 5 分钟
  const deadline = Date.now() + timeoutMs;

  console.log('[Browser] 等待用户登录 Discord... (最多 5 分钟)');
  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 1000));
    if (result.token && result.userId && result.username) {
      break;
    }
  }

  clearInterval(intervalId);

  // 最终再检查一次
  await checkToken();

  if (!result.token || !result.userId) {
    await browser.close();
    throw new Error('采集超时或用户信息不完整，请确认已在 Discord 页面完成登录');
  }

  console.log(`[Browser] 采集完成: ${result.username} (${result.userId})`);
  console.log('[Browser] 关闭浏览器');
  await browser.close();

  return result;
}

module.exports = { captureDiscordAccount };
