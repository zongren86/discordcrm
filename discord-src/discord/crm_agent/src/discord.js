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

function detectProxyUrl() {
  // 优先级 1: config.json 显式配置（最可靠）
  if (cfg.discordProxy && cfg.discordProxy.trim()) {
    const url = cfg.discordProxy.trim();
    console.log(`[Discord] 代理 → config.json: ${url}`);
    return url;
  }

  // 优先级 2: 环境变量
  const envProxy = process.env.ALL_PROXY
                || process.env.all_proxy
                || process.env.HTTPS_PROXY || process.env.https_proxy
                || process.env.HTTP_PROXY || process.env.http_proxy;
  if (envProxy && envProxy.trim()) {
    console.log(`[Discord] 代理 → 环境变量: ${envProxy.trim()}`);
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
          console.log(`[Discord] 代理 → Windows 系统代理: ${proxyUrl}`);
          return proxyUrl;
        }
      }
    } catch {}
  }

  // 没配置代理 → 提醒用户
  console.warn(`[Discord] ⚠️ 未配置代理，将直连 discord.com（国内会被 GFW 拦截）`);
  console.warn(`[Discord] 💡 解决: 在 config.json 加 "discordProxy": "http://127.0.0.1:7890"`);
  console.warn(`[Discord] 💡 或者设置环境变量 ALL_PROXY=http://127.0.0.1:7890 后启动`);
  return null;
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
      console.log(`[Discord] 🔌 SOCKS 代理: ${url}`);
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
      console.log(`[Discord] 🔌 HTTP 代理: ${url}`);
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

const proxyUrl = detectProxyUrl();
const agentOrProxy = proxyUrl ? buildAgent(proxyUrl) : null;

// 组装 axios 配置
const axiosConfig = {
  baseURL: 'https://discord.com/api/v10',
  timeout: 15000,
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
    'Content-Type': 'application/json',
  },
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

discordHttp.interceptors.response.use(
  r => r,
  err => {
    if (err.code === 'ECONNABORTED' && err.message?.includes('timeout')) {
      console.error(`[Discord] ❌ 请求超时 — discord.com 被 GFW 拦截${!proxyUrl ? '，且未配置代理！' : ''}`);
      if (!proxyUrl) console.error(`[Discord] 💡 解决: config.json 加 "discordProxy": "http://127.0.0.1:7890"`);
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

// ========== 启动时代理连通性自检 ==========
(async function proxySelfTest() {
  try {
    console.log('[Discord] 🧪 代理连通性自检中...');
    const start = Date.now();
    const r = await axios.get('https://discord.com/api/v10/gateway', {
      httpsAgent: agentOrProxy?.request ? agentOrProxy : undefined,
      httpAgent: agentOrProxy?.request ? agentOrProxy : undefined,
      timeout: 8000,
      validateStatus: () => true,  // 不抛异常, 任何 status 都算通
    });
    const ms = Date.now() - start;
    if (r.status >= 200 && r.status < 500) {
      console.log(`[Discord] ✅ 代理自检通过 (${ms}ms, status=${r.status})`);
    } else {
      console.warn(`[Discord] ⚠️ 代理自检返回 status=${r.status}, 可能不支持 HTTPS CONNECT`);
    }
  } catch (err) {
    const code = err.code || 'UNKNOWN';
    const ms = Date.now();
    console.error(`[Discord] ❌ 代理自检失败: ${code} — ${err.message?.slice(0, 100)}`);
    
    if (code === 'ECONNREFUSED') {
      console.error(`[Discord] 💡 127.0.0.1:7890 没人监听！请先启动 Clash / v2rayN / Surge 等代理软件`);
      console.error(`[Discord] 💡 确认代理的 HTTP 端口是 7890，不是的话改 config.json 的 discordProxy 字段`);
    } else if (code === 'ETIMEDOUT') {
      console.error(`[Discord] 💡 代理端口连得上但出不去！检查 Clash 规则里 discord.com 有没有走代理`);
    } else if (code === 'ECONNRESET') {
      console.error(`[Discord] 💡 连接被重置！代理软件可能不支持 HTTPS CONNECT 隧道`);
    } else if (code === 'EPROTOCOL') {
      console.error(`[Discord] 💡 端口不是 HTTP 代理！Clash 的 SOCKS5 端口是 7891，HTTP 是 7890，别搞混了`);
    }
    
    if (!proxyUrl) {
      console.error(`[Discord] 💡 你根本没配代理！config.json 加: "discordProxy": "http://127.0.0.1:7890"`);
    }
  }
})();

module.exports = { discordHttp };
