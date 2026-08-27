#!/usr/bin/env bash
#
# MuMu Agent 发布脚本
# 用法:
#   ./release.sh patch      # 默认，2.13.1 → 2.13.2
#   ./release.sh minor      # 2.13.x → 2.14.0
#   ./release.sh major      # 2.x.x  → 3.0.0
#   ./release.sh 2.15.0     # 指定具体版本号
#   ./release.sh --show     # 只显示当前版本，不递增

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MODE="${1:-patch}"

show() {
    python3 -c "import json; print(json.load(open('config.json'))['version'])"
}

if [[ "$MODE" == "--show" ]]; then
    echo "当前版本: $(show)"
    exit 0
fi

python3 << PYEOF
import re, json, sys, os, shutil

mode = "$MODE"

# 1. 读当前版本（权威源: config.json）
with open('config.json') as f:
    cfg = json.load(f)
cur = cfg['version']

m = re.match(r'^v?(\d+)\.(\d+)\.(\d+)$', cur)
if not m:
    print(f"❌ 版本格式错误: {cur}")
    sys.exit(1)

major, minor, patch = int(m[1]), int(m[2]), int(m[3])

if mode == 'patch':
    patch += 1
elif mode == 'minor':
    minor += 1
    patch = 0
elif mode == 'major':
    major += 1
    minor = 0
    patch = 0
else:
    # 直接指定版本号
    m2 = re.match(r'^v?(\d+)\.(\d+)\.(\d+)$', mode)
    if m2:
        major, minor, patch = int(m2[1]), int(m2[2]), int(m2[3])
    else:
        print(f"❌ 无效参数: {mode}  (应该是 patch|minor|major|或具体版本如 2.15.0)")
        sys.exit(1)

next_v = f"v{major}.{minor}.{patch}"

if next_v == cur:
    print(f"⚠️  版本没变: {cur}")
    sys.exit(0)

print(f"当前: {cur}  →  新版本: {next_v}")

# 2. 更新 config.json (权威源)
cfg['version'] = next_v
with open('config.json', 'w') as f:
    json.dump(cfg, f, indent=2, ensure_ascii=False)
    f.write('\n')
print(f"✅ config.json  → {next_v}")

# 3. 同步 package.json
with open('package.json') as f:
    pkg = json.load(f)
pkg['version'] = next_v
with open('package.json', 'w') as f:
    json.dump(pkg, f, indent=2, ensure_ascii=False)
    f.write('\n')
print(f"✅ package.json → {next_v}")

# 4. 打包 tar.gz 到 server-admin/target
proj_root = os.path.dirname(os.getcwd())
target_dir = os.path.join(proj_root, 'server-admin', 'target')
os.makedirs(target_dir, exist_ok=True)

tar_name = f"mumu-agent-{next_v}.tar.gz"
tar_path = os.path.join(target_dir, tar_name)
if os.path.exists(tar_path):
    os.remove(tar_path)

import subprocess
files = ['agent.js', 'config.json', 'package.json', 'start_win.bat', 'start_mac.command']
cmd = ['tar', 'czf', tar_path] + files
r = subprocess.run(cmd, cwd=os.getcwd(), capture_output=True, text=True)
if r.returncode != 0:
    print(f"❌ 打包失败: {r.stderr}")
    sys.exit(1)

size = os.path.getsize(tar_path)
print(f"✅ 打包: {tar_path}  ({size/1024:.0f}KB)")
print(f"\n🎉 MuMu Agent {next_v} 发布完成")
PYEOF
