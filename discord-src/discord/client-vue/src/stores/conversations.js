import { defineStore } from 'pinia'
import {
  listConversations, listConversationsByAccount, listMessages, listOlderMessages, loadMoreMessages,
  sendMessage, openConversation, updateConversationStage, updateConversationPin,
  updateConversationRemark, markConversationAsRead
} from '@/api'

// 排序规则优先级：
// 1. 置顶用户
// 2. 通过客户且没消息（PROSPECT阶段 + 无消息）
// 3. 有未读消息的好友
// 4. 其他
// 5. 归档好友永远排在最下面
function getConvTime(c) {
  if (!c) return 0
  const ts = c.lastMessageAt || c.createdAt || c.stageChangedAt
  if (!ts) return 0
  const time = new Date(ts)
  return isNaN(time.getTime()) ? 0 : time.getTime()
}

function conversationSortScore(c) {
  if (!c) return 50
  const stage = c.stage || ''
  const isPinned = c.pinned === true || c.pinned === 'true'
  const hasMessages = !!c.lastMessageAt
  const hasUnread = (c.unreadCount && c.unreadCount > 0)

  // 归档排最底
  if (stage === 'ARCHIVED') return 50

  // 置顶排最顶
  if (isPinned) return 10

  // 通过客户（PROSPECT）且无消息
  if (stage === 'PROSPECT' && !hasMessages) return 20

  // 有未读消息
  if (hasUnread) return 30

  return 40
}

function sortConversations(list) {
  return list.sort((a, b) => {
    const sa = conversationSortScore(a)
    const sb = conversationSortScore(b)
    if (sa !== sb) return sa - sb

    // 同一分组内按有效时间倒序（最新排最上）
    return getConvTime(b) - getConvTime(a)
  })
}

