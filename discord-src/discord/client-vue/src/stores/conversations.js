import { defineStore } from 'pinia'
import {
  listConversations, listConversationsByAccount, listMessages, listOlderMessages, loadMoreMessages,
  sendMessage, openConversation, updateConversationStage, updateConversationPin,
  updateConversationRemark, markConversationAsRead
} from '@/api'

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
          this.conversations = newData
          this.conversations.sort((a, b) => {
            const ta = (a.lastMessageAt || a.lastMessageTime) ? new Date(a.lastMessageAt || a.lastMessageTime).getTime() : 0
            const tb = (b.lastMessageAt || b.lastMessageTime) ? new Date(b.lastMessageAt || b.lastMessageTime).getTime() : 0
            return tb - ta
          })
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
          // 以服务端COUNT为权威，直接覆盖本地的近似累加值（服务端unreadCount才是和DB对齐的真未读）
          Object.assign(existing, newConv)
          existing.pinned = preservedPinned ?? newConv.pinned
          merged.push(existing)
          existingMap.delete(newConv.id)
        } else {
          // 新会话，直接添加
          merged.push(newConv)
        }
      }

      // 排序
      merged.sort((a, b) => {
        const ta = (a.lastMessageAt || a.lastMessageTime) ? new Date(a.lastMessageAt || a.lastMessageTime).getTime() : 0
        const tb = (b.lastMessageAt || b.lastMessageTime) ? new Date(b.lastMessageAt || b.lastMessageTime).getTime() : 0
        return tb - ta
      })

      this.conversations = merged
    },
    selectConversation(id) {
      this.currentConversationId = id
      this.markAsRead(id)
    },
    async fetchMessages(convId) {
      this.loadingMessagesMap[convId] = true
      try {
        const res = await listMessages(convId, { daysBack: 1, pageSize: 20 })
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
        this.messagesMap[convId] = msgs
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
        this.hasMoreMap[convId] = page ? !!res.hasMore : msgs.length >= 50
        // eslint-disable-next-line no-console
        console.log(`[fetchMessages] conv=${convId} got=${msgs.length} hasMore=${this.hasMoreMap[convId]} oldestId=${this.oldestCursorMap?.[convId]?.id}`, msgs[0] ? { firstAt: msgs[0].createdAt, lastAt: msgs[msgs.length - 1].createdAt } : null)
        return msgs
      } finally {
        this.loadingMessagesMap[convId] = false
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
          const res = await listOlderMessages(convId, cursor.createdAt, cursor.id, 20)
          const page = !Array.isArray(res) && res && (Array.isArray(res?.messages) || Array.isArray(res?.data))
            ? res
            : null
          msgs = page ? (res.messages || res.data || []) : (Array.isArray(res) ? res : (res?.data || []))
          pageHasMore = page ? !!res.hasMore : msgs.length >= 50
          this.hasMoreMap[convId] = pageHasMore
        } else if (earliest) {
          // 兼容老接口（discord 账号级拉历史）
          // eslint-disable-next-line no-console
          console.log(`[loadMore] conv=${convId} fallback(earliest) => earliestId=${earliest}`)
          const res = await loadMoreMessages(convId, earliest)
          msgs = Array.isArray(res) ? res : (res?.data || [])
          this.hasMoreMap[convId] = msgs.length >= 50
        } else if (this.hasMoreMap[convId]) {
          // 没有游标但还有更多（典型是"当天没消息"，后端已返回空+hasMore=true）：
          // 用"近30天/最近20条"重新拉一次，保证能把最后一天有消息的内容拉出来
          // eslint-disable-next-line no-console
          console.log(`[loadMore] conv=${convId} fallback(30d) => no cursor/earliest, re-fetch 30d/20`)
          const res = await listMessages(convId, { daysBack: 30, pageSize: 20 })
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
          this.hasMoreMap[convId] = page ? !!res.hasMore : msgs.length >= 50
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
          // 合并后再排一次，避免极端情况下乱序
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
        } else if (pageHasMore && !(cursor && cursor.createdAt && cursor.id)) {
          // 没拿到新消息，但 hasMore 为 true，说明可能是游标异常 → 关闭 hasMore 避免无限点
          this.hasMoreMap[convId] = false
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
      const existing = this.messagesMap[convId] || []
      if (existing.some(m => m.id === msg.id || (m.discordMessageId && msg.discordMessageId && m.discordMessageId === msg.discordMessageId))) {
        return
      }
      existing.push(msg)
      // 按发送时间重新排序
      existing.sort((a, b) => {
        const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
        const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
        return ta - tb
      })
      this.messagesMap[convId] = existing
      const conv = this.conversations.find(c => c.id === convId)
      if (conv) {
        if (this.currentConversationId !== convId) {
          conv.unreadCount = (conv.unreadCount || 0) + 1
        }
        const snippet = (msg.translatedContent || msg.content || '').slice(0, 60)
        conv.lastMessagePreview = snippet
        if (this.currentConversationId === convId) {
          conv.lastMessageSnippet = snippet
          const time = msg.createdAt || new Date().toISOString()
          conv.lastMessageAt = time
          conv.lastMessageTime = time
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
