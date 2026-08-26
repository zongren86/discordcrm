import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
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

// 全局错误处理
app.config.errorHandler = (err, vm, info) => {
  console.error('Vue Error:', err, info)
  alert('Vue Error: ' + err.message)
}

window.onerror = (msg, source, lineno, colno, error) => {
  console.error('Window Error:', msg)
  alert('Error: ' + msg)
}

window.addEventListener('unhandledrejection', (event) => {
  console.error('Promise Error:', event.reason)
  alert('Promise Error: ' + event.reason)
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
