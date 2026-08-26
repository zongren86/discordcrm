import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { WS_BASE } from '@/config'

let stompClient = null
let connecting = false

function buildWsUrl() {
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
