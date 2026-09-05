/**
 * Discord REST API 客户端
 *
 * 代理检测优先级：
 *   1. config.json 的 discordProxy 显式配置（支持 http/https/socks5/socks4）
 *   2. 系统环境变量 ALL_PROXY / HTTPS_PROXY / HTTP_PROXY
 *   3. Windows 注册表 IE 代理设置
 *   都没有则直连
 *
 * 依赖可选：没装 socks-proxy-agent → 自动降级为 HTTP 代理
 */
const axios = require('axios');
const { loadConfig } = require('./config');
const { execSync } = require('child_process');

const cfg = loadConfig();

// 可选依赖：try-catch 按需加载
let HttpsProxyAgent = null;
let SocksProxyAgent = null;
try { HttpsProxyAgent = require('https-proxy-agent').HttpsProxyAgent; } catch {}
try { SocksProxyAgent = require('socks-proxy-agent').SocksProxyAgent; } catch {}

// 本地自动探测：猫熊 VPN / Clash / v2rayN / Shadowrocket / Surge 常见端口
const LOCAL_PROBE_CANDIDATES = [
  { url: 'http://127.0.0.1:7890',  label: 'HTTP 7890  (Clash全家桶默认)' },
  { url: 'socks5://127.0.0.1:7891', label: 'SOCKS 7891 (Clash全家桶默认)' },
  { url: 'http://127.0.0.1:10809', label: 'HTTP 10809 (v2rayN默认)' },
  { url: 'socks5://127.0.0.1:10808', label: 'SOCKS 10808 (v2rayN默认)' },
  { url: 'http://127.0.0.1:6152',  label: 'HTTP 6152  (Surge默认)' },
  { url: 'socks5://127.0.0.1:1080', label: 'SOCKS 1080 (Shadowrocket/QuantumultX)' },
];

let localProbeCache = null;  // 只探测一次，结果缓存

function detectProxyUrl() {
  // 优先级 1: config.json 显式配置
  //   "discordProxy": "http://127.0.0.1:7890" → 强制用这个
  //   "discordProxy": "none" / "" / null      → 强制直连（生产服务器）
  if (cfg.discordProxy !== undefined && cfg.discordProxy !== null && cfg.discordProxy !== '') {
    const v = cfg.discordProxy.trim().toLowerCase();
    if (v === 'none' || v === 'direct' || v === 'off') {
      if (!cfg.production) console.log('[Discord] 代理 → config.json 明确禁用 ("discordProxy": "none")，强制直连');
      return 'NONE';  // 特殊标记，告诉上层不要走代理
    }
    if (!cfg.production) console.log(`[Discord] 代理 → config.json: ${cfg.discordProxy.trim()}`);
    return cfg.discordProxy.trim();
  }

  // 优先级 2: 环境变量
  const envProxy = process.env.ALL_PROXY
                || process.env.all_proxy
                || process.env.HTTPS_PROXY || process.env.https_proxy
                || process.env.HTTP_PROXY || process.env.http_proxy;
  if (envProxy && envProxy.trim()) {
    if (!cfg.production) console.log(`[Discord] 代理 → 环境变量: ${envProxy.trim()}`);
    return envProxy.trim();
  }

  // 优先级 3: Windows 注册表
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
        const match = server.match(/ProxyServer\s+REG_SZ\s+(.+)/i);
        if (match) {
          let proxyUrl = match[1].trim();
          if (proxyUrl.includes('=')) {
            const httpsMatch = proxyUrl.match(/https=([^;]+)/i);
            const httpMatch = proxyUrl.match(/http=([^;]+)/i);
            proxyUrl = (httpsMatch || httpMatch)[1];
          }
          if (!proxyUrl.startsWith('http')) proxyUrl = 'http://' + proxyUrl;
          if (!cfg.production) console.log(`[Discord] 代理 → Windows 系统代理: ${proxyUrl}`);
          return proxyUrl;
        }
      }
    } catch {}
  }

  // 优先级 4: 自动探测本地常见代理端口（异步探测，返回 null 让 findWorkingProxy 接管）
  if (!cfg.production) console.log(`[Discord] 🔍 未配置代理，开始探测本地常见端口（猫熊/Clash/v2rayN/Surge...）`);
  return 'AUTO_PROBE';  // 特殊标记
}

