const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
const { execSync, execFileSync, spawn, spawnSync } = require('child_process');
const NL = String.fromCharCode(10);

function getTimestamp() {
    const now = new Date();
    const pad = (n, w=2) => String(n).padStart(w, '0');
    return `${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}.${String(now.getMilliseconds()).padStart(3, '0')}`;
}

// ========== 配置加载 ==========
const CONFIG_DIR = __dirname;
let config = {};

function loadConfig() {
    const platform = process.platform;
    const platformMap = {
        'darwin': 'mac',
        'win32': 'win',
        'linux': 'linux'
    };
    const platformName = platformMap[platform] || platform;
    
    const configPath = path.join(CONFIG_DIR, 'config.json');
    
    try {
        if (!fs.existsSync(configPath)) {
            console.error('[Agent] 未找到 config.json');
            config = {
                userId: 'merchantadmin',
                merchantId: 1,
                serverUrl: 'ws://localhost:9090/ws/agent',
                heartbeatInterval: 30000,
                autoStart: true
            };
            return true;
        }
        
        const rawConfig = JSON.parse(fs.readFileSync(configPath, 'utf8'));
        
        // 检查是否有 platforms 字段（新格式）
        if (rawConfig.platforms && rawConfig.platforms[platform]) {
            // 新格式：从 platforms 中获取当前平台的配置
            const platformConfig = rawConfig.platforms[platform];
            console.log(`[Agent] 找到平台配置: ${platform} -> mumuPath=${platformConfig.mumuPath || '未设置'}`);
            config = {
                userId: rawConfig.userId,
                merchantId: rawConfig.merchantId,
                serverUrl: rawConfig.serverUrl,
                heartbeatInterval: rawConfig.heartbeatInterval || 30000,
                autoStart: rawConfig.autoStart || true,
                mumuPath: platformConfig.mumuPath || rawConfig.mumuPath || '',
                adbPath: platformConfig.adbPath || rawConfig.adbPath || ''
            };
            console.log(`[Agent] 加载配置 (新格式, ${platformName}平台):`, JSON.stringify(config, null, 2));
        } else {
            // 旧格式：直接使用顶层配置
            if (rawConfig.platforms) {
                console.warn(`[Agent] platforms 存在但未找到当前平台 ${platform} 的配置`);
                console.log(`[Agent] 可用的平台: ${Object.keys(rawConfig.platforms).join(', ')}`);
            }
            config = rawConfig;
            console.log(`[Agent] 加载配置 (旧格式):`, JSON.stringify(config, null, 2));
        }
        
        return true;
    } catch (e) {
        console.error('[Agent] config.json 解析失败:', e.message);
        config = {
            userId: 'merchantadmin',
            merchantId: 1,
            serverUrl: 'ws://localhost:9090/ws/agent',
            heartbeatInterval: 30000,
            autoStart: true
        };
        return true;
    }
}

// ========== Device ID 管理 ==========
const DEVICE_ID_PATH = path.join(__dirname, '.device_id');

function getOrCreateDeviceId() {
    try {
        if (fs.existsSync(DEVICE_ID_PATH)) {
            return fs.readFileSync(DEVICE_ID_PATH, 'utf8').trim();
        }
        const newId = uuidv4();
        fs.writeFileSync(DEVICE_ID_PATH, newId);
        console.log('[Agent] 生成新的 Device ID:', newId);
        return newId;
    } catch (e) {
        console.error('[Agent] Device ID 处理失败:', e.message);
        return 'unknown-device';
    }
}

// ========== MuMu 模拟器控制 ==========
class MuMuController {
    constructor() {
        this.mumuPath = null;
        this.mumuAppPath = null;
        this.adbPath = null;

        if (config.mumuPath) {
            const p = config.mumuPath;
            if (!fs.existsSync(p)) {
                console.warn('[MuMu] WARN: mumuPath 不存在，将尝试自动发现:', p);
            } else {
                try {
                    const stat = fs.statSync(p);
                    if (stat.isDirectory()) {
                        if (p.endsWith('.app')) {
                            const appName = path.basename(p, '.app');
                            const macOsDir = path.join(p, 'Contents', 'MacOS');
                            if (fs.existsSync(macOsDir) && fs.statSync(macOsDir).isDirectory()) {
                                const files = fs.readdirSync(macOsDir);
                                for (const candidate of [appName, 'mumutool', 'mumu-cli']) {
                                    const cp = path.join(macOsDir, candidate);
                                    if (fs.existsSync(cp) && fs.statSync(cp).isFile()) {
                                        console.log('[MuMu] 检测到 macOS .app bundle, 解析可执行文件:', cp);
                                        this.mumuPath = cp;
                                        break;
                                    }
                                }
                                if (!this.mumuPath) {
                                    for (const file of files) {
                                        const fp = path.join(macOsDir, file);
                                        try {
                                            if (fs.statSync(fp).isFile() && file.toLowerCase().includes('mumu')) {
                                                this.mumuPath = fp;
                                                break;
                                            }
                                        } catch (_) {}
                                    }
                                }
                            }
                        } else {
                            console.warn('[MuMu] WARN: mumuPath 是目录而非文件，将从中扫描 MuMuManager:', p);
                            this.mumuPath = p;
                        }
                    } else {
                        this.mumuPath = p;
                    }
                } catch (e) {
                    console.warn('[MuMu] WARN: 无法访问 mumuPath，将尝试自动发现:', e.message);
                }
            }
            this.mumuAppPath = config.mumuPath;
        } else {
            console.warn('[MuMu] WARN: 未配置 mumuPath，将尝试自动发现');
        }

        if (this.mumuPath) {
            console.log('[MuMu] 使用配置中的 mumuPath:', this.mumuPath);
        }

        if (config.adbPath && fs.existsSync(config.adbPath)) {
            this.adbPath = config.adbPath;
            console.log('[MuMu] 使用配置中的 adbPath:', config.adbPath);
        } else {
            if (config.adbPath) {
                console.warn('[MuMu] WARN: adbPath 不存在，将尝试自动发现:', config.adbPath);
            } else {
                console.warn('[MuMu] WARN: 未配置 adbPath，将尝试自动发现');
            }
        }

        this.mumutoolPath = this.findMumutoolPath();
        this.mumuManagerPath = this.findMumuManagerPath();

        if (!this.adbPath) {
            const autoAdb = this.findAdbPath();
            if (autoAdb) {
                this.adbPath = autoAdb;
                console.log('[MuMu] 自动发现 adbPath:', autoAdb);
            } else {
                console.warn('[MuMu] 错误: 无法自动发现 adb，ADB 相关功能将不可用');
            }
        }

        this.vmsBasePath = this.findVmsBasePath();
        console.log('[MuMu] ADB 路径:', this.adbPath || '未找到');
        console.log('[MuMu] MuMu 路径:', this.mumuPath || '未找到');
        console.log('[MuMu] mumutool 路径:', this.mumutoolPath || '未找到');
        console.log('[MuMu] MuMuManager 路径:', this.mumuManagerPath || '未找到');

        if (!this.mumuManagerPath && !this.mumutoolPath) {
            console.error('[MuMu] 错误: 未找到 MuMuManager 或 mumutool，请检查 MuMu 是否正确安装');
            console.error('[MuMu] 建议手动配置 config.json 的 mumuPath 指向 MuMu 安装根目录');
        }

        this.diagnoseInstallation();
    }

    
    diagnoseInstallation() {
        const os = process.platform;
        console.log('[MuMu] ====== 诊断 MuMu 安装 ======');
        console.log('[MuMu] 操作系统:', os);
        console.log('[MuMu] mumuPath:', this.mumuPath);
        console.log('[MuMu] adbPath:', this.adbPath);
        console.log('[MuMu] mumutoolPath:', this.mumutoolPath || '未找到');
        
        // 扫描 mumuPath 目录
        if (this.mumuPath && fs.existsSync(this.mumuPath)) {
            try {
                let scanDir = this.mumuPath;
                if (fs.statSync(scanDir).isFile()) {
                    scanDir = path.dirname(scanDir);
                }
                console.log(`[MuMu] 扫描目录: ${scanDir}`);
                
                const scanFiles = (dir, depth = 0) => {
                    if (depth > 2) return;
                    try {
                        const files = fs.readdirSync(dir);
                        console.log(`[MuMu] 目录 ${path.basename(dir)}: ${files.length} 个文件`);
                        files.slice(0, 20).forEach(f => {
                            const fullPath = path.join(dir, f);
                            try {
                                const stat = fs.statSync(fullPath);
                                const prefix = stat.isDirectory() ? '[DIR]' : '[FILE]';
                                // 高亮显示关键工具
                                const lowerF = f.toLowerCase();
                                const highlight = (lowerF.includes('mumu') || lowerF.includes('adb') || lowerF.includes('tool')) ? ' <<<' : '';
                                console.log(`[MuMu]   ${prefix} ${f}${highlight}`);
                            } catch (e) {}
                        });
                        // 递归扫描子目录
                        for (const f of files) {
                            const fullPath = path.join(dir, f);
                            try {
                                if (fs.statSync(fullPath).isDirectory() && (f === 'shell' || f === 'bin' || depth === 0)) {
                                    scanFiles(fullPath, depth + 1);
                                }
                            } catch (e) {}
                        }
                    } catch (e) {
                        console.log(`[MuMu] 扫描目录失败: ${e.message}`);
                    }
                };
                scanFiles(scanDir);
            } catch (e) {
                console.log(`[MuMu] 诊断失败: ${e.message}`);
            }
        } else {
            console.log('[MuMu] mumuPath 不存在或未设置');
        }
        console.log('[MuMu] ====== 诊断结束 ======');
    }

    findAdbPath() {
        const os = process.platform;
        const mumuPath = config.mumuPath || '';

        // 0. 先尝试从 MuMu 安装目录查找 adb
        if (mumuPath) {
            try {
                let mumuBaseDir = mumuPath;
                if (fs.existsSync(mumuPath) && fs.statSync(mumuPath).isFile()) {
                    mumuBaseDir = path.dirname(mumuPath);
                }
                // 检查 MuMu 目录下是否有 adb.exe
                const adbInMumu = path.join(mumuBaseDir, os === 'win32' ? 'adb.exe' : 'adb');
                if (fs.existsSync(adbInMumu)) {
                    console.log(`[MuMu] 在 MuMu 目录找到 ADB: ${adbInMumu}`);
                    return adbInMumu;
                }
                // 检查 shell 子目录
                const adbInShell = path.join(mumuBaseDir, 'shell', os === 'win32' ? 'adb.exe' : 'adb');
                if (fs.existsSync(adbInShell)) {
                    console.log(`[MuMu] 在 MuMu/shell 目录找到 ADB: ${adbInShell}`);
                    return adbInShell;
                }
            } catch (e) {}
        }

        // 1. 首先尝试从 PATH 查找
        try {
            let cmd;
            if (os === 'win32') {
                // Windows 使用 where 命令
                cmd = 'where adb 2>nul';
            } else {
                // macOS/Linux 使用 which 或 command -v
                cmd = 'which adb 2>/dev/null || command -v adb 2>/dev/null';
            }
            const result = execSync(cmd, { encoding: 'utf8', shell: true, timeout: 5000 }).trim();
            if (result) {
                const firstLine = result.split(NL)[0].trim();
                if (firstLine && fs.existsSync(firstLine)) return firstLine;
            }
        } catch {}

        // 2. 从 ANDROID_HOME 环境变量查找
        const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
        if (androidHome) {
            const adbName = os === 'win32' ? 'adb.exe' : 'adb';
            const p = path.join(androidHome, 'platform-tools', adbName);
            if (fs.existsSync(p)) return p;
        }

        // 3. 从 MuMu 安装目录查找 (MuMu 自带 adb)
        if (this.mumuPath) {
            const mumuAdbName = os === 'win32' ? 'adb.exe' : 'adb';
            const mumuAdbPaths = [
                path.join(this.mumuPath, 'shell', mumuAdbName),
                path.join(this.mumuPath, mumuAdbName),
            ];
            for (const p of mumuAdbPaths) {
                if (fs.existsSync(p)) return p;
            }
        }

        // 4. 常见路径搜索
        const commonPaths = [];
        if (os === 'win32') {
            // Windows 常见路径
            const winPaths = [
                process.env.LOCALAPPDATA + '\\Android\\Sdk\\platform-tools\\adb.exe',
                process.env.ANDROID_HOME + '\\platform-tools\\adb.exe',
                process.env.USERPROFILE + '\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe',
                'C:\\Android\\platform-tools\\adb.exe',
                'C:\\Program Files\\Android\\Android Studio\\plugins\\\\..\\..\\..\\..\\Sdk\\platform-tools\\adb.exe',
                'C:\\Users\\' + (process.env.USERNAME || '') + '\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe',
            ];
            commonPaths.push(...winPaths);
        } else {
            // macOS/Linux 常见路径
            commonPaths.push(
                '/Users/' + (process.env.USER || '') + '/Library/Android/sdk/platform-tools/adb',
                '/usr/local/bin/adb',
                '/opt/homebrew/bin/adb',
                '/usr/lib/android-sdk/platform-tools/adb',
                '/opt/android-sdk/platform-tools/adb'
            );
        }

        for (const p of commonPaths) {
            if (p && !p.includes('undefined') && !p.includes('null') && fs.existsSync(p)) return p;
        }

        console.warn('[MuMu] ADB 未找到，请确保已安装 Android SDK 并配置 ANDROID_HOME 环境变量');
        return null;
    }

