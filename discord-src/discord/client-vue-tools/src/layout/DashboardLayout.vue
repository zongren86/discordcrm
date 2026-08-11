<template>
  <div class="dashboard-layout">
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
            <div class="brand-sub">工具管理台</div>
          </div>
        </div>
      </div>

      <el-menu
        class="sidebar-menu"
        :default-active="activeMenu"
        :router="true"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer">
        <div class="user-info">
          <el-avatar :size="36" class="user-avatar" :style="avatarStyle">
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
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Monitor, User, OfficeBuilding, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useAccountsStore } from '@/stores/accounts'
import { useGuildServersStore } from '@/stores/guildServers'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const accounts = useAccountsStore()
const guildServers = useGuildServersStore()

const menuItems = [
  { path: '/emulator', title: '模拟器', icon: Monitor },
  { path: '/accounts', title: 'Discord账号', icon: User },
  { path: '/guilds', title: '服务器成员', icon: OfficeBuilding }
]

const activeMenu = computed(() => route.path)

const agentInitial = computed(() => {
  const name = auth.agent?.displayName || auth.agent?.username || 'A'
  return name.charAt(0).toUpperCase()
})

const roleLabel = computed(() => {
  const roleMap = { PLATFORM_ADMIN: '平台管理员', MERCHANT_ADMIN: '商户管理员', MANAGER: '主管', SALES: '销售', SERVICE: '客服' }
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

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' })
    auth.logout()
    accounts.$reset()
    guildServers.$reset()
    router.push('/login')
  } catch (e) {}
}

onMounted(async () => {
  try {
    await Promise.allSettled([accounts.fetchAccounts()])
  } catch (e) {}
})
</script>

<style scoped>
.dashboard-layout { width: 100%; height: 100%; display: flex; background: var(--color-bg); }
.sidebar { width: 240px; flex-shrink: 0; background: var(--color-bg-2); border-right: 1px solid var(--color-border); display: flex; flex-direction: column; }
.sidebar-header { padding: 20px 16px 16px; border-bottom: 1px solid var(--color-border); }
.brand { display: flex; align-items: center; gap: 12px; }
.brand-icon { width: 40px; height: 40px; border-radius: 12px; background: linear-gradient(135deg, var(--color-primary), var(--color-pink)); color: #fff; display: flex; align-items: center; justify-content: center; }
.brand-name { font-size: 16px; font-weight: 700; color: var(--color-text); line-height: 1.2; }
.brand-sub { font-size: 11px; color: var(--color-text-3); margin-top: 2px; }
.sidebar-menu { flex: 1; padding: 12px 8px; overflow-y: auto; }
.sidebar-menu :deep(.el-menu-item) { height: 44px; border-radius: 8px; margin-bottom: 2px; display: flex; align-items: center; gap: 12px; padding: 0 16px !important; }
.sidebar-menu :deep(.el-menu-item .el-icon) { font-size: 18px; }
.sidebar-menu :deep(.el-menu-item span) { flex: 1; font-size: 14px; }
.sidebar-footer { border-top: 1px solid var(--color-border); padding: 14px 16px; }
.user-info { display: flex; align-items: center; gap: 10px; }
.user-avatar { flex-shrink: 0; }
.user-meta { flex: 1; min-width: 0; }
.user-name { font-size: 13px; font-weight: 600; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-role { font-size: 11px; color: var(--color-text-3); margin-top: 2px; }
.logout-btn { padding: 4px; font-size: 18px; }
.main-area { flex: 1; min-width: 0; height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.main-area > * { flex: 1; min-height: 0; }
.page-enter-active, .page-leave-active { transition: opacity 0.15s ease, transform 0.15s ease; }
.page-enter-from { opacity: 0; transform: translateX(6px); }
.page-leave-to { opacity: 0; transform: translateX(-6px); }
</style>
