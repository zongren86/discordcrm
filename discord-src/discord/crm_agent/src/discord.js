/**
 * Discord REST API 客户端
 * 独立实例，支持配置 HTTP/HTTPS 代理（解决 Windows 家庭网络 GFW 问题）
 */
const axios = require('axios');
const { loadConfig } = require('./config');

const cfg = loadConfig();

// 从 config.json 读取代理配置（解决 GFW / 网络隔离问题）
// Windows 用户如果开了 Clash/Surge/V2Ray，填 'http://127.0.0.1:7890' 或对应端口
const proxy = cfg.discordProxy || '';

const discordHttp = axios.create({
  baseURL: 'https://discord.com/api/v10',
  timeout: 15000,
  proxy: proxy ? parseProxy(proxy) : false,  // false = 不走代理
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    'Content-Type': 'application/json',
  },
});

function parseProxy(url) {
  try {
    const u = new URL(url);
    return { host: u.hostname, port: parseInt(u.port) || (u.protocol === 'https:' ? 443 : 80) };
  } catch {
    console.error('[Discord] ❌ 代理格式错误:', url, '→ 应为 http://host:port');
    return false;
  }
}

if (proxy) {
  console.log(`[Discord] 🛰️ 代理已启用: ${proxy}`);
} else {
  console.log('[Discord] 直连模式（无代理）');
}

module.exports = { discordHttp };
