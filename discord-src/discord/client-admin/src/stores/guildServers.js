import { defineStore } from 'pinia'
import {
  listGuildServers,
  saveGuildServer,
  deleteGuildServer,
  listGuildServerMembers,
  countGuildServerMembers,
  fetchGuildMembers,
  getMemberFetchTask,
  getMemberFetchTasks,
  getLatestServerTask,
  resolveMemberLink,
  getGuildMerchantConfig,
  stopMemberFetchTask
} from '@/api'

export const useGuildServersStore = defineStore('guildServers', {
  state: () => ({
    servers: [],
    loading: false,
    currentServer: null,
    members: [],
    membersCount: 0,
    currentTaskId: null,
    currentTask: null,
    fetching: false
  }),
  actions: {
    async fetchServers(discordAccountId) {
      this.loading = true
      try {
        this.servers = await listGuildServers(discordAccountId)
        return this.servers
      } finally {
        this.loading = false
      }
    },
    async saveServer(data) {
      const server = await saveGuildServer(data)
      const idx = this.servers.findIndex(s => s.id === server.id)
      if (idx >= 0) {
        this.servers[idx] = server
      } else {
        this.servers.push(server)
      }
      return server
    },
    async deleteServer(id) {
      await deleteGuildServer(id)
      this.servers = this.servers.filter(s => s.id !== id)
      if (this.currentServer?.id === id) {
        this.currentServer = null
        this.members = []
      }
    },
    selectServer(server) {
      this.currentServer = server
      this.members = []
    },
    async fetchMembers(id) {
      const result = await listGuildServerMembers(id, { size: 1000 })
      this.members = result && Array.isArray(result.members) ? result.members : []
      this.membersCount = result ? (result.total || this.members.length) : 0
    },
    async fetchMembersList(id, params = {}) {
      const result = await listGuildServerMembers(id, params)
      if (result && Array.isArray(result.members)) {
        return { list: result.members, total: result.total || 0, totalPages: result.totalPages || 0 }
      }
      return { list: Array.isArray(result) ? result : [], total: 0, totalPages: 0 }
    },
    async fetchMembersCount(id, params = {}) {
      const count = await countGuildServerMembers(id, params)
      return count || { count: 0 }
    },
    async startFetch(data) {
      this.fetching = true
      try {
        this.currentTask = await fetchGuildMembers(data)
        return this.currentTask
      } finally {
        this.fetching = false
      }
    },
    async pollTask(taskId) {
      return getMemberFetchTask(taskId)
    },
    async resolveLink(link, discordAccountId) {
      return resolveMemberLink(link, discordAccountId)
    },
    async getActiveTasks() {
      return getMemberFetchTasks()
    },
    async getLatestTask(serverId) {
      return getLatestServerTask(serverId)
    },
    async stopTask(taskId) {
      return stopMemberFetchTask(taskId)
    },
    async loadMerchantConfig() {
      return getGuildMerchantConfig()
    }
  }
})
