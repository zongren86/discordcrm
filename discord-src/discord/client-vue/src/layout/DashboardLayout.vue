<template>
  <div class="dashboard-layout">
    <!-- 左侧菜单 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="brand">
          <div class="brand-icon">
            <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor">
              <path d="M19.27 5.33C17.94 4.71 16.5 4.26 15 4a.09.09 0 00-.07.03c-.18.33-.39.76-.53 1.09a16.09 16.09 0 00-4.8 0c-.14-.34-.35-.76-.54-1.09-.01-.02-.04-.03-.07-.03-1.5.26-2.93.71-4.27 1.33-.01 0-.02.01-.03.02-2.72 4.07-3.47 8.03-3.1 11.95 0 .02.01.04.03.05 1.8 1.32 3.53 2.12 5.24 2.65.03.01.06 0 .07-.02.4-.55.76-1.13 1.07-1.74.02-.04 0-.08-.04-.09-.57-.22-1.11-.48-1.64-.78-.04-.02-.04-.08-.01-.11.11-.08.22-.17.33-.25.02-.02.05-.02.07-.01 3.44 1.57 7.15 1.57 10.55 0 .02-.01.05-.01.07.01.11.09.22.17.33.26.04.03.04.09-.01.11-.52.31-1.07.56-1.64.78-.04.01-.05.06-.04.09.32.61.68 1.19 1.07 1.74.03.01.06.02.09.01 1.72-.53 3.45-1.33 5.25-2.65.02-.01.03-.03.03-.05.44-4.53-.73-8.46-3.1-11.95-.01-.01-.02-.02-.04-.02z"/>
            </svg>
          </div>
          <div class="brand-text">
            <div class="brand-name">Discord CRM</div>
            <div class="brand-sub">管理后台</div>
          </div>
        </div>
      </div>

      <el-menu
        v-loading="menuLoading"
        class="sidebar-menu"
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :router="true"
        @select="handleSelect"
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
                  <el-badge
                    v-if="child.path === '/chat' && totalUnread > 0"
                    :value="totalUnread > 99 ? '99+' : totalUnread"
                    class="unread-badge"
                  />
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
                    <el-badge
                      v-if="grandchild.path === '/chat' && totalUnread > 0"
                      :value="totalUnread > 99 ? '99+' : totalUnread"
                      class="unread-badge"
                    />
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
              <el-badge
                v-if="item.path === '/chat' && totalUnread > 0"
                :value="totalUnread > 99 ? '99+' : totalUnread"
                class="unread-badge"
              />
            </span>
          </el-menu-item>
        </template>
      </el-menu>

      <div class="sidebar-footer">
        <div class="user-info">
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
      </div>
    </aside>

    <!-- 主工作区 -->
    <main class="main-area">
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, User, UserFilled, OfficeBuilding, DataAnalysis, SwitchButton, Shop, Setting,
  Lock, Document, Bell, Cpu, Monitor, Grid, Menu, Tools, Key, Tickets
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useConversationsStore } from '@/stores/conversations'
import { useAccountsStore } from '@/stores/accounts'
import { useGuildServersStore } from '@/stores/guildServers'
import { stopWebSocket } from '@/services/websocket'
import { api } from '@/api'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const accounts = useAccountsStore()
const conversations = useConversationsStore()
const guildServers = useGuildServersStore()

// 菜单加载状态
const menuLoading = ref(false)
const menuTree = ref([])

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

// 加载菜单树
async function loadMenuTree() {
  menuLoading.value = true
  try {
    const data = await api.get('/auth/menu-tree')
    if (Array.isArray(data)) {
      menuTree.value = data
    } else {
      ElMessage.error('服务器繁忙，请稍后再试')
      menuTree.value = []
    }
  } catch (e) {
    // API 不可用时提示用户
    ElMessage.error('服务器繁忙，请稍后再试')
    menuTree.value = []
  } finally {
    menuLoading.value = false
  }
}

const activeMenu = computed(() => route.path)

const defaultOpeneds = computed(() => {
  const paths = []
  function collect(items) {
    for (const item of items) {
      if (item.children && item.children.length > 0) {
        paths.push(item.path || item.code)
        collect(item.children)
      }
    }
  }
  collect(menuTree.value)
  return paths
})

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
  // 优先查找消息中心
  const chatPath = findFirstPath(menuTree.value.filter(item => item.code === 'chat'))
  if (chatPath) return chatPath
  // 否则查找第一个有路径的菜单项
  return findFirstPath(menuTree.value) || '/stats'
})

const totalUnread = computed(() =>
  conversations.conversations.reduce((sum, c) => sum + (c.unreadCount || 0), 0)
)

const prospectCount = computed(() =>
  conversations.conversations.filter(c => c.stage === 'PROSPECT').length
)

const baseTitle = 'D-CRM聚合'
const baseFaviconUrl = '/favicon.ico'

