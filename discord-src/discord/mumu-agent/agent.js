const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
const { execSync, execFileSync, spawn } = require('child_process');
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
                serverUrl: 'ws://localhost:8090/ws/agent',
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
            serverUrl: 'ws://localhost:8090/ws/agent',
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
        // 必须使用用户配置的可执行文件完整路径
        if (!config.mumuPath) {
            console.error('[MuMu] 错误: config.json 中未配置 mumuPath');
            console.error('[MuMu] 请设置 MuMu 可执行文件的完整路径');
            console.error('[MuMu] Windows示例: C:\\Program Files\\Netease\\MuMu\\nx_main\\MuMuNxMain.exe');
            console.error('[MuMu] macOS示例: /Applications/MuMuPlayer.app');
            throw new Error('config.json 中未配置 mumuPath, 请检查配置文件');
        }
        // 检查路径是否为文件
        if (!fs.existsSync(config.mumuPath)) {
            console.error('[MuMu] 错误: mumuPath 路径不存在:', config.mumuPath);
            throw new Error('mumuPath 路径不存在: ' + config.mumuPath);
        }
        this.mumuAppPath = config.mumuPath;

        try {
            const stat = fs.statSync(config.mumuPath);
            if (stat.isDirectory()) {
                if (config.mumuPath.endsWith('.app')) {
                    const appName = path.basename(config.mumuPath, '.app');
                    const macOsDir = path.join(config.mumuPath, 'Contents', 'MacOS');
                    if (fs.existsSync(macOsDir) && fs.statSync(macOsDir).isDirectory()) {
                        const files = fs.readdirSync(macOsDir);
                        const candidates = [
                            appName,
                            'mumutool',
                            'mumu-cli',
                        ];
                        let resolved = null;
                        for (const candidate of candidates) {
                            const candidatePath = path.join(macOsDir, candidate);
                            if (fs.existsSync(candidatePath) && fs.statSync(candidatePath).isFile()) {
                                resolved = candidatePath;
                                break;
                            }
                        }
                        if (!resolved) {
                            for (const file of files) {
                                const filePath = path.join(macOsDir, file);
                                try {
                                    if (fs.statSync(filePath).isFile() && (file === appName || file.toLowerCase().includes('mumu'))) {
                                        resolved = filePath;
                                        break;
                                    }
                                } catch (_) {}
                            }
                        }
                        if (resolved) {
                            console.log('[MuMu] 检测到 macOS .app bundle, 解析可执行文件:', resolved);
                            this.mumuPath = resolved;
                        } else {
                            console.error('[MuMu] 错误: 在 .app bundle 的 Contents/MacOS/ 中未找到可执行文件');
                            throw new Error('mumuPath 是 .app bundle, 但未找到可执行文件');
                        }
                    } else {
                        console.error('[MuMu] 错误: .app bundle 中未找到 Contents/MacOS/ 目录');
                        throw new Error('mumuPath 是 .app bundle, 但结构无效');
                    }
                } else {
                    console.error('[MuMu] 错误: mumuPath 是目录而非文件:', config.mumuPath);
                    console.error('[MuMu] 请设置可执行文件的完整路径, 如: C:\\...\\MuMuNxMain.exe');
                    throw new Error('mumuPath 是目录, 请设置可执行文件完整路径');
                }
            } else {
                this.mumuPath = config.mumuPath;
            }
        } catch (e) {
            if (e.message.includes('目录') || e.message.includes('.app')) {
                throw e;
            }
            console.error('[MuMu] 错误: 无法访问 mumuPath:', e.message);
            throw new Error('mumuPath 无效: ' + e.message);
        }
        console.log('[MuMu] 使用配置中的 mumuPath:', this.mumuPath);
        
        if (!config.adbPath) {
            console.error('[MuMu] 错误: config.json 中未配置 adbPath');
            console.error('[MuMu] 请设置 adb.exe 的完整路径');
            throw new Error('config.json 中未配置 adbPath, 请检查配置文件');
        }
        if (!fs.existsSync(config.adbPath)) {
            console.error('[MuMu] 错误: adbPath 路径不存在:', config.adbPath);
            throw new Error('adbPath 路径不存在: ' + config.adbPath);
        }
        console.log('[MuMu] 使用配置中的 adbPath:', config.adbPath);
        this.adbPath = config.adbPath;
        
        this.mumutoolPath = this.findMumutoolPath();
        
        // 初始化 vmsBasePath - 用于定位模拟器 vm.json 文件
        this.vmsBasePath = this.findVmsBasePath();
        console.log('[MuMu] ADB 路径:', this.adbPath);
        console.log('[MuMu] MuMu 路径:', this.mumuPath);
        console.log('[MuMu] mumutool 路径:', this.mumutoolPath || '未找到');
        
        // 诊断：扫描 MuMu 安装目录
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
        
        // 获取 MuMu 安装根目录（mumuPath 可能是文件路径，需要取目录）
        let mumuBasePath = this.mumuPath;
        if (mumuBasePath && fs.existsSync(mumuBasePath) && fs.statSync(mumuBasePath).isFile()) {
            mumuBasePath = path.dirname(mumuBasePath);
        }
        
        if (os === 'darwin') {
            const mumuBase = this.mumuAppPath || this.mumuPath;
            candidates.push(`${mumuBase}/Contents/MacOS/mumutool`);
            candidates.push(`${mumuBase}/Contents/MacOS/mumu-cli`);
            candidates.push(`${mumuBase}/Contents/MacOS/`);
        } else if (os === 'win32') {
            // Windows 下尝试多个可能的路径（基于 MuMu 安装根目录）
            if (mumuBasePath) {
                const mumuBaseDir = fs.existsSync(mumuBasePath) && fs.statSync(mumuBasePath).isFile() 
                    ? path.dirname(mumuBasePath) 
                    : mumuBasePath;
                    
                // MuMu 新版使用 mumu-cli.exe 作为命令行工具
                candidates.push(path.join(mumuBaseDir, 'mumu-cli.exe'));
                candidates.push(path.join(mumuBaseDir, 'shell', 'mumu-cli.exe'));
                // 旧版使用 mumutool.exe
                candidates.push(path.join(mumuBaseDir, 'mumutool.exe'));
                candidates.push(path.join(mumuBaseDir, 'shell', 'mumutool.exe'));
                candidates.push(mumuBaseDir); // 整个目录
                
                // 扫描 mumuBaseDir 及子目录查找工具
                try {
                    // 先扫描主目录
                    if (fs.existsSync(mumuBaseDir) && fs.statSync(mumuBaseDir).isDirectory()) {
                        const files = fs.readdirSync(mumuBaseDir);
                        console.log(`[MuMu] MuMu主目录内容: ${files.join(', ')}`);
                        for (const file of files) {
                            const lowerFile = file.toLowerCase();
                            // 匹配 mumutool 或 mumu-cli（注意：mumu-cli 不包含 "mumutool"）
                            if (lowerFile.includes('mumutool') || lowerFile.includes('mumu-cli') || lowerFile === 'mumu-cli.exe') {
                                const fullPath = path.join(mumuBaseDir, file);
                                candidates.push(fullPath);
                                console.log(`[MuMu] 发现MuMu工具: ${fullPath}`);
                            }
                        }
                        // 检查 shell 子目录
                        const shellDir = path.join(mumuBaseDir, 'shell');
                        if (fs.existsSync(shellDir) && fs.statSync(shellDir).isDirectory()) {
                            const shellFiles = fs.readdirSync(shellDir);
                            console.log(`[MuMu] shell 目录内容: ${shellFiles.join(', ')}`);
                            for (const file of shellFiles) {
                                const lowerFile = file.toLowerCase();
                                if (lowerFile.includes('mumutool') || lowerFile.includes('mumu-cli') || lowerFile === 'adb.exe') {
                                    const fullPath = path.join(shellDir, file);
                                    candidates.push(fullPath);
                                    console.log(`[MuMu] 发现shell工具: ${fullPath}`);
                                }
                            }
                        }
                    }
                } catch (e) {
                    console.warn(`[MuMu] 扫描MuMu目录失败: ${e.message}`);
                }
            }
            
            // 不再使用硬编码路径，只使用用户配置的路径
            // 如果用户配置的路径找不到 mumu-cli，将无法使用 mumutool 模式
        }
        
        for (const p of candidates) {
            try {
                if (p && fs.existsSync(p)) {
                    console.log(`[MuMu] 检查路径: ${p}`);
                    // 如果是目录，尝试在目录中查找 mumutool
                    if (fs.statSync(p).isDirectory()) {
                        const files = fs.readdirSync(p);
                        const tool = files.find(f => f.toLowerCase().includes('mumutool') || f.toLowerCase().includes('mumu-cli'));
                        if (tool) {
                            const fullPath = path.join(p, tool);
                            console.log(`[MuMu] 在目录中找到 MuMu 工具: ${fullPath}`);
                            return fullPath;
                        }
                    } else {
                        console.log(`[MuMu] 找到 MuMu 工具: ${p}`);
                        return p;
                    }
                }
            } catch (e) {
                // 忽略错误
            }
        }
        
        console.warn(`[MuMu] 未找到 mumutool, 将使用系统命令作为后备方案`);
        console.warn(`[MuMu] 请检查 MuMu 安装目录下是否有 mumutool 或 mumu-cli 工具`);
        console.warn(`[MuMu] 尝试过的路径: ${candidates.slice(0, 5).join(', ')}`);
        return null;
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
        
        const isWindows = process.platform === 'win32';
        const toolPath = this.mumutoolPath;
        
        if (!fs.existsSync(toolPath)) {
            throw new Error(`mumu-cli 不存在: ${toolPath}`);
        }
        
        try {
            let result;
            if (isWindows) {
                // Windows: 使用 cmd.exe /c 执行，彻底解决路径空格问题
                // cmd.exe /c 需要整个命令作为一个带引号的字符串
                const allArgs = [toolPath, ...args];
                const cmdStr = allArgs.map((a, idx) => {
                    // 第一个参数是可执行文件路径，始终加引号
                    if (idx === 0) return '"' + a + '"';
                    // 其他参数如果包含空格或特殊字符也加引号
                    if (a.includes(' ') || a.includes('"') || a.includes('\t')) {
                        return '"' + a.replace(/"/g, '\\"') + '"';
                    }
                    return a;
                }).join(' ');
                const cmdLine = 'cmd.exe /c "' + cmdStr + '"';
                console.log(`[MuMu] CMD: ${cmdLine}`);
                result = execSync(cmdLine, { 
                    timeout: timeout, 
                    encoding: 'utf8', 
                    stdio: ['pipe', 'pipe', 'pipe'],
                    cwd: path.dirname(toolPath)
                });
            } else {
                result = execFileSync(toolPath, args, { 
                    timeout: timeout, 
                    encoding: 'utf8', 
                    stdio: ['pipe', 'pipe', 'pipe']
                });
            }
            
            const output = result.trim();
            console.log(`[MuMu] ${command} 返回: ${output.substring(0, 200)}`);
            
            try {
                return JSON.parse(output);
            } catch {
                return { errcode: 0, message: output, return: null };
            }
        } catch (e) {
            const errorOutput = (e.stderr ? e.stderr.toString() : e.stdout ? e.stdout.toString() : e.message).trim();
            console.error(`[MuMu] ${command} 执行错误: ${errorOutput}`);
            
            try {
                const jsonMatch = errorOutput.match(/\{[\s\S]*\}/);
                if (jsonMatch) {
                    return JSON.parse(jsonMatch[0]);
                }
            } catch {}
            
            return { errcode: -1, message: errorOutput || e.message };
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
                    const index = parseInt(dirName);
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
                                if (contents.length > 0) {
                                    const hasMumuFiles = contents.some(f => 
                                        f.startsWith('vm_') || 
                                        f.endsWith('.img') || 
                                        f === 'setting' ||
                                        f === 'config' ||
                                        f.includes('data')
                                    );
                                    if (hasMumuFiles || contents.length >= 2) {
                                        indices.push(index);
                                        console.log(`[MuMu] 找到模拟器 ${index}: 目录有 ${contents.length} 个内容 (无vm.json)`);
                                    }
                                }
                            } catch (e) {
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
                console.log('[MuMu] Windows平台: 使用文件扫描方式获取模拟器列表');
                const indices = this.scanVmJsonFiles();
                console.log(`[MuMu] 扫描到 ${indices.length} 个模拟器索引: ${indices.join(',')}`);
                
                for (const index of indices) {
                    try {
                        const vmConfig = this.readVmConfig(index);
                        const emulator = this.buildEmulatorFromVmConfig(index, vmConfig);
                        if (emulator) {
                            emulators.push(emulator);
                        }
                    } catch (e) {
                        console.warn(`[MuMu] 获取模拟器 ${index} 详情失败: ${e.message}`);
                    }
                }
                
                if (emulators.length === 0 && this.mumutoolPath) {
                    console.log('[MuMu] 文件扫描无结果，尝试 mumu-cli 命令...');
                    try {
                        const result = await this.execMumutool(['shell'], 3000);
                        console.log(`[MuMu] shell 命令返回: errcode=${result.errcode}`);
                    } catch (e) {
                        console.warn(`[MuMu] mumu-cli 命令失败: ${e.message}`);
                    }
                    
                    if (emulators.length === 0) {
                        console.log('[MuMu] 尝试遍历 0-31 索引...');
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
                            }
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
                
                // 搜索所有目录
                for (const vmsDir of allVmsDirs) {
                    const vmCandidate = path.join(vmsDir, String(index));
                    if (fs.existsSync(vmCandidate)) {
                        vmDir = vmCandidate;
                        break;
                    }
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
                                vmName: config.vmName || ''
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
                        vmName: `V${String(index + 1).padStart(3, '0')}`
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
                // Windows: 查找 MuMuPlayer.exe 并启动
                const exePath = path.join(this.mumuPath, 'MuMuPlayer.exe');
                if (fs.existsSync(exePath)) {
                    try { execFileSync(exePath, ['-v', String(index)], { timeout: 5000, shell: true }); } catch(e) { execSync(`start "" "${exePath}" -v ${index}`, { timeout: 5000, shell: true }); }
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    const port = 16384 + index * 32;
                    await this.connectAdb(port);
                    return { success: true, message: '启动命令已发送' };
                }
                return { success: false, message: '未找到 MuMuPlayer.exe' };
            }
            return { success: false, message: '需要手动启动模拟器' };
        } catch (e) {
            return { success: false, message: e.message };
        }
    }

    async stopEmulator(index) {
        try {
            if (this.mumutoolPath) {
                const result = await this.execMumutool(['close', String(index)]);
                if (result.errcode === 0) {
                    return { success: true, message: '关闭命令已发送' };
                } else {
                    return { success: false, message: result.message || '关闭失败' };
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
            if (!this.mumutoolPath) {
                return { success: false, message: '未找到 mumutool，无法应用配置' };
            }
            
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
            
            const result = await this.execMumutool([
                'config', String(index),
                '--setting', JSON.stringify(setting)
            ]);
            
            if (result.errcode === 0) {
                console.log(`[MuMu] 模拟器${index} 已应用配置: cpu=${cpuCores}核, mem=${memoryGb}GB, name=${vmName}`);
                return { success: true, message: '配置应用成功' };
            } else {
                console.warn(`[MuMu] 模拟器${index} 配置应用失败:`, result.message);
                return { success: false, message: result.message || '配置应用失败' };
            }
        } catch (e) {
            console.warn(`[MuMu] 模拟器${index} 配置应用异常:`, e.message);
            return { success: false, message: e.message };
        }
    }

    async deleteEmulator(index) {
        try {
            // 先尝试停止模拟器再删除
            try {
                await this.stopEmulator(index);
                await new Promise(resolve => setTimeout(resolve, 1500));
            } catch (e) {
                console.warn(`[MuMu] 删除前停止模拟器${index}失败: ${e.message}`);
            }

            if (this.mumutoolPath) {
                const result = await this.execMumutool(['delete', String(index)]);
                if (result.errcode === 0) {
                    return { success: true, message: '删除成功' };
                } else {
                    console.warn(`[MuMu] mumutool 删除模拟器失败: ${result.message}, 尝试手动删除`);
                }
            }

            // Fallback: 手动删除模拟器数据目录
            const deleteResult = await this.deleteEmulatorData(index);
            if (deleteResult.success) {
                return { success: true, message: '删除成功(手动清理)' };
            }
            return deleteResult;
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
                    path.join(this.mumuPath, 'vms', String(index)),
                    path.join(process.env.PUBLIC || 'C:\\Users\\Public', 'Documents', 'MuMu', 'vms', `v${index}`)
                ];
                for (const p of candidates) {
                    if (fs.existsSync(p)) { vmDir = p; break; }
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
    const serverUrl = config.serverUrl || 'ws://localhost:8090/ws/agent';
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
                    
                    if (mumu.mumutoolPath) {
                        // 使用 mumutool create --count 命令创建模拟器
                        const createArgs = ['create', '--count', String(neededCount), '--type', 'phone'];
                        
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
                        
                        // 等待文件系统就绪
                        await new Promise(r => setTimeout(r, 200));
                        
                        // 使用解析出的索引进行配置
                        const configTasks = createdIndices.map(async (index) => {
                            const vmName = 'V' + String(index + 1).padStart(3, '0');
                            console.log('[Agent] 为模拟器 index=' + index + ' 配置: 名称=' + vmName + ', CPU=' + cpuCores + '核, 内存=' + memoryGb + 'GB');
                            
                            // 使用 --setting 参数一次性配置
                            const settingObj = { vmName: vmName };
                            if (cpuCores > 0) settingObj.vmCpuCount = cpuCores;
                            if (memoryGb > 0) settingObj.vmMemoryOfMB = memoryGb * 1024;
                            const settingJson = JSON.stringify(settingObj);
                            
                            try {
                                const cfgResult = await mumu.execMumutool(['config', String(index), '--setting', settingJson]);
                                if (cfgResult.errcode === 0) {
                                    console.log('[Agent] 配置模拟器 ' + vmName + ' 成功: cpu=' + cpuCores + '核, 内存=' + memoryGb + 'GB');
                                } else {
                                    console.warn('[Agent] 配置模拟器失败: ' + cfgResult.message);
                                    // 回退：逐字段配置
                                    try {
                                        if (cpuCores > 0) {
                                            await mumu.execMumutool(['config', String(index), 'vmCpuCount', String(cpuCores)]);
                                        }
                                        if (memoryGb > 0) {
                                            await mumu.execMumutool(['config', String(index), 'vmMemoryOfMB', String(memoryGb * 1024)]);
                                        }
                                        await mumu.execMumutool(['config', String(index), 'vmName', vmName]);
                                        console.log('[Agent] 回退配置成功: ' + vmName);
                                    } catch (fallbackErr) {
                                        console.warn('[Agent] 回退配置也失败: ' + fallbackErr.message);
                                    }
                                }
                            } catch (e) {
                                console.warn('[Agent] 配置模拟器异常: ' + e.message);
                            }
                            
                            // 验证配置（仅日志，不影响结果）
                            try {
                                const verifyResult = await mumu.execMumutool(['info', String(index)]);
                                console.log('[Agent] 验证模拟器 index=' + index + ': ' + JSON.stringify(verifyResult).substring(0, 200));
                            } catch (e) {
                                console.warn('[Agent] 验证模拟器失败: ' + e.message);
                            }
                            
                            return { index, success: true, name: vmName, cpuCores, memoryGb };
                        });
                        // 并行执行所有配置任务
                        const configResults = await Promise.allSettled(configTasks);
                        
                        // 收集结果
                        for (const configResult of configResults) {
                            if (configResult.status === 'fulfilled') {
                                const { index, name } = configResult.value;
                                successCount++;
                                results.push({ index, success: true, message: '创建成功', name });
                                console.log(`[Agent] 模拟器 index=${index} 创建完成: ${name}`);
                            } else {
                                failCount++;
                                console.warn(`[Agent] 模拟器创建失败: ${configResult.reason}`);
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
                                            // 使用 mumutool create 创建单个模拟器
                                            const createResult = await this.execMumutool(['create', '--count', '1', '--type', 'phone']);
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
                                
                                // 设置名称和配置（即使没有 mumutool 也尝试通过直接编辑 vm.json 配置）
                                const vmName = 'V' + String(index + 1).padStart(3, '0');
                                try {
                                    const settingObj = {vmName: vmName, vmCpuCount: cpuCores, vmMemoryOfMB: memoryGb * 1024};
                                    if (mumu.mumutoolPath) {
                                        const settingArgs = ['config', String(index), '--setting', JSON.stringify(settingObj)];
                                        await mumu.execMumutool(settingArgs);
                                        console.log(`[Agent] 模拟器 ${vmName} 配置成功: cpu=${cpuCores}核, 内存=${memoryGb}GB`);
                                    } else {
                                        // 回退：直接编辑 vm.json 文件
                                        try {
                                            const vmJsonPath = path.join(mumu.vmsBasePath || '', String(index), 'setting', 'vm.json');
                                            if (fs.existsSync(vmJsonPath)) {
                                                let vmConfig = JSON.parse(fs.readFileSync(vmJsonPath, 'utf8'));
                                                if (cpuCores > 0) vmConfig.vmCpuCount = cpuCores;
                                                if (memoryGb > 0) vmConfig.vmMemoryOfMB = memoryGb * 1024;
                                                vmConfig.vmName = vmName;
                                                fs.writeFileSync(vmJsonPath, JSON.stringify(vmConfig, null, 2), 'utf8');
                                                console.log(`[Agent] 通过编辑 vm.json 配置模拟器 ${vmName} 成功`);
                                            } else {
                                                console.warn(`[Agent] vm.json 不存在，跳过配置: ${vmJsonPath}`);
                                            }
                                        } catch (jsonErr) {
                                            console.warn(`[Agent] 编辑 vm.json 失败: ${jsonErr.message}`);
                                        }
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
    let agentVersion = 'v2.7.0';
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
            serverUrl: 'ws://localhost:8090/ws/agent',
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
