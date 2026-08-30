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

// 响应拦截器：401 跳登录
http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error?.response?.status
    const data = error?.response?.data
    const msg = data?.message || data?.error || error.message || '请求失败'

    if (status === 401) {
      // 先清除 Pinia auth store（内存态），否则路由守卫会把 /login 重定向回来
      try {
        const auth = useAuthStore()
        auth.logout()
      } catch (e) {
        // Pinia 可能还没初始化，兜底手动清
        localStorage.removeItem('crm_token')
        localStorage.removeItem('crm_auth')
        localStorage.removeItem('crm_permissions')
        localStorage.removeItem('crm_menu_paths')
      }
      if (router.currentRoute.value.name !== 'Login') {
        ElMessage.warning('登录已过期，请重新登录')
        // 用 replace 避免浏览器后退又回到被踢下线的页面
        router.replace('/login')
      }
    } else if (status === 403) {
      ElMessage.error('权限不足 (403)')
    } else if (status === 404) {
      ElMessage.error('请求的资源不存在，请刷新页面或稍后重试')
    } else if (status === 409) {
      ElMessage.warning(data?.message || '数据冲突：该记录已存在')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default http