async function autoProbeLocalProxy() {
  if (localProbeCache) return localProbeCache;

  let HttpsProxyAgent = null, SocksProxyAgent = null;
  try { HttpsProxyAgent = require('https-proxy-agent').HttpsProxyAgent; } catch {}
  try { SocksProxyAgent = require('socks-proxy-agent').SocksProxyAgent; } catch {}

  async function testOne({ url, label }) {
    const net = require('net');
    return new Promise(resolve => {
      const u = new URL(url);
      const sock = net.connect({ host: u.hostname, port: u.port });
      let resolved = false;
      const finish = (ok) => { if (!resolved) { resolved = true; sock.destroy(); resolve(ok ? url : null); } };
      sock.once('connect', () => finish(true));
      sock.once('error', () => finish(false));
      setTimeout(() => finish(false), 800);
    });
  }

  for (const cand of LOCAL_PROBE_CANDIDATES) {
    process.stdout.write(`[Discord]   🔌 试 ${cand.label} ... `);
    const ok = await testOne(cand);
    if (ok) {
      if (!cfg.production) console.log(`✅ 通了！→ ${ok}`);
      localProbeCache = ok;
      return ok;
    }
    console.log(`❌ 不通`);
  }

  if (!cfg.production) console.log(`[Discord] 🔍 本地代理探测完毕 → 全部不通，将直连 discord.com`);
  localProbeCache = null;
  return null;
}
// Windows Clash 常见端口 fallback
// 用户配的 7890 如果是 SOCKS 端口或规则没代理 discord.com, 自动试其他端口
const PROXY_FALLBACKS = [
  // HTTP 端口 (Clash 默认 7890, v2rayN 默认 10809)
  { url: (p) => `http://127.0.0.1:${p}`, ports: [7890, 10809, 6152, 1080] },
  // SOCKS5 端口 (Clash 默认 7891, v2rayN 默认 10808)
  { url: (p) => `socks5://127.0.0.1:${p}`, ports: [7891, 10808, 1080] },
];

async function findWorkingProxy() {
  if (!proxyUrl) return null;  // 没配代理就不找了
  const HttpsProxyAgent = (require('https-proxy-agent').HttpsProxyAgent);
  const SocksProxyAgent = (require('socks-proxy-agent').SocksProxyAgent);
  
  async function testOne(url) {
    try {
      const u = new URL(url);
      const agent = (u.protocol.startsWith('socks'))
        ? new SocksProxyAgent(url)
        : new HttpsProxyAgent(url);
      const axios = require('axios');
      await axios.get('https://discord.com/api/v10/gateway', {
        [u.protocol.startsWith('socks') ? 'httpsAgent' : 'httpsAgent']: agent,
        [u.protocol.startsWith('socks') ? 'httpAgent' : 'httpAgent']: agent,
        timeout: 5000, validateStatus: () => true,
      });
      return agent;
    } catch { return null; }
  }

  // 1. 先试用户配的
  if (!cfg.production) console.log(`[Discord] 🧪 先试用户配置: ${proxyUrl}`);
  let agent = await testOne(proxyUrl);
  if (agent) { console.log(`[Discord] ✅ 用户配置可用`); return agent; }
  console.warn(`[Discord] ⚠️ 用户配置超时, 开始自动探测可用端口...`);

  // 2. 多协议多端口 fallback
  const tried = new Set();
  for (const fb of PROXY_FALLBACKS) {
    for (const port of fb.ports) {
      const url = fb.url(port);
      if (tried.has(url)) continue;
      tried.add(url);
      process.stdout.write(`[Discord]   试 ${url} ... `);
      agent = await testOne(url);
      if (agent) { console.log(`✅ 通了！`); console.log(`[Discord] ✅ 自动改用代理: ${url}`); return agent; }
      console.log(`超时`);
    }
  }
  return null;  // 全挂
}

