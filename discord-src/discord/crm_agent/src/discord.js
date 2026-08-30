/**
 * Discord REST API 客户端
 *
 * 代理检测优先级：
 *   1. config.json 的 discordProxy 显式配置（支持 http/https/socks5/socks4）
 *   2. 系统环境变量 ALL_PROXY / HTTPS_PROXY / HTTP_PROXY
 *   3. Windows 注册表 IE 代理设置（HKCU\...\Internet Settings）
 *   都没有则直连
 *
 * 关键：用 https-proxy-agent / socks-proxy-agent 处理代理，
 *  axios 原生 proxy 只支持 HTTP/HTTPS 且常失效。
 */
const axios = require('axios');
const { HttpsProxyAgent } = require('https-proxy-agent');
const { SocksProxyAgent } = require('socks-proxy-agent');
const { loadConfig } = require('./config');
const { execSync } = require('child_process');

const cfg = loadConfig();

function detectProxyUrl() {
  // 1. 显式配置（优先）
  if (cfg.discordProxy && cfg.discordProxy.trim()) {
    console.log(`[Discord] 📋 使用 config.json 代理: ${cfg.discordProxy.trim()}`);
    return cfg.discordProxy.trim();
  }

  // 2. 环境变量（ALL_PROXY 最高优先级）
  const envProxy = process.env.ALL_PROXY
                || process.env.all_proxy
                || process.env.HTTPS_PROXY || process.env.https_proxy
                || process.env.HTTP_PROXY || process.env.http_proxy;
  if (envProxy) {
    console.log(`[Discord] 📋 使用环境变量代理: ${envProxy.trim()}`);
    return envProxy.trim();
  }

  // 3. Windows 注册表（IE/系统代理）
  if (process.platform === 'win32') {
    try {
      const enable = execSync(
        'reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyEnable 2>nul',
        { encoding: 'utf8' }
      );
      if (enable.includes('0x1')) {
        const server = execSync(
          'reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyServer 2>nul',
          { encoding: 'utf8' }
        );
        const match = server.match(/ProxyServer\s+REG_SZ\s+(.+)/);
        if (match) {
          let proxyUrl = match[1].trim();
          if (proxyUrl.includes('=')) {
            const httpsMatch = proxyUrl.match(/https=([^;]+)/i);
            const httpMatch = proxyUrl.match(/http=([^;]+)/i);
            proxyUrl = (httpsMatch || httpMatch)[1];
          }
          if (!proxyUrl.startsWith('http')) proxyUrl = 'http://' + proxyUrl;
          console.log(`[Discord] 🛰️ 自动检测 Windows 系统代理: ${proxyUrl}`);
          return proxyUrl;
        }
      }
    } catch { /* 注册表读不到 = 没开代理 */ }
  }

  console.log('[Discord] 直连模式（未检测到代理）');
  return '';
}

function buildAgent(proxyUrl) {
  try {
    const u = new URL(proxyUrl);
    const protocol = u.protocol.toLowerCase();

    if (protocol === 'socks5:' || protocol === 'socks4:') {
      console.log(`[Discord] 🔌 使用 SOCKS 代理: ${proxyUrl}`);
      return new SocksProxyAgent(proxyUrl);
    }

    if (protocol === 'http:' || protocol === 'https:') {
      console.log(`[Discord] 🔌 使用 HTTP 代理: ${proxyUrl}`);
      return new HttpsProxyAgent(proxyUrl);
    }

    const fallbackUrl = 'http://' + proxyUrl.replace(/^\/*/, '');
    console.warn(`[Discord] ⚠️ 代理 URL 无协议头，尝试当 HTTP 用: ${fallbackUrl}`);
    return new HttpsProxyAgent(fallbackUrl);
  } catch (err) {
    console.error(`[Discord] ❌ 代理格式错误: ${proxyUrl}`, err.message);
    return null;
  }
}

const proxyUrl = detectProxyUrl();
const proxyAgent = proxyUrl ? buildAgent(proxyUrl) : null;

const discordHttp = axios.create({
  baseURL: 'https://discord.com/api/v10',
  timeout: 15000,
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    'Content-Type': 'application/json',
  },
  ...(proxyAgent ? {
    httpAgent: proxyAgent,
    httpsAgent: proxyAgent,
  } : {}),
});

discordHttp.interceptors.response.use(
  r => r,
  err => {
    if (err.code === 'ECONNABORTED' && err.message?.includes('timeout')) {
      console.error(`[Discord] ❌ 请求超时！discord.com 可能被 GFW 拦截`);
      if (!proxyAgent) {
        console.error(`[Discord] 💡 未配置代理！config.json 加: "discordProxy": "http://127.0.0.1:7890"`);
      } else {
        console.error(`[Discord] 💡 代理 ${proxyUrl} 可能有问题`);
      }
    } else if (err.code === 'ECONNREFUSED') {
      console.error(`[Discord] ❌ 代理连接被拒绝！代理服务没启动？`);
    } else if (err.response?.status === 407) {
      console.error(`[Discord] ❌ 代理需要认证（407）`);
    } else if (err.code === 'ECONNRESET') {
      console.error(`[Discord] ❌ 连接被重置！GFW 或代理配置错误`);
    }
    return Promise.reject(err);
  }
);

module.exports = { discordHttp };
