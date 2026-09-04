import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/global.css'
import { useAuthStore } from './stores/auth'
import { startWebSocket } from './services/websocket'
import permission from './directives/permission'

const pinia = createPinia()
const app = createApp(App)

// ============ 统一错误处理 ============
// 分类规则（按优先级）：
//   1. HMR/模块加载失败 → 提示刷新页面（开发环境常见，非真正 bug）
//   2. 网络/后端相关 → 友好提示网络问题
//   3. 其他 → 详细错误（开发模式显示完整堆栈）

const isDev = import.meta.env.DEV

function classifyError(err, defaultMsg) {
  const text = String(err?.message || err || defaultMsg)
  if (text.includes('Failed to fetch dynamically imported module') ||
      text.includes('Importing a module script failed') ||
      text.includes('Loading chunk') ||
      text.includes('HMR') || text.includes('vite')) {
    return {
      type: 'hmr',
      title: '页面需要刷新',
      desc: '代码已更新，请刷新页面 (Ctrl+Shift+R)',
      level: 'warning'
    }
  }
  if (text.includes('Network Error') || text.includes('timeout') ||
      text.includes('ECONNREFUSED') || text.includes('无法加载') ||
      text.includes('NetworkError')) {
    return {
      type: 'network',
      title: '网络连接异常',
      desc: '请检查后端服务是否正常运行，或刷新页面重试',
      level: 'error'
    }
  }
  if (text.includes('JWT') || text.includes('token') || text.includes('401') ||
      text.includes('登录') || text.includes('Unauthorized')) {
    return {
      type: 'auth',
      title: '登录状态已失效',
      desc: '请重新登录',
      level: 'warning'
    }
  }
  // Script error 没有有效信息，静默处理
  if (text === 'Script error.' || text.includes('Script error')) {
    return { type: 'silent', title: '', desc: '', level: 'info' }
  }
  return {
    type: 'unknown',
    title: '操作失败',
    desc: isDev ? text : '请稍后重试，如持续出现请联系管理员',
    level: 'error'
  }
}

// 防刷屏：同一错误 5 秒内只弹一次
const _lastErrorShown = new Map()
function showError(err) {
  const info = classifyError(err)
  // silent 类型不弹窗
  if (info.type === 'silent') return
  const key = info.type + '|' + info.title
  const now = Date.now()
  if (_lastErrorShown.has(key) && now - _lastErrorShown.get(key) < 5000) return
  _lastErrorShown.set(key, now)

  ElMessageBox.alert(
    info.desc + (isDev && info.type === 'unknown' ? '\n\n[开发信息] ' + err?.message : ''),
    info.title,
    {
      type: info.level,
      confirmButtonText: '知道了',
      customClass: 'global-error-dialog',
    }
  ).catch(() => {})
}

// Vue 组件错误
app.config.errorHandler = (err, vm, info) => {
  console.error('[Vue Error]', err, info)
  showError(err)
}

// 全局同步错误
window.onerror = (msg, source, lineno, colno, error) => {
  console.error('[Window Error]', msg, 'at', source, lineno)
  // 跨域 Script error 是浏览器安全机制，没有有效错误信息，静默处理
  if (msg === 'Script error.' || (typeof msg === 'string' && msg.includes('Script error'))) {
    console.warn('[Window Error] Script error (跨域脚本错误，静默处理)')
    return true
  }
  showError(error || msg)
}

// 未捕获的 Promise 拒绝
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  // Vite HMR 模块加载失败是正常的开发热更新现象，静默处理
  const text = String(reason?.message || reason || '')
  if (text.includes('dynamically imported module') || text.includes('HMR')) {
    console.warn('[HMR] 刷新页面即可:', text.split('\n')[0])
    event.preventDefault()
    return
  }
  // 网络请求被中断/取消（如页面切换导致的 abort）—— 静默处理
  if (reason?.name === 'AbortError' || text.includes('AbortError') || text.includes('canceled')) {
    console.warn('[Promise Error] 已取消的请求:', text.substring(0, 100))
    event.preventDefault()
    return
  }
  console.error('[Promise Error]', reason)
  showError(reason)
})

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.directive('permission', permission)

app.mount('#app')

// 初始化：如果已有登录信息，标记ready并启动WS
const auth = useAuthStore()
auth.init()
if (auth.isLoggedIn) {
  startWebSocket(auth.token)
}

console.log('Vue app mounted successfully')
