'use strict';

/**
 * account_fingerprint.js
 * 
 * 每个账号独立的浏览器指纹管理 —— 反检测核心模块
 * 
 * 策略（来自同行 node_agent 的 account_fingerprint）：
 *   1. 每账号独立 fingerprint 配置文件（~/.crm-agent/fingerprints/<agentName>.json）
 *   2. 首次启动时随机化生成，后续复用（保证同一账号指纹稳定）
 *   3. UA 版本跟随系统 Chrome 真实版本（不再硬编码 Chrome 131）
 *   4. discord_build_number 从 Discord 前端动态获取
 *   5. WebGL renderer / CPU / 内存 / 分辨率 / locale 全部随机化
 */

const fs = require('fs');
const path = require('path');
const os = require('os');

// ========== 随机化池（真实世界存在的值）==========

const CHROME_VERSIONS = [
  '143.0.0.0', '142.0.0.0', '141.0.0.0', '140.0.0.0',
  '139.0.0.0', '138.0.0.0', '137.0.0.0', '136.0.0.0',
];

const WEBGL_RENDERERS = [
  'ANGLE (Intel, Intel(R) UHD Graphics 630, OpenGL 4.5)',
  'ANGLE (Intel, Intel(R) UHD Graphics 620, OpenGL 4.5)',
  'ANGLE (Intel, Intel(R) Iris(R) Xe Graphics, OpenGL 4.5)',
  'ANGLE (AMD, AMD Radeon(TM) Graphics, OpenGL 4.5)',
  'ANGLE (AMD, AMD Radeon RX 5500 XT, OpenGL 4.5)',
  'ANGLE (AMD, AMD Radeon RX 6600 XT, OpenGL 4.5)',
  'ANGLE (NVIDIA, NVIDIA GeForce GTX 1650, OpenGL 4.5)',
  'ANGLE (NVIDIA, NVIDIA GeForce RTX 3060, OpenGL 4.5)',
  'ANGLE (NVIDIA, NVIDIA GeForce RTX 4060, OpenGL 4.5)',
  'ANGLE (Intel, Intel(R) HD Graphics 520, OpenGL 4.5)',
];

const WEBGL_VENDORS = ['Google Inc.', 'Intel Inc.', 'AMD', 'NVIDIA Corporation'];
const PLATFORMS = ['Win32', 'Win32', 'Win32', 'MacIntel', 'Win32'];
// 时区 → 对应 locale 列表（确保地理合理，避免 Discord 风控）
// ⭐ 时区 → 唯一本地语言（1 对 1，防违规第一原则！）
// 每个时区只对应一个最主流的本地语言，不允许多选
const TZ_LOCALE_PAIRS = [
  // ── 东亚 ──
  { timezone: 'Asia/Shanghai',     locale: 'zh-CN' },    // 中国大陆
  { timezone: 'Asia/Hong_Kong',    locale: 'zh-TW' },    // 香港（繁体）
  { timezone: 'Asia/Taipei',       locale: 'zh-TW' },    // 台湾（繁体）
  { timezone: 'Asia/Singapore',    locale: 'zh-CN' },    // 新加坡（华人为主，简体）
  { timezone: 'Asia/Tokyo',        locale: 'ja-JP' },    // 日本
  { timezone: 'Asia/Seoul',        locale: 'ko-KR' },    // 韩国

  // ── 东南亚 ──
  { timezone: 'Asia/Bangkok',      locale: 'th-TH' },    // 泰国
  { timezone: 'Asia/Jakarta',      locale: 'id-ID' },    // 印尼
  { timezone: 'Asia/Manila',      locale: 'en-US' },    // 菲律宾（英语为主）
  { timezone: 'Asia/Kuala_Lumpur', locale: 'zh-CN' },    // 马来西亚（华人多）
  { timezone: 'Asia/Vientiane',    locale: 'lo-LA' },    // 老挝
  { timezone: 'Asia/Ho_Chi_Minh',  locale: 'vi-VN' },    // 越南

  // ── 北美 ──
  { timezone: 'America/New_York',      locale: 'en-US' },    // 美东
  { timezone: 'America/Los_Angeles',   locale: 'en-US' },    // 美西
  { timezone: 'America/Chicago',       locale: 'en-US' },    // 美中
  { timezone: 'America/Toronto',       locale: 'en-US' },    // 加拿大（英语为主）
  { timezone: 'America/Vancouver',     locale: 'en-US' },    // 加拿大西岸
  { timezone: 'America/Mexico_City',   locale: 'es-MX' },    // 墨西哥
  { timezone: 'America/Sao_Paulo',     locale: 'pt-BR' },    // 巴西

  // ── 欧洲 ──
  { timezone: 'Europe/London',     locale: 'en-GB' },    // 英国
  { timezone: 'Europe/Berlin',     locale: 'de-DE' },    // 德国
  { timezone: 'Europe/Paris',      locale: 'fr-FR' },    // 法国
  { timezone: 'Europe/Madrid',     locale: 'es-ES' },    // 西班牙
  { timezone: 'Europe/Rome',       locale: 'it-IT' },    // 意大利
  { timezone: 'Europe/Amsterdam',  locale: 'nl-NL' },    // 荷兰
  { timezone: 'Europe/Moscow',      locale: 'ru-RU' },    // 俄罗斯
  { timezone: 'Europe/Istanbul',    locale: 'tr-TR' },    // 土耳其

  // ── 中东/北非 ──
  { timezone: 'Asia/Dubai',        locale: 'en-US' },    // 迪拜（英语为主）
  { timezone: 'Asia/Tehran',       locale: 'fa-IR' },    // 伊朗
  { timezone: 'Africa/Cairo',      locale: 'ar-SA' },    // 埃及
  { timezone: 'Africa/Johannesburg', locale: 'en-US' },  // 南非

  // ── 大洋洲 ──
  { timezone: 'Australia/Sydney',   locale: 'en-US' },  // 澳洲（英语）
  { timezone: 'Australia/Melbourne', locale: 'en-US' },
  { timezone: 'Pacific/Auckland',    locale: 'en-US' },  // 新西兰
];

