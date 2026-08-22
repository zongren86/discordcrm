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
      { path: 'customers', name: 'Customers', component: () => import('@/views/Customers.vue'), meta: { title: '客户管理', icon: 'UserFilled', permissions: ['customers'] } },
      { path: 'guilds', name: 'Guilds', component: () => import('@/views/Guilds.vue'), meta: { title: '服务器列表', icon: 'OfficeBuilding', permissions: ['guilds'] } },
      { path: 'guild-members', name: 'GuildMembers', component: () => import('@/views/GuildMembers.vue'), meta: { title: '服务器成员', icon: 'User', permissions: ['guild-members'] } },
      { path: 'emulator', name: 'Emulator', component: () => import('@/views/EmulatorView.vue'), meta: { title: '好友管理', icon: 'Monitor', permissions: ['emulator'] } },
      { path: 'ai-settings', name: 'AISettings', component: () => import('@/views/AISettings.vue'), meta: { title: 'AI配置', icon: 'Cpu', permissions: ['ai-settings'] } },
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

// 获取用户有权限访问的第一个路径（优先消息中心）
function getFirstAllowedPath(auth) {
  // 优先检查消息中心
  if (auth.hasMenuPath('/chat')) {
    return '/chat'
  }
  
  // 如果没有消息中心权限，按顺序查找第一个有权限的菜单
  const pathList = [
    '/stats', '/account-numbers', '/accounts', '/customers',
    '/guilds', '/guild-members', '/emulator', '/ai-settings',
    '/users', '/roles', '/features', '/audit'
  ]
  
  for (const path of pathList) {
    if (auth.hasMenuPath(path)) {
      return path
    }
  }
  
  // 兜底：如果没有任何权限，返回默认路径
  return '/chat'
}

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()
  
  // 未登录用户访问需要认证的页面，跳转到登录页
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    console.log('路由守卫: 未登录，跳转到登录页')
    next('/login')
    return
  }
  
  // 已登录用户访问登录页或根路径，跳转到第一个有权限的页面
  if ((to.path === '/login' || to.path === '/') && auth.isLoggedIn) {
    const targetPath = getFirstAllowedPath(auth)
    console.log('路由守卫: 已登录，跳转到:', targetPath)
    next(targetPath)
    return
  }
  
  // 已登录用户，使用缓存权限检查（登录时已获取，不调API）
  if (auth.isLoggedIn && to.meta.requiresAuth !== false) {
    const path = to.path
    const hasPermission = auth.hasMenuPath(path)
    console.log(`路由守卫: 检查路径 ${path}, 权限: ${hasPermission}, menuPaths:`, auth.menuPaths)
    
    if (!hasPermission) {
      // 没有权限，跳转到第一个有权限的页面
      const targetPath = getFirstAllowedPath(auth)
      console.log('路由守卫: 无权限，跳转到:', targetPath)
      next(targetPath)
      return
    }
  }
  
  // 其他情况直接放行
  console.log('路由守卫: 放行', to.path)
  next()
})

export default router
