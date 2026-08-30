/**
 * Discord REST API 客户端
 * 支持三种代理来源（按优先级）：
 *   1. config.json 的 discordProxy 显式配置
 *   2. 系统环境变量 HTTPS_PROXY / HTTP_PROXY
 *   3. Windows 注册表 IE 代理设置（HKCU\...\Internet Settings）
 *   都没有则直连
 */
const axios = require('axios');
const { loadConfig } = require('./config');
const { execSync } = require('child_process');

const cfg = loadConfig();

function detectProxy() {
  // 1. 显式配置（优先）
  if (cfg.discordProxy && cfg.discordProxy.trim()) {
    console.log(`[Discord] 📋 使用 config.json 代理: ${cfg.discordProxy}`);
    return cfg.discordProxy.trim();
  }

  // 2. 环境变量（Linux/macOS/Windows 通用）
  const envProxy = process.env.HTTPS_PROXY || process.env.https_proxy 
                || process.env.HTTP_PROXY || process.env.http_proxy;
  if (envProxy) {
    console.log(`[Discord] 📋 使用环境变量代理: ${envProxy}`);
    return envProxy.trim();
  }

  // 3. Windows 注册表（IE/系统代理）
  if (process.platform === 'win32') {
    try {
      // 读 ProxyEnable
      const enable = execSync('reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyEnable 2>nul', { encoding: 'utf8' });
      if (enable.includes('0x1')) {
        const server = execSync('reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings" /v ProxyServer 2>nul', { encoding: 'utf8' });
        const match = server.match(/ProxyServer\s+REG_SZ\s+(.+)/);
        if (match) {
          let proxyUrl = match[1].trim();
          // 可能是 "server:port" 或 "http=server:port;https=server:port" 格式
          if (proxyUrl.includes('=')) {
            // 提取 http:// 或 https:// 后面的部分
            const httpsMatch = proxyUrl.match(/https=([^;]+)/i);
            const httpMatch = proxyUrl.match(/http=([^;]+)/i);
            proxyUrl = (httpsMatch || httpMatch)[1];
          }
          if (!proxyUrl.startsWith('http')) proxyUrl = 'http://' + proxyUrl;
          console.log(`[Discord] 🛰️ 自动检测 Windows 系统代理: ${proxyUrl}`);
          return proxyUrl;
        }
      }
    } catch { /* 注册表读不到 = 没开代理，忽略 */ }
  }

  console.log('[Discord] 直连模式（未检测到代理）');
  return '';
}

function parseProxy(url) {
  try {
    const u = new URL(url);
    // axios 原生只支持 HTTP/HTTPS 代理
    if (u.protocol === 'http:' || u.protocol === 'https:') {
      return { host: u.hostname, port: parseInt(u.port) || (u.protocol === 'https:' ? 443 : 80) };
    }
    console.warn(`[Discord] ⚠️ 代理协议 ${u.protocol} axios 不支持，尝试当 HTTP 用`);
    return { host: u.hostname, port: parseInt(u.port) || 80 };
  } catch {
    console.error('[Discord] ❌ 代理格式错误:', url);
    return false;
  }
}

const proxyUrl = detectProxy();
const proxyConfig = proxyUrl ? parseProxy(proxyUrl) : false;

const discordHttp = axios.create({
  baseURL: 'https://discord.com/api/v10',
  timeout: 15000,
  proxy: proxyConfig,
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    'Content-Type': 'application/json',
  },
});

// 错误拦截：超时提示代理可能问题
discordHttp.interceptors.response.use(
  r => r,
  err => {
    if (err.code === 'ECONNABORTED' && err.message?.includes('timeout')) {
      console.error(`[Discord] ❌ 请求超时！discord.com 被 GFW 拦截，代理可能未生效`);
      console.error(`[Discord] 💡 请检查 config.json 的 discordProxy 或 Windows 系统代理是否开启`);
    } else if (err.code === 'ECONNREFUSED' && proxyConfig) {
      console.error(`[Discord] ❌ 代理连接失败 ${proxyConfig.host}:${proxyConfig.port}`);
    } else if (err.response?.status === 407) {
      console.error(`[Discord] ❌ 代理需要认证（407 Proxy Auth Required）`);
    }
    return Promise.reject(err);
  }
);

module.exports = { discordHttp };
