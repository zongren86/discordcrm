const axios = require('axios');
const { loadConfig } = require('./config');

const cfg = loadConfig();

// 确保 serverUrl 以 /api 结尾（防御用户漏写）
let baseURL = cfg.serverUrl.replace(/\/$/, '');
if (!baseURL.endsWith('/api')) {
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
  maxRedirects: 0,
});

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
    
    // poll 404 = 无任务（正常空闲），完全静默
    const isPollNoTask = url?.includes('/tasks/poll') && status === 404;
    if (isPollNoTask) {
      return Promise.reject(err);
    }
    
    let hint = '';
    if (status === 401 && url?.includes('/heartbeat')) {
      hint = '\n  ⚠️ Token 无效！请在前端「配置→代理管理」复制正确的 token 到 config.json';
    } else if (err.code === 'ERR_TOO_MANY_REDIRECTS' || (typeof status === 'number' && status >= 300 && status < 400)) {
      hint = '\n  ⚠️ 检测到重定向！Windows 用户请检查是否开了全局代理（Clash/Surge/V2Ray），请关闭或把 ' + cfg.serverUrl + ' 加入直连规则';
    } else if (err.code === 'ECONNREFUSED') {
      hint = '\n  ⚠️ 连接被拒绝！请确认后端 ' + cfg.serverUrl + ' 可达';
    }
    
    console.error(`[HTTP] ${err.config?.method?.toUpperCase() || '?'} ${url} → ${status} ${msg}${hint}`);
    return Promise.reject(err);
  }
);

module.exports = { http, cfg };
