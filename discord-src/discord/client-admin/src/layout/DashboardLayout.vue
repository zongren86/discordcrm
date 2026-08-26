<template>
  <div class="dashboard-layout" :class="{ 'sidebar-collapsed': theme.sidebarCollapsed }">
    <!-- 左侧菜单 -->
    <aside class="sidebar" :class="{ collapsed: theme.sidebarCollapsed }">
      <div class="sidebar-header">
        <div class="brand">
          <div class="brand-icon">
            <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
              <path d="M19.27 5.33C17.94 4.71 16.5 4.26 15 4a.09.09 0 00-.07.03c-.18.33-.39.76-.53 1.09a16.09 16.09 0 00-4.8 0c-.14-.34-.35-.76-.54-1.09-.01-.02-.04-.03-.07-.03-1.5.26-2.93.71-4.27 1.33-.01 0-.02.01-.03.02-2.72 4.07-3.47 8.03-3.1 11.95 0 .02.01.04.03.05 1.8 1.32 3.53 2.12 5.24 2.65.03.01.06 0 .07-.02.4-.55.76-1.13 1.07-1.74.02-.04 0-.08-.04-.09-.57-.22-1.11-.48-1.64-.78-.04-.02-.04-.08-.01-.11.11-.08.22-.17.33-.25.02-.02.05-.02.07-.01 3.44 1.57 7.15 1.57 10.55 0 .02-.01.05-.01.07.01.11.09.22.17.33.26.04.03.04.09-.01.11-.52.31-1.07.56-1.64.78-.04.01-.05.06-.04.09.32.61.68 1.19 1.07 1.74.03.01.06.02.09.01 1.72-.53 3.45-1.33 5.25-2.65.02-.01.03-.03.03-.05.44-4.53-.73-8.46-3.1-11.95-.01-.01-.02-.02-.04-.02z"/>
            </svg>
          </div>
          <div v-if="!theme.sidebarCollapsed" class="brand-text">
            <div class="brand-name">Discord CRM</div>
          </div>
          <!-- 折叠时显示的展开按钮 -->
          <el-tooltip v-if="theme.sidebarCollapsed" content="展开菜单" placement="right">
            <el-button class="sidebar-toggle-btn" circle size="small" @click="theme.toggleSidebar()">
              <el-icon><Expand /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>

      <el-menu
        v-loading="menuLoading"
        class="sidebar-menu"
        :default-active="activeMenu"
        v-model:openeds="openeds"
        @select="handleSelect"
        :collapse="theme.sidebarCollapsed"
        :collapse-transition="false"
      >
        <!-- 动态菜单渲染 -->
        <template v-for="item in menuTree" :key="item.path || item.code">
          <!-- 有子菜单的项 -->
          <el-sub-menu v-if="item.children && item.children.length > 0" :index="item.path || item.code">
            <template #title>
              <el-icon v-if="item.icon"><component :is="resolveIcon(item.icon)" /></el-icon>
              <span>{{ item.title }}</span>
            </template>
            <!-- 子菜单（支持递归） -->
            <template v-for="child in item.children" :key="child.path || child.code">
              <el-menu-item v-if="!child.children || child.children.length === 0" :index="child.path || child.code">
                <el-icon v-if="child.icon"><component :is="resolveIcon(child.icon)" /></el-icon>
                <span class="menu-title">
                  {{ child.title }}
                  
                </span>
              </el-menu-item>
              <el-sub-menu v-else :index="child.path || child.code">
                <template #title>
                  <el-icon v-if="child.icon"><component :is="resolveIcon(child.icon)" /></el-icon>
                  <span>{{ child.title }}</span>
                </template>
                <!-- 三级菜单 -->
                <el-menu-item v-for="grandchild in child.children" :key="grandchild.path || grandchild.code" :index="grandchild.path || grandchild.code">
                  <el-icon v-if="grandchild.icon"><component :is="resolveIcon(grandchild.icon)" /></el-icon>
                  <span class="menu-title">
                    {{ grandchild.title }}
                    
                  </span>
                </el-menu-item>
              </el-sub-menu>
            </template>
          </el-sub-menu>
          <!-- 没有子菜单的项 -->
          <el-menu-item v-else :index="item.path || item.code">
            <el-icon v-if="item.icon"><component :is="resolveIcon(item.icon)" /></el-icon>
            <span class="menu-title">
              {{ item.title }}
              
            </span>
          </el-menu-item>
        </template>
      </el-menu>

      <div class="sidebar-footer">
        <div v-if="!theme.sidebarCollapsed" class="user-info">
          <el-avatar
            :size="36"
            class="user-avatar"
            :style="avatarStyle"
          >
            {{ agentInitial }}
          </el-avatar>
          <div class="user-meta">
            <div class="user-name">{{ auth.agent?.displayName || auth.agent?.username || 'Agent' }}</div>
            <div class="user-role">{{ roleLabel }}</div>
          </div>
          <el-tooltip content="退出登录">
            <el-button link type="danger" class="logout-btn" @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <div v-else class="user-info-collapsed">
          <el-avatar
            :size="36"
            class="user-avatar"
            :style="avatarStyle"
          >
            {{ agentInitial }}
          </el-avatar>
        </div>
      </div>
    </aside>

    <!-- 主工作区 -->
    <div class="main-wrapper">
      <!-- 顶栏 -->
      <header class="top-bar">
        <div class="top-bar-left">
          <!-- 菜单展开/收起按钮 -->
          <el-tooltip :content="theme.sidebarCollapsed ? '展开菜单' : '收起菜单'" placement="bottom">
            <el-button circle size="small" class="top-bar-btn" @click="theme.toggleSidebar()">
              <el-icon><Fold v-if="!theme.sidebarCollapsed" /><Expand v-else /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
        <div class="top-bar-right">
          <!-- 深色模式切换 -->
          <el-tooltip :content="theme.isDark ? '浅色模式' : '深色模式'" placement="bottom">
            <el-button circle size="small" class="top-bar-btn" @click="theme.toggleTheme()">
              <el-icon><Sunny v-if="theme.isDark" /><Moon v-else /></el-icon>
            </el-button>
          </el-tooltip>
          <!-- 全屏切换 -->
          <el-tooltip :content="theme.isFullscreen ? '退出全屏' : '全屏'" placement="bottom">
            <el-button circle size="small" class="top-bar-btn" @click="theme.toggleFullscreen()">
              <el-icon><FullScreen /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </header>

      <main class="main-area">
        <router-view />
      </main>
    </div>
  </div>
