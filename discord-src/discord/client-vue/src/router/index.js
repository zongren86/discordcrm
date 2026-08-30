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
      { path: 'stats', name: 'Stats', component: () => import('@/views/Stats.vue'), meta: { title: '仪表盘', icon: 'DataAnalysis', permissions: ['dashboard'] } },
      { path: 'chat', name: 'Chat', component: () => import('@/views/Chat.vue'), meta: { title: '消息中心', icon: 'ChatDotRound', permissions: ['chat'] } },
      { path: 'account-numbers', name: 'AccountNumbers', component: () => import('@/views/AccountNumbers.vue'), meta: { title: '账号编号管理', icon: 'Tickets', permissions: ['account-numbers'] } },
      { path: 'accounts', name: 'Accounts', component: () => import('@/views/Accounts.vue'), meta: { title: 'Discord账号管理', icon: 'User', permissions: ['accounts'] } },
      { path: 'customers', name: 'Customers', component: () => import('@/views/Customers.vue'), meta: { title: '客户管理', icon: 'UserFilled', permissions: ['customers'] } },      { path: 'ai-settings', name: 'AISettings', component: () => import('@/views/AISettings.vue'), meta: { title: 'AI配置', icon: 'Cpu', permissions: ['ai-settings'] } },
      { path: 'agent-servers', name: 'AgentServers', component: () => import('@/views/AgentServers.vue'), meta: { title: '代理管理', icon: 'Connection', permissions: ['agent-servers'] } },
      { path: 'merchants', name: 'Merchants', component: () => import('@/views/Merchants.vue'), meta: { title: '商户管理', icon: 'OfficeBuilding', permissions: ['merchants'] } },
      { path: 'users', name: 'Users', component: () => import('@/views/Users.vue'), meta: { title: '用户管理', icon: 'User', permissions: ['users'] } },
      { path: 'roles', name: 'Roles', component: () => import('@/views/Roles.vue'), meta: { title: '角色管理', icon: 'Lock', permissions: ['roles'] } },
      { path: 'features', name: 'Features', component: () => import('@/views/Features.vue'), meta: { title: '功能管理', icon: 'Grid', permissions: ['features'] } },
      { path: 'audit', name: 'AuditLogs', component: () => import('@/views/AuditLogs.vue'), meta: { title: '操作日志', icon: 'Document', permissions: ['audit'] } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 获取用户有权限访问的第一个路径
function getFirstAllowedPath(auth) {
  // 如果没有任何菜单权限，返回 null
  if (!auth.menuPaths || auth.menuPaths.length === 0) {
    return null
  }
  
  // 优先检查消息中心
  if (auth.hasMenuPath('/chat')) {
    return '/chat'
  }
  
  // 按顺序查找第一个有权限的菜单
  const pathList = [
    '/stats', '/account-numbers', '/accounts', '/customers',
    '/ai-settings', '/agent-servers',
    '/merchants', '/users', '/roles', '/features', '/audit'
  ]
  
  for (const path of pathList) {
    if (auth.hasMenuPath(path)) {
      return path
    }
  }
  
  // 没有任何有权限的路径
  return null
}

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()
  
  // 未登录用户访问需要认证的页面，跳转到登录页
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    console.log('路由守卫: 未登录，跳转到登录页')
    next('/login')
    return
  }
  
  // 已登录用户访问登录页
  if (to.path === '/login' && auth.isLoggedIn) {
    const targetPath = getFirstAllowedPath(auth)
    if (targetPath) {
      console.log('路由守卫: 已登录，跳转到:', targetPath)
      next(targetPath)
    } else {
      // 没有任何权限，清除状态并跳转到登录页
      auth.logout()
      next('/login')
    }
    return
  }
  
  // 已登录用户访问根路径
  if (to.path === '/' && auth.isLoggedIn) {
    const targetPath = getFirstAllowedPath(auth)
    if (targetPath) {
      console.log('路由守卫: 已登录，跳转到:', targetPath)
      next(targetPath)
    } else {
      // 没有任何权限，清除状态并跳转到登录页
      auth.logout()
      next('/login')
    }
    return
  }
  
  // 已登录用户，检查权限
  if (auth.isLoggedIn && to.meta.requiresAuth !== false) {
    // 如果没有任何菜单权限，直接跳到登录页
    if (!auth.menuPaths || auth.menuPaths.length === 0) {
      console.log('路由守卫: 无权限数据，跳转到登录页')
      auth.logout()
      next('/login')
      return
    }
    
    const path = to.path
    const hasPermission = auth.hasMenuPath(path)
    console.log(`路由守卫: 检查路径 ${path}, 权限: ${hasPermission}`)
    
    if (!hasPermission) {
      // 没有权限，跳转到第一个有权限的页面
      const targetPath = getFirstAllowedPath(auth)
      if (targetPath && targetPath !== path) {
        console.log('路由守卫: 无权限，跳转到:', targetPath)
        next(targetPath)
      } else {
        // 真的没有任何权限或已在目标路径，放行
        next()
      }
      return
    }
  }
  
  // 其他情况直接放行
  console.log('路由守卫: 放行', to.path)
  next()
})

export default router
