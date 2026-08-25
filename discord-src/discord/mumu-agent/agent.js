const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
const { execSync, execFileSync, spawn } = require('child_process');
const NL = String.fromCharCode(10);

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
        // 如果配置中没有 mumuPath，则查找
        if (config.mumuPath) {
            console.log('[MuMu] 使用配置中的 mumuPath:', config.mumuPath);
            this.mumuPath = config.mumuPath;
        } else {
            console.warn('[MuMu] 配置中未设置 mumuPath, 自动查找...');
            this.mumuPath = this.findMuMuPath();
        }
        
        // 如果配置中没有 adbPath，则查找
        if (config.adbPath) {
            console.log('[MuMu] 使用配置中的 adbPath:', config.adbPath);
            this.adbPath = config.adbPath;
        } else {
            console.warn('[MuMu] 配置中未设置 adbPath, 自动查找...');
            this.adbPath = this.findAdbPath();
        }
        
        this.mumutoolPath = this.findMumutoolPath();
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
            candidates.push(`${this.mumuPath}/Contents/MacOS/mumutool`);
            candidates.push(`${this.mumuPath}/Contents/MacOS/mumu-cli`);
            candidates.push(`${this.mumuPath}/Contents/MacOS/`);
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
            
            // 备选路径（扫描整个 MuMu 安装目录）
            const altPaths = [
                'C:\Program Files\Netease\MuMu\nx_main',
                'C:\Program Files\Netease\MuMu',
                'C:\Program Files (x86)\Netease\MuMu\nx_main',
                'C:\Program Files\Netease\MuMuPlayer-12.0',
                'C:\Program Files (x86)\Netease\MuMuPlayer-12.0',
            ];
            for (const altPath of altPaths) {
                candidates.push(path.join(altPath, 'shell', 'mumutool.exe'));
                candidates.push(path.join(altPath, 'shell', 'mumu-cli.exe'));
                candidates.push(path.join(altPath, 'mumutool.exe'));
                candidates.push(path.join(altPath, 'mumu-cli.exe'));
                try {
                    if (fs.existsSync(altPath) && fs.statSync(altPath).isDirectory()) {
                        const files = fs.readdirSync(altPath);
                        for (const file of files) {
                            const lowerFile = file.toLowerCase();
                            if (lowerFile.includes('mumutool') || lowerFile.includes('mumu-cli') || lowerFile === 'mumu-cli.exe') {
                                candidates.push(path.join(altPath, file));
                            }
                        }
                        // 检查 shell 子目录
                        const shellDir = path.join(altPath, 'shell');
                        if (fs.existsSync(shellDir) && fs.statSync(shellDir).isDirectory()) {
                            const shellFiles = fs.readdirSync(shellDir);
                            for (const file of shellFiles) {
                                const lowerFile = file.toLowerCase();
                                if (lowerFile.includes('mumutool') || lowerFile.includes('mumu-cli') || lowerFile === 'mumu-cli.exe') {
                                    candidates.push(path.join(shellDir, file));
                                }
                            }
                        }
                    }
                } catch (e) {}
            }
        }
        
        for (const p of candidates) {
            try {
                if (p && fs.existsSync(p)) {
                    console.log(`[MuMu] 检查路径: ${p}`);
                    // 如果是目录，尝试在目录中查找 mumutool
                    if (fs.statSync(p).isDirectory()) {
                        const files = fs.readdirSync(p);
                        const tool = files.find(f => f.toLowerCase().includes('mumutool') || f.toLowerCase() === 'mumu-cli');
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
        const cmd = `"${this.adbPath}" ${args.join(' ')}`;
        try {
            const result = execSync(cmd, { timeout, encoding: 'utf8' });
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
        try {
            // 使用 execFileSync 传递数组参数，避免 shell 吃掉 JSON 中的引号
            const result = execFileSync(this.mumutoolPath, args, { 
                timeout: timeout, 
                encoding: 'utf8', 
                stdio: ['pipe', 'pipe', 'pipe'],
                shell: process.platform === 'win32'  // Windows 需要 shell 模式
            });
            console.log(`[MuMu] ${command} 返回: ${result.trim().substring(0, 200)}`);
            try {
                return JSON.parse(result);
            } catch {
                return { errcode: 0, message: result.trim(), return: null };
            }
        } catch (e) {
            const errorMsg = e.stderr ? e.stderr.toString().trim() : e.message;
            console.error(`[MuMu] ${command} 执行错误: ${errorMsg}`);
            try {
                if (errorMsg) {
                    return JSON.parse(errorMsg);
                }
            } catch {}
            return { errcode: -1, message: errorMsg };
        }
    }

    async connectAdb(port) {
        if (!this.adbPath) return false;
        try {
            execSync(`"${this.adbPath}" connect 127.0.0.1:${port}`, { timeout: 5000 });
            return true;
        } catch {
            return false;
        }
    }

    async getEmulators() {
        const emulators = [];
        try {
            if (this.mumutoolPath) {
                console.log(`[MuMu] 使用 mumutool 获取模拟器列表`);
                const result = await this.execMumutool(['info', 'all']);
                if (result.errcode === 0 && result.return) {
                    for (const item of result.return.results) {
                        const index = item.index;
                        let status = 'STOPPED';
                        if (item.state === 'running') {
                            status = 'RUNNING';
                            if (item.adb_port) {
                                await this.connectAdb(item.adb_port);
                            }
                        }
                        // 读取 vm.json 获取 CPU/内存配置
                        let cpuCount = 1;
                        let memoryMB = 1024;
                        try {
                            const vmConfig = this.readVmConfig(index);
                            if (vmConfig) {
                                if (vmConfig.vmCpuCount > 0) cpuCount = vmConfig.vmCpuCount;
                                if (vmConfig.vmMemoryOfMB > 0) memoryMB = vmConfig.vmMemoryOfMB;
                            }
                        } catch (e) {
                            // 忽略读取错误，使用默认值
                        }
                        emulators.push({
                            index,
                            adbPort: item.adb_port || (16384 + index * 32),
                            status,
                            name: item.name || `V${String(index + 1).padStart(3, '0')}`,
                            cpuCount,
                            memoryMB
                        });
                    }
                    return emulators;
                }
            }

            // Fallback: 通过 ADB 检测
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
        
        // 最终后备方案：扫描 vms 目录下的 vm.json 文件
        if (emulators.length === 0) {
            try {
                console.log('[MuMu] 尝试通过扫描 vm.json 文件获取模拟器列表...');
                const os = process.platform;
                let vmsDirs = [];
                
                if (os === 'darwin') {
                    const homeDir = process.env.HOME || require('os').homedir();
                    vmsDirs = [
                        path.join(homeDir, 'Library', 'Application Support', 'com.netease.mumu.nemux', 'vms'),
                        path.join(homeDir, 'Library', 'Application Support', 'Netease', 'MuMuPlayer-12.0', 'vms'),
                    ];
                } else if (os === 'win32') {
                    let mumuBasePath = this.mumuPath || '';
                    if (mumuBasePath && fs.existsSync(mumuBasePath) && fs.statSync(mumuBasePath).isFile()) {
                        mumuBasePath = path.dirname(mumuBasePath);
                    }
                    vmsDirs = [
                        path.join(mumuBasePath, 'vms'),
                        'C:\Program Files\Netease\MuMu\vms',
                        'C:\Program Files\Netease\MuMuPlayer-12.0\vms',
                    ];
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
                            try {
                                const vmJsonPath = path.join(vmsDir, dirName, 'vm.json');
                                if (fs.existsSync(vmJsonPath)) {
                                    const vmConfig = JSON.parse(fs.readFileSync(vmJsonPath, 'utf8'));
                                    const index = parseInt(dirName);
                                    if (!isNaN(index) && !emulators.find(e => e.index === index)) {
                                        emulators.push({
                                            index,
                                            adbPort: 16384 + index * 32,
                                            status: 'STOPPED',
                                            name: vmConfig.vmName || `V${String(index + 1).padStart(3, '0')}`,
                                            cpuCount: vmConfig.vmCpuCount || 1,
                                            memoryMB: vmConfig.vmMemoryOfMB || 1024
                                        });
                                    }
                                }
                            } catch (e) {
                                // 忽略单个文件读取错误
                            }
                        }
                        if (emulators.length > 0) break; // 找到就不再搜索其他目录
                    }
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
                        const checkResult = execSync(`"${this.mumutoolPath}" info all`, { 
                            encoding: 'utf8', timeout: 5000 
                        });
                        const parsed = JSON.parse(checkResult);
                        if (parsed.errcode === 0 && parsed.return && parsed.return.results && parsed.return.results.length > 0) {
                            console.log('[MuMu] 通过mumutool检测到模拟器运行中');
                            return true;
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
                // 获取 MuMu 安装根目录（mumuPath 可能是文件路径）
                let mumuBasePath = this.mumuPath || '';
                if (mumuBasePath && fs.existsSync(mumuBasePath) && fs.statSync(mumuBasePath).isFile()) {
                    mumuBasePath = path.dirname(mumuBasePath);
                }
                vmDir = path.join(mumuBasePath, 'vms', String(index));
                // 如果默认路径不存在，尝试其他常见路径
                if (!fs.existsSync(vmDir)) {
                    const altPaths = [
                        path.join(mumuBasePath, 'vms'),
                        path.join(mumuBasePath, 'data', 'vms'),
                        'C:\Program Files\Netease\MuMuPlayer-12.0\vms',
                    ];
                    for (const altVms of altPaths) {
                        const vmCandidate = path.join(altVms, String(index));
                        if (fs.existsSync(vmCandidate)) {
                            vmDir = vmCandidate;
                            break;
                        }
                    }
                }
            }

            if (vmDir) {
                const configFile = path.join(vmDir, 'setting', 'vm.json');
                if (fs.existsSync(configFile)) {
                    const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
                    return {
                        vmCpuCount: config.vmCpuCount || 0,
                        vmMemoryOfMB: config.vmMemoryOfMB || 0,
                        vmName: config.vmName || ''
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
                    execSync(`start "" "${exePath}" -v ${index}`, { timeout: 5000, shell: true });
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
const mumu = new MuMuController();

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
        if (msg.type === 'TASK_RESULT') {
            console.log(`[Agent] 发送任务结果: type=${msg.type}, status=${msg.params?.status}, taskId=${msg.taskId}`);
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
                        // 使用 mumutool create --count 命令（count 必须 >= 2）
                        // 如果只需要创建1个，先创建2个，然后删除1个
                        let createCount = neededCount;
                        let needCleanup = false;
                        
                        if (createCount === 1) {
                            createCount = 2;
                            needCleanup = true;
                        }
                        
                        // 注意：mumutool create 的 --setting 不支持 JSON，先创建再配置
                        const createArgs = ['create', '--count', String(createCount), '--type', 'phone'];
                        
                        console.log(`[Agent] 使用 mumutool ${createArgs.join(' ')} 创建模拟器`);
                        const result = await mumu.execMumutool(createArgs);
                        console.log(`[Agent] mumutool create 结果: errcode=${result.errcode}, message=${result.message}`);
                        
                        if (result.errcode === 0 && result.return) {
                            const createdResults = result.return.results || [];
                            
                            // 如果多创建了，删除多余的
                            if (needCleanup && createdResults.length > 1) {
                                const lastIndex = createdResults[createdResults.length - 1].index;
                                console.log(`[Agent] 删除多余的模拟器 index=${lastIndex}`);
                                try {
                                    await mumu.execMumutool(['delete', String(lastIndex)]);
                                    console.log(`[Agent] 已删除多余的模拟器`);
                                } catch (delErr) {
                                    console.warn(`[Agent] 删除模拟器失败: ${delErr.message}`);
                                }
                                createdResults.pop();
                            }
                            
                            // 等待模拟器文件系统就绪
                            await new Promise(r => setTimeout(r, 1500));
                            
                            for (const item of createdResults) {
                                const vmName = 'V' + String(item.index + 1).padStart(3, '0');
                                // 一次性应用所有配置：名称 + CPU + 内存
                                const settingObj = { vmName: vmName };
                                if (cpuCores > 0) settingObj.vmCpuCount = cpuCores;
                                if (memoryGb > 0) settingObj.vmMemoryOfMB = memoryGb * 1024;
                                const settingStr = JSON.stringify(settingObj);
                                
                                try {
                                    const cfgResult = await mumu.execMumutool(['config', String(item.index), '--setting', settingStr]);
                                    if (cfgResult.errcode === 0) {
                                        console.log(`[Agent] 模拟器 index=${item.index} 配置成功: ${vmName}, ${cpuCores}核/${memoryGb}GB`);
                                    } else {
                                        console.warn(`[Agent] 模拟器 index=${item.index} 配置失败: ${cfgResult.message}`);
                                        // 重试一次
                                        await new Promise(r2 => setTimeout(r2, 800));
                                        const retryResult = await mumu.execMumutool(['config', String(item.index), '--setting', settingStr]);
                                        if (retryResult.errcode !== 0) {
                                            console.warn(`[Agent] 模拟器 index=${item.index} 重试配置仍失败: ${retryResult.message}`);
                                        }
                                    }
                                } catch (e) {
                                    console.warn(`[Agent] 模拟器 index=${item.index} 配置异常: ${e.message}`);
                                }
                                
                                successCount++;
                                results.push({ index: item.index, success: true, message: '创建成功', name: vmName });
                                console.log(`[Agent] 模拟器 index=${item.index} 创建完成: ${vmName}`);
                            }
                        } else {
                            failCount = neededCount;
                            results.push({ index: -1, success: false, message: result.message || '创建失败' });
                            console.log(`[Agent] 模拟器创建失败: ${result.message}`);
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
                        
                        for (const index of neededIndices) {
                            try {
                                let cmd;
                                if (os === 'win32') {
                                    // Windows: 使用 start 命令启动 MuMu
                                    // 优先使用 MuMuNxMain.exe，然后使用 MuMuManager.exe
                                    let mumuExe = mumu.mumuPath || config.mumuPath || '';
                                    
                                    // 如果 mumuPath 指向的目录中没有可执行文件，尝试查找
                                    if (!mumuExe || !fs.existsSync(mumuExe)) {
                                        console.warn(`[Agent] mumuExe 无效: ${mumuExe}, 尝试查找...`);
                                        // 尝试在常见位置查找
                                        const candidates = [
                                            'C:\Program Files\Netease\MuMu\nx_main\MuMuNxMain.exe',
                                            'C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe',
                                            'C:\Program Files (x86)\Netease\MuMu\nx_main\MuMuNxMain.exe',
                                        ];
                                        for (const c of candidates) {
                                            if (fs.existsSync(c)) {
                                                mumuExe = c;
                                                console.log(`[Agent] 找到 MuMu 可执行文件: ${mumuExe}`);
                                                break;
                                            }
                                        }
                                    }
                                    
                                    if (!mumuExe || !fs.existsSync(mumuExe)) {
                                        throw new Error(`未找到 MuMu 可执行文件, 请检查安装路径`);
                                    }
                                    
                                    console.log(`[Agent] 使用 MuMu 可执行文件: ${mumuExe}`);
                                    cmd = `start "" "${mumuExe}" --args -v ${index}`;
                                } else {
                                    // macOS: 使用 open 命令
                                    let mumuAppPath = mumu.mumuPath || config.mumuPath || '/Applications/MuMuPlayer.app';
                                    if (!fs.existsSync(mumuAppPath)) {
                                        mumuAppPath = '/Applications/MuMuPlayer.app';
                                    }
                                    cmd = `open "${mumuAppPath}" --args -v ${index}`;
                                }
                                console.log(`[Agent] 执行: ${cmd}`);
                                execSync(cmd, { timeout: 15000, shell: true });
                                await new Promise(resolve => setTimeout(resolve, 3000));
                                
                                // 设置名称和配置
                                const vmName = 'V' + String(index + 1).padStart(3, '0');
                                try {
                                    const settingArgs = ['config', String(index), '--setting', JSON.stringify({vmName: vmName, vmCpuCount: cpuCores, vmMemoryOfMB: memoryGb * 1024})];
                                    if (mumu.mumutoolPath) {
                                        await mumu.execMumutool(settingArgs);
                                    } else {
                                        console.warn(`[Agent] 跳过设置: mumutool 不可用`);
                                    }
                                } catch (e) {
                                    console.warn(`[Agent] 设置模拟器 ${index} 失败: ${e.message}`);
                                }
                                successCount++;
                                results.push({ index, success: true, message: '创建命令已发送' });
                                console.log(`[Agent] 模拟器 ${vmName} 创建命令已发送`);
                            } catch (e) {
                                failCount++;
                                results.push({ index, success: false, message: e.message });
                                console.error(`[Agent] 模拟器 v${index} 创建异常:`, e.message);
                            }
                        }
                    }
                    
                    // 返回结果
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: failCount === 0 ? 'SUCCESS' : (successCount > 0 ? 'PARTIAL' : 'FAILED') },
                        data: { 
                            success: successCount > 0, 
                            message: `创建完成: 成功${successCount}个, 失败${failCount}个`,
                            successCount,
                            failCount,
                            total: neededCount,
                            results
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
    console.log('========================================');
    console.log('  MuMu Agent v2.0.0 (mumutool 增强版)');
    console.log('========================================');
    
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