// 加权选一个 (timezone, locale) 配对
function pickTzLocalePair() {
  const pair = pick(TZ_LOCALE_PAIRS);
  const locale = pair.locale;
  return { timezone: pair.timezone, locale };
}
const VIEWPORTS = [
  { width: 1920, height: 1080 },
  { width: 1440, height: 900 },
  { width: 1536, height: 864 },
  { width: 1366, height: 768 },
  { width: 1600, height: 900 },
  { width: 1680, height: 1050 },
  { width: 1920, height: 1200 },
  { width: 1280, height: 720 },
];
const CPUS = [4, 6, 8, 8, 8, 12, 12, 16];
const MEMORIES = [4, 8, 8, 8, 16, 16, 16, 32];
const DPRs = [1, 1, 1, 1, 1.25, 1.5, 2];
const DISCORD_BUILD_NUMBERS = [
  '482285', '482104', '481984', '481749', '481539', '481327', '481092',
];

// 时区 → UTC 偏移（分钟）
const TZ_OFFSETS = {
  'Asia/Shanghai': -480, 'Asia/Tokyo': -540, 'Asia/Hong_Kong': -480,
  'Asia/Singapore': -480, 'America/New_York': 300, 'America/Los_Angeles': 480,
  'Europe/London': 0, 'Europe/Berlin': -60, 'Australia/Sydney': -600,
};

// ========== 地理感知：代理 IP → 匹配 fingerprint ==========

// 国家代码 → 可能的时区列表（ISO 3166-1 alpha-2 → IANA TZ）
const COUNTRY_TZ_HINTS = {
  'CN': ['Asia/Shanghai'],
  'SG': ['Asia/Singapore'],
  'HK': ['Asia/Hong_Kong'],
  'TW': ['Asia/Taipei'],
  'JP': ['Asia/Tokyo'],
  'KR': ['Asia/Seoul'],
  'US': ['America/New_York', 'America/Los_Angeles', 'America/Chicago'],
  'CA': ['America/Toronto', 'America/Vancouver'],
  'GB': ['Europe/London'],
  'FR': ['Europe/Paris'],
  'DE': ['Europe/Berlin'],
  'ES': ['Europe/Madrid'],
  'IT': ['Europe/Rome'],
  'RU': ['Europe/Moscow'],
  'AU': ['Australia/Sydney'],
  'TH': ['Asia/Bangkok'],
  'ID': ['Asia/Jakarta'],
  'MY': ['Asia/Kuala_Lumpur', 'Asia/Singapore'],
  'VN': ['Asia/Ho_Chi_Minh'],
  'PH': ['Asia/Manila'],
  'IN': ['Asia/Kolkata'],
  'AE': ['Asia/Dubai'],
  'SA': ['Asia/Riyadh'],
  'NL': ['Europe/Amsterdam'],
  'PL': ['Europe/Warsaw'],
  'UA': ['Europe/Kiev'],
  'BR': ['America/Sao_Paulo'],
  'MX': ['America/Mexico_City'],
  'AR': ['America/Argentina/Buenos_Aires'],
  'ZA': ['Africa/Johannesburg'],
  'EG': ['Africa/Cairo'],
};

// 缓存：agentName → 探测结果（避免每次启动都请求 geolocation API）
const geoCache = new Map();

/**
 * 通过代理探测出口 IP 的地理位置
 * @param {string|null} proxyUrl 代理地址（null=直连）
 * @returns {Promise<{country:string, timezone:string, ip:string, source:string}|null>}
 */
