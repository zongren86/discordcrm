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

// 安全转换大整数的函数 (和 discord.js 里的一致, 确保 Snowflake/accountId 不丢精度)
function convertBigIntsInJson(raw) {
  if (typeof raw !== 'string') return raw;
  let inString = false, escapeNext = false;
  let out = '', buf = '';
  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i];
    if (escapeNext) { out += ch; escapeNext = false; continue; }
    if (ch === '\\' && inString) { out += ch; escapeNext = true; continue; }
    if (ch === '"') {
      if (!inString && buf) {
        if (buf.length >= 16 && buf.length <= 20) out += '"' + buf + '"';
        else out += buf;
        buf = '';
      }
      inString = !inString;
      out += ch;
      continue;
    }
    if (inString) { out += ch; continue; }
    if (/[0-9]/.test(ch)) { buf += ch; }
    else {
      if (buf) {
        if (buf.length >= 16 && buf.length <= 20) out += '"' + buf + '"';
        else out += buf;
        buf = '';
      }
      out += ch;
    }
  }
  if (buf) {
    if (buf.length >= 16 && buf.length <= 20) out += '"' + buf + '"';
    else out += buf;
  }
  return out;
}

const http = axios.create({
  baseURL,
  timeout: 30000,
  maxRedirects: 0,
  // 处理后端返回的 Snowflake/discordId 等大数字, 避免 JS 精度丢失
  transformResponse: [(raw) => {
    if (typeof raw !== 'string') return raw;
    const fixed = convertBigIntsInJson(raw);
    try { return JSON.parse(fixed); } catch { return raw; }
  }],
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
      const loc = err.response?.headers?.location || '(无 Location 头)';
      hint = '\n  ⚠️ 检测到 ' + status + ' 重定向！';
      hint += '\n  → Location: ' + loc;
      hint += '\n  可能原因: 全局代理软件（Clash/Surge/V2Ray/Proxifier）劫持了后端请求，';
      hint += '\n  请关闭代理或把 ' + cfg.serverUrl + ' 加入直连规则（不走代理）。';
    } else if (err.code === 'ECONNREFUSED') {
      hint = '\n  ⚠️ 连接被拒绝！请确认后端 ' + cfg.serverUrl + ' 可达';
    }
    
    console.error(`[HTTP] ${err.config?.method?.toUpperCase() || '?'} ${url} → ${status} ${msg}${hint}`);
    return Promise.reject(err);
  }
);

module.exports = { http, cfg };
