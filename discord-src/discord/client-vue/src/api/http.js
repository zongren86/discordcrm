import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import { API_BASE } from '@/config'

// 使用配置文件中的 API_BASE
const http = axios.create({
  baseURL: API_BASE,
  timeout: 120000
})

// ============ 全局连接状态监控 ============
let networkErrorCount = 0          // 累计网络错误次数
let disconnectBannerShown = false  // 是否已弹断连提示
let longDisconnectLoggedOut = false  // 长时间断连已自动登出

const NETWORK_ERROR_TYPES = ['Network Error', 'ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'timeout', 'ERR_NETWORK', 'ERR_CONNECTION']
const DISCONNECT_AUTO_LOGOUT_MS = 30_000  // 断连 30 秒后自动登出跳登录页
const PING_INTERVAL_MS = 3000              // 心跳间隔

/** 判断 axios 错误是否属于后端不可达类型 */
function isNetworkError(err) {
  if (!err) return false
  const code = err.code || ''
  const msg = err.message || ''
  // axios 无 response → 纯网络错误
  if (!err.response && (err.request || NETWORK_ERROR_TYPES.some(t => msg.includes(t)))) {
    return true
  }
  // 502 / 503 / 504 → 网关/后端重启中
  const status = err.response?.status
  return status === 502 || status === 503 || status === 504 || status === 521 || status === 522 || status === 523
}

/** 后台心跳 —— 定期 ping /api/auth/ping 检测后端是否恢复 */
let _pingTimer = null
function startConnectionMonitor() {
  if (_pingTimer) return
  _pingTimer = setInterval(async () => {
    const auth = useAuthStore()
    if (!auth.isLoggedIn) return
    try {
      await axios.get(API_BASE + '/auth/ping', {
        headers: { Authorization: 'Bearer ' + auth.token },
        timeout: 2000,
        // 不走 http 实例的拦截器（避免触发错误处理）
        validateStatus: (s) => s < 500
      })
      // Ping 成功 → 后端恢复
      if (!auth.connected) {
        auth.setConnected(true)
        networkErrorCount = 0
        disconnectBannerShown = false
        longDisconnectLoggedOut = false
        ElMessage.success('✅ 服务器连接已恢复')
      }
    } catch (pingErr) {
      // Ping 失败 → 如果是 401，说明 token 也失效了，直接跳登录
      if (pingErr.response?.status === 401) {
        forceLogout('登录已过期，请重新登录')
        return
      }
      // 继续保持 disconnected
      auth.setConnected(false)
      const since = auth.disconnectedSince || Date.now()
      auth.disconnectedSince = since
      // 断连超过阈值 → 自动登出跳登录页
      if (!longDisconnectLoggedOut && Date.now() - since > DISCONNECT_AUTO_LOGOUT_MS) {
        longDisconnectLoggedOut = true
        forceLogout('服务器连接已中断 ' + Math.round((Date.now() - since) / 1000) + ' 秒，请重新登录')
      }
    }
  }, PING_INTERVAL_MS)
}

function forceLogout(msg) {
  longDisconnectLoggedOut = true
  disconnectBannerShown = true  // 防止 401 时又弹一次 banner
  try {
    const auth = useAuthStore()
    auth.logout()
  } catch (e) {
    localStorage.removeItem('crm_token')
    localStorage.removeItem('crm_auth')
    localStorage.removeItem('crm_permissions')
    localStorage.removeItem('crm_menu_paths')
  }
  ElMessage.error(msg)
  if (router.currentRoute.value.name !== 'Login') {
    router.replace('/login')
  }
}

// 请求拦截器：附加 Bearer Token
http.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('crm_token')
    if (token) {
      config.headers.Authorization = 'Bearer ' + token
    }
    return config
  },
  (err) => Promise.reject(err)
)

// 响应拦截器：401 跳登录 + 网络错误检测后端断连
http.interceptors.response.use(
  (response) => {
    // 请求成功 → 重置网络错误计数
    if (networkErrorCount > 0) {
      networkErrorCount = 0
      disconnectBannerShown = false
      const auth = useAuthStore()
      if (!auth.connected) auth.setConnected(true)
    }
    return response.data
  },
  (error) => {
    const status = error?.response?.status
    const data = error?.response?.data
    const msg = data?.message || data?.error || error.message || '请求失败'

    if (status === 401) {
      forceLogout('登录已过期，请重新登录')
    } else if (isNetworkError(error)) {
      // 后端不可达（重启中 / 已挂 / 网络断）
      networkErrorCount++
      const auth = useAuthStore()
      auth.setConnected(false)
      auth.disconnectedSince = auth.disconnectedSince || Date.now()
      // 累计错误 ≥ 2 次才弹提示（避免瞬时抖动）
      if (!disconnectBannerShown && networkErrorCount >= 2) {
        disconnectBannerShown = true
        ElMessage.warning('⚠️ 服务器连接失败，正在重试...（如持续显示请检查后端是否重启）')
      }
      // 启动心跳监控（只需启动一次）
      startConnectionMonitor()
    } else if (status === 403) {
      ElMessage.error('权限不足 (403)')
    } else if (status === 404) {
      // 静默 404，不刷屏
    } else if (status === 409) {
      ElMessage.warning(data?.message || '数据冲突：该记录已存在')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

// 启动心跳（应用初始化时就启，即使没登录也无害）
startConnectionMonitor()

export default http