    findMumutoolPath() {
        const os = process.platform;
        const candidates = [];
        
        let mumuBasePath = this.mumuPath;
        if (mumuBasePath && fs.existsSync(mumuBasePath) && fs.statSync(mumuBasePath).isFile()) {
            mumuBasePath = path.dirname(mumuBasePath);
        }
        
        if (os === 'darwin') {
            const mumuBase = this.mumuAppPath || this.mumuPath;
            candidates.push(`${mumuBase}/Contents/MacOS/mumutool`);
            candidates.push(`${mumuBase}/Contents/MacOS/mumu-cli`);
        } else if (os === 'win32') {
            if (mumuBasePath) {
                const dirsToScan = [
                    mumuBasePath,
                    path.join(mumuBasePath, 'shell'),
                    path.join(mumuBasePath, 'nx_main'),
                    path.join(mumuBasePath, 'Tools'),
                    path.join(mumuBasePath, 'bin'),
                    path.dirname(mumuBasePath),
                ];
                
                for (const dir of dirsToScan) {
                    if (dir && fs.existsSync(dir) && fs.statSync(dir).isDirectory()) {
                        try {
                            const files = fs.readdirSync(dir);
                            for (const file of files) {
                                const lowerFile = file.toLowerCase();
                                if (lowerFile === 'mumu-cli.exe' || lowerFile === 'mumutool.exe') {
                                    const fullPath = path.join(dir, file);
                                    candidates.push(fullPath);
                                    console.log(`[MuMu] 扫描到 MuMu 工具: ${fullPath}`);
                                }
                            }
                        } catch (e) {
                        }
                    }
                }
            }
            
            const commonPaths = [
                'C:\\Program Files\\Netease\\MuMu\\nx_main\\mumu-cli.exe',
                'C:\\Program Files\\Netease\\MuMu\\shell\\mumu-cli.exe',
                'C:\\Program Files\\Netease\\MuMuPlayer-12.0\\nx_main\\mumu-cli.exe',
                'C:\\Program Files\\Netease\\MuMuPlayer-12.0\\shell\\mumu-cli.exe',
                'C:\\Program Files\\Netease\\MuMu\\mumu-cli.exe',
                'C:\\Program Files (x86)\\Netease\\MuMu\\nx_main\\mumu-cli.exe',
                'C:\\Program Files (x86)\\Netease\\MuMuPlayer-12.0\\nx_main\\mumu-cli.exe',
            ];
            candidates.push(...commonPaths);
        }
        
        const uniqueCandidates = [...new Set(candidates.filter(c => c && c.length > 0))];
        
        for (const p of uniqueCandidates) {
            try {
                if (p && fs.existsSync(p)) {
                    if (fs.statSync(p).isFile()) {
                        console.log(`[MuMu] 找到 MuMu 工具: ${p}`);
                        return p;
                    } else if (fs.statSync(p).isDirectory()) {
                        try {
                            const files = fs.readdirSync(p);
                            const tool = files.find(f => {
                                const lf = f.toLowerCase();
                                return lf === 'mumu-cli.exe' || lf === 'mumutool.exe' || lf === 'mumu-cli' || lf === 'mumutool';
                            });
                            if (tool) {
                                const fullPath = path.join(p, tool);
                                console.log(`[MuMu] 在目录中找到 MuMu 工具: ${fullPath}`);
                                return fullPath;
                            }
                        } catch (e) {}
                    }
                }
            } catch (e) {}
        }
        
        console.warn(`[MuMu] 未找到 mumu-cli 工具，尝试过的路径: ${uniqueCandidates.slice(0, 5).join(', ')}`);
        return null;
    }

    
    findMumuManagerPath() {
        if (process.platform !== 'win32') return null;
        const candidates = [];
        let base = this.mumuPath || '';
        if (base && fs.existsSync(base) && fs.statSync(base).isFile()) {
            base = path.dirname(base);
        }
        if (base) {
            candidates.push(path.join(base, 'shell', 'MuMuManager.exe'));
            candidates.push(path.join(base, 'MuMuManager.exe'));
            if (path.basename(base).toLowerCase() === 'nx_main' || path.basename(base).toLowerCase() === 'shell') {
                candidates.push(path.join(path.dirname(base), 'shell', 'MuMuManager.exe'));
            }
        }
        candidates.push('C:\\Program Files\\Netease\\MuMuPlayer-12.0\\shell\\MuMuManager.exe');
        candidates.push('C:\\Program Files\\Netease\\MuMuPlayerGlobal-12.0\\shell\\MuMuManager.exe');
        candidates.push('C:\\Program Files (x86)\\Netease\\MuMuPlayer-12.0\\shell\\MuMuManager.exe');
        for (const p of candidates) {
            try { if (p && fs.existsSync(p)) { console.log('[MuMu] 找到 MuMuManager: ' + p); return p; } } catch (e) {}
        }
        return null;
    }

