import { defineStore } from 'pinia'
import {
  listConversations, listConversationsByAccount, listMessages, loadMoreMessages,
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
    earliestIdMap: {}      // convId -> earliest msg id
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
     * 保留本地的未读计数等状态，用后端新数据更新其他字段
     */
    mergeConversations(newData) {
      const existingMap = new Map(this.conversations.map(c => [c.id, c]))
      const merged = []

      for (const newConv of newData) {
        const existing = existingMap.get(newConv.id)
        if (existing) {
          // 保留本地状态，如未读计数
          const preservedUnread = existing.unreadCount || 0
          const preservedPinned = existing.pinned
          // 用新数据更新，但保留未读计数（除非本地为0且新数据有值）
          Object.assign(existing, newConv)
          if (preservedUnread > 0) {
            existing.unreadCount = preservedUnread
          }
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
        const res = await listMessages(convId)
        const msgs = Array.isArray(res) ? res : (res?.data || [])
        // 按发送时间正序排序（使用 discordCreatedAt，降级使用 createdAt）
        msgs.sort((a, b) => {
          const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
          const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
          return ta - tb
        })
        this.messagesMap[convId] = msgs
        this.earliestIdMap[convId] = msgs.length > 0 ? msgs[0].id : null
        this.hasMoreMap[convId] = msgs.length >= 50
        return msgs
      } finally {
        this.loadingMessagesMap[convId] = false
      }
    },
    async loadMore(convId) {
      const earliest = this.earliestIdMap[convId]
      if (!earliest || this.hasMoreMap[convId] === false) return []
      try {
        const res = await loadMoreMessages(convId, earliest)
        const msgs = Array.isArray(res) ? res : (res?.data || [])
        msgs.sort((a, b) => {
          const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
          const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
          return ta - tb
        })
        if (msgs.length > 0) {
          const existing = this.messagesMap[convId] || []
          this.messagesMap[convId] = [...msgs, ...existing]
          // 重新排序合并后的消息
          this.messagesMap[convId].sort((a, b) => {
            const ta = a.discordCreatedAt ? new Date(a.discordCreatedAt).getTime() : (a.createdAt ? new Date(a.createdAt).getTime() : 0)
            const tb = b.discordCreatedAt ? new Date(b.discordCreatedAt).getTime() : (b.createdAt ? new Date(b.createdAt).getTime() : 0)
            return ta - tb
          })
          this.earliestIdMap[convId] = msgs[0].id
        }
        this.hasMoreMap[convId] = msgs.length >= 50
        return msgs
      } catch (e) {
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