</template>
<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, User, UserFilled, OfficeBuilding, DataAnalysis, SwitchButton, Shop, Setting,
  Lock, Document, Bell, Cpu, Monitor, Grid, Menu, Tools, Key, Tickets,
  Fold, Expand, Sunny, Moon, Aim, FullScreen
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useGuildServersStore } from '@/stores/guildServers'
import { useThemeStore } from '@/stores/theme'
import { stopWebSocket } from '@/services/websocket'
import { api } from '@/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const guildServers = useGuildServersStore()
const theme = useThemeStore()

// 菜单加载状态
const menuLoading = ref(false)
const menuTree = ref([])
const openeds = ref([])

// 图标映射表
const iconMap = {
  'ChatDotRound': ChatDotRound,
  'User': User,
  'UserFilled': UserFilled,
  'OfficeBuilding': OfficeBuilding,
  'DataAnalysis': DataAnalysis,
  'SwitchButton': SwitchButton,
  'Shop': Shop,
  'Setting': Setting,
  'Lock': Lock,
  'Document': Document,
  'Bell': Bell,
  'Cpu': Cpu,
  'Monitor': Monitor,
  'Grid': Grid,
  'Menu': Menu,
  'Tools': Tools,
  'Key': Key,
  'Tickets': Tickets
}

