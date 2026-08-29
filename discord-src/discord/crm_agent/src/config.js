const fs = require('fs');
const path = require('path');

const CONFIG_PATH = path.join(__dirname, '..', 'config.json');

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    console.error(`[配置] 未找到 config.json，请先复制 config.example.json 到 config.json 并填写正确的值`);
    process.exit(1);
  }
  const cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  // 校验必填
  if (!cfg.serverUrl || !cfg.token) {
    console.error('[配置] serverUrl 和 token 必填');
    process.exit(1);
  }
  return cfg;
}

module.exports = { loadConfig, CONFIG_PATH };