function buildAgent(proxyUrl) {
  try {
    let url = proxyUrl;
    // 无协议头自动补 http://
    if (!url.match(/^[a-z]+:\/\//i)) {
      url = 'http://' + url;
    }
    const u = new URL(url);
    const protocol = u.protocol.toLowerCase();

    if ((protocol === 'socks5:' || protocol === 'socks4:') && SocksProxyAgent) {
      if (!cfg.production) console.log(`[Discord] 🔌 SOCKS 代理: ${url}`);
      return new SocksProxyAgent(url);
    }

    if (protocol === 'socks5:' || protocol === 'socks4:') {
      console.warn(`[Discord] ⚠️ SOCKS 代理但未装 socks-proxy-agent，降级为 HTTP`);
      // 降级：加 http:// 但用户真填 SOCKS 的话还是会失败...
      // 让用户明确知道需要 npm install
      console.warn(`[Discord] 💡 请执行: npm install socks-proxy-agent`);
      return null;
    }

    if (HttpsProxyAgent) {
      if (!cfg.production) console.log(`[Discord] 🔌 HTTP 代理: ${url}`);
      return new HttpsProxyAgent(url);
    }

    // 都没装 → 用 axios 原生 proxy（虽然有 bug，但能走就行）
    console.warn(`[Discord] ⚠️ 未装 https-proxy-agent，用 axios 原生 proxy`);
    const { hostname, port } = u;
    const portNum = parseInt(port) || (u.protocol === 'https:' ? 443 : 80);
    return { host: hostname, port: portNum, protocol: u.protocol.replace(':', '') };
  } catch (err) {
    console.error(`[Discord] ❌ 代理格式错误: ${proxyUrl}`, err.message);
    return null;
  }
}

let proxyUrl = detectProxyUrl();
// 处理特殊标记：NONE=强制直连, AUTO_PROBE=异步探测后决定
if (proxyUrl === 'NONE') {
  // 显式禁用代理 → 生产服务器场景，直接跳过
  proxyUrl = null;
}
// buildAgent 先返回临时 agent, 启动时再 findWorkingProxy / autoProbeLocalProxy 替换
let agentOrProxy = (proxyUrl && proxyUrl !== 'AUTO_PROBE') ? buildAgent(proxyUrl) : null;

/**
 * 安全地把 JSON 里的大整数（16-20 位 Snowflake ID）转成字符串字面量
 * 逐字符扫描，维护 inString 状态，只在非字符串区域替换
 * 绝对不破坏字符串内部的数字（比如 content 里的数字、URL 里的 ID）
 */
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

// 组装 axios 配置
const axiosConfig = {
  baseURL: 'https://discord.com/api/v10',
  timeout: 15000,
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    'Content-Type': 'application/json',
  },
  // Snowflake ID 19 位，超过 JS Number.MAX_SAFE_INTEGER (2^53-1 = 9007199254740991)
  // 用逐字符扫描把 JSON 属性值里的长整数（16-20 位）转成字符串，再 JSON.parse
  // 安全：绝对不破坏字符串内部的数字（如 content 里的数字、URL 里的 ID）
  transformResponse: [(raw) => {
    if (typeof raw !== 'string') return raw;
    const fixed = convertBigIntsInJson(raw);
    try { return JSON.parse(fixed); } catch { return raw; }
  }],
};

if (agentOrProxy) {
  if (agentOrProxy.request) {
    // 是 Agent 实例（https-proxy-agent / socks-proxy-agent）
    axiosConfig.httpAgent = agentOrProxy;
    axiosConfig.httpsAgent = agentOrProxy;
  } else if (agentOrProxy.host) {
    // 是 axios 原生 proxy 配置（降级）
    axiosConfig.proxy = agentOrProxy;
  }
}

const discordHttp = axios.create(axiosConfig);

