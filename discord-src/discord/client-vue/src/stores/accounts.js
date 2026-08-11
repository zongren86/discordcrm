import { defineStore } from 'pinia'
import {
  listAccounts, createAccount, updateAccount, deleteAccount,
  importToken, batchImport, syncAccountRelationships
} from '@/api'

export const useAccountsStore = defineStore('accounts', {
  state: () => ({
    accounts: [],
    loading: false
  }),
  getters: {
    // AccountDto 字段：accountType；同时兼容旧字段 type
    userAccounts: (state) => state.accounts.filter(a => a.accountType === 'USER' || a.type === 'USER'),
    botAccounts: (state) => state.accounts.filter(a => a.accountType === 'BOT' || a.type === 'BOT'),
    getAccountById: (state) => (id) => state.accounts.find(a => a.id === id)
  },
  actions: {
    async fetchAccounts() {
      this.loading = true
      try {
        const res = await listAccounts()
        this.accounts = Array.isArray(res) ? res : (res?.data || [])
        return this.accounts
      } finally {
        this.loading = false
      }
    },
    async createAccount(payload) {
      const res = await createAccount(payload)
      await this.fetchAccounts()
      return res
    },
    async updateAccount(id, payload) {
      const res = await updateAccount(id, payload)
      await this.fetchAccounts()
      return res
    },
    async deleteAccount(id) {
      await deleteAccount(id)
      await this.fetchAccounts()
    },
    async importToken(payload) {
      const res = await importToken(payload)
      await this.fetchAccounts()
      return res
    },
    async batchImport(payload) {
      const res = await batchImport(payload)
      await this.fetchAccounts()
      return res
    },
    async syncRelationships(id) {
      return await syncAccountRelationships(id)
    }
  }
})