// 本地代理自动探测：猫熊 VPN / Clash / v2rayN / Surge / Shadowrocket
const LOCAL_PROBE_PORTS = [
  { url: 'http://127.0.0.1:7890',   label: 'HTTP 7890  (Clash/猫熊默认)' },
  { url: 'socks5://127.0.0.1:7891', label: 'SOCKS 7891 (Clash/猫熊默认)' },
  { url: 'http://127.0.0.1:10809',  label: 'HTTP 10809 (v2rayN默认)' },
  { url: 'socks5://127.0.0.1:10808',label: 'SOCKS 10808 (v2rayN默认)' },
  { url: 'http://127.0.0.1:6152',   label: 'HTTP 6152  (Surge默认)' },
  { url: 'socks5://127.0.0.1:1080', label: 'SOCKS 1080 (Shadowrocket)' },
];
let localProbeCache = null;

async function probeLocalProxy() {
  if (localProbeCache) return localProbeCache;
  const net = require('net');
  for (const p of LOCAL_PROBE_PORTS) {
    const url = p.url;
    const m = url.match(/^(https?)?:\/\/(\d+\.\d+\.\d+\.\d+):(\d+)/);
    if (!m) continue;
    const host = m[2], port = parseInt(m[3]);
    try {
      await new Promise((resolve, reject) => {
        const sock = net.createConnection(port, host, () => { sock.destroy(); resolve(); });
        sock.setTimeout(800, () => { sock.destroy(); reject(new Error('timeout')); });
        sock.on('error', reject);
      });
      console.log('[Fingerprint] 🔍 自动探测到本地代理:', p.label, '→', url);
      localProbeCache = url;
      return url;
    } catch {}
  }
  localProbeCache = 'NONE';  // 缓存"无代理"结果，不要每次都扫
  return null;
}

