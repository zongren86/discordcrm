import { useAuthStore } from '../stores/auth'

/**
 * v-permission="'BTN_XXX'" 或 v-permission="['BTN_XXX','BTN_YYY']"（数组时任一命中即可）
 * 移除没有该功能权限的元素（不是仅隐藏，直接从 DOM 移除）
 */
export default {
  mounted(el, binding) {
    const authStore = useAuthStore()
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (!codes.some(code => authStore.hasPermission(code))) {
      el.parentNode?.removeChild(el)
    }
  },
}
