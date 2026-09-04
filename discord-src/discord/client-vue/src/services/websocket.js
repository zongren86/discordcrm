import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useConversationsStore } from '@/stores/conversations'
import { WS_BASE } from '@/config'

let stompClient = null
let connecting = false
let msgListener = null

function buildWsUrl() {
  // SockJS requires HTTP(S) URL, not ws://
  if (WS_BASE.startsWith('ws://')) {
    return 'http://' + WS_BASE.substring(5)
  }
  if (WS_BASE.startsWith('wss://')) {
    return 'https://' + WS_BASE.substring(6)
  }
  return WS_BASE
}

export function startWebSocket(token) {
  if (stompClient && stompClient.connected) return
  if (connecting) return
  connecting = true

  const socket = new SockJS(buildWsUrl())
  stompClient = new Client({
    webSocketFactory: () => socket,
    connectHeaders: token ? { Authorization: 'Bearer ' + token } : {},
    reconnectDelay: 5000,
    debug: (msg) => {
      if (msg.includes('SUBSCRIBE') || msg.includes('MESSAGE') || msg.includes('CONNECT')) {
        console.log('[WS]', msg.trim())
      }
    },
    onConnect: (frame) => {
      connecting = false
      console.log('[WS] connected successfully')
      try {
        // 订阅全局消息主题
        stompClient.subscribe('/topic/messages', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            console.log('[WS] received message on /topic/messages:', payload)
            handleIncomingMessage(payload)
          } catch (e) {
            console.warn('[WS] parse msg error', e)
          }
        })
        // 订阅会话更新主题
        stompClient.subscribe('/topic/conversations', (msg) => {
          try {
            const conv = JSON.parse(msg.body)
            const store = useConversationsStore()
            const existing = store.conversations.find(c => c.id === conv.id)
            if (existing) {
              // 保留原有的未读计数，取 max(客户端累加值, 后端精确值)
              const preservedUnread = existing.unreadCount || 0
              Object.assign(existing, conv)
              const serverUnread = conv.unreadCount || 0
              // 客户端累加值 > 后端返回值 时保留客户端的（代表刚收到新消息但后端还没刷新完）
              if (preservedUnread > serverUnread) {
                existing.unreadCount = preservedUnread
              }
            } else {
              // 新会话，直接添加
              store.conversations.push(conv)
            }
          } catch (e) {
            console.warn('[WS] conversation update parse error', e)
          }
        })
        console.log('[WS] subscribed to /topic/messages and /topic/conversations')
      } catch (e) {
        console.warn('[WS] subscribe error', e)
      }
    },
    onStompError: (frame) => {
      console.warn('[WS] STOMP error', frame?.headers?.message)
      connecting = false
    },
    onWebSocketError: (e) => {
      console.warn('[WS] socket error', e)
      connecting = false
    },
    onWebSocketClose: () => {
      connecting = false
      console.warn('[WS] connection closed, will retry...')
    }
  })

  try {
    stompClient.activate()
  } catch (e) {
    connecting = false
    console.warn('[WS] activate error', e)
  }
}

export function stopWebSocket() {
  if (stompClient) {
    try {
      stompClient.deactivate()
    } catch (e) {}
    stompClient = null
  }
  connecting = false
}

function handleIncomingMessage(payload) {
  if (!payload) return
  console.log('[WS] handleIncomingMessage:', payload)
  const convStore = useConversationsStore()
  // 后端推送的 MessageDto 直接就是消息对象，包含 conversationId 字段
  const conversationId = payload.conversationId
  const message = payload
  if (conversationId && message && message.id) {
    convStore.appendMessage(conversationId, message)
    console.log('[WS] message appended to conv', conversationId)
  } else {
    console.warn('[WS] missing conversationId or message.id', { conversationId, hasId: !!message?.id })
  }
}