// 异步自检 + 自动 fallback
(async function init() {
  // 特殊：本地自动探测模式（没配代理）
  if (proxyUrl === 'AUTO_PROBE') {
    const found = await autoProbeLocalProxy();
    if (found) {
      if (!cfg.production) console.log(`[Discord] ✅ 探测到本地代理: ${found}`);
      proxyUrl = found;
      agentOrProxy = buildAgent(found);
      // 后续走 findWorkingProxy 验证 + fallback 裸连
    } else {
      if (!cfg.production) console.log('[Discord] ℹ️ 本地无可用代理，将直连 discord.com');
      proxyUrl = null;
      agentOrProxy = null;
    }
  }

  if (!proxyUrl) {
    // 没配代理 → 直接测裸连 (VPN/TUN 模式)
    if (!cfg.production) console.log('[Discord] 🧪 裸连自检中...');
    try {
      await axios.get('https://discord.com/api/v10/gateway', { timeout: 8000, validateStatus: () => true });
      console.log('[Discord] ✅ 裸连自检通过!');
    } catch(e) {
      console.error('[Discord] ❌ 裸连也不通!');
      console.error('[Discord] 💡 国内环境请启动本地代理并确保端口可达');
      console.error('[Discord] 💡 或在 config.json 加 "discordProxy": "http://127.0.0.1:7890"');
    }
    return;
  }
  const working = await findWorkingProxy();
  if (working && working !== agentOrProxy) {
    agentOrProxy = working;
    axiosConfig.httpAgent = working;
    axiosConfig.httpsAgent = working;
    Object.assign(discordHttp.defaults, {
      httpAgent: working, httpsAgent: working,
    });
    if (!cfg.production) console.log('[Discord] ✅ axios agent 已切换到可用代理');
  } else if (!working) {
    console.error('[Discord] ❌ 所有代理端口都试过了, 全不通!');
    // 关键: 自动测裸连, 看 Windows TUN/系统代理能不能接管
    if (!cfg.production) console.log('[Discord] 🧪 尝试裸连 (让 TUN/系统代理接管)...');
    try {
      await axios.get('https://discord.com/api/v10/gateway', { timeout: 8000, validateStatus: () => true });
      if (!cfg.production) console.log('[Discord] ✅ 裸连通了！自动切换到裸连模式（依赖系统代理/TUN）');
      // 清掉所有 agent, 让 Node 直连 (Windows Clash TUN 会自动路由)
      Object.assign(discordHttp.defaults, { httpAgent: undefined, httpsAgent: undefined, proxy: undefined });
      agentOrProxy = null;
      // 注: config.json 的 discordProxy 暂时忽略
    } catch(e) {
      console.error('[Discord] ❌ 裸连也不通, 彻底无网!');
      console.error('[Discord] 💡 Clash 完全没在工作, 检查:');
      console.error('[Discord] 💡   1. Clash 是否启动 + 订阅是否更新');
      console.error('[Discord] 💡   2. 是否需要开 TUN/增强模式 (Clash 设置里)');
      console.error('[Discord] 💡   3. 系统代理是否已设 (控制面板→Internet选项→LAN设置)');
    }
  }
})();

// 标记: 是否已经在 fallback 裸连中, 避免无限重试
let isFallingBack = false;

discordHttp.interceptors.response.use(
  r => r,
  async (err) => {
    const code = err.code;
    const isNetworkError = ['ECONNABORTED', 'ECONNRESET', 'ECONNREFUSED', 'EPIPE', 'ETIMEDOUT'].includes(code);
    
    // 关键: 请求失败 + 配了代理 + 还没 fallback → 去掉 agent 裸连重试一次
    if (isNetworkError && agentOrProxy && !isFallingBack && !err.config?._fallbackTried) {
      console.warn(`[Discord] ⚠️ 代理请求失败(${code}), 自动裸连重试一次...`);
      isFallingBack = true;
      try {
        // 裸连: 清掉 agent, 让系统/TUN接管
        const bareConfig = { ...err.config, _fallbackTried: true };
        delete bareConfig.httpAgent;
        delete bareConfig.httpsAgent;
        delete bareConfig.proxy;
        const retry = await axios.request(bareConfig);
        console.warn(`[Discord] ✅ 裸连成功! 以后自动忽略代理, 用裸连模式`);
        // 后续所有请求都裸连
        agentOrProxy = null;
        Object.assign(discordHttp.defaults, { httpAgent: undefined, httpsAgent: undefined, proxy: undefined });
        isFallingBack = false;
        return retry;
      } catch (bareErr) {
        isFallingBack = false;
        // 裸连也失败, 输出诊断
        console.error(`[Discord] ❌ 裸连也失败(${bareErr.code})! GFW 完全不通`);
      }
    }
    
    // 常规错误提示
    if (code === 'ECONNABORTED' || (code === 'ETIMEDOUT')) {
      console.error(`[Discord] ❌ 请求超时`);
    } else if (code === 'ECONNREFUSED') {
      console.error(`[Discord] ❌ 代理端口没人监听! Clash 没启动?`);
    } else if (err.response?.status === 407) {
      console.error(`[Discord] ❌ 代理需要认证(407)`);
    } else if (code === 'ECONNRESET') {
      console.error(`[Discord] ❌ 连接被重置`);
    }
    return Promise.reject(err);
  }
);



/**
 * 获取当前 token 账号的好友列表（包括已接受 + 待请求）
 * Discord API: GET /users/@me/relationships
 * 返回格式: [{ id, username, global_name, avatar, type, ... }]
 *   type=1 好友  type=2 待接收  type=3 发送中  type=4 阻止
 */
async function fetchFriends(token) {
  const resp = await discordHttp.get('/users/@me/relationships', {
    headers: { Authorization: token }
  });
  return resp.data;
}

/**
 * 拉取账号的所有 DM 频道（含 1:1 和群组）
 * Discord API: GET /users/@me/channels
 * 返回: [{ id, type, recipients: [{id, username, global_name, avatar}], ... }]
 */