function resolveIcon(iconName) {
  if (!iconName) return Menu
  return iconMap[iconName] || Menu
}

// 收集所有需要展开的菜单路径
function collectOpeneds(items) {
  const paths = []
  function collect(list) {
    for (const item of list) {
      if (item.children && item.children.length > 0) {
        paths.push(item.path || item.code)
        collect(item.children)
      }
    }
  }
  collect(items)
  return paths
}

const INCLUDED_CODES = ['guilds', 'guild-members', 'friend-manage']

// 需要过滤掉的菜单路径和代码
const EXCLUDED_PATHS = [
  '/dashboard', '/chat', '/stats', '/account-numbers',
  '/accounts', '/customers', '/ai-settings', '/users',
  '/roles', '/features', '/audit', '/logs', '/settings',
  '/friends', '/customer-service', '/merchants'
]
const EXCLUDED_CODES = ['dashboard', 'chat', 'customer', 'config', 'system', 'log']

function filterMenuTree(items) {
  return items
    .filter(item => {
      if (item.path && EXCLUDED_PATHS.some(p => item.path.startsWith(p))) return false
      if (item.code && EXCLUDED_CODES.includes(item.code)) return false
      return true
    })
    .map(item => {
      if (item.children && item.children.length > 0) {
        item.children = filterMenuTree(item.children)
      }
      return item
    })
    .filter(item => {
      if (item.children && item.children.length > 0) return true
      if (item.code && INCLUDED_CODES.includes(item.code)) return true
      if (item.path && INCLUDED_CODES.some(code => item.path.includes(code))) return true
      return false
    })
}

// 加载菜单树
async function loadMenuTree() {
  menuLoading.value = true
  try {
    const data = await api.get('/auth/menu-tree')
    if (Array.isArray(data)) {
      const filteredData = filterMenuTree(data)
      menuTree.value = filteredData
      await nextTick()
      openeds.value = collectOpeneds(filteredData)
    } else {
      ElMessage.error('服务器繁忙，请稍后再试')
      menuTree.value = []
    }
  } catch (e) {
    ElMessage.error('服务器繁忙，请稍后再试')
    menuTree.value = []
  } finally {
    menuLoading.value = false
  }
}

const activeMenu = computed(() => route.path)

const defaultRoute = computed(() => {
  const findFirstPath = (items) => {
    for (const item of items) {
      if (item.path && !item.children) return item.path
      if (item.children) {
        const found = findFirstPath(item.children)
        if (found) return found
      }
    }
    return null
  }
  return findFirstPath(menuTree.value) || '/guilds'
})

const agentInitial = computed(() => {
  const name = auth.agent?.displayName || auth.agent?.username || 'A'
  return name.charAt(0).toUpperCase()
})

const roleLabel = computed(() => {
  const roleMap = {
    PLATFORM_ADMIN: '平台管理员',
    MERCHANT_ADMIN: '商户管理员',
    MANAGER: '主管',
    SALES: '销售',
    SERVICE: '客服'
  }
  const role = auth.agent?.role
  const label = roleMap[role] || role || '用户'
  const merchant = auth.agent?.merchantName
  return merchant ? `${label} · ${merchant}` : label
})

const avatarStyle = computed(() => ({
  background: 'linear-gradient(135deg, var(--color-primary), var(--color-pink))',
  color: '#fff',
  fontWeight: 600
}))

function handleSelect(index) {
  if (!index) return
  console.log('菜单点击:', index, '当前路径:', route.path)
  
  // 规范化路径：确保以 / 开头
  let targetPath = index
  if (!targetPath.startsWith('/')) {
    targetPath = '/' + targetPath
  }
  
  // 避免重复导航到相同路径
  if (route.path === targetPath) {
    console.log('当前已是该路径，跳过导航')
    return
  }
  
  try {
    router.push(targetPath).then(() => {
      console.log('导航成功:', targetPath)
    }).catch(err => {
      console.error('导航失败:', err)
      // 如果导航失败，尝试 replace
      router.replace(targetPath).catch(e => console.error('replace 也失败:', e))
    })
  } catch (e) {
    console.error('导航异常:', e)
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    try {
      stopWebSocket()
    } catch (e) {}
    
    auth.logout()
    guildServers.$reset()
    
    await nextTick()
    router.push('/login')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error('退出登录失败')
    }
  }
}

