'use strict';

/**
 * discord_gateway_fetcher.js — Node.js 版 Discord Gateway 成员采集
 *
 * 在 mumu-agent 机器上直接跑，出口 IP = 用户电脑（干净！）
 * 逻辑完全参照 Java 版 GatewayMemberFetcher，保持行为一致
 *
 * 防反作弊：
 *   ✅ 出口 IP = 用户电脑真实 IP
 *   ✅ IDENTIFY properties 按 agent 所在 OS 真实生成
 *   ✅ 请求间隔 pageDelayMs ± 10s 随机抖动
 *   ✅ 连续空响应自动拉长间隔 ×2
 */

const WebSocket = require('ws');
const zlib = require('zlib');

const GATEWAY_URL = 'wss://gateway.discord.gg/?v=10&encoding=json';
const ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789_.';

function getTimestamp() {
    const now = new Date();
    const pad = (w, n = 2) => String(w).padStart(n, '0');
    return `${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

class DiscordGatewayFetcher {
    constructor({ token, guildId, fetchLimit, maxRequests, pageDelayMs, maxDepth, progressCallback, resultCallback }) {
        this.token = token;
        this.guildId = guildId;
        this.fetchLimit = fetchLimit || 2000000;
        this.maxRequests = maxRequests || 1000;
        this.pageDelayMs = Math.max(10000, pageDelayMs || 60000);
        this.maxDepth = maxDepth || 5;
        this.progressCallback = progressCallback;
        this.resultCallback = resultCallback;

        this.members = new Map();       // userId -> memberData
        this.seq = -1;
        this.connected = false;
        this.ready = false;
        this.heartbeatInterval = null;
        this.hbIntervalMs = 41250;
        this.ws = null;
        this.stop = false;
        this.guildName = '';

        this.requestsSent = 0;
        this.currentPrefix = '';
        this.prefixesDone = 0;
        this.prefixesTotal = ALPHABET.length;
        this.prefixQueue = [];
        this.visitedPrefixes = new Set();

        this.totalRespondedMembers = 0;
        this.totalResponseTimeMs = 0;
        this.lastResponded = 0;
        this.lastDeduped = 0;
        this.lastRequestTimeMs = 0;
        this.lastPrefix = '';
        this.reconnects = 0;
        this.fetchStartTimeMs = 0;

        this.pendingChunks = new Map();
        this.currentRequestComplete = false;
        this.currentRequestResolver = null;
        this.totalRespondedInRequest = 0;
        this.chunksReceived = 0;
        this.currentChunkIndex = -1;
        this.currentChunkCount = 0;
        this.currentMembersBuffer = [];

        this.requestLock = Promise.resolve();
    }

    getOsInfo() {
        const plat = process.platform;
        if (plat === 'win32') return { os: 'Windows', device: 'Desktop' };
        if (plat === 'darwin') return { os: 'MacOS', device: 'Mac' };
        return { os: 'Linux', device: 'Desktop' };
    }

    async start() {
        this.fetchStartTimeMs = Date.now();
        this.emitProgress({ stage: 'ready', msg: '正在连接 Discord Gateway...' });
        try {
            await this.connect();
            this.emitProgress({ stage: 'ready', msg: `已连接, guildName=${this.guildName || '未知'}` });
            await this.fetchAll();
            this.emitProgress({ stage: 'done', msg: '采集完成' });
            this.emitResult({ success: true, members: this.getMemberList(), error: null });
        } catch (e) {
            console.error('[GatewayFetcher] 采集失败:', e.message);
            this.emitProgress({ stage: 'done', msg: '采集失败: ' + e.message });
            this.emitResult({ success: false, members: [], error: e.message });
        } finally {
            this.disconnect();
        }
    }

    async connect() {
        const { os, device } = this.getOsInfo();
        console.log(`[GatewayFetcher] 连接 Gateway, os=${os}, device=${device}`);
        // 防反作弊：UA 用真实 Chrome 格式，版本号随机
        const chromeMajor = 140 + Math.floor(Math.random() * 6);
        const ua = `Mozilla/5.0 (${os === 'MacOS' ? 'Macintosh; Intel Mac OS X 10_15_7' : 'Windows NT 10.0; Win64; x64'}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor}.0.0.0 Safari/537.36`;

        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error('WebSocket 连接超时(30s)')), 30000);

            this.ws = new WebSocket(GATEWAY_URL, {
                headers: { 'User-Agent': ua },
                perMessageDeflate: false,
            });

            this.ws.on('open', () => {
                clearTimeout(timeout);
                this.connected = true;
                console.log('[GatewayFetcher] WebSocket 已连接');
                this.sendIdentify(os, device, ua);
            });

            this.ws.on('message', async (data, isBinary) => {
                try {
                    let text;
                    if (isBinary) {
                        text = await new Promise((res, rej) => {
                            zlib.inflate(data, (err, buf) => err ? rej(err) : res(buf.toString()));
                        });
                    } else {
                        text = data.toString();
                    }
                    this.handleMessage(JSON.parse(text));
                } catch (e) {
                    console.warn('[GatewayFetcher] 处理消息失败:', e.message);
                }
            });

            this.ws.on('error', (err) => {
                console.error('[GatewayFetcher] WebSocket 错误:', err.message);
                clearTimeout(timeout);
                reject(err);
            });

            this.ws.on('close', (code, reason) => {
                console.warn(`[GatewayFetcher] WebSocket 断开: code=${code}, reason=${reason}`);
                this.connected = false;
                if (this.heartbeatInterval) { clearInterval(this.heartbeatInterval); this.heartbeatInterval = null; }
            });
        });
    }

    sendIdentify(os, device, ua) {
        const identify = {
            op: 2,
            d: {
                token: this.token,
                intents: 2,  // GUILD_MEMBERS
                properties: { os, browser: 'Chrome', device },
                compress: true,
                large_threshold: 250,
                shard: [0, 1],
            },
        };
        this.ws.send(JSON.stringify(identify));
        console.log(`[GatewayFetcher] 发送 IDENTIFY, os=${os}, device=${device}`);
    }

    handleMessage(msg) {
        const op = msg.op;
        if (op === 10) {  // Hello
            this.hbIntervalMs = msg.d.heartbeat_interval || 41250;
            this.startHeartbeat();
            this.ws.send(JSON.stringify({ op: 1, d: this.seq }));
        } else if (op === 11) {  // Heartbeat ACK
        } else if (op === 7) {   // Reconnect
            console.log('[GatewayFetcher] 收到 RECONNECT, 重新连接...');
            this.reconnect();
        } else if (op === 9) {   // Invalid Session
            console.warn('[GatewayFetcher] Invalid Session, resumable=' + msg.d);
            if (!msg.d) {
                this.disconnect();
                this.emitResult({ success: false, members: [], error: 'Discord Token 无效 (code 4010)' });
            }
        } else if (op === 0) {   // Dispatch
            const t = msg.t;
            this.seq = msg.s ?? this.seq;
            if (t === 'READY') {
                this.ready = true;
                if (msg.d.guilds && msg.d.guilds.length > 0 && msg.d.guilds[0].name) {
                    this.guildName = msg.d.guilds[0].name;
                }
                console.log(`[GatewayFetcher] READY, guildName=${this.guildName}`);
            } else if (t === 'GUILD_MEMBERS_CHUNK') {
                this.handleMembersChunk(msg.d);
            } else if (t === 'RESUMED') {
                console.log('[GatewayFetcher] RESUMED');
            }
        }
    }

    startHeartbeat() {
        if (this.heartbeatInterval) clearInterval(this.heartbeatInterval);
        this.heartbeatInterval = setInterval(() => {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({ op: 1, d: this.seq }));
            }
        }, this.hbIntervalMs);
    }

    handleMembersChunk(chunk) {
        const members = chunk.members || [];
        this.totalRespondedMembers += members.length;
        this.lastResponded = members.length;
        this.chunksReceived++;
        this.currentChunkCount = chunk.chunk_count;
        this.currentChunkIndex = chunk.chunk_index;

        for (const m of members) {
            // 归一化成员数据（保留核心字段）
            const user = m.user || {};
            const memberData = {
                id: user.id || '',
                username: user.username || '',
                discordName: user.global_name || m.nick || user.username || '',
                avatarUrl: user.avatar ? `https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.png` : null,
                nick: m.nick || null,
                roles: m.roles ? m.roles.join(',') : '',
                joinedAt: m.joined_at || null,
            };
            this.members.set(user.id, memberData);
        }

        // 全部 chunks 到达后完成本次请求
        if (chunk.chunk_index >= chunk.chunk_count - 1) {
            this.currentRequestComplete = true;
            if (this.currentRequestResolver) this.currentRequestResolver(true);
        }
    }

    async reconnect() {
        try {
            this.reconnects++;
            this.disconnect();
            await sleep(1000 * Math.min(30, Math.pow(2, this.reconnects)));
            await this.connect();
        } catch (e) {
            console.error('[GatewayFetcher] 重连失败:', e.message);
            throw e;
        }
    }

    disconnect() {
        this.stop = true;
        if (this.heartbeatInterval) { clearInterval(this.heartbeatInterval); this.heartbeatInterval = null; }
        if (this.ws) {
            try { this.ws.close(); } catch {}
            this.ws = null;
        }
    }

    emitProgress(extra = {}) {
        if (!this.progressCallback) return;
        const payload = {
            requestsSent: this.requestsSent,
            membersUnique: this.members.size,
            currentPrefix: this.currentPrefix,
            prefixesDone: this.prefixesDone,
            prefixesTotal: this.prefixesTotal,
            reconnects: this.reconnects,
            totalRespondedMembers: this.totalRespondedMembers,
            totalResponseTimeMs: this.totalResponseTimeMs,
            lastResponded: this.lastResponded,
            lastDeduped: this.lastDeduped,
            lastRequestTimeMs: this.lastRequestTimeMs,
            lastPrefix: this.lastPrefix,
            elapsed: Date.now() - this.fetchStartTimeMs,
            stage: extra.stage || 'fetching',
            msg: extra.msg || '',
            ...extra,
        };
        try { this.progressCallback(payload); } catch {}
    }

    emitResult(payload) {
        if (!this.resultCallback) return;
        if (payload.success && payload.members && payload.members.length > 500) {
            // 大消息分批传（避免超过 WebSocket 大小限制）
            const batchSize = 500;
            for (let i = 0; i < payload.members.length; i += batchSize) {
                const isLast = i + batchSize >= payload.members.length;
                const batch = payload.members.slice(i, i + batchSize);
                try { this.resultCallback({ ...payload, members: batch, batchIndex: Math.floor(i/batchSize), totalBatches: Math.ceil(payload.members.length/batchSize), isLast }); } catch {}
            }
        } else {
            try { this.resultCallback(payload); } catch {}
        }
    }

    getMemberList() {
        return Array.from(this.members.values()).slice(0, this.fetchLimit);
    }

    generateSubPrefixes(prefix) {
        const subPrefixes = [];
        for (const c of ALPHABET) subPrefixes.push(prefix + c);
        return subPrefixes;
    }

    async fetchAll() {
        // 初始化前缀队列
        for (const c of ALPHABET) {
            this.prefixQueue.push(c);
            this.visitedPrefixes.add(c);
        }

        let emptyRounds = 0;  // 连续空响应计数，用于拉长间隔
        const originalDelay = this.pageDelayMs;

        while (this.prefixQueue.length > 0 && !this.stop) {
            if (this.requestsSent >= this.maxRequests) {
                console.log(`[GatewayFetcher] 达到最大请求数 ${this.maxRequests}`);
                break;
            }
            if (this.members.size >= this.fetchLimit) {
                console.log(`[GatewayFetcher] 达到最大成员数 ${this.fetchLimit}`);
                break;
            }

            if (!this.connected || !this.ready) {
                try { await this.reconnect(); } catch { break; }
            }

            const prefix = this.prefixQueue.shift();
            this.currentPrefix = prefix;
            const beforeSize = this.members.size;
            const requestStart = Date.now();

            // 发 REQUEST_GUILD_MEMBERS
            const payload = {
                op: 8,
                d: { guild_id: this.guildId, limit: 100, query: prefix },
            };

            this.currentRequestComplete = false;
            this.totalRespondedInRequest = 0;
            this.chunksReceived = 0;
            this.currentMembersBuffer = [];

            await new Promise(resolve => { this.currentRequestResolver = resolve; });

            try {
                this.ws.send(JSON.stringify(payload));
            } catch (e) {
                console.warn(`[GatewayFetcher] 发送请求失败: ${e.message}`);
                continue;
            }

            // 等待响应（最多 10 秒）
            const waitStart = Date.now();
            while (!this.currentRequestComplete && Date.now() - waitStart < 10000 && !this.stop) {
                await sleep(200);
            }

            const requestTime = Date.now() - requestStart;
            this.requestsSent++;
            const respondedCount = this.currentRequestComplete ? this.lastResponded : 0;
            const newMembers = this.members.size - beforeSize;
            this.lastDeduped = newMembers;
            this.lastRequestTimeMs = requestTime;
            this.prefixesDone++;
            this.lastPrefix = prefix;
            this.totalResponseTimeMs += requestTime;

            console.log(`[GatewayFetcher] 前缀 '${prefix}': 响应${respondedCount}, 新增${newMembers}, 耗时${requestTime}ms, 总计${this.members.size()} (${this.prefixesDone}/${this.prefixesTotal})`);

            this.emitProgress();

            // 剪枝/扩展
            if (respondedCount === 0) {
                emptyRounds++;
                if (emptyRounds >= 3) {
                    this.pageDelayMs = Math.min(600000, this.pageDelayMs * 2);
                    console.warn(`[GatewayFetcher] 连续${emptyRounds}次空响应，拉长间隔到 ${this.pageDelayMs}ms`);
                }
            } else {
                emptyRounds = 0;
                this.pageDelayMs = originalDelay;
                if (prefix.length < this.maxDepth) {
                    const subs = this.generateSubPrefixes(prefix);
                    for (const sub of subs) {
                        if (!this.visitedPrefixes.has(sub)) {
                            this.prefixQueue.push(sub);
                            this.visitedPrefixes.add(sub);
                        }
                    }
                }
            }

            // 间隔 + 防反作弊抖动 ±10s
            const jitter = Math.floor(Math.random() * 20000) - 10000;
            const delay = Math.max(10000, this.pageDelayMs + jitter);
            await sleep(delay);
        }

        console.log(`[GatewayFetcher] 采集完成: members=${this.members.size()}, requests=${this.requestsSent}`);
    }
}

module.exports = { DiscordGatewayFetcher };
