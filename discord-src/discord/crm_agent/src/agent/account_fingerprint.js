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
const LOCALES = ['zh-CN', 'zh-CN', 'en-US', 'ja-JP', 'zh-TW', 'ko-KR', 'en-GB'];
const TIMEZONES = [
  'Asia/Shanghai', 'Asia/Shanghai', 'Asia/Tokyo', 'Asia/Hong_Kong',
  'America/New_York', 'America/Los_Angeles', 'Europe/London', 'Europe/Berlin',
  'Asia/Singapore', 'Australia/Sydney',
];
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

function generateLanguages() {
  const all = ['zh-CN', 'zh', 'en-US', 'en', 'ja'];
  const first = pickWeighted(LOCALES);
  const rest = all.filter(l => l !== first).sort(() => Math.random() - 0.5).slice(0, 2);
  return [first, ...rest].join(',');
}

function generateFingerprint(agentName) {
  const chromeVersion = getSystemChromeVersion();
  const platform = pickWeighted(PLATFORMS);
  const isMac = platform === 'MacIntel';
  const viewport = pick(VIEWPORTS);
  
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
    
    locale: pickWeighted(LOCALES),
    timezone: pickWeighted(TIMEZONES),
    languages: generateLanguages(),
    
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

function getOrCreateFingerprint(agentName) {
  let fp = loadFingerprint(agentName);
  if (!fp) {
    fp = generateFingerprint(agentName);
    saveFingerprint(fp);
    const gpu = fp.webglRenderer.split(',')[1]?.trim() || fp.webglRenderer;
    console.log('[Fingerprint] 🆕 为 ' + agentName + ' 生成新指纹: Chrome ' + fp.chromeVersion + ', GPU=' + gpu);
  } else {
    console.log('[Fingerprint] ✅ 加载 ' + agentName + ' 指纹: Chrome ' + fp.chromeVersion + ', build=' + fp.discordBuildNumber);
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
  ].join('\n');
}

function getDefaultInitScript() {
  return [
    "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });",
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Object; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Function; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Proxy; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Map; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Set; } catch {}',
    'try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Error; } catch {}',
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
  listAllFingerprints, getFingerprintDir,
};