export const useConversationsStore = defineStore('conversations', {
  state: () => ({
    conversations: [],
    loadingConversations: false,
    initialLoadDone: false,  // 首次加载完成标志
    currentConversationId: null,
    messagesMap: {},       // convId -> Message[]
    loadingMessagesMap: {}, // convId -> bool
    hasMoreMap: {},        // convId -> bool
    earliestIdMap: {},      // convId -> earliest msg id
    oldestCursorMap: {}     // convId -> { createdAt, id } 下次加载更早一页的游标
  }),
  getters: {
    currentConversation: (state) =>
      state.conversations.find(c => c.id === state.currentConversationId),
    currentMessages: (state) =>
      state.messagesMap[state.currentConversationId] || []
  },
  actions: {
    /**
     * 动态无刷新技术 - 静默更新会话列表
     * @param {Object} params - 查询参数
     * @param {boolean} silent - 是否静默更新（true=不显示loading，用于定时轮询）
     */
    async fetchConversations(params = {}, silent = false) {
      // 只有首次加载或手动刷新时才显示loading
      if (!silent) {
        this.loadingConversations = true
      }
      try {
        const res = params.accountId || params.stage || params.keyword || params.pinnedOnly
          ? await listConversations(params)
          : (params.accountId ? await listConversationsByAccount(params.accountId) : await listConversations())
        const newData = Array.isArray(res) ? res : (res?.data || [])

        if (silent && this.conversations.length > 0) {
          // 静默模式：增量更新，保留本地状态
          this.mergeConversations(newData)
        } else {
          // 全量替换（首次加载或手动刷新）
          this.conversations = sortConversations(newData)
        }
        this.initialLoadDone = true
        return this.conversations
      } finally {
        if (!silent) {
          this.loadingConversations = false
        }
      }
    },

    /**
     * 增量合并会话数据（动态无刷新技术核心）
     * 以服务端返回的未读数为准（服务端COUNT是权威值）；
     * 仅当服务端返回null/undefined时保留本地未读状态，其他情况直接覆盖更新，
     * 避免"用户在Discord端已读/系统清零/客服发送OUTBOUND后"本地旧的未读数一直不更新导致不一致。
     */
    mergeConversations(newData) {
      const existingMap = new Map(this.conversations.map(c => [c.id, c]))
      const merged = []

      for (const newConv of newData) {
        const existing = existingMap.get(newConv.id)
        if (existing) {
          const preservedPinned = existing.pinned
          // 保护 lastMessageAt：只允许被更晚的时间覆盖，防止轮询返回的旧时间覆盖掉 WebSocket 推送的最新时间
          // 这是修复"时间闪烁"的关键：新消息到达时 lastMessageAt 被更新为最新，
          // 但随后的轮询 fetchConversations 返回的可能是数据库中稍旧的 lastMessageAt，
          // Object.assign 会把它覆盖回去，造成"显示最新时间→显示更早时间→又显示最新时间"的闪烁
          const oldLastTime = existing.lastMessageAt
          const newLastTime = newConv.lastMessageAt
          // 保护客户端实时累加的 unreadCount：
          // WebSocket 消息推送会 +1，但轮询可能返回 DB 中尚未更新的旧值，
          // 这里取 max(现有客户端累加值, 服务端返回值)，避免实时红点闪烁
          const clientUnread = existing.unreadCount || 0
          Object.assign(existing, newConv)
          existing.pinned = preservedPinned ?? newConv.pinned
          // 客户端累加值 > 服务端返回值 时保留客户端的（代表有新未读消息刚到）
          const serverUnread = newConv.unreadCount || 0
          if (clientUnread > serverUnread) {
            existing.unreadCount = clientUnread
          }
          // 如果本地已有更晚的 lastMessageAt（来自 WebSocket 推送），保留本地值
          if (oldLastTime && newLastTime) {
            const oldTs = new Date(oldLastTime).getTime()
            const newTs = new Date(newLastTime).getTime()
            if (!isNaN(oldTs) && !isNaN(newTs) && oldTs > newTs) {
              existing.lastMessageAt = oldLastTime
            }
          } else if (oldLastTime && !newLastTime) {
            existing.lastMessageAt = oldLastTime
          }
          merged.push(existing)
          existingMap.delete(newConv.id)
        } else {
          // 新会话，直接添加
          merged.push(newConv)
        }
      }

      // 排序
      this.conversations = sortConversations(merged)
    },
    selectConversation(id) {
      this.currentConversationId = id
      this.markAsRead(id)
    },
    async fetchMessages(convId) {
      this.loadingMessagesMap = { ...this.loadingMessagesMap, [convId]: true }
      try {
        const res = await listMessages(convId, { daysBack: 1, pageSize: 10 })
        // 兼容新结构 MessagePageDto { messages, hasMore, oldestId, oldestCreatedAt } 与老结构 List<MessageDto>
        const page = !Array.isArray(res) && res && (Array.isArray(res?.messages) || Array.isArray(res?.data))
          ? res
          : null
        const msgs = page
          ? (res.messages || res.data || [])
          : (Array.isArray(res) ? res : (res?.data || []))
        // 按发送时间正序排序（使用 discordCreatedAt，降级使用 createdAt）
        msgs.sort((a, b) => {
          const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
          const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
          return ta - tb
        })
        this.messagesMap = { ...this.messagesMap, [convId]: msgs }
        if (msgs.length > 0) {
          this.earliestIdMap[convId] = msgs[0].id
          this.oldestCursorMap[convId] = {
            createdAt: res?.oldestCreatedAt || msgs[0].discordCreatedAt || msgs[0].createdAt,
            id: res?.oldestId != null ? res.oldestId : msgs[0].id
          }
        } else {
          this.earliestIdMap[convId] = null
          if (this.oldestCursorMap) delete this.oldestCursorMap[convId]
        }
        this.hasMoreMap[convId] = page ? !!res.hasMore : msgs.length >= 10
        // eslint-disable-next-line no-console
        console.log(`[fetchMessages] conv=${convId} got=${msgs.length} hasMore=${this.hasMoreMap[convId]} oldestId=${this.oldestCursorMap?.[convId]?.id}`, msgs[0] ? { firstAt: msgs[0].createdAt, lastAt: msgs[msgs.length - 1].createdAt } : null)
        return msgs
      } finally {
        this.loadingMessagesMap = { ...this.loadingMessagesMap, [convId]: false }
      }
    },
    async loadMore(convId) {
      if (this.hasMoreMap[convId] === false) return []
      try {
        let msgs = []
        let pageHasMore = false
        const cursor = this.oldestCursorMap?.[convId]
        const earliest = this.earliestIdMap[convId]
        if (cursor && cursor.createdAt && cursor.id) {
          // 走新的游标分页：按时间倒序加载更早一页（性能更好）
          // eslint-disable-next-line no-console
          console.log(`[loadMore] conv=${convId} cursor(c) => oldestCreatedAt=${cursor.createdAt} oldestId=${cursor.id}`)
          const res = await listOlderMessages(convId, cursor.createdAt, cursor.id, 10)
          const page = !Array.isArray(res) && res && (Array.isArray(res?.messages) || Array.isArray(res?.data))
            ? res
            : null
          msgs = page ? (res.messages || res.data || []) : (Array.isArray(res) ? res : (res?.data || []))
          pageHasMore = page ? !!res.hasMore : msgs.length >= 10
          this.hasMoreMap[convId] = pageHasMore
        } else if (earliest) {
          // 兼容老接口（discord 账号级拉历史）
          // eslint-disable-next-line no-console
          console.log(`[loadMore] conv=${convId} fallback(earliest) => earliestId=${earliest}`)
          const res = await loadMoreMessages(convId, earliest)
          msgs = Array.isArray(res) ? res : (res?.data || [])
          this.hasMoreMap[convId] = msgs.length >= 10
        } else if (this.hasMoreMap[convId]) {
          // 没有游标但还有更多（典型是"当天没消息"，后端已返回空+hasMore=true）：
          // 用"近30天/最近10条"重新拉一次，保证能把最后一天有消息的内容拉出来
          // eslint-disable-next-line no-console
          console.log(`[loadMore] conv=${convId} fallback(30d) => no cursor/earliest, re-fetch 30d/10`)
          const res = await listMessages(convId, { daysBack: 30, pageSize: 10 })
          const page = !Array.isArray(res) && res && (Array.isArray(res?.messages) || Array.isArray(res?.data))
            ? res
            : null
          msgs = page ? (res.messages || res.data || []) : (Array.isArray(res) ? res : (res?.data || []))
          msgs.sort((a, b) => {
            const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
            const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
            return ta - tb
          })
          // 直接用这次拉到的完整页替换当前（当前是空），并设置游标
          const existing = this.messagesMap[convId] || []
          if (existing.length === 0) {
            this.messagesMap[convId] = msgs
          } else {
            this.messagesMap[convId] = [...msgs, ...existing]
            this.messagesMap[convId].sort((a, b) => {
              const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
              const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
              return ta - tb
            })
          }
          if (msgs.length > 0) {
            this.earliestIdMap[convId] = msgs[0].id
            this.oldestCursorMap[convId] = {
              createdAt: res?.oldestCreatedAt || msgs[0].discordCreatedAt || msgs[0].createdAt,
              id: res?.oldestId != null ? res.oldestId : msgs[0].id
            }
          }
          this.hasMoreMap[convId] = page ? !!res.hasMore : msgs.length >= 10
          // eslint-disable-next-line no-console
          console.log(`[loadMore] fallback(30d) conv=${convId} got=${msgs.length} hasMore=${this.hasMoreMap[convId]}`)
          return msgs
        } else {
          this.hasMoreMap[convId] = false
          return []
        }
        msgs.sort((a, b) => {
          const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
          const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
          return ta - tb
        })
        // eslint-disable-next-line no-console
        console.log(`[loadMore] conv=${convId} merged older page size=${msgs.length} pageHasMore=${pageHasMore}`)
        if (msgs.length > 0) {
          const existing = this.messagesMap[convId] || []
          this.messagesMap[convId] = [...msgs, ...existing]
          this.messagesMap[convId].sort((a, b) => {
            const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
            const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
            return ta - tb
          })
          this.earliestIdMap[convId] = msgs[0].id
          this.oldestCursorMap[convId] = {
            createdAt: msgs[0].discordCreatedAt || msgs[0].createdAt,
            id: msgs[0].id
          }
        }
        return msgs
      } catch (e) {
        // eslint-disable-next-line no-console
        console.error('[loadMore] error', convId, e)
        this.hasMoreMap[convId] = false
        return []
      }
    },
    async send(convId, content, targetLanguage, extra = {}) {
      // 乐观更新：发送前立即把 lastMessageAt 设为当前时间，确保好友列表实时反映最后消息时间
      const nowTime = new Date().toISOString()
      const conv = this.conversations.find(c => c.id === convId)
      if (conv) {
        conv.lastMessageAt = nowTime
        conv.lastMessageTime = nowTime
      }
      const res = await sendMessage(convId, content, targetLanguage, extra)
      if (res && res.id) {
        this.appendMessage(convId, res)
      }
      return res
    },
    async open(accountId, discordUserId) {
      const res = await openConversation(accountId, discordUserId)
      // 动态无刷新技术：静默刷新
      await this.fetchConversations({}, true)
      if (res && res.id) {
        this.currentConversationId = res.id
        await this.fetchMessages(res.id)
      }
      return res
    },
    async updateStage(id, stage) {
      const res = await updateConversationStage(id, stage)
      const conv = this.conversations.find(c => c.id === id)
      if (conv) conv.stage = stage
      if (this.currentConversationId === id) {
        this.currentConversation = { ...this.currentConversation, stage }
      }
      return res
    },
    async updatePin(id, pinned) {
      const res = await updateConversationPin(id, pinned)
      const conv = this.conversations.find(c => c.id === id)
      if (conv) conv.pinned = pinned
      return res
    },
    async updateRemark(id, remark) {
      const res = await updateConversationRemark(id, remark)
      const conv = this.conversations.find(c => c.id === id)
      if (conv) conv.remark = remark
      return res
    },
    // === WebSocket 推送调用 ===
    appendMessage(convId, msg) {
      if (!msg || !msg.id) return
      const existing = [...(this.messagesMap[convId] || [])]  // 创建副本以触发响应式
      const idx = existing.findIndex(m => m.id === msg.id || (m.discordMessageId && msg.discordMessageId && m.discordMessageId === msg.discordMessageId))
      if (idx >= 0) {
        // 相同ID消息：UPDATE而非SKIP（用于翻译结果回填、ASR完成等场景）
        // 使用splice确保Vue检测到数组元素变化
        existing.splice(idx, 1, { ...existing[idx], ...msg })
        this.messagesMap = { ...this.messagesMap, [convId]: existing }
        // 更新会话预览（用最新的翻译内容）
        const conv = this.conversations.find(c => c.id === convId)
        if (conv) {
          const snippet = (msg.translatedContent || msg.content || '').slice(0, 60)
          conv.lastMessagePreview = snippet
          // UPDATE 分支也更新 lastMessageAt：取 max(本地, 消息时间)
          // 防止 WebSocket 先到+send()返回后到 导致 lastMessageAt 不更新
          const time = msg.createdAt || new Date().toISOString()
          const localTs = conv.lastMessageAt ? new Date(conv.lastMessageAt).getTime() : 0
          const msgTs = new Date(time).getTime()
          if (!isNaN(msgTs) && (!conv.lastMessageAt || isNaN(localTs) || msgTs > localTs)) {
            conv.lastMessageAt = time
            conv.lastMessageTime = time
          }
          if (this.currentConversationId === convId) {
            conv.lastMessageSnippet = snippet
          }
        }
        return
      }
      existing.push(msg)
      // 按发送时间重新排序
      existing.sort((a, b) => {
        const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
        const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
        return ta - tb
      })
      this.messagesMap = { ...this.messagesMap, [convId]: existing }
      const conv = this.conversations.find(c => c.id === convId)
      if (conv) {
        if (this.currentConversationId !== convId) {
          conv.unreadCount = (conv.unreadCount || 0) + 1
        }
        const snippet = (msg.translatedContent || msg.content || '').slice(0, 60)
        conv.lastMessagePreview = snippet
        const time = msg.createdAt || new Date().toISOString()
        // 保护 lastMessageAt：只允许更晚的时间覆盖（即使不是当前会话也更新，保持时间最新）
        const localTs = conv.lastMessageAt ? new Date(conv.lastMessageAt).getTime() : 0
        const msgTs = new Date(time).getTime()
        if (!isNaN(msgTs) && (!conv.lastMessageAt || isNaN(localTs) || msgTs > localTs)) {
          conv.lastMessageAt = time
          conv.lastMessageTime = time
        }
        conv.lastMessageTime = time
        if (this.currentConversationId === convId) {
          conv.lastMessageSnippet = snippet
        }
      }
    },
    markAsRead(convId) {
      const conv = this.conversations.find(c => c.id === convId)
      if (conv) conv.unreadCount = 0
      if (convId) {
        markConversationAsRead(convId).catch(() => {})
      }
    },
    markCurrentAsRead() {
      if (!this.currentConversationId) return
      const conv = this.conversations.find(c => c.id === this.currentConversationId)
      if (conv) conv.unreadCount = 0
      markConversationAsRead(this.currentConversationId).catch(() => {})
    },
    reset() {
      this.conversations = []
      this.currentConversationId = null
      this.messagesMap = {}
      this.loadingMessagesMap = {}
      this.hasMoreMap = {}
      this.earliestIdMap = {}
      this.initialLoadDone = false  // 重置首次加载标志
    }
  }
})