async function fetchDmChannels(token) {
  const resp = await discordHttp.get('/users/@me/channels', {
    headers: { Authorization: token }
  });
  return resp.data;
}

/**
/**
 * 发送带附件的消息（multipart/form-data）
 * Discord API: POST /channels/{channelId}/messages
 * body: content + 多次 file 字段
 *
 * files: [{ filename, contentType, dataBase64 }]
 *
 * ★ 使用 fd.getBuffer() + req.write() + Node.js 原生 https
 *    fd.pipe() 在某些环境下会丢失 multipart 后续字段，改为先拿完整 buffer 再一次性 write
 *    axios 的 transformRequest / getBuffer 路径会损坏 multipart 结构
 *    必须加 Content-Length header 确保 Discord 正确接收完整请求体
 */
/**
 * ★ 手动构建 multipart/form-data —— 不依赖 form-data 包
 *    form-data v4 的 getBuffer()/pipe() 在某些环境（Windows + 代理）下会丢失字段
 *    手动构建最可控：每个 boundary 分隔符、每个字段名、每个文件名都精确控制
 */
async function sendMessageWithFiles(token, channelId, content, files) {
  const https = require('https');
  const { URL } = require('url');
  const crypto = require('crypto');

  // 生成 boundary（16字节随机hex）
  const boundary = '----DiscordBoundary' + crypto.randomBytes(8).toString('hex');
  const CRLF = '\r\n';
  const parts = [];

  // 1. content 字段（文本部分）
  if (content) {
    const header = Buffer.from(
      `--${boundary}${CRLF}` +
      `Content-Disposition: form-data; name="content"${CRLF}${CRLF}`
    );
    const body = Buffer.from(content, 'utf8');
    parts.push(header, body, Buffer.from(CRLF));
  }

  // 2. 每个文件字段
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    const fileBuf = Buffer.from(f.dataBase64, 'base64');
    const filename = (f.filename || `file_${i}`).replace(/"/g, '\\"');
    const contentType = f.contentType || 'application/octet-stream';
    const header = Buffer.from(
      `--${boundary}${CRLF}` +
      `Content-Disposition: form-data; name="file"; filename="${filename}"${CRLF}` +
      `Content-Type: ${contentType}${CRLF}${CRLF}`
    );
    console.log(`[sendMessageWithFiles] file[${i}] filename=${filename} size=${fileBuf.length} contentType=${contentType}`);
    parts.push(header, fileBuf, Buffer.from(CRLF));
  }

  // 3. 结束 boundary
  const endBuf = Buffer.from(`--${boundary}--${CRLF}`);
  parts.push(endBuf);

  // 合并所有 parts
  const bodyBuffer = Buffer.concat(parts);

  console.log(`[sendMessageWithFiles] contentBytes=${content ? Buffer.byteLength(content) : 0}, files=${files.length}, totalBytes=${bodyBuffer.length}`);

  // 发送
  const targetUrl = new URL(`https://discord.com/api/v10/channels/${channelId}/messages`);
  const headers = {
    'Content-Type': `multipart/form-data; boundary=${boundary}`,
    'Content-Length': bodyBuffer.length,
    'Authorization': token,
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
  };

  return new Promise((resolve, reject) => {
    const req = https.request({
      method: 'POST',
      hostname: targetUrl.hostname,
      port: 443,
      path: targetUrl.pathname,
      headers,
      timeout: 20000,
      agent: agentOrProxy && agentOrProxy.request ? agentOrProxy : undefined,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const bodyBuf = Buffer.concat(chunks);
        try {
          const data = JSON.parse(bodyBuf.toString('utf8'));
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(data);
          } else {
            reject(new Error(`HTTP ${res.statusCode}: ${data.message || data.code || bodyBuf.toString().slice(0, 200)}`));
          }
        } catch (e) {
          reject(new Error(`HTTP ${res.statusCode}: ${bodyBuf.toString().slice(0, 200)}`));
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('请求超时')); });
    req.write(bodyBuffer);
    req.end();
  });
}


// 暴露当前代理 URL（供 browser.js 启动 Chrome 时用）
function getProxyUrl() {
  if (!proxyUrl || proxyUrl === 'NONE' || proxyUrl === 'AUTO_PROBE') return null;
  return proxyUrl;
}

module.exports = { discordHttp, fetchFriends, fetchDmChannels, sendMessageWithFiles, getProxyUrl };
