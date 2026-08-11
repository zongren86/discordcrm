import { defineStore } from 'pinia'
import { login } from '@/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('crm_token') || '',
    agent: JSON.parse(localStorage.getItem('crm_auth') || 'null'),
    _ready: false
  }),
  getters: {
    isLoggedIn: (state) => !!state.token && !!state.agent,
    isReady: (state) => state._ready
  },
  actions: {
    async login(username, password) {
      const res = await login(username, password)
      this.token = res.token
      localStorage.setItem('crm_token', res.token)
      const agentData = {
        id: res.agentId ?? res.id,
        username: res.username,
        displayName: res.displayName ?? res.name,
        role: res.role,
        merchantId: res.merchantId ?? null,
        merchantName: res.merchantName ?? null
      }
      this.agent = agentData
      localStorage.setItem('crm_auth', JSON.stringify(agentData))
      this._ready = true
      return res
    },
    init() {
      if (this.token && this.agent) {
        this._ready = true
      }
    },
    logout() {
      this.token = ''
      this.agent = null
      this._ready = false
      localStorage.removeItem('crm_token')
      localStorage.removeItem('crm_auth')
    }
  }
})
