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
    redirect: '/emulator',
    meta: { requiresAuth: true },
    children: [
      { path: 'emulator', name: 'Emulator', component: () => import('@/views/EmulatorView.vue'), meta: { title: '模拟器', icon: 'Monitor' } },
      { path: 'accounts', name: 'Accounts', component: () => import('@/views/Accounts.vue'), meta: { title: 'Discord账号', icon: 'User' } },
      { path: 'guilds', name: 'Guilds', component: () => import('@/views/Guilds.vue'), meta: { title: '服务器成员', icon: 'OfficeBuilding' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    next('/login')
  } else if (to.path === '/login' && auth.isLoggedIn) {
    next('/emulator')
  } else {
    next()
  }
})

export default router
