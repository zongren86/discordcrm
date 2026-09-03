'use strict';

/**
 * gateway_manager.js
 * 
 * Discord Gateway WS 连接管理（来自同行 node_agent 的 gateway_manager）
 * 
 * 为什么要自己管 Gateway？
 *   - 拿到 token 后，建立 Gateway 会话让连接更像真实 Discord 客户端
 *   - generation 计数追踪重连次数
 *   - Opcode 7 (RECONNECT) / 9 (INVALID_SESSION) 自动处理
 *   - 同行项目实测：有 Gateway 会话的账号比没有的更稳定（封号率显著降低）
 * 
 * 注意：不引入 discord.js（避免额外依赖），直接用 ws 原生实现 Gateway 协议
 */

const WebSocket = require('ws');
const https = require('https');

const GATEWAY_VERSION = 10;
const INTENTS = 0; // GUILDS only，最少权限

// ========== 状态 ==========

const _connections = new Map(); // agentName -> GatewayConnection

class GatewayConnection {
  constructor(agentName, token, opts = {}) {
    this.agentName = agentName;
    this.token = token;
    this.opts = opts;
    this.ws = null;
    this.generation = 0;
    this.status = 'idle'; // idle, connecting, connected, reconnecting, disconnected
    this.heartbeatInterval = null;
    this.heartbeatAck = true;
    this.lastSequence = null;
    this.resumeUrl = null;
    this.sessionId = null;
    this._reconnectDelay = 1000;
  }

  async connect() {
    this.generation++;
    this.status = 'connecting';
    console.log('[Gateway] ' + this.agentName + ' 连接 Gateway (gen=' + this.generation + ')');

    try {
      // 1. 获取 Gateway URL
      const gwUrl = await this._fetchGatewayUrl();
      
      // 2. 建立 WS
      const url = gwUrl + '?v=' + GATEWAY_VERSION + '&encoding=json';
      this.ws = new WebSocket(url);

      this.ws.on('open', () => {
        console.log('[Gateway] ' + this.agentName + ' WS 已打开');
      });

      this.ws.on('message', (data) => this._onMessage(data));
      this.ws.on('error', (err) => {
        console.warn('[Gateway] ' + this.agentName + ' WS 错误:', err.message);
      });
      this.ws.on('close', (code, reason) => {
        this.status = 'disconnected';
        console.log('[Gateway] ' + this.agentName + ' WS 关闭 (code=' + code + ', gen=' + this.generation + ')');
        if (this.status !== 'shutting_down') {
          setTimeout(() => this.reconnect(), 3000);
        }
      });

    } catch (e) {
      console.error('[Gateway] ' + this.agentName + ' 连接失败:', e.message);
      this.status = 'disconnected';
      setTimeout(() => this.reconnect(), 5000);
    }
  }

  _fetchGatewayUrl() {
    return new Promise((resolve, reject) => {
      const req = https.get('https://discord.com/api/v' + GATEWAY_VERSION + '/gateway/bot', {
        headers: { Authorization: 'Bot ' + this.token },
        timeout: 5000,
      }, (res) => {
        let data = '';
        res.on('data', c => data += c);
        res.on('end', () => {
          try {
            const r = JSON.parse(data);
            resolve(r.url);
          } catch { reject(new Error('invalid gateway response')); }
        });
      });
      req.on('error', reject);
      req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
    });
  }

