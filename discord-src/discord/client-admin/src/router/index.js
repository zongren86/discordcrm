import { createRouter, createWebHashHistory } from "vue-router"
import { useAuthStore } from "@/stores/auth"

const routes = [
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/Login.vue"),
    meta: { requiresAuth: false }
  },
  {
    path: "/no-permission",
    name: "NoPermission",
    component: () => import("@/views/NoPermission.vue"),
    meta: { requiresAuth: false }
  },
  {
    path: "/",
    name: "Layout",
    component: () => import("@/layout/DashboardLayout.vue"),
    meta: { requiresAuth: true },
    children: [
      { path: "guilds", name: "Guilds", component: () => import("@/views/Guilds.vue"), meta: { title: "服务器管理", icon: "OfficeBuilding", permissions: ["guilds"] } },
      { path: "guild-members", name: "GuildMembers", component: () => import("@/views/GuildMembers.vue"), meta: { title: "服务器成员", icon: "User", permissions: ["guild-members"] } },
      { path: "emulator", name: "Emulator", component: () => import("@/views/EmulatorView.vue"), meta: { title: "好友管理", icon: "Monitor", permissions: ["emulator"] } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

function getFirstAllowedPath(auth) {
  if (!auth.menuPaths || auth.menuPaths.length === 0) {
    return null
  }

  const pathList = [
    "/guilds", "/guild-members", "/emulator"
  ]

  for (const path of pathList) {
    if (auth.hasMenuPath(path)) {
      return path
    }
  }

  return null
}

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    console.log("路由守卫: 未登录，跳转到登录页")
    next("/login")
    return
  }

  if (to.path === "/login" && auth.isLoggedIn) {
    const targetPath = getFirstAllowedPath(auth)
    if (targetPath) {
      console.log("路由守卫: 已登录，跳转到:", targetPath)
      next(targetPath)
    } else {
      next("/no-permission")
    }
    return
  }

  if (to.path === "/" && auth.isLoggedIn) {
    const targetPath = getFirstAllowedPath(auth)
    if (targetPath) {
      console.log("路由守卫: 已登录，跳转到:", targetPath)
      next(targetPath)
    } else {
      next("/no-permission")
    }
    return
  }

  if (to.path === "/no-permission") {
    next()
    return
  }

  if (auth.isLoggedIn && to.meta.requiresAuth !== false) {
    if (!auth.menuPaths || auth.menuPaths.length === 0) {
      console.log("路由守卫: 无权限数据，跳转到无权限页")
      next("/no-permission")
      return
    }

    const path = to.path
    const hasPermission = auth.hasMenuPath(path)
    console.log(`路由守卫: 检查路径 ${path}, 权限: ${hasPermission}`)

    if (!hasPermission) {
      const targetPath = getFirstAllowedPath(auth)
      if (targetPath && targetPath !== path) {
        console.log("路由守卫: 无权限，跳转到:", targetPath)
        next(targetPath)
      } else {
        next("/no-permission")
      }
      return
    }
  }

  console.log("路由守卫: 放行", to.path)
  next()
})

export default router
