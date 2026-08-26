<template>
  <div class="no-permission-page">
    <div class="no-permission-card">
      <el-icon class="icon" :size="80" color="#f56c6c">
        <CircleCloseFilled />
      </el-icon>
      <h1>暂无访问权限</h1>
      <p>您当前的账号在本系统中暂无配置任何访问权限。</p>
      <p>如有需要，请联系系统管理员为您分配相应的功能权限。</p>
      <el-button type="primary" @click="logout">返回登录页</el-button>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from "vue-router"
import { useAuthStore } from "@/stores/auth"
import { CircleCloseFilled } from "@element-plus/icons-vue"

const router = useRouter()
const auth = useAuthStore()

function logout() {
  auth.logout()
  router.replace("/login")
}
</script>

<style scoped>
.no-permission-page {
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg, #1e1b2e);
}

.no-permission-card {
  text-align: center;
  padding: 40px 60px;
  background: var(--color-bg-2, #2a2740);
  border-radius: 12px;
  border: 1px solid var(--color-border, #3d3a54);
}

.icon {
  margin-bottom: 20px;
}

h1 {
  margin: 0 0 16px;
  font-size: 24px;
  color: var(--color-text, #fff);
}

p {
  margin: 8px 0;
  color: var(--color-text-2, #aaa);
  font-size: 14px;
}

.el-button {
  margin-top: 24px;
}
</style>