  _onMessage(data) {
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    const { op, d, s, t } = msg;
    if (s !== null && s !== undefined) this.lastSequence = s;

    switch (op) {
      case 10: // HELLO → 发送 IDENTIFY + 启动心跳
        this._sendIdentify();
        this._startHeartbeat(d.heartbeat_interval);
        break;
      case 11: // HEARTBEAT_ACK
        this.heartbeatAck = true;
        break;
      case 7: // RECONNECT
        console.log('[Gateway] ' + this.agentName + ' Opcode 7 → reconnect');
        this._stopHeartbeat();
        this._attemptResume();
        break;
      case 9: // INVALID_SESSION
        console.warn('[Gateway] ' + this.agentName + ' Opcode 9 → invalid session, resume=' + d);
        if (d) this._attemptResume();
        else this._fullReconnect();
        break;
      case 0: // DISPATCH
        if (t === 'READY') {
          this.status = 'connected';
          this.sessionId = d.session_id;
          this.resumeUrl = d.resume_gateway_url;
          console.log('[Gateway] ✅ ' + this.agentName + ' READY (user=' + d.user?.username + ', gen=' + this.generation + ')');
        } else if (t === 'RESUMED') {
          this.status = 'connected';
          console.log('[Gateway] ✅ ' + this.agentName + ' RESUMED (gen=' + this.generation + ')');
        }
        break;
    }
  }

  _sendIdentify() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const identify = {
      op: 2,
      d: {
        token: this.token,
        intents: this.opts.intents ?? INTENTS,
        properties: {
          os: 'Windows',
          browser: 'Chrome',
          device: 'Desktop',
        },
        presence: {
          status: 'online',
          activities: [],
        },
      },
    };
    this.ws.send(JSON.stringify(identify));
  }

  _sendResume() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.sessionId) return;
    const resume = {
      op: 6,
      d: {
        token: this.token,
        session_id: this.sessionId,
        seq: this.lastSequence,
      },
    };
    this.ws.send(JSON.stringify(resume));
  }

  _startHeartbeat(interval) {
    this._stopHeartbeat();
    this.heartbeatAck = true;
    this.heartbeatInterval = setInterval(() => {
      if (!this.heartbeatAck) {
        console.warn('[Gateway] ' + this.agentName + ' heartbeat ACK 超时 → reconnect');
        this._fullReconnect();
        return;
      }
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.heartbeatAck = false;
        this.ws.send(JSON.stringify({ op: 1, d: this.lastSequence }));
      }
    }, interval);
  }

  _stopHeartbeat() {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  _attemptResume() {
    if (this.resumeUrl && this.sessionId) {
      console.log('[Gateway] ' + this.agentName + ' 尝试 RESUME');
      this._stopHeartbeat();
      this._sendResume();
    } else {
      this._fullReconnect();
    }
  }

  _fullReconnect() {
    console.log('[Gateway] ' + this.agentName + ' 完全重连');
    this._stopHeartbeat();
    if (this.ws) { try { this.ws.close(); } catch {} }
    this.reconnect();
  }

  reconnect() {
    if (this.status === 'shutting_down') return;
    this.status = 'reconnecting';
    setTimeout(() => this.connect(), this._reconnectDelay);
    this._reconnectDelay = Math.min(this._reconnectDelay * 2, 30000);
  }

  disconnect() {
    this.status = 'shutting_down';
    this._stopHeartbeat();
    if (this.ws) { try { this.ws.close(); } catch {} }
    _connections.delete(this.agentName);
  }

  getStatus() {
    return {
      agentName: this.agentName,
      status: this.status,
      generation: this.generation,
      lastSequence: this.lastSequence,
      hasSession: !!this.sessionId,
      wsReady: this.ws?.readyState,
    };
  }
}

// ========== 导出 API ==========

function connectAgent(agentName, token, opts = {}) {
  const existing = _connections.get(agentName);
  if (existing) existing.disconnect();
  const conn = new GatewayConnection(agentName, token, opts);
  _connections.set(agentName, conn);
  conn.connect();
  return conn;
}

function disconnectAgent(agentName) {
  const conn = _connections.get(agentName);
  if (conn) conn.disconnect();
}

function getConnection(agentName) {
  return _connections.get(agentName);
}

function getAllStatus() {
  return [..._connections.values()].map(c => c.getStatus());
}

// 自动清理：退出时关闭所有
process.on('exit', () => {
  for (const conn of _connections.values()) {
    try { conn.disconnect(); } catch {}
  }
});

module.exports = { connectAgent, disconnectAgent, getConnection, getAllStatus };
