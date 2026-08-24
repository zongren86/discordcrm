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
        this.adbPath = this.findAdbPath();
        this.mumuPath = config.mumuPath || this.findMuMuPath();
        console.log('[MuMu] ADB 路径:', this.adbPath);
        console.log('[MuMu] MuMu 路径:', this.mumuPath);
    }

    findAdbPath() {
        try {
            return execSync('which adb', { encoding: 'utf8' }).trim();
        } catch {
            // 尝试常见路径
            const paths = [
                '/usr/local/bin/adb',
                '/opt/homebrew/bin/adb',
                process.env.ANDROID_HOME + '/platform-tools/adb',
                process.env.LOCALAPPDATA + '/Android/Sdk/platform-tools/adb.exe',
                '/mnt/c/Windows/System32/adb.exe'
            ];
            for (const p of paths) {
                if (fs.existsSync(p)) return p;
            }
            return null;
        }
    }

    findMuMuPath() {
        const os = process.platform;
        if (os === 'darwin') {
            return '/Applications/MuMuPlayer.app';
        } else if (os === 'win32') {
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

    async getEmulators() {
        const emulators = [];
        try {
            const devicesOutput = await this.execAdb(['devices']);
            const lines = devicesOutput.split('\n').slice(1); // 跳过标题行
            
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
                        emulators.push({
                            index,
                            adbPort: port,
                            status: state === 'device' ? 'RUNNING' : 'STOPPED',
                            name: `V${String(index + 1).padStart(3, '0')}`
                        });
                    }
                }
            }
        } catch (e) {
            console.warn('[MuMu] 获取模拟器列表失败:', e.message);
        }
        return emulators;
    }

    async startEmulator(index) {
        const port = 16384 + index * 32;
        try {
            // 检查是否已连接
            const devices = await this.getEmulators();
            const existing = devices.find(d => d.index === index);
            if (existing && existing.status === 'RUNNING') {
                return { success: true, message: '模拟器已在运行' };
            }
            
            // 尝试通过 MuMu 命令行启动
            if (process.platform === 'darwin') {
                const cmd = `open "${this.mumuPath}" --args -v ${index}`;
                execSync(cmd, { timeout: 5000 });
                return { success: true, message: '启动命令已发送' };
            }
            return { success: false, message: '需要手动启动模拟器' };
        } catch (e) {
            return { success: false, message: e.message };
        }
    }

    async stopEmulator(index) {
        const port = 16384 + index * 32;
        const deviceId = `127.0.0.1:${port}`;
        try {
            await this.execAdb(['-s', deviceId, 'shell', 'reboot', '-p']);
            return { success: true, message: '关闭命令已发送' };
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
            
        case 'PING':
            send({ type: 'PONG' });
            break;
            
        default:
            console.log(`[Agent] 未处理的消息类型: ${type}`);
    }
}

// ========== 主程序入口 ==========
function main() {
    console.log('========================================');
    console.log('  MuMu Agent v1.0.0');
    console.log('========================================');
    
    if (!loadConfig()) {
        console.error('[Agent] 请创建 config.json 配置文件');
        console.error('[Agent] 模板:');
        console.error(JSON.stringify({
            userId: 'merchantadmin2',
            serverUrl: 'wss://your-server.com/ws/agent',
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
