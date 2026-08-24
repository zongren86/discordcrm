const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');

// ========== 配置加载 ==========
const CONFIG_PATH = path.join(__dirname, 'config.json');
let config = {};

function loadConfig() {
    try {
        if (fs.existsSync(CONFIG_PATH)) {
            config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
            console.log('[Agent] 配置加载成功:', JSON.stringify(config, null, 2));
            return true;
        }
    } catch (e) {
        console.error('[Agent] 配置文件读取失败:', e.message);
    }
    return false;
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
        this.mumuPath = config.mumuPath || this.findMuMuPath();
        this.adbPath = config.adbPath || this.findAdbPath();
        this.mumutoolPath = this.findMumutoolPath();
        console.log('[MuMu] ADB 路径:', this.adbPath);
        console.log('[MuMu] MuMu 路径:', this.mumuPath);
        console.log('[MuMu] mumutool 路径:', this.mumutoolPath || '未找到');
    }

    findAdbPath() {
        const os = process.platform;

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
                const firstLine = result.split('
')[0].trim();
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
                process.env.LOCALAPPDATA + '\Android\Sdk\platform-tools\adb.exe',
                process.env.ANDROID_HOME + '\platform-tools\adb.exe',
                process.env.USERPROFILE + '\AppData\Local\Android\Sdk\platform-tools\adb.exe',
                'C:\Android\platform-tools\adb.exe',
                'C:\Program Files\Android\Android Studio\plugins\\..\..\..\..\Sdk\platform-tools\adb.exe',
                'C:\Users\' + (process.env.USERNAME || '') + '\AppData\Local\Android\Sdk\platform-tools\adb.exe',
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
        if (os === 'darwin') {
            const p = `${this.mumuPath}/Contents/MacOS/mumutool`;
            if (fs.existsSync(p)) return p;
        } else if (os === 'win32') {
            const p = `${this.mumuPath}/shell/mumutool.exe`;
            if (fs.existsSync(p)) return p;
        }
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
            throw new Error('mumutool 未找到');
        }
        const cmd = `"${this.mumutoolPath}" ${args.join(' ')}`;
        try {
            const result = execSync(cmd, { timeout, encoding: 'utf8' });
            return JSON.parse(result);
        } catch (e) {
            throw new Error(`mumutool 执行失败: ${e.message}`);
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
                        emulators.push({
                            index,
                            adbPort: item.adb_port || (16384 + index * 32),
                            status,
                            name: item.name || `V${String(index + 1).padStart(3, '0')}`
                        });
                    }
                    return emulators;
                }
            }

            // Fallback: 通过 ADB 检测
            const devicesOutput = await this.execAdb(['devices']);
            const lines = devicesOutput.split('\n').slice(1);
            
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
                            emulators.push({
                                index,
                                adbPort: port,
                                status: state === 'device' ? 'RUNNING' : 'STOPPED',
                                name: `V${String(index + 1).padStart(3, '0')}`
                            });
                        }
                    }
                }
            }
        } catch (e) {
            console.warn('[MuMu] 获取模拟器列表失败:', e.message);
        }
        return emulators;
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
            const heartbeatMsg = {
                type: 'HEARTBEAT',
                data: {
                    deviceId: deviceId,
                    emulators: emulators
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
                const index = msg.params?.index;
                const count = msg.params?.count || 1;
                
                if (mumu.mumutoolPath) {
                    try {
                        const cmd = `open "${config.mumuPath || '/Applications/MuMuPlayer.app'}" --args -v ${index}`;
                        execSync(cmd, { timeout: 10000 });
                        send({
                            type: 'TASK_RESULT',
                            taskId: msg.taskId,
                            params: { status: 'SUCCESS' },
                            data: { success: true, message: '创建命令已发送', index }
                        });
                    } catch (e) {
                        send({
                            type: 'TASK_RESULT',
                            taskId: msg.taskId,
                            params: { status: 'FAILED' },
                            data: { success: false, message: e.message }
                        });
                    }
                } else {
                    send({
                        type: 'TASK_RESULT',
                        taskId: msg.taskId,
                        params: { status: 'FAILED' },
                        data: { success: false, message: 'mumutool 未找到' }
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