    execMumuManager(args) {
        const result = spawnSync(this.mumuManagerPath, args, { timeout: 20000, encoding: 'utf8', cwd: path.dirname(this.mumuManagerPath) });
        const exitCode = result.status;
        const stdout = (result.stdout || '').trim();
        const stderr = (result.stderr || '').trim();
        let json = null;
        if (stdout) {
            try { json = JSON.parse(stdout); }
            catch (e) {
                // 逐行/提取 {} 块解析
                const matches = stdout.match(/\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}/g);
                if (matches) {
                    for (const m of matches) {
                        try { const obj = JSON.parse(m); if (obj && typeof obj === 'object') { json = json || (Array.isArray(obj) ? obj : [obj]); if (!Array.isArray(json)) json = [json, obj]; else json.push(obj); } } catch (e2) {}
                    }
                }
            }
        }
        return { exitCode, stdout, stderr, json };
    }

    findVmsBasePath() {
        const os = process.platform;
        const candidates = [];
        
        // 从 mumuPath 推断 vms 目录
        let mumuDir = this.mumuPath;
        if (mumuDir) {
            try {
                if (fs.existsSync(mumuDir) && fs.statSync(mumuDir).isFile()) {
                    mumuDir = path.dirname(mumuDir);
                }
                // MuMu 通常在安装目录下有 vms 子目录
                candidates.push(path.join(mumuDir, 'vms'));
                candidates.push(mumuDir);
            } catch (e) {
                console.warn('[MuMu] 无法从 mumuPath 推断 vms 目录: ' + e.message);
            }
        }
        
        // macOS 常见路径
        if (os === 'darwin') {
            candidates.push(path.join(this.mumuAppPath || '/Applications/MuMuPlayer.app', 'Contents', 'Resources', 'vms'));
            // 用户数据目录
            const home = require('os').homedir();
            candidates.push(path.join(home, 'Library', 'Containers', 'com.netease.mumu.nemux', 'Data', 'vms'));
            candidates.push(path.join(home, 'Library', 'Application Support', 'MuMu', 'vms'));
        } else if (os === 'win32') {
            // Windows 常见路径
            candidates.push('C:\\Users\\' + require('os').userInfo().username + '\\Documents\\MuMu\\vms');
            candidates.push('C:\\Users\\' + require('os').userInfo().username + '\\AppData\\Local\\MuMu\\vms');
        }
        
        // 检查每个候选路径
        for (const candidate of candidates) {
            try {
                if (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory()) {
                    console.log('[MuMu] 找到 vms 目录: ' + candidate);
                    return candidate;
                }
            } catch (e) {}
        }
        
        console.warn('[MuMu] 未找到 vms 目录，将使用 mumuPath 所在目录');
        return mumuDir || '';
    }

    findMuMuPath() {
        const os = process.platform;
        if (os === 'darwin') {
            const paths = [
                '/Applications/MuMuPlayer.app',
                '/Applications/MuMu Player.app'
            ];
            for (const p of paths) {
                if (fs.existsSync(p)) return p;
            }
            return '/Applications/MuMuPlayer.app';
        } else if (os === 'win32') {
            // 搜索 MuMu 安装路径
            const searchPaths = [
                'C:\\Program Files\\Netease\\MuMuPlayer-12.0',
                'C:\\Program Files (x86)\\Netease\\MuMuPlayer-12.0',
                'D:\\Program Files\\Netease\\MuMuPlayer-12.0',
                process.env.MUMU_PATH,
                'C:\\MuMuPlayer',
                'D:\\MuMuPlayer'
            ];
            for (const p of searchPaths) {
                if (p && fs.existsSync(p)) {
                    console.log('[MuMu] 找到 MuMu 安装路径:', p);
                    return p;
                }
            }
            return 'C:\\Program Files\\Netease\\MuMuPlayer-12.0';
        }
        return '/opt/MuMuPlayer';
    }

    async execAdb(args, timeout = 10000) {
        if (!this.adbPath) {
            throw new Error('ADB 未找到');
        }
        try {
            const result = execFileSync(this.adbPath, args, { timeout, encoding: 'utf8', shell: false });
            return result.trim();
        } catch (e) {
            throw new Error(`ADB 执行失败: ${e.message}`);
        }
    }

    async execMumutool(args, timeout = 30000) {
        if (!this.mumutoolPath) {
            throw new Error('mumu-cli 未找到');
        }
        const command = path.basename(this.mumutoolPath);
        console.log(`[MuMu] 执行 ${command} ${args.join(' ')}`);
        
        const toolPath = this.mumutoolPath;
        
        if (!fs.existsSync(toolPath)) {
            throw new Error(`mumu-cli 不存在: ${toolPath}`);
        }
        
        try {
            const result = spawnSync(toolPath, args, { 
                timeout: timeout,
                cwd: path.dirname(toolPath),
                encoding: 'utf8',
                maxBuffer: 1024 * 1024 * 10
            });
            
            if (result.error) {
                throw result.error;
            }
            
            const output = (result.stdout || '').trim();
            const errOutput = (result.stderr || '').trim();
            
            console.log(`[MuMu] ${command} stdout: ${output.substring(0, 200)}`);
            if (errOutput) {
                console.log(`[MuMu] ${command} stderr: ${errOutput.substring(0, 200)}`);
            }
            
            const toParse = output || errOutput;
            try {
                return JSON.parse(toParse);
            } catch {
                const jsonMatch = toParse.match(/\{[\s\S]*\}/);
                if (jsonMatch) {
                    try {
                        return JSON.parse(jsonMatch[0]);
                    } catch {}
                }
                return { errcode: result.status === 0 ? 0 : -1, message: toParse, return: null };
            }
        } catch (e) {
            console.error(`[MuMu] ${command} 执行错误: ${e.message}`);
            
            const errMsg = e.message || '';
            try {
                const jsonMatch = errMsg.match(/\{[\s\S]*\}/);
                if (jsonMatch) {
                    return JSON.parse(jsonMatch[0]);
                }
            } catch {}
            
            return { errcode: -1, message: errMsg };
        }
    }

    async connectAdb(port) {
        if (!this.adbPath) return false;
        try {
            execFileSync(this.adbPath, ['connect', `127.0.0.1:${port}`], { timeout: 5000, shell: false });
            return true;
        } catch {
            return false;
        }
    }

    extractEmulatorList(result) {
        if (!result || typeof result !== 'object') return null;

        if (result.return && Array.isArray(result.return.results)) {
            console.log('[MuMu] 匹配格式: result.return.results (array)');
            return result.return.results;
        }

        if (Array.isArray(result.return)) {
            console.log('[MuMu] 匹配格式: result.return (array)');
            return result.return;
        }

        if (result.data && Array.isArray(result.data.results)) {
            console.log('[MuMu] 匹配格式: result.data.results (array)');
            return result.data.results;
        }

        if (Array.isArray(result.data)) {
            console.log('[MuMu] 匹配格式: result.data (array)');
            return result.data;
        }

        if (result.data && Array.isArray(result.data.return)) {
            console.log('[MuMu] 匹配格式: result.data.return (array)');
            return result.data.return;
        }

        if (result.return && typeof result.return === 'object' && !Array.isArray(result.return) && result.return.index !== undefined) {
            console.log('[MuMu] 匹配格式: result.return (single object with index)');
            return [result.return];
        }

        if (result.data && typeof result.data === 'object' && !Array.isArray(result.data) && result.data.index !== undefined) {
            console.log('[MuMu] 匹配格式: result.data (single object with index)');
            return [result.data];
        }

        for (const key of Object.keys(result)) {
            const val = result[key];
            if (val && typeof val === 'object') {
                if (Array.isArray(val)) {
                    if (val.length > 0 && val[0] && (val[0].index !== undefined || val[0].adb_port !== undefined || val[0].state !== undefined)) {
                        console.log('[MuMu] 匹配格式: 递归搜索 result.' + key + ' (array)');
                        return val;
                    }
                } else {
                    for (const subKey of Object.keys(val)) {
                        const subVal = val[subKey];
                        if (Array.isArray(subVal) && subVal.length > 0 && subVal[0] && (subVal[0].index !== undefined || subVal[0].adb_port !== undefined || subVal[0].state !== undefined)) {
                            console.log('[MuMu] 匹配格式: 递归搜索 result.' + key + '.' + subKey + ' (array)');
                            return subVal;
                        }
                    }
                }
            }
        }

        console.warn('[MuMu] 未能匹配任何已知格式');
        return null;
    }

    buildEmulatorFromVmConfig(index, vmConfig) {
        let status = 'STOPPED';
        let cpuCount = 1;
        let memoryMB = 1024;
        let name = `V${String(index + 1).padStart(3, '0')}`;
        let adbPort = 16384 + index * 32;

        if (vmConfig) {
            const isRunning = this.isProcessRunning(index);
            status = isRunning ? 'RUNNING' : 'STOPPED';

            if (vmConfig.vmCpuCount && vmConfig.vmCpuCount > 0) {
                cpuCount = vmConfig.vmCpuCount;
            }
            if (vmConfig.vmMemoryOfMB && vmConfig.vmMemoryOfMB > 0) {
                memoryMB = vmConfig.vmMemoryOfMB;
            }
            if (vmConfig.vmName) {
                name = vmConfig.vmName;
            }
            if (vmConfig.adb_port && vmConfig.adb_port > 0) {
                adbPort = vmConfig.adb_port;
            }

            if (status === 'RUNNING') {
                try {
                    this.connectAdb(adbPort);
                } catch (e) {}
            }
        }

        return {
            index,
            adbPort,
            status,
            name,
            cpuCount,
            memoryMB
        };
    }

    isProcessRunning(index) {
        const os = process.platform;
        try {
            if (os === 'win32') {
                const result = execSync(
                    'tasklist /FI "IMAGENAME eq MuMuNxMain.exe" /NH 2>nul || echo NOT_FOUND',
                    { encoding: 'utf8', shell: true, timeout: 2000 }
                );
                if (result.trim() === 'NOT_FOUND' || result.includes('INFO: No tasks')) {
                    return false;
                }
                return true;
            } else if (os === 'darwin') {
                try {
                    execSync('pgrep -x "MuMuPlayer"', { stdio: 'ignore' });
                    return true;
                } catch (e) {
                    return false;
                }
            }
        } catch (e) {
            return false;
        }
        return false;
    }

    scanVmJsonFiles() {
        const indices = [];
        const os = process.platform;
        
        let vmsDirs = [];
        
        if (os === 'darwin') {
            const homeDir = process.env.HOME || require('os').homedir();
            vmsDirs = [
                path.join(homeDir, 'Library', 'Application Support', 'com.netease.mumu.nemux', 'vms'),
                path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer-12.0', 'vms'),
            ];
        } else if (os === 'win32') {
            const userProfile = process.env.USERPROFILE || '';
            const homeDir = require('os').homedir();
            const appData = process.env.APPDATA || '';
            const localAppData = process.env.LOCALAPPDATA || '';
            
            vmsDirs = [];
            
            if (userProfile) {
                vmsDirs.push(path.join(userProfile, 'Documents', 'MuMuPlayer', 'vms'));
                vmsDirs.push(path.join(userProfile, 'Documents', 'MuMuPlayer-12.0', 'vms'));
                vmsDirs.push(path.join(userProfile, 'Documents', 'MuMu', 'vms'));
                vmsDirs.push(path.join(userProfile, 'Documents', 'Netease', 'MuMuPlayer', 'vms'));
                vmsDirs.push(path.join(userProfile, 'Documents', 'Netease', 'MuMuPlayer-12.0', 'vms'));
            }
            if (homeDir) {
                vmsDirs.push(path.join(homeDir, 'Documents', 'MuMuPlayer', 'vms'));
                vmsDirs.push(path.join(homeDir, 'Documents', 'MuMuPlayer-12.0', 'vms'));
            }
            
            if (appData) {
                vmsDirs.push(path.join(appData, 'Netease', 'MuMuPlayer', 'vms'));
                vmsDirs.push(path.join(appData, 'Netease', 'MuMuPlayer-12.0', 'vms'));
            }
            if (localAppData) {
                vmsDirs.push(path.join(localAppData, 'Netease', 'MuMuPlayer', 'vms'));
                vmsDirs.push(path.join(localAppData, 'Netease', 'MuMuPlayer-12.0', 'vms'));
            }
            
            let mumuPath = this.mumuPath || '';
            if (mumuPath && fs.existsSync(mumuPath)) {
                let installDir = mumuPath;
                if (fs.statSync(mumuPath).isFile()) {
                    installDir = path.dirname(mumuPath);
                }
                if (path.basename(installDir).toLowerCase() === 'nx_main') {
                    installDir = path.dirname(installDir);
                }
                vmsDirs.push(path.join(installDir, 'vms'));
                vmsDirs.push(path.join(installDir, 'nx_main', 'vms'));
                const parentDir = path.dirname(installDir);
                if (parentDir) {
                    vmsDirs.push(path.join(parentDir, 'MuMuPlayer', 'vms'));
                    vmsDirs.push(path.join(parentDir, 'MuMuPlayer-12.0', 'vms'));
                    vmsDirs.push(path.join(parentDir, 'Netease', 'MuMuPlayer', 'vms'));
                    vmsDirs.push(path.join(parentDir, 'Netease', 'MuMuPlayer-12.0', 'vms'));
                }
            }
            
            vmsDirs = [...new Set(vmsDirs.filter(d => d && d.length > 0))];
            
            console.log(`[MuMu] 搜索 ${vmsDirs.length} 个可能的 vms 目录...`);
        }
        
        for (const vmsDir of vmsDirs) {
            if (fs.existsSync(vmsDir) && fs.statSync(vmsDir).isDirectory()) {
                const dirs = fs.readdirSync(vmsDir).filter(d => {
                    const fullPath = path.join(vmsDir, d);
                    try {
                        return fs.statSync(fullPath).isDirectory();
                    } catch (e) { return false; }
                });
                console.log(`[MuMu] 在 ${vmsDir} 找到 ${dirs.length} 个模拟器目录`);
                
                for (const dirName of dirs) {
                    let index = parseInt(dirName);
                    if (isNaN(index) && /^myvm(\d+)$/i.test(dirName)) {
                        index = parseInt(dirName.replace(/^myvm/i, ''));
                    }
                    if (!isNaN(index) && !indices.includes(index)) {
                        const vmDirPath = path.join(vmsDir, dirName);
                        
                        const possiblePaths = [
                            path.join(vmDirPath, 'vm.json'),
                            path.join(vmDirPath, 'setting', 'vm.json'),
                            path.join(vmDirPath, 'config', 'vm.json'),
                        ];
                        const hasVmJson = possiblePaths.some(p => fs.existsSync(p));
                        
                        if (hasVmJson) {
                            indices.push(index);
                            console.log(`[MuMu] 找到模拟器 ${index}: vm.json 存在`);
                        } else {
                        try {
                            const contents = fs.readdirSync(vmDirPath);
                            console.log(`[MuMu] 模拟器 ${index} 目录内容: ${contents.slice(0, 10).join(', ')}`);
                            
                            if (contents.length > 0) {
                                const hasMumuMarker = contents.some(f => 
                                    f.startsWith('vm_') || 
                                    f.endsWith('.img') || 
                                    f === 'setting' ||
                                    f === 'config' ||
                                    f === 'data' ||
                                    f.includes('data') ||
                                    f === 'cache' ||
                                    f === 'logs' ||
                                    f === 'MuMu' ||
                                    f === 'emulator' ||
                                    f.startsWith('hardware') ||
                                    f.startsWith('kernel') ||
                                    f.startsWith('system')
                                );
                                
                                if (hasMumuMarker) {
                                    indices.push(index);
                                    console.log(`[MuMu] 找到模拟器 ${index}: 目录有 ${contents.length} 个内容 (标记匹配: ${contents.filter(f => 
                                        f.startsWith('vm_') || f.endsWith('.img') || f === 'setting' ||
                                        f === 'config' || f === 'data' || f === 'cache' ||
                                        f === 'logs' || f === 'MuMu' || f === 'emulator'
                                    ).join(', ')})`);
                                } else if (contents.length >= 1) {
                                    indices.push(index);
                                    console.log(`[MuMu] 找到模拟器 ${index}: 目录有 ${contents.length} 个内容 (无特定标记，但非空)`);
                                }
                            }
                        } catch (e) {
                            console.warn(`[MuMu] 读取模拟器 ${index} 目录内容失败: ${e.message}`);
                        }
                    }
                    }
                }
                if (indices.length > 0) break;
            }
        }
        
        return indices;
    }

    async getEmulators() {
        const emulators = [];
        const os = process.platform;
        
        try {
            if (os === 'win32') {
                console.log('[MuMu] Windows平台: 获取模拟器列表');
                
                // 0. 优先使用 MuMuManager info（MuMu 12 官方工具，最可靠）
                if (this.mumuManagerPath) {
                    try {
                        console.log('[MuMu] 使用 MuMuManager info -v all 获取列表...');
                        const r = this.execMumuManager(['info', '-v', 'all']);
                        console.log('[MuMu] MuMuManager info 退出码=' + r.exitCode + ', stdout前300字符: ' + r.stdout.substring(0, 300));
                        if (r.exitCode === 0 && r.json) {
                            let items = r.json;
                            if (Array.isArray(items)) {
                                // 已经是数组
                            } else if (items && typeof items === 'object') {
                                // MuMuManager 返回 key-value 对象 {"0": {...}, "1": {...}}，展平为 values
                                items = Object.values(items);
                            } else {
                                items = [];
                            }
                            for (const item of items) {
                                if (!item || item.index === undefined) continue;
                                const idx = parseInt(item.index);
                                if (isNaN(idx)) continue;
                                const isRunning = !!(item.is_process_started || item.is_android_started);
                                emulators.push({
                                    index: idx,
                                    name: item.name || ('MuMu-' + idx),
                                    status: isRunning ? 'RUNNING' : 'STOPPED',
                                    cpuCount: 0,
                                    memoryMB: 0,
                                    adbPort: item.adb_port || 0
                                });
                            }
                            console.log('[MuMu] MuMuManager 获取到 ' + emulators.length + ' 个模拟器');
                            if (emulators.length > 0) return emulators;
                        }
                    } catch (mmErr) {
                        console.warn('[MuMu] MuMuManager info 失败: ' + mmErr.message + ', 尝试其他方式');
                    }
                }
                
                // 1. 先尝试使用 mumu-cli list 命令
                if (this.mumutoolPath) {
                    try {
                        console.log('[MuMu] 尝试 mumu-cli list 命令...');
                        const listResult = await this.execMumutool(['list']);
                        console.log(`[MuMu] list 命令返回: ${JSON.stringify(listResult).substring(0, 300)}`);
                        
                        if (listResult.errcode === 0 && listResult.return) {
                            let listData = listResult.return;
                            if (typeof listData === 'string') {
                                try { listData = JSON.parse(listData); } catch(e) {}
                            }
                            
                            // 解析返回的模拟器列表
                            if (Array.isArray(listData)) {
                                for (const item of listData) {
                                    try {
                                        let idx = -1;
                                        if (typeof item === 'object' && item.index !== undefined) {
                                            idx = typeof item.index === 'number' ? item.index : parseInt(item.index);
                                        } else {
                                            idx = parseInt(item);
                                        }
                                        
                                        if (idx >= 0 && !emulators.find(e => e.index === idx)) {
                                            const vmConfig = this.readVmConfig(idx);
                                            const emulator = this.buildEmulatorFromVmConfig(idx, vmConfig);
                                            if (emulator) {
                                                emulators.push(emulator);
                                            }
                                        }
                                    } catch (e) {
                                        console.warn(`[MuMu] 解析 list 项失败: ${e.message}`);
                                    }
                                }
                            } else if (listData && typeof listData === 'object') {
                                // 可能是 { "0": {...}, "1": {...} } 格式
                                Object.keys(listData).forEach(key => {
                                    const idx = parseInt(key);
                                    if (!isNaN(idx) && !emulators.find(e => e.index === idx)) {
                                        const vmConfig = this.readVmConfig(idx);
                                        const emulator = this.buildEmulatorFromVmConfig(idx, vmConfig);
                                        if (emulator) {
                                            emulators.push(emulator);
                                        }
                                    }
                                });
                            }
                            
                            console.log(`[MuMu] 通过 list 命令获取到 ${emulators.length} 个模拟器`);
                        }
                    } catch (listErr) {
                        console.warn(`[MuMu] list 命令失败: ${listErr.message}`);
                    }
                }
                
                // 2. 如果命令方式没有获取到，使用文件扫描作为回退
                if (emulators.length === 0) {
                    console.log('[MuMu] 命令方式无结果，使用文件扫描方式...');
                    const indices = this.scanVmJsonFiles();
                    console.log(`[MuMu] 扫描到 ${indices.length} 个模拟器索引: ${indices.join(',')}`);
                    
                    for (const index of indices) {
                        try {
                            const vmConfig = this.readVmConfig(index);
                            const emulator = this.buildEmulatorFromVmConfig(index, vmConfig);
                            if (emulator && !emulators.find(e => e.index === index)) {
                                emulators.push(emulator);
                            }
                        } catch (e) {
                            console.warn(`[MuMu] 获取模拟器 ${index} 详情失败: ${e.message}`);
                        }
                    }
                }
                
                // 3. 如果文件扫描也没结果，遍历 0-31 索引
                if (emulators.length === 0) {
                    console.log('[MuMu] 文件扫描无结果，尝试遍历 0-31 索引...');
                    for (let i = 0; i < 32; i++) {
                        try {
                            const vmConfig = this.readVmConfig(i);
                            if (vmConfig) {
                                const emulator = this.buildEmulatorFromVmConfig(i, vmConfig);
                                if (emulator) {
                                    emulators.push(emulator);
                                    console.log(`[MuMu] 通过索引 ${i} 找到模拟器`);
                                }
                            }
                        } catch (e) {
                            console.warn(`[MuMu] 遍历索引 ${i} 读取模拟器配置失败: ${e.message}`);
                        }
                    }
                }
                
                console.log(`[MuMu] Windows获取到 ${emulators.length} 个模拟器`);
                return emulators;
            }
            
            if (this.mumutoolPath) {
                console.log(`[MuMu] 使用 mumutool 获取模拟器列表`);
                
                let emulatorIndices = [];
                let emulatorDetails = {};
                
                try {
                    const listResult = await this.execMumutool(['list']);
                    console.log(`[MuMu] list 命令返回: errcode=${listResult.errcode}`);
                    
                    if (listResult.errcode === 0 && listResult.return) {
                        let listData = listResult.return;
                        if (typeof listData === 'string') {
                            try { listData = JSON.parse(listData); } catch(e) {}
                        }
                        
                        if (Array.isArray(listData)) {
                            emulatorIndices = listData.map(item => {
                                if (typeof item === 'object' && item.index !== undefined) return item.index;
                                return parseInt(item);
                            }).filter(i => !isNaN(i));
                        } else if (listData && listData.results && Array.isArray(listData.results)) {
                            emulatorIndices = listData.results.map(item => item.index).filter(i => i !== undefined && !isNaN(i));
                        } else if (listData && typeof listData === 'object') {
                            Object.keys(listData).forEach(key => {
                                const idx = parseInt(key);
                                if (!isNaN(idx)) emulatorIndices.push(idx);
                            });
                        }
                    }
                } catch (listErr) {
                    console.warn(`[MuMu] list 命令失败: ${listErr.message}, 尝试其他方式`);
                }
                
                if (emulatorIndices.length === 0) {
                    console.log(`[MuMu] list 命令无结果，尝试扫描 vm.json 文件...`);
                    emulatorIndices = this.scanVmJsonFiles();
                    console.log(`[MuMu] 扫描到 ${emulatorIndices.length} 个模拟器索引: ${emulatorIndices.join(',')}`);
                }
                
                if (emulatorIndices.length === 0) {
                    console.log(`[MuMu] 扫描无结果，尝试遍历 info 命令...`);
                    for (let i = 0; i < 32; i++) {
                        try {
                            const infoResult = await this.execMumutool(['info', String(i)]);
                            if (infoResult.errcode === 0 && infoResult.return) {
                                emulatorIndices.push(i);
                                if (typeof infoResult.return === 'object' && infoResult.return !== null) {
                                    emulatorDetails[i] = infoResult.return;
                                }
                            }
                        } catch (e) {
                            break;
                        }
                    }
                    console.log(`[MuMu] 通过 info 命令遍历到 ${emulatorIndices.length} 个模拟器`);
                }
                
                for (const index of emulatorIndices) {
                    try {
                        let detail = emulatorDetails[index];
                        if (!detail) {
                            const infoResult = await this.execMumutool(['info', String(index)]);
                            if (infoResult.errcode === 0 && infoResult.return) {
                                detail = infoResult.return;
                            }
                        }
                        
                        let status = 'STOPPED';
                        let cpuCount = 1;
                        let memoryMB = 1024;
                        let name = `V${String(index + 1).padStart(3, '0')}`;
                        let adbPort = 16384 + index * 32;
                        
                        if (detail) {
                            if (detail.state === 'running' || detail.status === 'running') {
                                status = 'RUNNING';
                                if (detail.adb_port) {
                                    adbPort = detail.adb_port;
                                    try { await this.connectAdb(adbPort); } catch(e) {}
                                }
                            }
                            if (detail.vmName || detail.name) {
                                name = detail.vmName || detail.name;
                            }
                            if (detail.vmCpuCount && detail.vmCpuCount > 0) cpuCount = detail.vmCpuCount;
                            if (detail.vmMemoryOfMB && detail.vmMemoryOfMB > 0) memoryMB = detail.vmMemoryOfMB;
                            if (detail.adb_port) adbPort = detail.adb_port;
                        }
                        
                        try {
                            const vmConfig = this.readVmConfig(index);
                            if (vmConfig) {
                                if (vmConfig.vmCpuCount > 0) cpuCount = vmConfig.vmCpuCount;
                                if (vmConfig.vmMemoryOfMB > 0) memoryMB = vmConfig.vmMemoryOfMB;
                                if (vmConfig.vmName) name = vmConfig.vmName;
                            }
                        } catch (e) {}
                        
                        emulators.push({
                            index,
                            adbPort,
                            status,
                            name,
                            cpuCount,
                            memoryMB
                        });
                    } catch (e) {
                        console.warn(`[MuMu] 获取模拟器 ${index} 详情失败: ${e.message}`);
                    }
                }
                
                console.log(`[MuMu] 获取到 ${emulators.length} 个模拟器`);
                return emulators;
            }

            const devicesOutput = await this.execAdb(['devices']);
            const lines = devicesOutput.split(NL).slice(1);
            
            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed || trimmed.startsWith('*') || trimmed.startsWith('-')) continue;
                
                const parts = trimmed.split('\t');
                if (parts.length >= 2) {
                    const deviceId = parts[0];
                    const state = parts[1];
                    
                    if (deviceId.startsWith('127.0.0.1:')) {
                        const port = parseInt(deviceId.split(':')[1]);
                        const index = Math.floor((port - 16384) / 32);
                        if (index >= 0 && index < 100) {
                            let cpuCount = 1;
                            let memoryMB = 1024;
                            try {
                                const vmConfig = this.readVmConfig(index);
                                if (vmConfig) {
                                    if (vmConfig.vmCpuCount > 0) cpuCount = vmConfig.vmCpuCount;
                                    if (vmConfig.vmMemoryOfMB > 0) memoryMB = vmConfig.vmMemoryOfMB;
                                }
                            } catch (e) {}
                            emulators.push({
                                index,
                                adbPort: port,
                                status: state === 'device' ? 'RUNNING' : 'STOPPED',
                                name: `V${String(index + 1).padStart(3, '0')}`,
                                cpuCount,
                                memoryMB
                            });
                        }
                    }
                }
            }
        } catch (e) {
            console.warn('[MuMu] 获取模拟器列表失败:', e.message);
        }
        
        if (emulators.length === 0) {
            try {
                console.log('[MuMu] 尝试通过扫描 vm.json 文件获取模拟器列表...');
                const indices = this.scanVmJsonFiles();
                
                for (const index of indices) {
                    try {
                        let cpuCount = 1;
                        let memoryMB = 1024;
                        let name = `V${String(index + 1).padStart(3, '0')}`;
                        try {
                            const vmConfig = this.readVmConfig(index);
                            if (vmConfig) {
                                if (vmConfig.vmCpuCount > 0) cpuCount = vmConfig.vmCpuCount;
                                if (vmConfig.vmMemoryOfMB > 0) memoryMB = vmConfig.vmMemoryOfMB;
                                if (vmConfig.vmName) name = vmConfig.vmName;
                            }
                        } catch (e) {}
                        emulators.push({
                            index,
                            adbPort: 16384 + index * 32,
                            status: 'STOPPED',
                            name,
                            cpuCount,
                            memoryMB
                        });
                    } catch (e) {}
                }
                console.log(`[MuMu] 通过vm.json扫描找到 ${emulators.length} 个模拟器`);
            } catch (e) {
                console.warn('[MuMu] vm.json扫描失败:', e.message);
            }
        }
        
        return emulators;
    }

    async isMuMuPlayerRunning() {
        const os = process.platform;

        try {
            if (os === 'win32') {
                const mumuPath = this.mumuPath || '';
                
                // 优先通过 mumutool 检测模拟器
                if (this.mumutoolPath && fs.existsSync(this.mumutoolPath)) {
                    try {
                        // Windows 下 info all 不可用，改用 list 命令或检测进程
                        const result = await this.execMumutool(['list'], 5000);
                        if (result.errcode === 0 && result.return) {
                            let listData = result.return;
                            if (typeof listData === 'string') {
                                try { listData = JSON.parse(listData); } catch(e) {}
                            }
                            if (Array.isArray(listData) && listData.length > 0) {
                                console.log('[MuMu] 通过mumutool list检测到模拟器');
                                return true;
                            }
                            if (listData && listData.results && Array.isArray(listData.results) && listData.results.length > 0) {
                                console.log('[MuMu] 通过mumutool list检测到模拟器');
                                return true;
                            }
                            // 如果 list 返回了数据（非空对象），也认为有模拟器
                            if (listData && typeof listData === 'object' && Object.keys(listData).length > 0) {
                                console.log('[MuMu] 通过mumutool list检测到模拟器');
                                return true;
                            }
                        }
                    } catch (e1) {
                        console.warn('[MuMu] mumutool检测失败:', e1.message);
                    }
                }

                // Windows: 检测多种可能的 MuMu 进程名
                const processNames = ['MuMuNxMain.exe', 'MuMuPlayer.exe', 'NemuPlayer.exe'];
                for (const name of processNames) {
                    const cmd = `tasklist /FI "IMAGENAME eq ${name}" /NH 2>nul`;
                    try {
                        const result = execSync(cmd, { encoding: 'utf8', shell: true, timeout: 2000 });
                        if (result.trim().length > 0 && result.trim() !== 'INFO: No tasks are running which match the specified criteria.') {
                            console.log('[MuMu] 检测到进程:', name);
                            return true;
                        }
                    } catch (e) {
                        // 继续尝试下一个
                    }
                }

                // 尝试使用 findstr 模糊匹配
                const cmd = 'tasklist | findstr /I "MuMu"';
                try {
                    const result = execSync(cmd, { encoding: 'utf8', shell: true, timeout: 3000 });
                    if (result.trim().length > 0) {
                        console.log('[MuMu] 检测到MuMu相关进程:', result.trim().split('\n')[0]);
                        return true;
                    }
                } catch (e) {
                    // 忽略
                }

                console.log('[MuMu] 未检测到MuMuPlayer进程');
                return false;
            } else {
                // macOS/Linux: 使用 ps aux 过滤 MuMuPlayer 进程
                const cmd = 'ps aux | grep -i "[M]uMuPlayer"';
                const result = execSync(cmd, { encoding: 'utf8', shell: true, timeout: 3000 });
                return result.trim().length > 0;
            }
        } catch (e) {
            console.warn('[MuMu] isMuMuPlayerRunning异常:', e.message);
            return false;
        }
    }

    readVmConfig(index) {
        try {
            const os = process.platform;
            let vmDir = null;

            if (os === 'darwin') {
                const homeDir = process.env.HOME || require('os').homedir();
                const vmsDirs = [
                    path.join(homeDir, 'Library', 'Application Support', 'com.netease.mumu.nemux', 'vms'),
                    path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer-12.0', 'vms'),
                    path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer', 'vms'),
                    path.join(homeDir, 'Documents', 'MuMu', 'vms')
                ];
                for (const vmsDir of vmsDirs) {
                    const vmCandidate = path.join(vmsDir, String(index));
                    if (fs.existsSync(vmCandidate)) { vmDir = vmCandidate; break; }
                }
            } else if (os === 'win32') {
                let mumuBasePath = this.mumuPath || '';
                if (mumuBasePath && fs.existsSync(mumuBasePath) && fs.statSync(mumuBasePath).isFile()) {
                    mumuBasePath = path.dirname(mumuBasePath);
                }
                
                const userProfile = process.env.USERPROFILE || '';
                const homeDir = require('os').homedir();
                
                // 收集所有可能的 vms 目录
                const allVmsDirs = [];
                if (mumuBasePath) {
                    allVmsDirs.push(path.join(mumuBasePath, 'vms'));
                    if (mumuBasePath.endsWith('nx_main')) {
                        allVmsDirs.push(path.join(path.dirname(mumuBasePath), 'vms'));
                    }
                }
                allVmsDirs.push(
                    'C:\Program Files\Netease\MuMu\vms',
                    'C:\Program Files\Netease\MuMuPlayer-12.0\vms',
                    'C:\Program Files\Netease\MuMu\nx_main\vms',
                    path.join(homeDir, 'Documents', 'MuMuPlayer', 'vms'),
                    path.join(homeDir, 'Documents', 'Netease', 'MuMuPlayer-12.0', 'vms'),
                    path.join(homeDir, 'Documents', 'MuMu', 'vms'),
                );
                if (userProfile && userProfile !== homeDir) {
                    allVmsDirs.push(
                        path.join(userProfile, 'Documents', 'MuMuPlayer', 'vms'),
                        path.join(userProfile, 'Documents', 'Netease', 'MuMuPlayer-12.0', 'vms'),
                    );
                }
                
                // 搜索所有目录（支持 MuMu 12 的 myvm{index} 和旧版 {index}）
                for (const vmsDir of allVmsDirs) {
                    for (const sub of ['myvm' + index, String(index)]) {
                        const vmCandidate = path.join(vmsDir, sub);
                        if (fs.existsSync(vmCandidate)) {
                            vmDir = vmCandidate;
                            break;
                        }
                    }
                    if (vmDir) break;
                }
            }

            if (vmDir) {
                const configPaths = [
                    path.join(vmDir, 'setting', 'vm.json'),
                    path.join(vmDir, 'config', 'vm.json'),
                    path.join(vmDir, 'vm.json'),
                ];
                
                for (const configFile of configPaths) {
                    if (fs.existsSync(configFile)) {
                        try {
                            const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
                            return {
                                vmCpuCount: config.vmCpuCount || 0,
                                vmMemoryOfMB: config.vmMemoryOfMB || 0,
                                vmName: config.vmName || '',
                                adb_port: config.adb_port || config.adbPort || 0
                            };
                        } catch (e) {
                            console.warn(`[MuMu] 读取配置文件失败: ${configFile}, ${e.message}`);
                        }
                    }
                }
                
                if (fs.existsSync(vmDir)) {
                    console.log(`[MuMu] 模拟器 ${index} 无配置文件，使用默认值`);
                    return {
                        vmCpuCount: 1,
                        vmMemoryOfMB: 1024,
                        vmName: `V${String(index + 1).padStart(3, '0')}`,
                        adb_port: 0
                    };
                }
            }
        } catch (e) {
            // Ignore read errors
        }
        return null;
    }

    async startEmulator(index) {
        try {
            // Windows: MuMu 12 官方 MuMuManager 优先
            if (process.platform === 'win32' && this.mumuManagerPath) {
                const r = this.execMumuManager(['control', '-v', String(index), 'launch']);
                if (r.exitCode === 0) {
                    console.log('[MuMu] MuMuManager launch 模拟器' + index + ' 命令已发送');
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    const port = 16384 + index * 32;
                    try { await this.connectAdb(port); } catch (e) {}
                    return { success: true, message: '启动命令已发送(MuMuManager)' };
                } else {
                    console.warn('[MuMu] MuMuManager launch 失败(退出码=' + r.exitCode + '): ' + (r.stderr || r.stdout).substring(0, 200));
                }
            }
            if (this.mumutoolPath) {
                const result = await this.execMumutool(['open', String(index)]);
                if (result.errcode === 0) {
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    try {
                        const info = await this.execMumutool(['info', String(index)]);
                        if (info.return && info.return.adb_port) {
                            await this.connectAdb(info.return.adb_port);
                        }
                    } catch {}
                    return { success: true, message: '启动命令已发送' };
                } else {
                    return { success: false, message: result.message || '启动失败' };
                }
            }

            // Fallback: macOS 使用 open 命令，Windows 使用 start 命令
            if (process.platform === 'darwin') {
                const cmd = `open "${this.mumuPath}" --args -v ${index}`;
                execSync(cmd, { timeout: 5000 });
                await new Promise(resolve => setTimeout(resolve, 3000));
                const port = 16384 + index * 32;
                await this.connectAdb(port);
                return { success: true, message: '启动命令已发送' };
            } else if (process.platform === 'win32') {
                // Windows: 优先使用 mumu-cli.exe open 命令
                const mumuCliPath = path.join(this.mumuPath, 'mumu-cli.exe');
                const mumuCliShellPath = path.join(this.mumuPath, 'shell', 'mumu-cli.exe');
                const cliPath = fs.existsSync(mumuCliPath) ? mumuCliPath : (fs.existsSync(mumuCliShellPath) ? mumuCliShellPath : null);
                if (cliPath) {
                    try {
                        const result = spawnSync(cliPath, ['open', String(index)], { timeout: 10000, encoding: 'utf8', cwd: path.dirname(cliPath) });
                        if (result.error) throw result.error;
                        await new Promise(resolve => setTimeout(resolve, 3000));
                        const port = 16384 + index * 32;
                        await this.connectAdb(port);
                        return { success: true, message: '启动命令已发送' };
                    } catch (e) {
                        console.warn(`[MuMu] mumu-cli open 失败: ${e.message}`);
                    }
                }
                // Fallback: 查找 MuMuPlayer.exe 并启动
                const exePath = path.join(this.mumuPath, 'MuMuPlayer.exe');
                if (fs.existsSync(exePath)) {
                    try { execFileSync(exePath, ['-v', String(index)], { timeout: 5000, shell: true }); } catch(e) { execSync(`start "" "${exePath}" -v ${index}`, { timeout: 5000, shell: true }); }
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    const port = 16384 + index * 32;
                    await this.connectAdb(port);
                    return { success: true, message: '启动命令已发送' };
                }
                return { success: false, message: '未找到 mumu-cli.exe 或 MuMuPlayer.exe' };
            }
            return { success: false, message: '需要手动启动模拟器' };
        } catch (e) {
            return { success: false, message: e.message };
        }
    }

    async stopEmulator(index) {
        try {
            // Windows: MuMu 12 官方 MuMuManager 优先
            if (process.platform === 'win32' && this.mumuManagerPath) {
                const r = this.execMumuManager(['control', '-v', String(index), 'shutdown']);
                if (r.exitCode === 0) {
                    console.log('[MuMu] MuMuManager shutdown 模拟器' + index + ' 命令已发送');
                    return { success: true, message: '关闭命令已发送(MuMuManager)' };
                } else {
                    console.warn('[MuMu] MuMuManager shutdown 失败(退出码=' + r.exitCode + '): ' + (r.stderr || r.stdout).substring(0, 200));
                }
            }
            if (this.mumutoolPath) {
                const result = await this.execMumutool(['close', String(index)]);
                if (result.errcode === 0) {
                    return { success: true, message: '关闭命令已发送' };
                } else {
                    return { success: false, message: result.message || '关闭失败' };
                }
            }

            // Fallback: Windows 优先使用 mumu-cli.exe close
            if (process.platform === 'win32') {
                const mumuCliPath = path.join(this.mumuPath, 'mumu-cli.exe');
                const mumuCliShellPath = path.join(this.mumuPath, 'shell', 'mumu-cli.exe');
                const cliPath = fs.existsSync(mumuCliPath) ? mumuCliPath : (fs.existsSync(mumuCliShellPath) ? mumuCliShellPath : null);
                if (cliPath) {
                    try {
                        const result = spawnSync(cliPath, ['close', String(index)], { timeout: 10000, encoding: 'utf8', cwd: path.dirname(cliPath) });
                        if (result.error) throw result.error;
                        return { success: true, message: '关闭命令已发送' };
                    } catch (e) {
                        console.warn(`[MuMu] mumu-cli close 失败: ${e.message}`);
                    }
                }
            }
            // Fallback: 使用 ADB 关机
            const port = 16384 + index * 32;
            const deviceId = `127.0.0.1:${port}`;
            await this.execAdb(['-s', deviceId, 'shell', 'reboot', '-p']);
            return { success: true, message: '关闭命令已发送' };
        } catch (e) {
            return { success: false, message: e.message };
        }
    }

    async restartEmulator(index) {
        try {
            // Windows: MuMu 12 官方 MuMuManager 优先
            if (process.platform === 'win32' && this.mumuManagerPath) {
                const r = this.execMumuManager(['control', '-v', String(index), 'restart']);
                if (r.exitCode === 0) {
                    console.log('[MuMu] MuMuManager restart 模拟器' + index + ' 命令已发送');
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    const port = 16384 + index * 32;
                    try { await this.connectAdb(port); } catch (e) {}
                    return { success: true, message: '重启命令已发送(MuMuManager)' };
                } else {
                    console.warn('[MuMu] MuMuManager restart 失败(退出码=' + r.exitCode + '): ' + (r.stderr || r.stdout).substring(0, 200));
                }
            }
            if (this.mumutoolPath) {
                const result = await this.execMumutool(['restart', String(index)]);
                if (result.errcode === 0) {
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    try {
                        const info = await this.execMumutool(['info', String(index)]);
                        if (info.return && info.return.adb_port) {
                            await this.connectAdb(info.return.adb_port);
                        }
                    } catch {}
                    return { success: true, message: '重启命令已发送' };
                } else {
                    return { success: false, message: result.message || '重启失败' };
                }
            }

            // Fallback: 先关闭再启动
            await this.stopEmulator(index);
            await new Promise(resolve => setTimeout(resolve, 2000));
            return await this.startEmulator(index);
        } catch (e) {
            return { success: false, message: e.message };
        }
    }


    async applyEmulatorSetting(index, cpuCores, memoryGb, vmName) {
        try {
            const setting = {};
            if (vmName) {
                setting.vmName = vmName;
            }
            if (cpuCores && cpuCores > 0) {
                setting.vmCpuCount = cpuCores;
            }
            if (memoryGb && memoryGb > 0) {
                setting.vmMemoryOfMB = memoryGb * 1024;
            }
            
            if (Object.keys(setting).length === 0) {
                return { success: true, message: '无需应用配置' };
            }
            
            // 先尝试 mumu-cli 命令
            if (this.mumutoolPath) {
                try {
                    const result = await this.execMumutool([
                        'config', String(index),
                        '--setting', JSON.stringify(setting)
                    ]);
                    if (result.errcode === 0) {
                        console.log(`[MuMu] 模拟器${index} 通过 mumu-cli 配置成功: cpu=${cpuCores}核, mem=${memoryGb}GB, name=${vmName}`);
                        return { success: true, message: '配置应用成功' };
                    } else {
                        console.warn(`[MuMu] 模拟器${index} mumu-cli 配置失败: ${result.message}`);
                    }
                } catch (cliErr) {
                    console.warn(`[MuMu] 模拟器${index} mumu-cli 配置异常: ${cliErr.message}`);
                }
            }
            
            // 回退：直接编辑 vm.json 文件
            console.log(`[MuMu] 模拟器${index} 尝试直接编辑 vm.json 配置`);
            const vmResult = this.updateVmJsonConfig(index, cpuCores, memoryGb, vmName);
            if (vmResult.success) {
                console.log(`[MuMu] 模拟器${index} 通过 vm.json 配置成功: cpu=${cpuCores}核, mem=${memoryGb}GB, name=${vmName}`);
                return { success: true, message: '配置应用成功(vm.json)' };
            }
            
            return { success: false, message: vmResult.message || '配置应用失败' };
        } catch (e) {
            console.warn(`[MuMu] 模拟器${index} 配置应用异常:`, e.message);
            return { success: false, message: e.message };
        }
    }

    updateVmJsonConfig(index, cpuCores, memoryGb, vmName) {
        try {
            const vmDirs = this.getAllVmDirs();
            // 目录名候选: MuMu 12 Windows 用 myvm{index}，macOS 用 {index}
            const subNames = [String(index), 'myvm' + index, String(index).padStart(3, '0')];
            for (const vmDir of vmDirs) {
                const configPaths = [];
                for (const sub of subNames) {
                    configPaths.push(
                        path.join(vmDir, sub, 'setting', 'vm.json'),
                        path.join(vmDir, sub, 'config', 'vm.json'),
                        path.join(vmDir, sub, 'vm.json'),
                    );
                }
                
                for (const configFile of configPaths) {
                    if (fs.existsSync(configFile)) {
                        let config = {};
                        try {
                            config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
                        } catch (e) {
                            config = {};
                        }
                        
                        if (cpuCores && cpuCores > 0) {
                            config.vmCpuCount = cpuCores;
                        }
                        if (memoryGb && memoryGb > 0) {
                            config.vmMemoryOfMB = memoryGb * 1024;
                        }
                        if (vmName) {
                            config.vmName = vmName;
                        }
                        
                        fs.writeFileSync(configFile, JSON.stringify(config, null, 2), 'utf8');
                        console.log(`[MuMu] 已更新 vm.json: ${configFile}, vmCpuCount=${config.vmCpuCount}, vmMemoryOfMB=${config.vmMemoryOfMB}, vmName=${config.vmName}`);
                        return { success: true, message: 'vm.json 配置成功' };
                    }
                }
            }
            
            // 尝试使用 vmsBasePath
            if (this.vmsBasePath) {
                const configPaths = [
                    path.join(this.vmsBasePath, String(index), 'setting', 'vm.json'),
                    path.join(this.vmsBasePath, String(index), 'config', 'vm.json'),
                    path.join(this.vmsBasePath, String(index), 'vm.json'),
                ];
                for (const configFile of configPaths) {
                    if (fs.existsSync(configFile)) {
                        let config = {};
                        try {
                            config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
                        } catch (e) {
                            config = {};
                        }
                        
                        if (cpuCores && cpuCores > 0) {
                            config.vmCpuCount = cpuCores;
                        }
                        if (memoryGb && memoryGb > 0) {
                            config.vmMemoryOfMB = memoryGb * 1024;
                        }
                        if (vmName) {
                            config.vmName = vmName;
                        }
                        
                        fs.writeFileSync(configFile, JSON.stringify(config, null, 2), 'utf8');
                        console.log(`[MuMu] 已更新 vm.json: ${configFile}, vmCpuCount=${config.vmCpuCount}, vmMemoryOfMB=${config.vmMemoryOfMB}, vmName=${config.vmName}`);
                        return { success: true, message: 'vm.json 配置成功' };
                    }
                }
            }
            
            return { success: false, message: '未找到 vm.json 文件' };
        } catch (e) {
            return { success: false, message: '编辑 vm.json 失败: ' + e.message };
        }
    }

    getAllVmDirs() {
        const os = process.platform;
        const dirs = [];
        
        if (this.vmsBasePath) {
            dirs.push(this.vmsBasePath);
        }
        
        if (os === 'darwin') {
            const homeDir = process.env.HOME || require('os').homedir();
            dirs.push(
                path.join(homeDir, 'Library', 'Application Support', 'com.netease.mumu.nemux', 'vms'),
                path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer', 'vms'),
                path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer-12.0', 'vms'),
                path.join(homeDir, 'Documents', 'MuMu', 'vms'),
                path.join(homeDir, 'Documents', 'MuMuPlayer', 'vms'),
            );
        } else if (os === 'win32') {
            const homeDir = require('os').homedir();
            const userProfile = process.env.USERPROFILE || homeDir;
            const publicDir = process.env.PUBLIC || 'C:\\Users\\Public';
            const appData = process.env.APPDATA || '';
            const localAppData = process.env.LOCALAPPDATA || '';
            
            if (userProfile) {
                dirs.push(
                    path.join(userProfile, 'Documents', 'MuMuPlayer', 'vms'),
                    path.join(userProfile, 'Documents', 'MuMuPlayer-12.0', 'vms'),
                    path.join(userProfile, 'Documents', 'MuMu', 'vms'),
                    path.join(userProfile, 'Documents', 'Netease', 'MuMuPlayer', 'vms'),
                    path.join(userProfile, 'Documents', 'Netease', 'MuMuPlayer-12.0', 'vms'),
                );
            }
            if (publicDir) {
                dirs.push(
                    path.join(publicDir, 'Documents', 'MuMu', 'vms'),
                    path.join(publicDir, 'Documents', 'MuMuPlayer', 'vms'),
                );
            }
            if (appData) {
                dirs.push(
                    path.join(appData, 'Netease', 'MuMuPlayer', 'vms'),
                    path.join(appData, 'Netease', 'MuMuPlayer-12.0', 'vms'),
                );
            }
            if (localAppData) {
                dirs.push(
                    path.join(localAppData, 'Netease', 'MuMuPlayer', 'vms'),
                    path.join(localAppData, 'Netease', 'MuMuPlayer-12.0', 'vms'),
                );
            }
            
            // 从 mumuPath 推断
            if (this.mumuPath) {
                let installDir = this.mumuPath;
                try {
                    if (fs.existsSync(installDir) && fs.statSync(installDir).isFile()) {
                        installDir = path.dirname(installDir);
                    }
                    const baseName = path.basename(installDir).toLowerCase();
                    if (baseName === 'nx_main') {
                        installDir = path.dirname(installDir);
                    }
                    dirs.push(path.join(installDir, 'vms'));
                    dirs.push(path.join(installDir, 'nx_main', 'vms'));
                    const parentDir = path.dirname(installDir);
                    if (parentDir) {
                        dirs.push(path.join(parentDir, 'MuMuPlayer', 'vms'));
                        dirs.push(path.join(parentDir, 'MuMuPlayer-12.0', 'vms'));
                        dirs.push(path.join(parentDir, 'Netease', 'MuMuPlayer', 'vms'));
                        dirs.push(path.join(parentDir, 'Netease', 'MuMuPlayer-12.0', 'vms'));
                    }
                } catch (e) {}
            }
        }
        
        // 过滤不存在的目录并去重
        return [...new Set(dirs.filter(d => d && d.length > 0))];
    }

    editWinVmConfigFiles(index, cpuCores, memoryGb, vmName) {
        // MuMu 12 Windows: 直接编辑 vms/myvm{index}/vm_config.json + customer_config.json
        const vmsDirs = this.getAllVmDirs();
        const subDirs = ['myvm' + index, String(index)];
        let edited = false;
        for (const vmsDir of vmsDirs) {
            for (const sub of subDirs) {
                const vmDir = path.join(vmsDir, sub);
                if (!fs.existsSync(vmDir)) continue;
                // vm_config.json: {"vm": {"cpu": "4", "memory": "6"}}
                const vmConfigPath = path.join(vmDir, 'vm_config.json');
                if (fs.existsSync(vmConfigPath)) {
                    try {
                        const cfg = JSON.parse(fs.readFileSync(vmConfigPath, 'utf8'));
                        if (!cfg.vm) cfg.vm = {};
                        if (cpuCores > 0) cfg.vm.cpu = String(cpuCores);
                        if (memoryGb > 0) cfg.vm.memory = String(memoryGb);
                        fs.writeFileSync(vmConfigPath, JSON.stringify(cfg, null, 2), 'utf8');
                        console.log('[MuMu] 已更新 vm_config.json: ' + vmConfigPath);
                        edited = true;
                    } catch (e) { console.warn('[MuMu] 编辑 vm_config.json 失败: ' + e.message); }
                }
                // customer_config.json: setting.performance.mode
                const ccPath = path.join(vmDir, 'customer_config.json');
                if (fs.existsSync(ccPath)) {
                    try {
                        const cfg = JSON.parse(fs.readFileSync(ccPath, 'utf8'));
                        if (!cfg.setting) cfg.setting = {};
                        if (!cfg.setting.performance) cfg.setting.performance = {};
                        if (!cfg.setting.performance.mode) cfg.setting.performance.mode = {};
                        if (cpuCores > 0 || memoryGb > 0) {
                            cfg.setting.performance.mode.choose = 'performance.mode.custom';
                            if (!cfg.setting.performance.mode.custom) cfg.setting.performance.mode.custom = {};
                            if (cpuCores > 0) cfg.setting.performance.mode.custom.cpu = String(cpuCores);
                            if (memoryGb > 0) cfg.setting.performance.mode.custom.memory = String(memoryGb);
                        }
                        fs.writeFileSync(ccPath, JSON.stringify(cfg, null, 2), 'utf8');
                        console.log('[MuMu] 已更新 customer_config.json: ' + ccPath);
                        edited = true;
                    } catch (e) { console.warn('[MuMu] 编辑 customer_config.json 失败: ' + e.message); }
                }
                // vm.json（旧格式回退）
                const vmJsonPath = path.join(vmDir, 'vm.json');
                if (fs.existsSync(vmJsonPath)) {
                    try {
                        const cfg = JSON.parse(fs.readFileSync(vmJsonPath, 'utf8'));
                        if (cpuCores > 0) cfg.vmCpuCount = cpuCores;
                        if (memoryGb > 0) cfg.vmMemoryOfMB = memoryGb * 1024;
                        if (vmName) cfg.vmName = vmName;
                        fs.writeFileSync(vmJsonPath, JSON.stringify(cfg, null, 2), 'utf8');
                        console.log('[MuMu] 已更新 vm.json: ' + vmJsonPath);
                        edited = true;
                    } catch (e) { console.warn('[MuMu] 编辑 vm.json 失败: ' + e.message); }
                }
                if (edited) return true;
            }
        }
        return edited;
    }

    async deleteEmulator(index) {
        let deletedOk = false;
        let lastError = '';

        try {
            // 先尝试停止模拟器再删除
            try {
                await this.stopEmulator(index);
                await new Promise(resolve => setTimeout(resolve, 1000));
            } catch (e) {
                console.warn(`[MuMu] 删除前停止模拟器${index}失败: ${e.message}`);
            }

            // Windows: 优先使用 MuMuManager delete（MuMu 12 官方）
            if (process.platform === 'win32' && this.mumuManagerPath) {
                try {
                    const r = this.execMumuManager(['delete', '-v', String(index)]);
                    if (r.exitCode === 0) {
                        console.log('[MuMu] MuMuManager delete 模拟器' + index + ' 执行成功(退出码=0)');
                        deletedOk = true;
                    } else {
                        const errMsg = (r.stderr || r.stdout || '').trim();
                        console.warn('[MuMu] MuMuManager delete 失败(退出码=' + r.exitCode + '): ' + errMsg.substring(0, 200));
                        lastError = 'MuMuManager退出码' + r.exitCode + ': ' + errMsg.substring(0, 100);
                    }
                } catch (e) {
                    console.warn('[MuMu] MuMuManager delete 异常: ' + e.message);
                    lastError = e.message;
                }
            }

            // Windows: 旧 mumu-cli.exe delete 命令
            if (process.platform === 'win32') {
                const mumuCliPath = path.join(this.mumuPath || '', 'mumu-cli.exe');
                const mumuCliShellPath = path.join(this.mumuPath || '', 'shell', 'mumu-cli.exe');
                const cliPath = fs.existsSync(mumuCliPath) ? mumuCliPath : (fs.existsSync(mumuCliShellPath) ? mumuCliShellPath : null);
                if (cliPath) {
                    try {
                        const result = spawnSync(cliPath, ['delete', String(index)], { timeout: 15000, encoding: 'utf8', cwd: path.dirname(cliPath) });
                        if (result.error) {
                            throw new Error(result.error.message);
                        }
                        if (result.status !== 0) {
                            const errMsg = (result.stderr || result.stdout || '').trim();
                            console.warn(`[MuMu] mumu-cli delete 退出码=${result.status}, stderr=${errMsg}`);
                            lastError = `mumu-cli退出码${result.status}: ${errMsg}`;
                        } else {
                            console.log(`[MuMu] mumu-cli delete 模拟器${index} 执行成功(退出码=0)`);
                            deletedOk = true;
                        }
                    } catch (e) {
                        console.warn(`[MuMu] mumu-cli delete 异常: ${e.message}, 尝试 mumutool 和手动删除`);
                        lastError = e.message;
                    }
                } else {
                    console.warn(`[MuMu] 未找到 mumu-cli.exe, 尝试 mumutool`);
                }
            }

            // 后验证：通过 getEmulators 确认物理模拟器是否真的没了
            if (deletedOk) {
                await new Promise(r => setTimeout(r, 1500));
                try {
                    const afterList = await this.getEmulators();
                    const stillExists = afterList.some(e => e.index === index);
                    if (stillExists) {
                        console.error(`[MuMu] ❌ mumu-cli delete 后模拟器${index}仍然存在！`);
                        deletedOk = false;
                        lastError = 'mumu-cli删除执行成功但模拟器仍存在';
                    } else {
                        console.log(`[MuMu] ✅ mumu-cli delete 后确认模拟器${index}已不存在`);
                        return { success: true, message: '删除成功' };
                    }
                } catch (ve) {
                    console.warn(`[MuMu] 后验证异常: ${ve.message}, 继续尝试其他方式`);
                }
            }

            // 尝试 mumutool delete
            if (this.mumutoolPath) {
                try {
                    const result = await this.execMumutool(['delete', String(index)]);
                    if (result.errcode === 0) {
                        deletedOk = true;
                        console.log(`[MuMu] mumutool delete 模拟器${index} 成功(errcode=0)`);
                        // 后验证
                        await new Promise(r => setTimeout(r, 1500));
                        try {
                            const afterList = await this.getEmulators();
                            const stillExists = afterList.some(e => e.index === index);
                            if (stillExists) {
                                console.error(`[MuMu] ❌ mumutool delete 后模拟器${index}仍然存在！`);
                                deletedOk = false;
                                lastError = 'mumutool删除执行成功但模拟器仍存在';
                            } else {
                                console.log(`[MuMu] ✅ mumutool delete 后确认模拟器${index}已不存在`);
                                return { success: true, message: '删除成功' };
                            }
                        } catch (ve) {
                            console.warn(`[MuMu] mumutool后验证异常: ${ve.message}`);
                        }
                    } else {
                        lastError = `mumutool errcode=${result.errcode}: ${result.message || ''}`;
                        console.warn(`[MuMu] mumutool 删除模拟器失败: ${result.message}`);
                    }
                } catch (e) {
                    console.warn(`[MuMu] mumutool delete 异常: ${e.message}`);
                    lastError = e.message;
                }
            }

            // Fallback: 手动删除模拟器数据目录
            const deleteResult = await this.deleteEmulatorData(index);
            if (deleteResult.success) {
                await new Promise(r => setTimeout(r, 1000));
                try {
                    const afterList = await this.getEmulators();
                    const stillExists = afterList.some(e => e.index === index);
                    if (stillExists) {
                        console.error(`[MuMu] ❌ 手动删除后模拟器${index}仍然存在！`);
                        return { success: false, message: '手动删除成功但模拟器仍存在' };
                    }
                    console.log(`[MuMu] ✅ 手动删除后确认模拟器${index}已不存在`);
                    return { success: true, message: '删除成功(手动清理)' };
                } catch (ve) {
                    console.error(`[MuMu] 手动删除后验证异常: ${ve.message}, 视为删除失败`);
                    return { success: false, message: '手动删除后验证异常: ' + ve.message };
                }
            }
            return { success: false, message: lastError || deleteResult.message || '删除失败' };
        } catch (e) {
            return { success: false, message: e.message };
        }
    }

    async deleteEmulatorData(index) {
        try {
            const os = process.platform;
            let vmDir = null;

            if (os === 'darwin') {
                const homeDir = process.env.HOME || require('os').homedir();
                const candidates = [
                    path.join(homeDir, 'Library', 'Application Support', 'com.netease.mumu.nemux', 'vms', String(index)),
                    path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer', 'vms', String(index)),
                    path.join(homeDir, 'Documents', 'MuMu', 'vms', String(index))
                ];
                for (const p of candidates) {
                    if (fs.existsSync(p)) { vmDir = p; break; }
                }
                if (!vmDir) {
                    const vmsCandidates = [
                        path.join(homeDir, 'Library', 'Application Support', 'com.netease.mumu.nemux', 'vms'),
                        path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer', 'vms'),
                        path.join(homeDir, 'Documents', 'MuMu', 'vms')
                    ];
                    for (const vmsDir of vmsCandidates) {
                        const vmPath = path.join(vmsDir, String(index));
                        if (fs.existsSync(vmPath)) { vmDir = vmPath; break; }
                    }
                }
            } else if (os === 'win32') {
                const candidates = [
                    // mumuPath 下的 vms 目录（如 MuMu\vms\0）
                    path.join(this.mumuPath || '', 'vms', String(index)),
                    // mumuPath 下的 vms\v{index} 格式
                    path.join(this.mumuPath || '', 'vms', `v${index}`),
                    // 公共文档下的 MuMu 数据
                    path.join(process.env.PUBLIC || 'C:\\Users\\Public', 'Documents', 'MuMu', 'vms', `v${index}`),
                    path.join(process.env.PUBLIC || 'C:\\Users\\Public', 'Documents', 'MuMuPlayer', 'vms', `v${index}`),
                    // 用户文档目录
                    path.join(process.env.USERPROFILE || 'C:\\Users\\Administrator', 'Documents', 'MuMu', 'vms', `v${index}`),
                    path.join(process.env.USERPROFILE || 'C:\\Users\\Administrator', 'Documents', 'Netease', 'MuMu', 'vms', `v${index}`),
                    // AppData Local
                    path.join(process.env.LOCALAPPDATA || '', 'Netease', 'MuMu', 'vms', `v${index}`),
                    path.join(process.env.LOCALAPPDATA || '', 'NetEase', 'MuMuPlayer', 'vms', `v${index}`)
                ].filter(p => p && !p.includes('undefined') && !p.includes('null'));
                console.log(`[MuMu] 搜索模拟器${index}数据目录, candidates=${candidates.length}`);
                for (const p of candidates) {
                    if (fs.existsSync(p)) {
                        vmDir = p;
                        console.log(`[MuMu] 找到模拟器数据目录: ${p}`);
                        break;
                    }
                }
                // 打印 vms 父目录帮助调试
                if (!vmDir && this.mumuPath) {
                    const vmsParent = path.join(this.mumuPath, 'vms');
                    if (fs.existsSync(vmsParent)) {
                        try {
                            const entries = fs.readdirSync(vmsParent);
                            console.log(`[MuMu] mumuPath/vms 目录内容: ${entries.join(', ')}`);
                        } catch {}
                    }
                }
            }

            if (vmDir && fs.existsSync(vmDir)) {
                console.log(`[MuMu] 手动删除模拟器数据目录: ${vmDir}`);
                // macOS: vms/{index} 可能是指向 bundles/*.mad 的符号链接
                try {
                    const lst = fs.lstatSync(vmDir);
                    if (lst.isSymbolicLink()) {
                        const realPath = fs.readlinkSync(vmDir);
                        console.log(`[MuMu] 检测到符号链接 -> ${realPath}`);
                        fs.unlinkSync(vmDir);
                        if (realPath && fs.existsSync(realPath)) {
                            fs.rmSync(realPath, { recursive: true, force: true });
                            console.log(`[MuMu] 已删除实际 bundle: ${realPath}`);
                        }
                    } else {
                        fs.rmSync(vmDir, { recursive: true, force: true });
                    }
                } catch (rmErr) {
                    fs.rmSync(vmDir, { recursive: true, force: true });
                }
                const parentDir = path.dirname(vmDir);
                try {
                    const files = fs.readdirSync(parentDir);
                    const configFiles = files.filter(f => f.includes(String(index)));
                    for (const cf of configFiles) {
                        try { fs.unlinkSync(path.join(parentDir, cf)); } catch {}
                    }
                } catch {}
                console.log(`[MuMu] 模拟器${index}数据目录已删除`);
                return { success: true, message: '数据目录已删除' };
            }

            console.log(`[MuMu] 模拟器${index}数据目录不存在，视为已删除`);
            return { success: true, message: '数据目录不存在，无需删除' };
        } catch (e) {
            return { success: false, message: `手动删除失败: ${e.message}` };
        }
    }
}
// ========== WebSocket 客户端 ==========
let ws = null;
let heartbeatInterval = null;
let reconnectTimer = null;
const HEARTBEAT_INTERVAL = 30000;
const RECONNECT_DELAY = 5000;

