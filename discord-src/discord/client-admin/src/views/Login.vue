<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <div class="logo-icon">
          <svg viewBox="0 0 24 24" width="48" height="48" fill="currentColor">
            <path d="M19.27 5.33C17.94 4.71 16.5 4.26 15 4a.09.09 0 00-.07.03c-.18.33-.39.76-.53 1.09a16.09 16.09 0 00-4.8 0c-.14-.34-.35-.76-.54-1.09-.01-.02-.04-.03-.07-.03-1.5.26-2.93.71-4.27 1.33-.01 0-.02.01-.03.02-2.72 4.07-3.47 8.03-3.1 11.95 0 .02.01.04.03.05 1.8 1.32 3.53 2.12 5.24 2.65.03.01.06 0 .07-.02.4-.55.76-1.13 1.07-1.74.02-.04 0-.08-.04-.09-.57-.22-1.11-.48-1.64-.78-.04-.02-.04-.08-.01-.11.11-.08.22-.17.33-.25.02-.02.05-.02.07-.01 3.44 1.57 7.15 1.57 10.55 0 .02-.01.05-.01.07.01.11.09.22.17.33.26.04.03.04.09-.01.11-.52.31-1.07.56-1.64.78-.04.01-.05.06-.04.09.32.61.68 1.19 1.07 1.74.03.01.06.02.09.01 1.72-.53 3.45-1.33 5.25-2.65.02-.01.03-.03.03-.05.44-4.53-.73-8.46-3.1-11.95-.01-.01-.02-.02-.04-.02zM8.52 14.91c-1.03 0-1.89-.95-1.89-2.12s.84-2.12 1.89-2.12c1.06 0 1.9.96 1.89 2.12 0 1.17-.84 2.12-1.89 2.12zm6.97 0c-1.03 0-1.89-.95-1.89-2.12s.84-2.12 1.89-2.12c1.06 0 1.9.96 1.89 2.12 0 1.17-.83 2.12-1.89 2.12z"/>
          </svg>
        </div>
        <h1 class="login-title">Discord CRM 管理后台</h1>
        <p class="login-subtitle">请登录您的账户以继续</p>
      </div>

      <el-form
        ref="formRef"
        class="login-form"
        :model="form"
        :rules="rules"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            size="large"
            placeholder="用户名"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          {{ loading ? "登录中..." : "登 录" }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick } from "vue"
import { useRouter } from "vue-router"
import { ElMessage } from "element-plus"
import { User, Lock } from "@element-plus/icons-vue"
import { useAuthStore } from "@/stores/auth"
import { startWebSocket } from "@/services/websocket"

const router = useRouter()
const auth = useAuthStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }]
}

function getFirstAllowedPath() {
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

async function handleLogin() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)

    ElMessage.success("登录成功")

    await nextTick()
    await nextTick()

    const targetPath = getFirstAllowedPath()
    console.log("登录成功，准备跳转到:", targetPath)

    if (targetPath === null) {
      await router.replace("/no-permission")
    } else {
      await router.replace(targetPath).catch(err => {
        console.warn("路由跳转失败:", err)
        return router.replace("/no-permission")
      })
    }

    startWebSocket(auth.token)
  } catch (e) {
    console.error("登录失败:", e)
    ElMessage.error("登录失败，请检查用户名和密码")
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(ellipse at top left, rgba(88,101,242,0.25), transparent 50%),
    radial-gradient(ellipse at bottom right, rgba(235,69,158,0.18), transparent 50%),
    var(--color-bg);
}

.login-card {
  width: 420px;
  max-width: calc(100% - 40px);
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 40px 36px 32px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.4);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 18px;
  background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
  color: #fff;
  margin-bottom: 16px;
}

.login-title {
  margin: 0 0 8px 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text);
  letter-spacing: 0.5px;
}

.login-subtitle {
  margin: 0;
  color: var(--color-text-2);
  font-size: 13px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.login-btn {
  width: 100%;
  height: 44px;
  margin-top: 8px;
  font-weight: 600;
  letter-spacing: 1px;
}
</style>