// 根据权限获取默认路由（默认服务器列表）
function getDefaultRoute() {
  // 新项目只有3个路由，按优先级排序
  const pathList = ['/guilds', '/guild-members', '/emulator']
  
  for (const path of pathList) {
    if (auth.hasMenuPath(path)) {
      return path
    }
  }
  
  return '/guilds'
}

onMounted(async () => {
  await loadMenuTree()
  
  // 登录后跳转到第一个有权限的页面（服务器列表）
  if (route.path === '/' || route.path === '/login') {
    router.push(getDefaultRoute())
  }
})
</script>

<style scoped>
.dashboard-layout {
  width: 100%;
  height: 100%;
  display: flex;
  background: var(--color-bg);
  overflow: hidden;
}

/* 侧边栏 */
.sidebar {
  width: 240px;
  flex-shrink: 0;
  background: var(--color-bg-2);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
  overflow: hidden;
}

.sidebar.collapsed {
  width: 64px;
}

.sidebar-header {
  padding: 16px 16px;
  border-bottom: 1px solid var(--color-border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
}

.brand-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.brand-name {
  font-size: var(--font-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
  line-height: 1.2;
}

.sidebar-toggle-btn {
  margin-left: auto;
}

.sidebar-menu {
  flex: 1;
  padding: 12px 8px;
  overflow-y: auto;
}

/* 菜单项布局 */
.sidebar-menu :deep(.el-menu-item) {
  height: 46px;
  border-radius: 8px;
  margin-bottom: 2px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px !important;
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item) {
  height: 38px;
  margin-left: 8px;
  padding: 0 14px 0 28px !important;
  color: var(--color-text-2);
  border-radius: 6px;
  position: relative;
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item::before) {
  content: '';
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--color-text-3);
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item:hover) {
  color: var(--color-text);
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item.is-active) {
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.sidebar-menu :deep(.el-sub-menu .el-menu-item.is-active::before) {
  background: var(--color-primary);
}

.sidebar-menu :deep(.el-menu-item .el-icon) {
  font-size: 18px;
}

.sidebar-menu :deep(.el-menu-item:not(.el-sub-menu .el-menu-item) .el-icon) {
  font-size: 20px;
}

.sidebar-menu :deep(.el-menu-item .menu-title) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.sidebar-menu :deep(.el-sub-menu__title) {
  height: 46px;
  border-radius: 8px;
  margin-bottom: 2px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px !important;
  color: var(--color-text);
}

.sidebar-menu :deep(.el-sub-menu__title .el-icon) {
  font-size: 20px;
}

/* 底部用户信息 */
.sidebar-footer {
  border-top: 1px solid var(--color-border);
  padding: 14px 16px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-info-collapsed {
  display: flex;
  justify-content: center;
}

.user-avatar {
  flex-shrink: 0;
}

.user-meta {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-size: var(--font-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-size: var(--font-xs);
  color: var(--color-text-3);
  margin-top: 2px;
}

.logout-btn {
  padding: 4px;
  font-size: 18px;
}

/* 主内容区 */
.main-wrapper {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.top-bar {
  height: 48px;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  flex-shrink: 0;
}

.top-bar-left,
.top-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.top-bar-btn {
  color: var(--color-text-2);
}

.top-bar-btn:hover {
  color: var(--color-primary);
  background: var(--color-primary-light);
}

.main-area {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.main-area > * {
  flex: 1;
  min-height: 0;
}

.page-enter-active,
.page-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.page-enter-from {
  opacity: 0;
  transform: translateX(6px);
}
.page-leave-to {
  opacity: 0;
  transform: translateX(-6px);
}
</style>