const deviceId = getOrCreateDeviceId();
let mumu = null;

function connect() {
    const serverUrl = config.serverUrl || 'ws://localhost:9090/ws/agent';
    const url = `${serverUrl}?deviceId=${deviceId}&userId=${encodeURIComponent(config.userId)}`;
    
    console.log(`[Agent] 正在连接: ${url}`);
    
    ws = new WebSocket(url);
    
    ws.on('open', () => {
        console.log('[Agent] WebSocket 连接成功');
        sendRegister();
        startHeartbeat();
    });
    
    ws.on('message', async (data) => {
        try {
            const msg = JSON.parse(data.toString());
            await handleMessage(msg);
        } catch (e) {
            console.error('[Agent] 消息处理失败:', e.message);
        }
    });
    
    ws.on('close', () => {
        console.log('[Agent] WebSocket 连接关闭');
        stopHeartbeat();
        scheduleReconnect();
    });
    
    ws.on('error', (err) => {
        console.error('[Agent] WebSocket 错误:', err.message);
    });
}

function scheduleReconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    console.log(`[Agent] ${RECONNECT_DELAY / 1000}秒后尝试重连...`);
    reconnectTimer = setTimeout(() => {
        connect();
    }, RECONNECT_DELAY);
}

function sendRegister() {
    const registerMsg = {
        type: 'REGISTER',
        deviceId: deviceId,
        userId: config.userId,
        params: {
            os: process.platform,
            osVersion: process.getSystemVersion ? process.getSystemVersion() : '',
            mumuPath: config.mumuPath || ''
        }
    };
    send(registerMsg);
}

