import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(localStorage.getItem('theme_dark') === 'true')
  const sidebarCollapsed = ref(localStorage.getItem('sidebar_collapsed') === 'true')
  const isFullscreen = ref(false)

  // 应用主题到 DOM
  function applyTheme() {
    if (isDark.value) {
      document.documentElement.setAttribute('data-theme', 'dark')
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.removeAttribute('data-theme')
      document.documentElement.classList.remove('dark')
    }
  }

  // 切换主题
  function toggleTheme() {
    isDark.value = !isDark.value
    localStorage.setItem('theme_dark', isDark.value)
    applyTheme()
  }

  // 设置主题
  function setTheme(dark) {
    isDark.value = dark
    localStorage.setItem('theme_dark', dark)
    applyTheme()
  }

  // 切换侧边栏
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem('sidebar_collapsed', sidebarCollapsed.value)
  }

  // 全屏切换
  function toggleFullscreen() {
    if (!document.fullscreenElement) {
      document.requestFullscreen()
      isFullscreen.value = true
    } else {
      document.exitFullscreen()
      isFullscreen.value = false
    }
  }

  // 监听全屏状态
  if (typeof document !== 'undefined') {
    document.addEventListener('fullscreenchange', () => {
      isFullscreen.value = !!document.fullscreenElement
    })
  }

  // 初始化应用主题
  applyTheme()

  return {
    isDark,
    sidebarCollapsed,
    isFullscreen,
    toggleTheme,
    setTheme,
    toggleSidebar,
    toggleFullscreen,
    applyTheme
  }
})
