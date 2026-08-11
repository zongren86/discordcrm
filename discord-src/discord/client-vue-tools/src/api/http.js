import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { API_BASE } from '@/config'

const http = axios.create({
  baseURL: API_BASE,
  timeout: 30000
})

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

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error?.response?.status
    const data = error?.response?.data
    const msg = data?.message || data?.error || error.message || '请求失败'

    if (status === 401) {
      localStorage.removeItem('crm_token')
      localStorage.removeItem('crm_auth')
      if (router.currentRoute.value.name !== 'Login') {
        ElMessage.warning('登录已过期，请重新登录')
        router.push('/login')
      }
    } else if (status === 403) {
      ElMessage.error('权限不足 (403)')
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default http
