const fs = require('fs');
const path = require('path');

const CONFIG_PATH = path.join(__dirname, '..', 'config.json');

function cleanUrl(raw) {
  if (!raw) return raw;
  // 1. 去掉前后空白
  let url = String(raw).trim();
  // 2. 去掉包裹的反引号 `http://...`（Markdown 粘贴常见问题）
  url = url.replace(/^`+|`+$/g, '');
  // 3. 去掉包裹的单引号 / 双引号（用户手滑加的）
  url = url.replace(/^['"]+|['"]+$/g, '');
  // 4. 再 trim 一次
  url = url.trim();
  return url;
}

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    console.error(`[配置] 未找到 config.json，请先复制 config.example.json 到 config.json 并填写正确的值`);
    process.exit(1);
  }
  const cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));

  // 统一清理 serverUrl（防用户粘贴 markdown 反引号等）
  if (cfg.serverUrl) {
    const original = cfg.serverUrl;
    cfg.serverUrl = cleanUrl(cfg.serverUrl);
    if (cfg.serverUrl !== original) {
      console.warn(`[配置] serverUrl 已自动修正: "${original}" → "${cfg.serverUrl}"`);
    }
  }
  // token 也清理一下（防粘贴带空白/引号）
  if (cfg.token) {
    cfg.token = String(cfg.token).trim().replace(/^['"]+|['"]+$/g, '');
  }

  // 校验必填
  if (!cfg.serverUrl || !cfg.token) {
    console.error('[配置] serverUrl 和 token 必填');
    process.exit(1);
  }

  // 校验 URL 合法性（简单检查）
  if (!/^https?:\/\//i.test(cfg.serverUrl)) {
    console.error(`[配置] serverUrl 必须以 http:// 或 https:// 开头，当前值: "${cfg.serverUrl}"`);
    process.exit(1);
  }

  return cfg;
}

module.exports = { loadConfig, CONFIG_PATH };