function startHeartbeat() {
    stopHeartbeat();
    heartbeatInterval = setInterval(async () => {
        try {
            const emulators = await mumu.getEmulators();
            const mumuPlayerRunning = await mumu.isMuMuPlayerRunning();
            const runningCount = emulators.filter(e => e.status === 'RUNNING').length;
            const heartbeatMsg = {
                type: 'HEARTBEAT',
                data: {
                    deviceId: deviceId,
                    emulators: emulators,
                    mumuPlayerRunning: mumuPlayerRunning,
                    emulatorCount: emulators.length,
                    runningEmulatorCount: runningCount
                }
            };
            send(heartbeatMsg);
        } catch (e) {
            console.warn('[Agent] 心跳失败:', e.message);
        }
    }, HEARTBEAT_INTERVAL);
}

function stopHeartbeat() {
    if (heartbeatInterval) {
        clearInterval(heartbeatInterval);
        heartbeatInterval = null;
    }
}

function send(msg) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(msg));
        const ts = getTimestamp();
        if (msg.type === 'TASK_RESULT') {
            console.log(`[${ts}] [Agent] 发送任务结果: type=${msg.type}, status=${msg.params?.status}, taskId=${msg.taskId}`);
        }
    } else {
        console.warn(`[Agent] 发送消息失败: WebSocket 未连接, type=${msg.type}`);
    }
}

