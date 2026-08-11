import { defineStore } from 'pinia'
import { listAccountGuilds, listGuildMembers } from '@/api'

export const useGuildsStore = defineStore('guilds', {
  state: () => ({
    guildsByAccount: {},   // accountId -> Guild[]
    loadingGuilds: {},
    selectedAccountId: null,
    selectedGuildId: null,
    members: [],           // 当前选中Guild的成员
    membersLoading: false,
    membersAfter: null,    // 分页游标
    membersHasMore: true,
    membersTotal: 0,
    membersError: ''       // 成员列表错误信息
  }),
  getters: {
    currentGuilds: (state) =>
      state.selectedAccountId ? (state.guildsByAccount[state.selectedAccountId] || []) : []
  },
  actions: {
    setSelectedAccount(id) {
      this.selectedAccountId = id
      this.selectedGuildId = null
      this.members = []
      this.membersAfter = null
      this.membersHasMore = true
      this.membersTotal = 0
    },
    setSelectedGuild(id) {
      this.selectedGuildId = id
      this.members = []
      this.membersAfter = null
      this.membersHasMore = true
      this.membersError = ''
    },
    async fetchGuilds(accountId) {
      this.loadingGuilds[accountId] = true
      try {
        const res = await listAccountGuilds(accountId)
        const list = Array.isArray(res) ? res : (res?.data || [])
        this.guildsByAccount[accountId] = list
        return list
      } finally {
        this.loadingGuilds[accountId] = false
      }
    },
    async fetchMembers(accountId, guildId, { reset = true, limit = 100 } = {}) {
      if (reset) {
        this.members = []
        this.membersAfter = null
        this.membersHasMore = true
      }
      if (!this.membersHasMore) return
      this.membersLoading = true
      try {
        const res = await listGuildMembers(accountId, guildId, limit, this.membersAfter)
        // 后端返回结构：{ members: [...], count, hasMore, after, error? }
        const list = Array.isArray(res?.members) ? res.members : (Array.isArray(res) ? res : [])
        const total = res?.count || list.length
        this.membersTotal = total
        this.membersError = res?.error || ''
        if (list.length > 0) {
          this.members = [...this.members, ...list]
          // 后端返回扁平结构，用 res.after 作为游标或取最后一个的 userId
          const last = list[list.length - 1]
          this.membersAfter = res?.after || last?.userId || last?.id || null
          // 用后端返回的 hasMore 优先，否则用数量判断
          this.membersHasMore = res?.hasMore !== undefined ? res.hasMore : (list.length >= limit)
        } else {
          this.membersHasMore = false
        }
        return list
      } finally {
        this.membersLoading = false
      }
    }
  }
})
