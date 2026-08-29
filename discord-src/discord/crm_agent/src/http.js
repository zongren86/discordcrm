const axios = require('axios');
const { loadConfig } = require('./config');

const cfg = loadConfig();

const http = axios.create({
  baseURL: cfg.serverUrl.replace(/\/$/, ''),
  timeout: 30000,
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
    console.error(`[HTTP] ${err.config?.method?.toUpperCase() || '?'} ${url} → ${err.response?.status || '?'} ${msg}`);
    return Promise.reject(err);
  }
);

module.exports = { http, cfg };
