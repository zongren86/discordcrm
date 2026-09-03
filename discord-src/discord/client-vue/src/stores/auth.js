import { defineStore } from 'pinia'
import { login, getAgentInfo, http } from '@/api'

export const useAuthStore = defineStore('auth', {
  state: () => {
    const safeParse = (str, fallback) => {
      try { return JSON.parse(str || JSON.stringify(fallback)) }
      catch { return fallback }
    }
    return {
      token: localStorage.getItem('crm_token') || '',
      agent: safeParse(localStorage.getItem('crm_auth'), null),
      permissions: safeParse(localStorage.getItem('crm_permissions'), []),
      menuPaths: safeParse(localStorage.getItem('crm_menu_paths'), []),
      _ready: false,
      connected: true,           // 后端连接状态
      disconnectedSince: null     // 断开时间戳，null=已连接
    }
  },
  getters: {
    isLoggedIn: (state) => !!state.token && !!state.agent,
    isReady: (state) => state._ready,
    hasPermission: (state) => (permission) => {
      if (!permission) return true
      if (!state.permissions || state.permissions.length === 0) return false
      return state.permissions.includes(permission)
    },
    hasAnyPermission: (state) => (permissions) => {
      if (!permissions || permissions.length === 0) return true
      if (!state.permissions || state.permissions.length === 0) return false
      return permissions.some(p => state.permissions.includes(p))
    },
    hasMenuPath: (state) => (path) => {
      if (!path) return true
      if (!state.menuPaths || state.menuPaths.length === 0) return false
      const normalized = path.replace(/^\//, '')
      return state.menuPaths.includes(path) || state.menuPaths.includes(normalized)
    }
  },
  actions: {
    async login(username, password) {
      const res = await login(username, password)
      this.token = res.token
      localStorage.setItem('crm_token', res.token)
      
      if (res.permissions) {
        this.permissions = res.permissions
        localStorage.setItem('crm_permissions', JSON.stringify(res.permissions))
      }
      
      const agentData = {
        id: res.agentId ?? res.id,
        username: res.username,
        displayName: res.displayName ?? res.name,
        accountType: res.accountType,
        merchantId: res.merchantId ?? null,
        merchantName: res.merchantName ?? null
      }
      this.agent = agentData
      localStorage.setItem('crm_auth', JSON.stringify(agentData))
      this._ready = true

      try {
        const permRes = await http.get('/auth/my-permissions')
        if (permRes) {
          this.menuPaths = permRes.menuPaths || []
          localStorage.setItem('crm_menu_paths', JSON.stringify(this.menuPaths))
        }
      } catch (e) {}

      return res
    },
    async fetchAgentInfo() {
      try {
        const res = await getAgentInfo()
        if (res) {
          const agentData = {
            id: res.agentId,
            username: res.username,
            displayName: res.displayName,
            accountType: res.accountType,
            merchantId: res.merchantId ?? null,
            merchantName: res.merchantName ?? null
          }
          this.agent = agentData
          localStorage.setItem('crm_auth', JSON.stringify(agentData))
          
          if (res.permissions) {
            this.permissions = res.permissions
            localStorage.setItem('crm_permissions', JSON.stringify(res.permissions))
          }
        }
      } catch (e) {
        console.warn('获取用户信息失败', e)
      }
    },
    async fetchPermissions() {
      try {
        const res = await http.get('/auth/my-permissions')
        if (res) {
          this.menuPaths = res.menuPaths || []
          localStorage.setItem('crm_menu_paths', JSON.stringify(this.menuPaths))
        }
      } catch (e) {}
    },
    init() {
      if (this.token && this.agent) {
        this._ready = true
      }
    },
    logout() {
      this.token = ''
      this.agent = null
      this.permissions = []
      this.menuPaths = []
      this._ready = false
      this.connected = true
      this.disconnectedSince = null
      localStorage.removeItem('crm_token')
      localStorage.removeItem('crm_auth')
      localStorage.removeItem('crm_permissions')
      localStorage.removeItem('crm_menu_paths')
    },
    setConnected(ok) {
      this.connected = !!ok
      this.disconnectedSince = ok ? null : Date.now()
    }
  }
})
