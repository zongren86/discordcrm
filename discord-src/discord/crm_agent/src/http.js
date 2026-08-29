const axios = require('axios');
const { loadConfig } = require('./config');

const cfg = loadConfig();

// 确保 serverUrl 以 /api 结尾（防御用户漏写）
let baseURL = cfg.serverUrl.replace(/\/$/, '');
if (!baseURL.endsWith('/api')) {
  // 如果结尾是 /api/xxx 的形式也修正
  if (baseURL.match(/\/api\/[^/]+$/)) {
    baseURL = baseURL.replace(/\/api\/[^/]+$/, '/api');
  } else {
    baseURL = baseURL + '/api';
  }
  console.warn(`[配置] serverUrl 缺少 /api 后缀，已自动补全为: ${baseURL}`);
}

const http = axios.create({
  baseURL,
  timeout: 30000,
  maxRedirects: 0,  // 禁用自动重定向，避免代理/VPN 劫持时出现 redirect loop
});

// 所有请求自动带上 X-Agent-Token 头
http.interceptors.request.use(c => {
  c.headers['X-Agent-Token'] = cfg.token;
  return c;
});

http.interceptors.response.use(
  r => r.data,
  err => {
    const url = err.config?.url || '';
    const msg = err.response?.data?.error || err.message;
    const status = err.response?.status || '?';
    const location = err.response?.headers?.location || '';
    let hint = '';
    if (err.code === 'ERR_TOO_MANY_REDIRECTS' || (typeof status === 'number' && status >= 300 && status < 400)) {
      hint = '\n  ⚠️ 检测到重定向！Windows 用户请检查是否开了全局代理（Clash/Surge/V2Ray），请关闭或把 ' + cfg.serverUrl + ' 加入直连规则';
    }
    if (status === 'ECONNREFUSED' || err.code === 'ECONNREFUSED') {
      hint = '\n  ⚠️ 连接被拒绝！请确认后端 ' + cfg.serverUrl + ' 可达，且 serverUrl 含 /api 后缀';
    }
    console.error(`[HTTP] ${err.config?.method?.toUpperCase() || '?'} ${url} → ${status} ${msg}${hint}`);
    return Promise.reject(err);
  }
);

module.exports = { http, cfg };