// 动态生成带徽章的favicon
function updateFavicon() {
  const size = 64
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')

  // 先绘制base64的D-CRM图标（简化版：深色圆形+字母）
  // 绘制渐变背景
  const gradient = ctx.createLinearGradient(0, 0, size, size)
  gradient.addColorStop(0, '#5865F2')
  gradient.addColorStop(1, '#EB459E')
  ctx.fillStyle = gradient
  ctx.beginPath()
  ctx.arc(size / 2, size / 2, size / 2 - 4, 0, Math.PI * 2)
  ctx.fill()

  // 绘制D字母
  ctx.fillStyle = '#fff'
  ctx.font = 'bold 36px Arial'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText('D', size / 2, size / 2)

  // 根据状态绘制徽章
  if (totalUnread.value > 0) {
    // 右上角绘制红色圆形徽章
    const badgeSize = 28
    const badgeX = size - 4
    const badgeY = 4

    ctx.fillStyle = '#ED4245'
    ctx.beginPath()
    ctx.arc(badgeX, badgeY, badgeSize / 2, 0, Math.PI * 2)
    ctx.fill()

    // 绘制数字
    ctx.fillStyle = '#fff'
    ctx.font = 'bold 18px Arial'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    const displayNum = totalUnread.value > 99 ? '99+' : totalUnread.value.toString()
    // 数字较多时缩小字号
    if (totalUnread.value > 99) {
      ctx.font = 'bold 12px Arial'
    }
    ctx.fillText(displayNum, badgeX, badgeY)
  } else if (prospectCount.value > 0) {
    // 右上角绘制红点
    const dotSize = 16
    const dotX = size - 2
    const dotY = 2

    ctx.fillStyle = '#ED4245'
    ctx.beginPath()
    ctx.arc(dotX, dotY, dotSize / 2, 0, Math.PI * 2)
    ctx.fill()

    // 白色边框
    ctx.strokeStyle = '#fff'
    ctx.lineWidth = 2
    ctx.stroke()
  }

  // 更新favicon
  const link = document.querySelector("link[rel~='icon']")
  if (!link) {
    const newLink = document.createElement('link')
    newLink.rel = 'icon'
    newLink.href = canvas.toDataURL('image/png')
    document.head.appendChild(newLink)
  } else {
    link.href = canvas.toDataURL('image/png')
  }
}

function updateTitle() {
  // 标签页标题保持简洁
  document.title = baseTitle
}

watch([totalUnread, prospectCount], () => {
  updateTitle()
  updateFavicon()
}, { immediate: true })

onBeforeUnmount(() => {
  document.title = baseTitle
  // 恢复原始favicon
  const link = document.querySelector("link[rel~='icon']")
  if (link) {
    link.href = baseFaviconUrl
  }
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

function handleSelect() {}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    // 1. 停止 WebSocket 连接
    try {
      stopWebSocket()
    } catch (e) {}
    
    // 2. 清理所有 store 状态
    auth.logout()
    conversations.reset()
    accounts.$reset()
    guildServers.$reset()
    
    // 3. 使用 nextTick 确保状态已更新，然后跳转
    await nextTick()
    router.push('/login')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error('退出登录失败')
    }
  }
}

onMounted(async () => {
  // 加载菜单树
  await loadMenuTree()
  
  // 设置默认路由：优先消息中心，否则第一个有权限的菜单
  if (route.path === '/' || route.path === '/login') {
    router.push(defaultRoute.value)
  }
  
  // 拉取基础数据
  try {
    await Promise.allSettled([
      accounts.fetchAccounts(),
      conversations.fetchConversations()
    ])
  } catch (e) {}
})
</script>

<style scoped>
.dashboard-layout {
  width: 100%;
  height: 100%;
  display: flex;
  background: var(--color-bg);
}

/* 侧边栏 */
.sidebar {
  width: 240px;
  flex-shrink: 0;
  background: var(--color-bg-2);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 20px 16px 16px;
  border-bottom: 1px solid var(--color-border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
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
}

.brand-name {
  font-size: var(--font-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
  line-height: 1.2;
}

.brand-sub {
  font-size: var(--font-xs);
  color: var(--color-text-3);
  margin-top: 2px;
}

.sidebar-menu {
  flex: 1;
  padding: 12px 8px;
  overflow-y: auto;
}

/* 菜单项布局 — 字体由 global.css 控制 */
.sidebar-menu :deep(.el-menu-item) {
  height: 46px;
  border-radius: 8px;
  margin-bottom: 2px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px !important;
}

/* 二级菜单项：缩进 + 更轻颜色 */
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

/* 图标尺寸 — 不在字体规范范围内，保留独立控制 */
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

/* 子菜单标题布局 — 字体由 global.css 控制 */
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

.unread-badge {
  flex-shrink: 0;
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