async function detectProxyGeo(proxyUrl) {
  // ⭐ 如果没传 proxyUrl，先自动探测本地代理端口
  if (!proxyUrl) {
    const autoProxy = await probeLocalProxy();
    if (autoProxy && autoProxy !== 'NONE') {
      proxyUrl = autoProxy;
      console.log('[Fingerprint] 🌐 使用自动探测的本地代理去查询地理');
    } else if (localProbeCache === 'NONE') {
      console.log('[Fingerprint] 🌐 无代理可用，直连查询地理（如果你的网络在中国可能返回 CN）');
    }
  }
  
  if (proxyUrl && proxyUrl !== 'NONE') {
    const cached = geoCache.get(proxyUrl);
    if (cached) {
      console.log('[Fingerprint] 🌐 地理缓存命中:', JSON.stringify(cached));
      return cached;
    }
  }

  // geolocation API 候选（免费，允许 HTTP/SOCKS 代理）
  // API 按优先级排序：快 → 准 → 兜底
  const GEO_APIS = [
    // ① ip-api.com（HTTP，快，免费，GFW 不易拦）
    { url: 'http://ip-api.com/json/?fields=status,countryCode,timezone,query', parser: (j) => j.status === 'success' ? { country: j.countryCode, timezone: j.timezone, ip: j.query } : null },
    // ② ipwho.is（HTTP，免费，有 country + timezone）
    { url: 'http://ipwho.is/', parser: (j) => j.success ? { country: j.country_code, timezone: j.timezone?.id || null, ip: j.ip } : null },
    // ③ ipapi.co（HTTPS，部分网络可能被 GFW 拦）
    { url: 'https://ipapi.co/json/', parser: (j) => ({ country: j.country_code, timezone: j.timezone, ip: j.ip }) },
    // ④ 兜底：只要 IP，无地理 → 回退随机
    { url: 'https://api.ipify.org?format=json', parser: (j) => ({ country: null, timezone: null, ip: j.ip }) },
  ];

  let HttpsProxyAgent = null, SocksProxyAgent = null;
  try { HttpsProxyAgent = require('https-proxy-agent').HttpsProxyAgent; } catch {}
  try { SocksProxyAgent = require('socks-proxy-agent').SocksProxyAgent; } catch {}

  function buildAgent(url) {
    if (!url) return null;
    try {
      if (url.startsWith('socks')) return SocksProxyAgent ? new SocksProxyAgent(url) : null;
      if (url.startsWith('http')) return HttpsProxyAgent ? new HttpsProxyAgent(url) : null;
    } catch {}
    return null;
  }

  for (const api of GEO_APIS) {
    try {
      const agent = buildAgent(proxyUrl);
      const https = require('https');
      const http = require('http');
      const lib = api.url.startsWith('https') ? https : http;
      const opts = agent ? { agent, timeout: 5000 } : { timeout: 5000 };

      const data = await new Promise((resolve, reject) => {
        const req = lib.get(api.url, opts, (res) => {
          let body = '';
          res.on('data', (c) => body += c);
          res.on('end', () => {
            try { resolve(JSON.parse(body)); } catch { reject(new Error('bad json')); }
          });
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
      });

      const parsed = api.parser(data);
      if (parsed && parsed.ip) {
        const result = { ...parsed, source: proxyUrl ? 'proxy' : 'direct' };
        if (proxyUrl) geoCache.set(proxyUrl, result);
        console.log('[Fingerprint] 🌐 地理探测: IP=' + result.ip + ' country=' + (result.country || '?') + ' tz=' + (result.timezone || '?') + (proxyUrl ? ' (via proxy)' : ' (direct)'));
        return result;
      }
    } catch { /* 下一个 API */ }
  }

  console.warn('[Fingerprint] ⚠️ 地理探测失败（所有 API 都不通），回退随机配对');
  return null;
}

/**
 * 把地理信息匹配到最合适的 TZ_LOCALE_PAIRS
 */
function matchPairToGeo(geo) {
  if (!geo) return pickTzLocalePair();

  // 1. 如果 geo.timezone 直接在 TZ_LOCALE_PAIRS 里 → 直接用
  const tzMatch = TZ_LOCALE_PAIRS.find((p) => p.timezone === geo.timezone);
  if (tzMatch) {
    // ⭐ 总是选 locales[0]（本地语言），防违规第一原则！
    // 随机选会导致日本 IP 配 en-US、美国 IP 配 fr-CA 等地理不匹配
    const locale = tzMatch.locale;
    return { timezone: tzMatch.timezone, locale, fromGeo: true };
  }

  // 2. 用 country 找 hint 的时区，再去 TZ_LOCALE_PAIRS 里匹配
  if (geo.country && COUNTRY_TZ_HINTS[geo.country]) {
    for (const tz of COUNTRY_TZ_HINTS[geo.country]) {
      const pair = TZ_LOCALE_PAIRS.find((p) => p.timezone === tz);
      if (pair) {
        // ⭐ 总是选本地语言（防违规）
        const locale = pair.locale;
        return { timezone: pair.timezone, locale, fromGeo: true };
      }
    }
  }

  // 3. 都不匹配 → 用 geo.timezone + 一个合理 locale（从同地区 pair 里借）
  if (geo.timezone) {
    // 简化：直接用 geo.timezone，locale 用 en-US 兜底
    return { timezone: geo.timezone, locale: 'en-US', fromGeo: true };
  }

  // 4. 最终回退随机
  return pickTzLocalePair();
}

// ========== 工具函数 ==========

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

function pickWeighted(arr) {
  const weights = arr.map((_, i) => Math.max(1, arr.length - i));
  const total = weights.reduce((a, b) => a + b, 0);
  let r = Math.random() * total;
  for (let i = 0; i < arr.length; i++) { r -= weights[i]; if (r <= 0) return arr[i]; }
  return arr[0];
}

function simpleHash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
  return (h >>> 0).toString(16);
}

// ========== 指纹存储路径 ==========

function getFingerprintDir() {
  const dir = path.join(os.homedir(), '.crm-agent', 'fingerprints');
  if (!fs.existsSync(dir)) { try { fs.mkdirSync(dir, { recursive: true }); } catch {} }
  return dir;
}

function getFingerprintPath(agentName) {
  const safeName = (agentName || 'default').replace(/[^\w-]/g, '_');
  return path.join(getFingerprintDir(), safeName + '.json');
}

// ========== 获取系统 Chrome 真实版本 ==========

let _cachedChromeVersion = null;

function getSystemChromeVersion() {
  if (_cachedChromeVersion) return _cachedChromeVersion;
  try {
    if (os.platform() === 'darwin') {
      const plist = '/Applications/Google Chrome.app/Contents/Info.plist';
      if (fs.existsSync(plist)) {
        const { execSync } = require('child_process');
        const out = execSync('plutil -extract CFBundleShortVersionString raw "' + plist + '" 2>/dev/null', { encoding: 'utf8' });
        const v = out.trim();
        if (v) { _cachedChromeVersion = v.split('.')[0] + '.0.0.0'; return _cachedChromeVersion; }
      }
    } else if (os.platform() === 'linux') {
      const { execSync } = require('child_process');
      try {
        const v = execSync('google-chrome --version 2>/dev/null || chromium-browser --version 2>/dev/null', { encoding: 'utf8', timeout: 3000 });
        const m = v.match(/(\d+)/);
        if (m) { _cachedChromeVersion = m[1] + '.0.0.0'; return _cachedChromeVersion; }
      } catch {}
    }
  } catch {}
  _cachedChromeVersion = pickWeighted(CHROME_VERSIONS.slice(0, 4));
  return _cachedChromeVersion;
}

// ========== 动态获取 Discord build_number ==========

let _cachedBuildNumber = null;

async function fetchDiscordBuildNumber() {
  if (_cachedBuildNumber) return _cachedBuildNumber;
  try {
    const https = require('https');
    return await new Promise((resolve) => {
      const req = https.get('https://discord.com/login', {
        headers: { 'User-Agent': 'Mozilla/5.0 Chrome/' + getSystemChromeVersion() },
        timeout: 5000,
      }, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          const m = data.match(/BUILD_NUMBER["']?\s*[:=]\s*["']?(\d+)/) ||
                    data.match(/build_number["']?\s*[:=]\s*["']?(\d+)/);
          if (m) { _cachedBuildNumber = m[1]; resolve(m[1]); }
          else { resolve(pickWeighted(DISCORD_BUILD_NUMBERS)); }
        });
      });
      req.on('error', () => resolve(pickWeighted(DISCORD_BUILD_NUMBERS)));
      req.on('timeout', () => { req.destroy(); resolve(pickWeighted(DISCORD_BUILD_NUMBERS)); });
    });
  } catch { return pickWeighted(DISCORD_BUILD_NUMBERS); }
}

// ========== 生成随机指纹 ==========

function generateLanguages(mainLocale) {
  // 基于主 locale 生成合理的 languages 数组（Chrome Accept-Language 格式）
  const langMap = {
    'zh-CN': ['zh-CN', 'zh', 'en-US', 'en'],
    'zh-TW': ['zh-TW', 'zh', 'en-US', 'en'],
    'zh-HK': ['zh-HK', 'zh-TW', 'zh', 'en-US'],
    'en-US': ['en-US', 'en', 'zh-CN', 'zh'],
    'en-GB': ['en-GB', 'en-US', 'en', 'zh-CN'],
    'en-SG': ['en-SG', 'en-US', 'en', 'zh-CN'],
    'ja-JP': ['ja-JP', 'ja', 'en-US', 'en'],
    'ko-KR': ['ko-KR', 'ko', 'en-US', 'en'],
    'fr-FR': ['fr-FR', 'fr', 'en-US', 'en'],
    'fr-CA': ['fr-CA', 'fr', 'en-US', 'en'],
    'de-DE': ['de-DE', 'de', 'en-US', 'en'],
    'es-ES': ['es-ES', 'es', 'en-US', 'en'],
    'ru-RU': ['ru-RU', 'ru', 'en-US', 'en'],
    'th-TH': ['th-TH', 'th', 'en-US', 'en'],
    'id-ID': ['id-ID', 'id', 'en-US', 'en'],
    'ar-SA': ['ar-SA', 'ar', 'en-US', 'en'],
  };
  const langs = langMap[mainLocale] || ['en-US', 'en', 'zh-CN', 'zh'];
  return langs.join(',');
}

/**
 * @param {string} agentName
 * @param {object} [opts]
 * @param {{timezone:string,locale:string}|null} [opts.preferredPair] 地理探测返回的优先配对
 * @param {string|null} [opts.geoHint] 手动指定地理提示（如 'SG', 'US'），优先级最高
 */
function generateFingerprint(agentName, opts) {
  opts = opts || {};
  const chromeVersion = getSystemChromeVersion();
  const platform = pickWeighted(PLATFORMS);
  const isMac = platform === 'MacIntel';
  const viewport = pick(VIEWPORTS);

  // 决定 timezone + locale + languages：地理优先 → 用户指定 → 随机
  let pair;
  if (opts.geoHint) {
    // 手动指定地理提示 → 匹配 pair
    const hintPair = matchPairToGeo({ country: opts.geoHint.toUpperCase(), timezone: null });
    pair = hintPair;
  } else if (opts.preferredPair) {
    pair = opts.preferredPair;
  } else {
    pair = pickTzLocalePair();
  }

  const fp = {
    agentName: agentName || 'default',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),

    chromeVersion: chromeVersion,
    userAgent: 'Mozilla/5.0 (' + (isMac ? 'Macintosh; Intel Mac OS X 10_15_7' : 'Windows NT 10.0; Win64; x64') + ') AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + chromeVersion + ' Safari/537.36',

    platform: platform,
    hardwareConcurrency: pick(CPUS),
    deviceMemory: pick(MEMORIES),
    webglVendor: pick(WEBGL_VENDORS),
    webglRenderer: pick(WEBGL_RENDERERS),

    viewport: viewport,
    devicePixelRatio: pick(DPRs),
    screenWidth: viewport.width,
    screenHeight: viewport.height,
    colorDepth: 24,

    locale: pair.locale,
    timezone: pair.timezone,
    languages: generateLanguages(pair.locale),

    discordBuildNumber: pickWeighted(DISCORD_BUILD_NUMBERS),

    canvasNoiseSeed: Math.random().toString(36).slice(2, 10),
    audioNoiseSeed: Math.random().toString(36).slice(2, 10),

    fingerprintId: simpleHash((agentName || 'default') + '-' + chromeVersion + '-' + Date.now()),
  };
  return fp;
}

// ========== 加载/保存指纹 ==========

function loadFingerprint(agentName) {
  const fpPath = getFingerprintPath(agentName);
  try {
    if (fs.existsSync(fpPath)) {
      const fp = JSON.parse(fs.readFileSync(fpPath, 'utf8'));
      const sysChrome = getSystemChromeVersion();
      const fpMajor = parseInt((fp.chromeVersion || '131').split('.')[0]);
      const sysMajor = parseInt(sysChrome.split('.')[0]);
      if (sysMajor - fpMajor >= 3) {
        console.warn('[Fingerprint] ' + agentName + ' Chrome版本落后 ' + (sysMajor - fpMajor) + ' 代，刷新指纹');
        const newFp = generateFingerprint(agentName);
        newFp.canvasNoiseSeed = fp.canvasNoiseSeed;
        newFp.audioNoiseSeed = fp.audioNoiseSeed;
        saveFingerprint(newFp);
        return newFp;
      }
      return fp;
    }
  } catch (e) { console.warn('[Fingerprint] 加载失败 ' + agentName + ':', e.message); }
  return null;
}

function saveFingerprint(fp) {
  fp.updatedAt = new Date().toISOString();
  try { fs.writeFileSync(getFingerprintPath(fp.agentName), JSON.stringify(fp, null, 2)); return true; }
  catch (e) { console.warn('[Fingerprint] 保存失败:', e.message); return false; }
}

/**
 * 获取或创建指纹（异步：会探测代理出口 IP 的地理位置）
 * @param {string} agentName
 * @param {object} [opts]
 * @param {string|null} [opts.proxyUrl] 代理地址（用于探测出口 IP 地理）
 * @param {string|null} [opts.geoHint] 手动指定国家代码（如 'SG'），跳过自动探测
 */
async function getOrCreateFingerprint(agentName, opts) {
  opts = opts || {};
  let fp = loadFingerprint(agentName);
  let preferredPair = null;

  // ⭐ 不管有没有旧指纹，都先探测地理（确保 IP 和 locale 一致）
  if (opts.geoHint) {
    preferredPair = matchPairToGeo({ country: opts.geoHint.toUpperCase(), timezone: null });
    console.log('[Fingerprint] 📍 手动 geoHint=' + opts.geoHint + ' → tz=' + preferredPair.timezone + ' locale=' + preferredPair.locale);
  } else if (opts.proxyUrl) {
    const geo = await detectProxyGeo(opts.proxyUrl);
    if (geo) preferredPair = matchPairToGeo(geo);
  } else {
    // 没有 proxyUrl → detectProxyGeo 内部会自动探测本地代理端口
    const geo = await detectProxyGeo(null);
    if (geo) preferredPair = matchPairToGeo(geo);
  }

  if (!fp) {
    // 全新指纹
    fp = generateFingerprint(agentName, { preferredPair, geoHint: opts.geoHint });
    saveFingerprint(fp);
    const gpu = fp.webglRenderer.split(',')[1]?.trim() || fp.webglRenderer;
    console.log('[Fingerprint] 🆕 为 ' + agentName + ' 生成新指纹: Chrome ' + fp.chromeVersion + ', GPU=' + gpu + ', tz=' + fp.timezone + ', locale=' + fp.locale);
  } else {
    // ⭐ 有旧指纹 → 检查地理是否一致，不一致则刷新！
    if (preferredPair && (fp.locale !== preferredPair.locale || fp.timezone !== preferredPair.timezone)) {
      console.warn('[Fingerprint] ⚠️ ' + agentName + ' 指纹地理不匹配！' +
        ' 旧 tz=' + fp.timezone + ' locale=' + fp.locale +
        ' vs 期望 tz=' + preferredPair.timezone + ' locale=' + preferredPair.locale +
        ' → 刷新指纹');
      // 刷新：保留原有的 canvas/audio 噪声（避免改变其他标识），只换 locale + timezone
      const newFp = generateFingerprint(agentName, { preferredPair, geoHint: opts.geoHint });
      newFp.canvasNoiseSeed = fp.canvasNoiseSeed;
      newFp.audioNoiseSeed = fp.audioNoiseSeed;
      newFp.fingerprintId = fp.fingerprintId;  // 保持同一个 fingerprintId
      saveFingerprint(newFp);
      fp = newFp;
      const gpu = fp.webglRenderer.split(',')[1]?.trim() || fp.webglRenderer;
      console.log('[Fingerprint] 🔄 刷新后: tz=' + fp.timezone + ' locale=' + fp.locale);
    } else {
      console.log('[Fingerprint] ✅ 加载 ' + agentName + ' 指纹: Chrome ' + fp.chromeVersion + ', build=' + fp.discordBuildNumber + ', tz=' + fp.timezone + ', locale=' + fp.locale);
    }
  }
  return fp;
}

// ========== 生成 Playwright initScript ==========

function buildInitScript(fp) {
  if (!fp) {
    console.warn('[Fingerprint] 无指纹，使用默认 initScript');
    return getDefaultInitScript();
  }
  
  const tzOffset = TZ_OFFSETS[fp.timezone] ?? -480;
  const webglVendor = fp.webglVendor.replace(/['`"]/g, '');
  const webglRenderer = fp.webglRenderer.replace(/['`"]/g, '');
  
  return [
    // 1. webdriver
    "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });",
    '',
    // 2. UA + 硬件
    "Object.defineProperty(navigator, 'userAgent', { get: () => " + JSON.stringify(fp.userAgent) + " });",
    "Object.defineProperty(navigator, 'platform', { get: () => " + JSON.stringify(fp.platform) + " });",
    "Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => " + fp.hardwareConcurrency + " });",
    "Object.defineProperty(navigator, 'deviceMemory', { get: () => " + fp.deviceMemory + " });",
    "Object.defineProperty(navigator, 'language', { get: () => " + JSON.stringify(fp.locale) + " });",
    "Object.defineProperty(navigator, 'languages', { get: () => " + JSON.stringify(fp.languages.split(',')) + " });",
    '',
    // 3. 时区
    'try {',
    '  Date.prototype.getTimezoneOffset = function() { return ' + tzOffset + '; };',
    '  const _origIDF = Intl.DateTimeFormat;',
    '  Intl.DateTimeFormat = function(...args) {',
    '    if (!args[0] || typeof args[0] === "string") args[0] = {};',
    '    args[0].timeZone = ' + JSON.stringify(fp.timezone) + ';',
    '    return new _origIDF(...args);',
    '  };',
    '} catch {}',
    '',
    // 4. screen
    'try {',
    "  Object.defineProperty(screen, 'width', { get: () => " + fp.screenWidth + " });",
    "  Object.defineProperty(screen, 'height', { get: () => " + fp.screenHeight + " });",
    "  Object.defineProperty(screen, 'availWidth', { get: () => " + fp.screenWidth + " });",
    "  Object.defineProperty(screen, 'availHeight', { get: () => " + (fp.screenHeight - 40) + " });",
    "  Object.defineProperty(screen, 'colorDepth', { get: () => " + fp.colorDepth + " });",
    "  Object.defineProperty(screen, 'pixelDepth', { get: () => " + fp.colorDepth + " });",
    '} catch {}',
    '',
    // 5. Chrome 对象
    'try {',
    '  window.chrome = window.chrome || {};',
    '  window.chrome.runtime = window.chrome.runtime || { connect: function(){}, sendMessage: function(){} };',
    '  window.chrome.csi = window.chrome.csi || function(){};',
    '  window.chrome.loadTimes = window.chrome.loadTimes || function() { return { firstPaintTime: Date.now()/1000, startLoadTime: Date.now()/1000 }; };',
    '} catch {}',
    '',
    // 6. WebGL
    'try {',
    '  const _origGP = WebGLRenderingContext.prototype.getParameter;',
    '  WebGLRenderingContext.prototype.getParameter = function(p) {',
    '    if (p === 0x1F00) return ' + JSON.stringify(webglVendor) + ';',
    '    if (p === 0x1F01) return ' + JSON.stringify(webglRenderer) + ';',
    '    return _origGP.apply(this, arguments);',
    '  };',
    '} catch {}',
    '',
    // 7. Canvas 噪声
    'try {',
    "  const _canvasSeed = " + JSON.stringify(fp.canvasNoiseSeed) + ";",
    '  const _origToDataURL = HTMLCanvasElement.prototype.toDataURL;',
    '  HTMLCanvasElement.prototype.toDataURL = function() {',
    '    const ctx = this.getContext("2d");',
    '    if (ctx) {',
    '      const n = parseInt(_canvasSeed.slice(0,4), 36) % 255;',
    '      const img = ctx.getImageData(0,0,this.width,this.height);',
    '      for (let i = 0; i < img.data.length; i += 4) img.data[i] ^= (n & 1);',
    '      ctx.putImageData(img, 0, 0);',
    '    }',
    '    return _origToDataURL.apply(this, arguments);',
    '  };',
    '} catch {}',
    '',
    // 8. Audio 噪声
    'try {',
    "  const _audioSeed = " + JSON.stringify(fp.audioNoiseSeed) + ";",
    '  const _origGetFreq = AnalyserNode.prototype.getFrequencyData;',
    '  AnalyserNode.prototype.getFrequencyData = function(a) {',
    '    _origGetFreq.apply(this, arguments);',
    '    const n = parseInt(_audioSeed.slice(0,4), 36) % 255;',
    '    for (let i = 0; i < a.length; i++) a[i] = (a[i] + (n%3)-1 + 256) % 256;',
    '  };',
    '} catch {}',
    '',
    // 9. 屏蔽 CDP 痕迹
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Object; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Function; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Proxy; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Map; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Set; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Error; } catch {}',
    '',
    // 10. 插件伪装
    'try {',
    '  Object.defineProperty(navigator, "plugins", {',
    '    get: () => [',
    '      { name: "Chrome PDF Plugin", filename: "internal-pdf-viewer", description: "Portable Document Format" },',
    '      { name: "Chrome PDF Viewer", filename: "mhjfbmdgcfjbbpaeojofohoefgiehjai", description: "" },',
    '      { name: "Native Client", filename: "internal-nacl-plugin", description: "" },',
    '    ],',
    '  });',
    '} catch {}',
    '',
    // 11. vendor
    "try { Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.' }); } catch {}",
    '',
    // 12. Discord build_number
    'try {',
    '  window.GLOBAL_ENV = Object.assign({}, window.GLOBAL_ENV || {}, { BUILD_NUMBER: ' + fp.discordBuildNumber + ' });',
    '} catch {}',

    // ⭐ 13. hCaptcha 特别在意的 CDP 痕迹清除
    'try {',
    '  const _origPQ = navigator.permissions.query.bind(navigator.permissions);',
    '  navigator.permissions.query = function(descriptor) {',
    '    if (descriptor && descriptor.name === "notifications") {',
    '      return Promise.resolve({ state: "prompt", onchange: null });',
    '    }',
    '    return _origPQ(descriptor);',
    '  };',
    '} catch {}',

    // ⭐ 14. chrome.debugger API 伪造
    'try {',
    '  if (window.chrome && !window.chrome.debugger) {',
    '    window.chrome.debugger = {',
    '      sendCommand: function() { return Promise.resolve({}); },',
    '      attach: function() { return Promise.resolve(); },',
    '      detach: function() { return Promise.resolve(); },',
    '      onEvent: { addListener: function(){}, removeListener: function(){} },',
    '      onDetach: { addListener: function(){}, removeListener: function(){} },',
    '    };',
    '  }',
    '} catch {}',

    // ⭐ 15. document 上的 cdc_ado* 变量（之前只清了 window）
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Object; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch {}',

    // ⭐ 16. iframe contentWindow 的 webdriver 也清掉
    'try {',
    '  const _origCE = document.createElement.bind(document);',
    '  document.createElement = function(tag) {',
    '    const el = _origCE(tag);',
    '    if (tag.toLowerCase() === "iframe") {',
    '      el.addEventListener("load", function() {',
    '        try { Object.defineProperty(el.contentWindow.navigator, "webdriver", { get: () => undefined }); } catch {}',
    '      });',
    '    }',
    '    return el;',
    '  };',
    '} catch {}',
  ].join('\n');
}

function getDefaultInitScript() {
  return [
    // 基础反检测
    "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });",
    "try { Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.' }); } catch {}",
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Object; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Function; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Proxy; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Map; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Set; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Error; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Object; } catch {}',
    'try { delete document.$cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch {}',

    // hCaptcha 特别在意的
    'try {',
    '  const _origPQ = navigator.permissions.query.bind(navigator.permissions);',
    '  navigator.permissions.query = function(d) {',
    '    if (d && d.name === "notifications") return Promise.resolve({ state: "prompt", onchange: null });',
    '    return _origPQ(d);',
    '  };',
    '} catch {}',

    'try {',
    '  if (window.chrome && !window.chrome.debugger) {',
    '    window.chrome.debugger = {',
    '      sendCommand: function() { return Promise.resolve({}); },',
    '      attach: function() { return Promise.resolve(); },',
    '      detach: function() { return Promise.resolve(); },',
    '      onEvent: { addListener: function(){}, removeListener: function(){} },',
    '      onDetach: { addListener: function(){}, removeListener: function(){} },',
    '    };',
    '  }',
    '} catch {}',

    // iframe 内容页
    'try {',
    '  const _origCE = document.createElement.bind(document);',
    '  document.createElement = function(tag) {',
    '    const el = _origCE(tag);',
    '    if (tag.toLowerCase() === "iframe") {',
    '      el.addEventListener("load", function() {',
    '        try { Object.defineProperty(el.contentWindow.navigator, "webdriver", { get: () => undefined }); } catch {}',
    '      });',
    '    }',
    '    return el;',
    '  };',
    '} catch {}',

    // 清理标题里的 automation 痕迹
    'try { document.title = document.title.replace(/[—-]\\s*Chrome.*Automation.*$/i, ""); } catch {}',
  ].join('\n');
}

// ========== 列出所有指纹 ==========

function listAllFingerprints() {
  const dir = getFingerprintDir();
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter(f => f.endsWith('.json'))
    .map(f => {
      try {
        const fp = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
        return { agentName: fp.agentName, chromeVersion: fp.chromeVersion, discordBuildNumber: fp.discordBuildNumber, platform: fp.platform, viewport: fp.viewport };
      } catch { return null; }
    })
    .filter(Boolean);
}

// ========== 导出 ==========

module.exports = {
  getOrCreateFingerprint, loadFingerprint, saveFingerprint, generateFingerprint,
  buildInitScript, getDefaultInitScript, getSystemChromeVersion, fetchDiscordBuildNumber,
  listAllFingerprints, getFingerprintDir, pickTzLocalePair,
  detectProxyGeo, matchPairToGeo, COUNTRY_TZ_HINTS, TZ_LOCALE_PAIRS,
};