async function handleMessage(msg) {
    const type = msg.type;
    console.log(`[Agent] 收到消息: ${type}`);
    
    switch (type) {
        case 'REGISTER_ACK':
            console.log(`[Agent] 注册成功: ${JSON.stringify(msg)}`);
            break;
            
        case 'ERROR':
            console.error(`[Agent] 错误: ${msg.message}`);
            break;
            
        case 'GET_EMULATORS':
            {
                const emulators = await mumu.getEmulators();
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: 'SUCCESS' },
                    data: { emulators }
                });
            }
            break;
            
        case 'START_EMULATOR':
            {
                const index = msg.params?.index;
                const result = await mumu.startEmulator(index);
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: result.success ? 'SUCCESS' : 'FAILED' },
                    data: result
                });
            }
            break;
            
        case 'STOP_EMULATOR':
            {
                const index = msg.params?.index;
                const result = await mumu.stopEmulator(index);
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: result.success ? 'SUCCESS' : 'FAILED' },
                    data: result
                });
            }
            break;

        case 'RESTART_EMULATOR':
            {
                const index = msg.params?.index;
                const result = await mumu.restartEmulator(index);
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: result.success ? 'SUCCESS' : 'FAILED' },
                    data: result
                });
            }
            break;
            
        case 'DELETE_EMULATOR':
            {
                const index = msg.params?.index;
                const result = await mumu.deleteEmulator(index);
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: result.success ? 'SUCCESS' : 'FAILED' },
                    data: result
                });
            }
            break;
            
        case 'PING':
            send({ type: 'PONG' });
            break;
            
        case 'BATCH_START':
            {
                const emulators = await mumu.getEmulators();
                let successCount = 0;
                let failCount = 0;
                const results = [];
                
                for (const emu of emulators) {
                    if (emu.status === 'RUNNING') {
                        successCount++;
                        results.push({ index: emu.index, success: true, message: '已在运行' });
                        continue;
                    }
                    try {
                        const result = await mumu.startEmulator(emu.index);
                        if (result.success) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                        results.push({ index: emu.index, ...result });
                    } catch (e) {
                        failCount++;
                        results.push({ index: emu.index, success: false, message: e.message });
                    }
                    await new Promise(resolve => setTimeout(resolve, 2000));
                }
                
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: failCount === 0 ? 'SUCCESS' : 'PARTIAL' },
                    data: {
                        total: emulators.length,
                        successCount,
                        failCount,
                        results
                    }
                });
            }
            break;
            
        case 'BATCH_STOP':
            {
                const emulators = await mumu.getEmulators();
                let successCount = 0;
                let failCount = 0;
                const results = [];
                
                for (const emu of emulators) {
                    if (emu.status !== 'RUNNING') {
                        successCount++;
                        results.push({ index: emu.index, success: true, message: '已停止' });
                        continue;
                    }
                    try {
                        const result = await mumu.stopEmulator(emu.index);
                        if (result.success) {
                            successCount++;
                        } else {
                            failCount++;
                        }
                        results.push({ index: emu.index, ...result });
                    } catch (e) {
                        failCount++;
                        results.push({ index: emu.index, success: false, message: e.message });
                    }
                }
                
                send({
                    type: 'TASK_RESULT',
                    taskId: msg.taskId,
                    params: { status: failCount === 0 ? 'SUCCESS' : 'PARTIAL' },
                    data: {
                        total: emulators.length,
                        successCount,
                        failCount,
                        results
                    }
                });
            }
            break;
            
        case 'CREATE_EMULATOR':
            {
                const requestedCount = msg.params?.count || 1;
                const cpuCores = msg.params?.cpuCores || 0;
                const memoryGb = msg.params?.memoryGb || 0;
                const addMode = msg.params?.addMode || false;
                console.log(`[Agent] 处理 CREATE_EMULATOR: requestedCount=${requestedCount}, cpuCores=${cpuCores}, memoryGb=${memoryGb}, addMode=${addMode}, mumutoolPath=${mumu.mumutoolPath || 'null'}`);
                
                try {
                    // 先获取当前已有的模拟器列表
                    const existingEmulators = await mumu.getEmulators();
                    const existingIndices = new Set(existingEmulators.map(e => e.index));
                    console.log(`[Agent] 当前已有 ${existingIndices.size} 个模拟器，索引: ${[...existingIndices].join(', ')}`);
                    
                    // 计算需要创建的数量
                    let neededCount;
                    if (addMode) {
                        // 追加模式：count 就是要新增的数量
                        neededCount = requestedCount;
                        console.log(`[Agent] 追加模式: 需要新增 ${neededCount} 个模拟器`);
                    } else {
                        // 设置模式: count 是目标总数，需要减去已有的
                        neededCount = requestedCount - existingIndices.size;
                        console.log(`[Agent] 设置模式: 目标总数 ${requestedCount}, 已有 ${existingIndices.size}, 需要创建 ${neededCount} 个`);
                    }
                    
                    if (neededCount <= 0) {
                        console.log(`[Agent] 无需创建（neededCount=${neededCount}）`);
                        send({
                            type: 'TASK_RESULT',
                            taskId: msg.taskId,
                            params: { status: 'SUCCESS' },
                            data: { success: true, message: `无需创建`, successCount: 0, failCount: 0 }
                        });
                        break;
                    }
                    
                    let successCount = 0;
                    let failCount = 0;
                    const results = [];
                    
                    if (process.platform === 'win32' && mumu.mumuManagerPath) {
                        // Windows MuMu 12 官方路径: MuMuManager create + setting + rename（无需启动模拟器，秒级完成）
                        console.log('[Agent] Windows: 使用 MuMuManager 创建 ' + neededCount + ' 个模拟器');
                        const createR = mumu.execMumuManager(['create', '-n', String(neededCount)]);
                        console.log('[Agent] MuMuManager create 退出码=' + createR.exitCode + ', stdout: ' + (createR.stdout || '').substring(0, 300));
                        if (createR.exitCode !== 0) {
                            send({ type: 'TASK_RESULT', taskId: msg.taskId, params: { status: 'FAILED' },
                                data: { success: false, message: 'MuMuManager create 失败: ' + (createR.stderr || createR.stdout || '').substring(0, 200), successCount: 0, failCount: neededCount } });
                            break;
                        }
                        // 等待创建落盘
                        await new Promise(r => setTimeout(r, 1500));
                        // 前后对比获取新索引
                        const afterList = await mumu.getEmulators();
                        const createdIndices = afterList.map(e => e.index).filter(i => !existingIndices.has(i));
                        console.log('[Agent] 新创建的模拟器索引: ' + createdIndices.join(', '));
                        
                        for (const index of createdIndices) {
                            const vmName = 'V' + String(index + 1).padStart(3, '0');
                            let configSuccess = false;
                            // 1. 设置 CPU/内存（MuMuManager setting，重启后生效，新实例为停止状态直接生效）
                            if (cpuCores > 0 || memoryGb > 0) {
                                try {
                                    const setArgs = ['setting', '-v', String(index), '-k', 'performance_mode', '-val', 'custom'];
                                    if (cpuCores > 0) setArgs.push('-k', 'performance.cpu.custom', '-val', String(cpuCores));
                                    if (memoryGb > 0) setArgs.push('-k', 'performance.mem.custom', '-val', String(memoryGb));
                                    const setR = mumu.execMumuManager(setArgs);
                                    console.log('[Agent] MuMuManager setting 结果: 退出码=' + setR.exitCode + ', stdout: ' + (setR.stdout || '').substring(0, 200) + ', stderr: ' + (setR.stderr || '').substring(0, 200));
                                    configSuccess = setR.exitCode === 0;
                                    if (!configSuccess) {
                                        // 回退: 直接编辑 vm_config.json
                                        try {
                                            const fr = mumu.editWinVmConfigFiles(index, cpuCores, memoryGb, null);
                                            if (fr) configSuccess = true;
                                        } catch (fe) { console.warn('[Agent] 编辑vm配置文件失败: ' + fe.message); }
                                    }
                                } catch (e) { console.warn('[Agent] setting 异常: ' + e.message); }
                            } else { configSuccess = true; }
                            // 2. 重命名
                            let renameOk = false;
                            try {
                                const rnR = mumu.execMumuManager(['rename', '-v', String(index), '-n', vmName]);
                                renameOk = rnR.exitCode === 0;
                                console.log('[Agent] MuMuManager rename ' + vmName + ' 结果: 退出码=' + rnR.exitCode);
                                if (!renameOk) {
                                    try { if (mumu.editWinVmConfigFiles(index, 0, 0, vmName)) renameOk = true; } catch (fe) {}
                                }
                            } catch (e) { console.warn('[Agent] rename 异常: ' + e.message); }
                            
                            if (configSuccess) successCount++; else failCount++;
                            results.push({ index, success: configSuccess, name: vmName, cpuCores, memoryGb, renameOk, engine: 'MuMuManager' });
                        }
                        send({ type: 'TASK_RESULT', taskId: msg.taskId, params: { status: failCount === 0 ? 'SUCCESS' : 'PARTIAL' },
                            data: { success: failCount === 0, successCount, failCount, results } });
                        break;
                    }
                    
                    if (mumu.mumutoolPath) {
                        // 使用 mumutool create --count 命令创建模拟器
                        let createArgs = ['create', '--count', String(neededCount), '--type', 'phone'];
                        if (cpuCores > 0) createArgs.push('--cpu', String(cpuCores));
                        if (memoryGb > 0) createArgs.push('--memory', String(memoryGb * 1024));
                        
                        console.log(`[Agent] 使用 mumutool ${createArgs.join(' ')} 创建 ${neededCount} 个模拟器`);
                        const result = await mumu.execMumutool(createArgs);
                        console.log(`[Agent] mumutool create 结果: ${JSON.stringify(result).substring(0, 200)}`);
                        
                        // 解析创建结果 - 支持多种格式
                        // 格式1: {"0":{"errcode":0}, "1":{"errcode":0}} (以索引为key的对象)
                        // 格式2: {errcode: 0, return: {results: [...]}}
                        // 格式3: {errcode: 0}
                        let createdIndices = [];
                        
                        if (result && typeof result === 'object') {
                            // 检查是否是 {0: {...}, 1: {...}} 格式
                            const keys = Object.keys(result);
                            const hasNumericKeys = keys.length > 0 && keys.every(k => !isNaN(parseInt(k)));
                            
                            if (hasNumericKeys) {
                                // 格式1: 以索引为key的对象
                                console.log('[Agent] 解析 create 结果: 检测到以索引为key的对象格式');
                                for (const key of keys) {
                                    const idx = parseInt(key);
                                    const val = result[key];
                                    if (val && val.errcode === 0) {
                                        createdIndices.push(idx);
                                        console.log(`[Agent] 创建模拟器 index=${idx} 成功`);
                                    } else {
                                        console.warn(`[Agent] 创建模拟器 index=${idx} 失败: ${val ? val.errmsg : '未知错误'}`);
                                    }
                                }
                            } else if (result.errcode === 0 && result.return) {
                                // 格式2: {errcode: 0, return: {results: [...]}}
                                console.log('[Agent] 解析 create 结果: 检测到标准格式');
                                const createdResults = result.return.results || [];
                                createdIndices = createdResults.map(item => item.index).filter(i => i !== undefined);
                            } else if (result.errcode === 0) {
                                // 格式3: 只有 errcode: 0，没有具体信息
                                console.log('[Agent] 解析 create 结果: 成功但无具体索引信息');
                                // 需要通过扫描获取新创建的模拟器索引
                            } else {
                                console.warn(`[Agent] 创建失败: ${result.message || JSON.stringify(result)}`);
                                failCount = neededCount;
                                send({
                                    type: 'TASK_RESULT',
                                    taskId: msg.taskId,
                                    params: { status: 'FAILED' },
                                    data: { success: false, message: result.message || '创建失败', successCount: 0, failCount: neededCount }
                                });
                                break;
                            }
                        }
                        
                        // 等待文件系统就绪 + 模拟器首次启动生成 vm.json
                        // Windows 上 MuMu 创建后首次启动才生成 vm.json，所以等 2 秒让文件系统稳定
                        await new Promise(r => setTimeout(r, 2000));
                        
                        // 串行配置每个模拟器（不能并行，避免多个模拟器同时启动冲突）
                        const results = [];
                        for (const index of createdIndices) {
                            const vmName = 'V' + String(index + 1).padStart(3, '0');
                            console.log('[Agent] === 模拟器 index=' + index + ' 配置开始 ===');
                            console.log('[Agent] 目标配置: 名称=' + vmName + ', CPU=' + cpuCores + '核, 内存=' + memoryGb + 'GB');
                            
                            // 步骤 A: 启动模拟器触发 vm.json 生成（Windows 上 MuMu 创建后首次启动才生成 vm.json）
                            let vmFileExisted = false;
                            try {
                                const preCheck = mumu.updateVmJsonConfig(index, 0, 0, null);
                                if (preCheck.success) {
                                    vmFileExisted = true;
                                    console.log('[Agent] vm.json 已存在，跳过启动触发');
                                } else {
                                    console.log('[Agent] vm.json 不存在，启动模拟器触发生成...');
                                    try {
                                        await mumu.startEmulator(index);
                                        console.log('[Agent] 模拟器 ' + index + ' 已启动，等待 vm.json 生成 (5秒)...');
                                        await new Promise(r => setTimeout(r, 5000));
                                        await mumu.stopEmulator(index);
                                        console.log('[Agent] 模拟器 ' + index + ' 已停止');
                                        await new Promise(r => setTimeout(r, 2000));
                                    } catch (startErr) {
                                        console.warn('[Agent] 启动/停止模拟器触发 vm.json 失败: ' + startErr.message);
                                    }
                                }
                            } catch (e) {
                                console.warn('[Agent] 检查 vm.json 时异常: ' + e.message);
                            }
                            
                            // 步骤 B: 配置 vm.json
                            let configSuccess = false;
                            try {
                                const vmResult = mumu.updateVmJsonConfig(index, cpuCores, memoryGb, vmName);
                                console.log('[Agent] vm.json 写入结果: ' + JSON.stringify(vmResult));
                                if (vmResult.success) {
                                    console.log('[Agent] ✅ vm.json 配置成功: ' + vmName + ', cpu=' + cpuCores + ', mem=' + memoryGb + 'GB');
                                    configSuccess = true;
                                } else {
                                    console.warn('[Agent] ❌ vm.json 配置失败: ' + vmResult.message);
                                }
                            } catch (vmErr) {
                                console.warn('[Agent] vm.json 配置异常: ' + vmErr.message);
                            }
                            
                            // 步骤 C: 回退到 mumu-cli config
                            if (!configSuccess && mumu.mumutoolPath) {
                                console.log('[Agent] 回退到 mumutool config...');
                                try {
                                    const settingObj = { vmName: vmName };
                                    if (cpuCores > 0) settingObj.vmCpuCount = cpuCores;
                                    if (memoryGb > 0) settingObj.vmMemoryOfMB = memoryGb * 1024;
                                    const cfgResult = await mumu.execMumutool(['config', String(index), '--setting', JSON.stringify(settingObj)]);
                                    if (cfgResult.errcode === 0) {
                                        console.log('[Agent] ✅ mumutool config 成功');
                                        configSuccess = true;
                                    } else {
                                        console.warn('[Agent] mumutool config 失败: ' + cfgResult.message + ', 尝试逐字段');
                                        try {
                                            if (cpuCores > 0) await mumu.execMumutool(['config', String(index), 'vmCpuCount', String(cpuCores)]);
                                            if (memoryGb > 0) await mumu.execMumutool(['config', String(index), 'vmMemoryOfMB', String(memoryGb * 1024)]);
                                            await mumu.execMumutool(['config', String(index), 'vmName', vmName]);
                                            console.log('[Agent] ✅ mumutool 逐字段配置成功');
                                            configSuccess = true;
                                        } catch (fallbackErr) {
                                            console.warn('[Agent] mumutool 逐字段也失败: ' + fallbackErr.message);
                                        }
                                    }
                                } catch (e) {
                                    console.warn('[Agent] mumutool 配置异常: ' + e.message);
                                }
                            }
                            
                            // 步骤 D: 配置后强制重启模拟器让 MuMu 重新读配置生效
                            if (configSuccess) {
                                try {
                                    console.log('[Agent] 配置成功，重启模拟器让配置生效...');
                                    await mumu.startEmulator(index);
                                    await new Promise(r => setTimeout(r, 3000));
                                    await mumu.stopEmulator(index);
                                    await new Promise(r => setTimeout(r, 2000));
                                    console.log('[Agent] ✅ 模拟器 ' + vmName + ' 重启完成，配置应已生效');
                                } catch (restartErr) {
                                    console.warn('[Agent] 重启模拟器失败（配置可能未完全生效）: ' + restartErr.message);
                                }
                            }
                            
                            results.push({ index, success: true, name: vmName, cpuCores, memoryGb, vmFileExisted, configSuccess });
                            console.log('[Agent] === 模拟器 index=' + index + ' 配置结束 ===');
                        }
                        // 基于串行配置结果计数
                        for (const r of results) {
                            if (r.success) {
                                successCount++;
                                console.log(`[Agent] 模拟器 index=${r.index} (${r.name}) 创建完成, vmFileExisted=${r.vmFileExisted}, configSuccess=${r.configSuccess}`);
                            } else {
                                failCount++;
                                console.warn(`[Agent] 模拟器创建失败: ${r.message || '未知'}`);
                            }
                        }
                    } else {
                        // Fallback: 使用系统命令逐个启动（macOS用open，Windows用start）
                        console.log(`[Agent] mumutool 未找到, 使用系统命令作为后备方案`);
                        const os = process.platform;
                        console.log(`[Agent] 后备方案: os=${os}, mumuPath=${mumu.mumuPath}, config.mumuPath=${config.mumuPath}`);
                        
                        // 计算目标总数
                        let targetCount;
                        if (addMode) {
                            // 追加模式：在现有基础上新增
                            targetCount = existingIndices.size + neededCount;
                        } else {
                            // 设置模式：直接使用 requestedCount
                            targetCount = requestedCount;
                        }
                        console.log(`[Agent] 后备方案: targetCount=${targetCount}, existingIndices.size=${existingIndices.size}`);
                        
                        // 获取最新的模拟器列表
                        const existingAfter = await mumu.getEmulators();
                        const existingSet = new Set(existingAfter.map(e => e.index));
                        
                        // 计算需要创建的索引
                        const neededIndices = [];
                        if (addMode) {
                            // 追加模式：找下一个可用的索引
                            let nextIndex = 0;
                            for (let i = 0; i < neededCount; i++) {
                                while (existingSet.has(nextIndex) || neededIndices.includes(nextIndex)) {
                                    nextIndex++;
                                }
                                neededIndices.push(nextIndex);
                                nextIndex++;
                            }
                        } else {
                            // 设置模式：补齐缺失的索引
                            for (let i = 0; i < targetCount; i++) {
                                if (!existingSet.has(i)) {
                                    neededIndices.push(i);
                                }
                            }
                        }
                        console.log(`[Agent] 需要创建索引: ${neededIndices.join(', ')}`);
                        
                        // 优化：并行创建多个模拟器，而不是串行
                        const createTasks = neededIndices.map(async (index) => {
                            try {
                                if (os === 'win32') {
                                    const mumuExe = config.mumuPath;
                                    console.log(`[Agent] 使用 config.mumuPath: ${mumuExe}`);
                                    
                                    if (!mumuExe || !fs.existsSync(mumuExe)) {
                                        throw new Error(`config.json 中配置的 mumuPath 不存在: ${mumuExe}`);
                                    }
                                    
                                    console.log(`[Agent] 使用 MuMu 可执行文件: ${mumuExe}`);
                                    // Windows 下使用 mumu-cli 创建模拟器（如果 mumutool 路径存在）
                                    if (this.mumutoolPath) {
                                        try {
                                            let fbCreateArgs = ['create', '--count', '1', '--type', 'phone'];
                                            if (cpuCores > 0) fbCreateArgs.push('--cpu', String(cpuCores));
                                            if (memoryGb > 0) fbCreateArgs.push('--memory', String(memoryGb * 1024));
                                            const createResult = await this.execMumutool(fbCreateArgs);
                                            console.log(`[Agent] mumutool create 结果: ${JSON.stringify(createResult).substring(0, 200)}`);
                                        } catch (e) {
                                            console.warn(`[Agent] mumutool create 失败: ${e.message}`);
                                        }
                                    } else {
                                        // 最后回退：直接复制现有模拟器配置
                                        console.warn(`[Agent] 无 mumutool，无法自动创建模拟器，请手动创建或配置 mumu-cli 路径`);
                                        throw new Error('无 mumutool，无法创建模拟器。请在 config.json 中配置正确的 mumuPath（需包含 mumu-cli.exe 工具）');
                                    }
                                } else {
                                    let mumuAppPath = mumu.mumuPath || config.mumuPath || '/Applications/MuMuPlayer.app';
                                    if (!fs.existsSync(mumuAppPath)) {
                                        mumuAppPath = '/Applications/MuMuPlayer.app';
                                    }
                                    const cmd = `open "${mumuAppPath}" --args -v ${index}`;
                                    console.log(`[Agent] 执行: ${cmd}`);
                                    execSync(cmd, { timeout: 15000, shell: true });
                                }
                                // 优化：减少等待时间到 500ms
                                await new Promise(resolve => setTimeout(resolve, 500));
                                
                                // 设置名称和配置：优先使用直接编辑 vm.json，再尝试 mumu-cli
                                const vmName = 'V' + String(index + 1).padStart(3, '0');
                                try {
                                    // 优先使用直接编辑 vm.json
                                    let cfgSuccess = false;
                                    try {
                                        const vmResult = mumu.updateVmJsonConfig(index, cpuCores, memoryGb, vmName);
                                        if (vmResult.success) {
                                            console.log(`[Agent] 通过 vm.json 配置模拟器 ${vmName} 成功: cpu=${cpuCores}核, 内存=${memoryGb}GB`);
                                            cfgSuccess = true;
                                        } else {
                                            console.warn(`[Agent] vm.json 配置失败: ${vmResult.message}`);
                                        }
                                    } catch (jsonErr) {
                                        console.warn(`[Agent] vm.json 配置异常: ${jsonErr.message}`);
                                    }
                                    
                                    // vm.json 失败时回退到 mumu-cli
                                    if (!cfgSuccess && mumu.mumutoolPath) {
                                        const settingObj = {vmName: vmName, vmCpuCount: cpuCores, vmMemoryOfMB: memoryGb * 1024};
                                        const settingArgs = ['config', String(index), '--setting', JSON.stringify(settingObj)];
                                        await mumu.execMumutool(settingArgs);
                                        console.log(`[Agent] 通过 mumu-cli 配置模拟器 ${vmName} 成功: cpu=${cpuCores}核, 内存=${memoryGb}GB`);
                                    }
                                } catch (e) {
                                    console.warn(`[Agent] 设置模拟器 ${index} 失败: ${e.message}`);
                                }

                                results.push({ index, success: true, name: vmName, cpuCores, memoryGb, message: '创建命令已发送' });
                                console.log(`[Agent] 模拟器 ${vmName} 创建命令已发送`);
                            } catch (e) {
                                failCount++;
                                results.push({ index, success: false, message: e.message });
                                console.error(`[Agent] 模拟器 v${index} 创建异常:`, e.message);
                            }
                        });
                        // 等待所有并行任务完成
                        await Promise.all(createTasks);
                    }
                    

                    // 返回结果 - 包含完整的模拟器列表供后端使用
                    // 创建后获取最新模拟器列表，确保后端能获取到实际配置
                    let allEmulators = [];
                    try {
                        allEmulators = await mumu.getEmulators();
                        console.log(`[Agent] 创建后获取到 ${allEmulators.length} 个模拟器`);
                    } catch (e) {
                        console.warn(`[Agent] 创建后获取模拟器列表失败: ${e.message}`);
                    }

                    // 如果 getEmulators 返回空，但 results 中有成功的记录，则从 results 构造模拟器列表
                    if (allEmulators.length === 0 && successCount > 0) {
                        console.log(`[Agent] getEmulators 返回空，从创建结果构造模拟器列表`);
                        for (const r of results) {
                            if (r.success) {
                                const emuName = r.name || `V${String(r.index + 1).padStart(3, '0')}`;
                                const emuCpu = r.cpuCores || cpuCores || 1;
                                const emuMem = r.memoryGb || memoryGb || 1;
                                allEmulators.push({
                                    index: r.index,
                                    adbPort: 16384 + r.index * 32,
                                    status: 'STOPPED',
                                    name: emuName,
                                    cpuCount: emuCpu,
                                    memoryMB: emuMem * 1024
                                });
                            }
                        }
                        console.log(`[Agent] 从创建结果构造出 ${allEmulators.length} 个模拟器`);
                    }
                    // 将结果与完整模拟器数据合并
                    const enrichedResults = results.map(r => {
                        const fullData = allEmulators.find(e => e.index === r.index);
                        if (fullData) {
                            return {
                                ...r,
                                cpuCount: fullData.cpuCount,
                                memoryMB: fullData.memoryMB,
                                status: fullData.status,
                                adbPort: fullData.adbPort,
                                name: fullData.name
                            };
                        }
                        return r;
                    });

                    const finalStatus = failCount === 0 ? 'SUCCESS' : (successCount > 0 ? 'PARTIAL' : 'FAILED');
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: finalStatus },
                        data: {
                            success: successCount > 0,
                            message: `创建完成: 成功${successCount}个, 失败${failCount}个`,
                            successCount,
                            failCount,
                            total: neededCount,
                            results: enrichedResults,
                            emulators: allEmulators
                        }
                    });
                } catch (e) {
                    console.error(`[Agent] CREATE_EMULATOR 错误:`, e.message);
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'FAILED' },
                        data: { success: false, message: e.message }
                    });
                }
            }
            break;
            
        case 'INSTALL_APK':
            {
                const index = msg.params?.index;
                const apkPath = msg.params?.apkPath;
                
                try {
                    const port = 16384 + index * 32;
                    await mumu.connectAdb(port);
                    const adbCmd = `${mumu.adbPath} -s 127.0.0.1:${port} install -r "${apkPath}"`;
                    execSync(adbCmd, { timeout: 60000 });
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'SUCCESS' },
                        data: { success: true, message: 'APK 安装完成' }
                    });
                } catch (e) {
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'FAILED' },
                        data: { success: false, message: e.message }
                    });
                }
            }
            break;
            
        case 'LAUNCH_APP':
            {
                const index = msg.params?.index;
                const packageName = msg.params?.packageName || 'com.discord.app';
                
                try {
                    const port = 16384 + index * 32;
                    await mumu.connectAdb(port);
                    const cmd = `${mumu.adbPath} -s 127.0.0.1:${port} shell am start -n ${packageName}/.MainActivity`;
                    execSync(cmd, { timeout: 10000 });
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'SUCCESS' },
                        data: { success: true, message: '应用启动命令已发送' }
                    });
                } catch (e) {
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'FAILED' },
                        data: { success: false, message: e.message }
                    });
                }
            }
            break;
            
        case 'SCREENSHOT':
            {
                const index = msg.params?.index;
                
                try {
                    const port = 16384 + index * 32;
                    await mumu.connectAdb(port);
                    const screenshotPath = `/tmp/mumu-screenshot-${index}.png`;
                    const cmd = `${mumu.adbPath} -s 127.0.0.1:${port} exec screencap -p /sdcard/screen.png && ${mumu.adbPath} -s 127.0.0.1:${port} pull /sdcard/screen.png "${screenshotPath}"`;
                    execSync(cmd, { timeout: 15000 });
                    
                    const base64 = fs.readFileSync(screenshotPath, { encoding: 'base64' });
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'SUCCESS' },
                        data: { success: true, screenshot: base64 }
                    });
                } catch (e) {
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'FAILED' },
                        data: { success: false, message: e.message }
                    });
                }
            }
            break;
            
        case 'EXEC_ADB':
            {
                const index = msg.params?.index;
                const adbArgs = msg.params?.args || [];
                
                try {
                    if (!mumu.adbPath) {
                        send({
                            type: 'TASK_RESULT',
                            taskId: msg.taskId,
                            params: { status: 'FAILED' },
                            data: { success: false, message: 'ADB 未找到' }
                        });
                        break;
                    }
                    
                    const port = 16384 + index * 32;
                    await mumu.connectAdb(port);
                    
                    // Build ADB command - handle Windows paths with spaces
                    let cmdStr;
                    if (process.platform === 'win32') {
                        // Windows: use quotes for paths with spaces
                        cmdStr = `"${mumu.adbPath}" -s 127.0.0.1:${port} ${adbArgs.join(' ')}`;
                    } else {
                        cmdStr = [mumu.adbPath, '-s', `127.0.0.1:${port}`, ...adbArgs].join(' ');
                    }
                    console.log(`[ADB] Executing: ${cmdStr}`);
                    
                    const result = execSync(cmdStr, { 
                        encoding: 'utf8', 
                        timeout: 30000,
                        shell: process.platform === 'win32' ? 'cmd.exe' : '/bin/sh'
                    });
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'SUCCESS' },
                        data: { success: true, output: result?.trim() || '' }
                    });
                } catch (e) {
                    console.error(`[ADB] Error: ${e.message}`);
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'FAILED' },
                        data: { success: false, message: e.message, output: e.stdout?.toString() || '' }
                    });
                }
            }
            break;
            
        default:
            console.log(`[Agent] 未处理的消息类型: ${type}`);
            send({
                type: 'TASK_RESULT',
                taskId: msg.taskId,
                params: { status: 'FAILED' },
                data: { success: false, message: `未处理的命令类型: ${type}` }
            });
    }
}

