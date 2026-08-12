import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/layout/DashboardLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: 'chat', name: 'Chat', component: () => import('@/views/Chat.vue'), meta: { title: '消息中心', icon: 'ChatDotRound', permissions: ['chat'] } },
      { path: 'customers', name: 'Customers', component: () => import('@/views/Customers.vue'), meta: { title: '客户管理', icon: 'UserFilled', permissions: ['customer'] } },
      { path: 'accounts', name: 'Accounts', component: () => import('@/views/Accounts.vue'), meta: { title: 'Discord账号', icon: 'User', permissions: ['account'] } },
      { path: 'guilds', name: 'Guilds', component: () => import('@/views/Guilds.vue'), meta: { title: '服务器成员', icon: 'OfficeBuilding', permissions: ['guild'] } },
      { path: 'stats', name: 'Stats', component: () => import('@/views/Stats.vue'), meta: { title: '客户统计', icon: 'DataAnalysis', permissions: ['stats'] } },
      { path: 'roles', name: 'Roles', component: () => import('@/views/Roles.vue'), meta: { title: '角色管理', icon: 'Lock', permissions: ['role'] } },
      { path: 'features', name: 'Features', component: () => import('@/views/Features.vue'), meta: { title: '功能管理', icon: 'Grid', permissions: ['feature'] } },
      { path: 'users', name: 'Users', component: () => import('@/views/Users.vue'), meta: { title: '用户管理', icon: 'Setting', permissions: ['user'] } },
      { path: 'merchants', name: 'Merchants', component: () => import('@/views/Merchants.vue'), meta: { title: '商户管理', icon: 'Shop', permissions: ['merchant'] } },
      { path: 'audit', name: 'AuditLogs', component: () => import('@/views/AuditLogs.vue'), meta: { title: '审计日志', icon: 'Document', permissions: ['audit'] } },
      { path: 'reminders', name: 'Reminders', component: () => import('@/views/Reminders.vue'), meta: { title: '提醒中心', icon: 'Bell', permissions: ['reminder'] } },
      { path: 'ai-settings', name: 'AISettings', component: () => import('@/views/AISettings.vue'), meta: { title: 'AI配置', icon: 'Cpu', permissions: ['ai'] } },
      { path: 'emulator', name: 'Emulator', component: () => import('@/views/EmulatorView.vue'), meta: { title: '模拟器', icon: 'Monitor', permissions: ['emulator'] } },
      { path: 'account-numbers', name: 'AccountNumbers', component: () => import('@/views/AccountNumbers.vue'), meta: { title: '账号编号管理', icon: 'Tickets', permissions: ['account-numbers'] } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 获取用户有权限访问的第一个路径
function getFirstAllowedPath(auth) {
  const pathList = [
    '/chat', '/customers', '/accounts', '/guilds', '/stats',
    '/reminders', '/roles', '/features', '/users', '/merchants',
    '/audit', '/ai-settings', '/emulator', '/account-numbers'
  ]
  
  for (const path of pathList) {
    if (auth.hasMenuPath(path)) {
      return path
    }
  }
  
  // 兜底：如果没有任何权限，返回默认路径
  return '/chat'
}

// 标记是否正在处理登录跳转，防止死循环
let isNavigatingAfterLogin = false
let permissionsRefreshing = false

router.beforeEach(async (to, from, next) => {
  const auth = useAuthStore()
  
  // 未登录用户访问需要认证的页面，跳转到登录页
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    next('/login')
    return
  }
  
  // 已登录用户访问登录页，跳转到第一个有权限的页面
  if (to.path === '/login' && auth.isLoggedIn) {
    next(getFirstAllowedPath(auth))
    return
  }
  
  // 已登录用户访问没有权限的页面（使用menuPaths判断）
  if (auth.isLoggedIn && to.meta.requiresAuth !== false) {
    const path = to.path === '/' ? '/chat' : to.path
    if (!auth.hasMenuPath(path)) {
      // 如果不在缓存的menuPaths中，尝试刷新权限
      if (!permissionsRefreshing) {
        permissionsRefreshing = true
        try {
          await auth.fetchPermissions()
        } catch (e) {}
        permissionsRefreshing = false
        
        // 刷新后再检查一次
        if (auth.hasMenuPath(path)) {
          next()
          return
        }
      }
      
      const allowedPath = getFirstAllowedPath(auth)
      if (allowedPath === to.path) {
        next()
      } else {
        next(allowedPath)
      }
      return
    }
  }
  
  // 其他情况直接放行
  next()
})

export default router