// ========== 主程序入口 ==========
function main() {
    // 先尝试读取 config.json 中的版本号（即使 loadConfig 失败也能显示）
    let agentVersion = 'v2.13.6';
    try {
        const configPath = path.join(__dirname, 'config.json');
        if (fs.existsSync(configPath)) {
            const rawConfig = JSON.parse(fs.readFileSync(configPath, 'utf8'));
            if (rawConfig.version) agentVersion = rawConfig.version;
        }
    } catch (e) {
        // 忽略读取错误
    }
    
    const ts = getTimestamp();
    console.log(`[${ts}] ========================================`);
    console.log(`[${ts}]  MuMu Agent ${agentVersion}`);
    console.log(`[${ts}] ========================================`);
    if (!loadConfig()) {
        console.error('[Agent] 请创建 config.json 配置文件');
        console.error('[Agent] 模板:');
        console.error(JSON.stringify({
            userId: 'merchantadmin',
            serverUrl: 'ws://localhost:9090/ws/agent',
            mumuPath: '/Applications/MuMuPlayer.app'
        }, null, 2));
        process.exit(1);
    }
    
    console.log(`[Agent] 商户: ${config.userId}`);
    console.log(`[Agent] Device ID: ${deviceId}`);
    console.log(`[Agent] 平台: ${process.platform}`);
    
    mumu = new MuMuController();
    connect();
    
    // 优雅退出
    process.on('SIGINT', () => {
        console.log('\n[Agent] 正在关闭...');
        if (ws) ws.close();
        process.exit(0);
    });
    
    process.on('SIGTERM', () => {
        console.log('\n[Agent] 正在关闭...');
        if (ws) ws.close();
        process.exit(0);
    });
}

main();
