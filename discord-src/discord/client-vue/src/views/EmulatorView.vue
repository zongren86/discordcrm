<template>
  <div class="emulator-view">
    <div v-if="loading" class="loading-wrap">
      <el-icon class="is-loading" :size="48"><Loading /></el-icon>
      <p>正在加载好友管理界面...</p>
    </div>

    <div v-else-if="!backendAvailable" class="error-wrap">
      <el-icon :size="48" color="#f56c6c"><WarningFilled /></el-icon>
      <h3>好友管理服务未运行</h3>
      <p>无法连接到模拟器后端服务（{{ config.EMU_API_URL }}）。</p>
      <p class="hint">请先启动模拟器管理器后端：</p>
      <pre>cd /Users/ren/CodeBuddy/20260807093456/backend && mvn spring-boot:run</pre>
      <el-button type="primary" @click="checkService">重新检测</el-button>
    </div>

    <div v-else class="content">
      <el-row :gutter="16">
        <!-- 左侧：控制面板 -->
        <el-col :span="8">
          <el-card class="panel" shadow="hover">
            <template #header>
              <div class="panel-header">
                <el-icon><Setting /></el-icon>
                <span>模拟器控制</span>
              </div>
            </template>
            <div class="panel-body">
              <div class="form-row">
                <label>模拟器数量</label>
                <el-input-number v-model="targetCount" :min="1" :max="50" size="small" />
                <el-button type="primary" size="small" @click="applyCount" :disabled="emuLoading">
                  {{ emuLoading ? '处理中...' : '应用' }}
                </el-button>
              </div>
              <div class="action-row">
                <el-button type="success" @click="startAll" :disabled="emuLoading" size="small">
                  <el-icon><VideoPlay /></el-icon> 全部启动
                </el-button>
                <el-button type="danger" @click="stopAll" :disabled="emuLoading" size="small">
                  <el-icon><VideoPause /></el-icon> 全部停止
                </el-button>
                <el-button type="warning" @click="restartAll" :disabled="emuLoading" size="small">
                  <el-icon><Refresh /></el-icon> 全部重启
                </el-button>
              </div>
            </div>
          </el-card>

          <el-card class="panel" shadow="hover" style="margin-top: 16px">
            <template #header>
              <div class="panel-header">
                <el-icon><ChatDotRound /></el-icon>
                <span>Discord 管理</span>
              </div>
            </template>
            <div class="panel-body">
              <div class="form-row">
                <label>APK 状态</label>
                <el-tag :type="apkDownloaded ? 'success' : 'warning'" size="small">
                  {{ apkDownloaded ? '已下载' : '未下载' }}
                </el-tag>
                <el-button size="small" @click="downloadApk" :disabled="apkLoading">
                  {{ apkLoading ? '下载中...' : '自动下载 APK' }}
                </el-button>
                <el-button size="small" @click="triggerApkUpload" :disabled="apkLoading">上传 APK</el-button>
                <input ref="apkInput" type="file" accept=".apk" @change="handleApkUpload" style="display:none" />
              </div>
              <div class="form-row">
                <el-button type="success" size="small" @click="installAllDiscord" :disabled="emuLoading">
                  全部安装 Discord
                </el-button>
              </div>
              <div class="form-row">
                <label>Discord 邮箱</label>
                <el-input v-model="discordEmail" type="email" placeholder="邮箱" size="small" />
              </div>
              <div class="form-row">
                <label>密码</label>
                <el-input v-model="discordPassword" type="password" placeholder="密码" size="small" show-password />
              </div>
              <div class="form-row">
                <el-button type="primary" size="small" @click="loginAllDiscord" :disabled="!discordEmail || !discordPassword">
                  全部登录
                </el-button>
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- 右侧：配置面板 -->
        <el-col :span="16">
          <el-card class="panel" shadow="hover">
            <template #header>
              <div class="panel-header">
                <el-icon><Key /></el-icon>
                <span>Discord 账号管理（{{ accounts.length }} 个）</span>
                <el-button link type="primary" size="small" @click="showAccountsPanel = !showAccountsPanel">
                  {{ showAccountsPanel ? '收起' : '展开' }}
                </el-button>
              </div>
            </template>
            <div v-show="showAccountsPanel" class="panel-body">
              <div class="hint">每行一个：邮箱,密码（按行号顺序分配给模拟器 1、2、3…）</div>
              <el-input
                v-model="accountsText"
                type="textarea"
                :rows="4"
                placeholder="alice@discord.com,password123&#10;bob@discord.com,pass456"
              />
              <div class="form-row">
                <el-button type="primary" size="small" @click="saveAccounts">保存账号</el-button>
                <el-button size="small" @click="loadAccounts">重新加载</el-button>
              </div>
            </div>
          </el-card>

          <el-card class="panel" shadow="hover" style="margin-top: 16px">
            <template #header>
              <div class="panel-header">
                <el-icon><User /></el-icon>
                <span>好友清单（{{ friends.length }} 个）</span>
                <el-button link type="primary" size="small" @click="showFriendsPanel = !showFriendsPanel">
                  {{ showFriendsPanel ? '收起' : '展开' }}
                </el-button>
              </div>
            </template>
            <div v-show="showFriendsPanel" class="panel-body">
              <div class="hint">每行一个 Discord 用户名（不带 @），全局共用一份清单</div>
              <el-input v-model="friendsText" type="textarea" :rows="4" placeholder="friend_user_1&#10;friend_user_2" />
              <div class="form-row">
                <el-button type="primary" size="small" @click="saveFriends">保存好友清单</el-button>
                <el-button size="small" @click="loadFriends">重新加载</el-button>
              </div>
            </div>
          </el-card>

          <el-card class="panel" shadow="hover" style="margin-top: 16px">
            <template #header>
              <div class="panel-header">
                <el-icon><Promotion /></el-icon>
                <span>自动加好友配置</span>
              </div>
            </template>
            <div class="panel-body">
              <el-row :gutter="12">
                <el-col :span="6">
                  <div class="form-row inline">
                    <label>间隔(秒)</label>
                    <el-input-number v-model="autoConfig.intervalSeconds" :min="0" size="small" style="width:100%" />
                  </div>
                </el-col>
                <el-col :span="6">
                  <div class="form-row inline">
                    <label>延迟下限</label>
                    <el-input-number v-model="autoConfig.delayMinSeconds" :min="0" size="small" style="width:100%" />
                  </div>
                </el-col>
                <el-col :span="6">
                  <div class="form-row inline">
                    <label>延迟上限</label>
                    <el-input-number v-model="autoConfig.delayMaxSeconds" :min="0" size="small" style="width:100%" />
                  </div>
                </el-col>
                <el-col :span="6">
                  <el-button type="primary" size="small" @click="saveAutoConfig" style="width:100%">保存配置</el-button>
                </el-col>
              </el-row>
              <div class="form-row" style="margin-top: 12px">
                <el-button type="success" size="small" @click="startAutoAll">
                  <el-icon><VideoPlay /></el-icon> 全部开始自动加好友
                </el-button>
                <el-button type="warning" size="small" @click="stopAutoAll">
                  <el-icon><VideoPause /></el-icon> 全部停止
                </el-button>
                <span class="hint-sm">间隔 + 随机延迟(下限~上限) 后添加下一个好友</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 模拟器卡片网格 -->
      <el-row :gutter="16" style="margin-top: 16px">
        <el-col v-for="emu in sortedEmulators" :key="emu.index" :span="8">
          <el-card class="emu-card" :class="'status-' + emu.status.toLowerCase()" shadow="hover">
            <div class="emu-header">
              <div class="emu-title">
                <span class="emu-name">#{{ emu.index }} {{ emu.name }}</span>
                <el-tag :type="statusTagType(emu.status)" size="small">{{ statusText(emu.status) }}</el-tag>
              </div>
              <div class="emu-ports">
                <span v-if="emu.adbPort">ADB: {{ emu.adbPort }}</span>
                <span v-if="emu.resolution">{{ emu.resolution }}</span>
              </div>
            </div>

            <div class="emu-info">
              <template v-if="emu.discordInstalled">
                <el-descriptions :column="2" size="small" border>
                  <el-descriptions-item label="账号" :span="2">
                    {{ emu.discordAccount || '未登录' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="已添加">{{ emu.addedCount || 0 }}</el-descriptions-item>
                  <el-descriptions-item label="下次添加">{{ formatCountdown(emu.nextAddAt) }}</el-descriptions-item>
                </el-descriptions>
                <div v-if="emu.autoLastResult" class="auto-result">{{ emu.autoLastResult }}</div>
              </template>
              <template v-else>
                <div class="no-discord" v-if="emu.status === 'RUNNING'">Discord 未安装</div>
              </template>
            </div>

            <div v-if="emu.lastError" class="emu-error">
              <el-icon color="#f56c6c"><WarningFilled /></el-icon>
              <span>{{ emu.lastError }}</span>
            </div>

            <div class="emu-actions">
              <template v-if="emu.status !== 'RUNNING'">
                <el-button type="success" size="small" @click="startEmulator(emu.index)">启动</el-button>
              </template>
              <template v-else>
                <el-button type="danger" size="small" @click="stopEmulator(emu.index)">停止</el-button>
                <el-button type="warning" size="small" @click="restartEmulator(emu.index)">重启</el-button>
              </template>

              <template v-if="emu.status === 'RUNNING'">
                <el-button v-if="!emu.discordInstalled" size="small" @click="installDiscord(emu.index)">
                  安装 Discord
                </el-button>
                <el-button v-else size="small" disabled>
                  <el-icon color="#67c23a"><CircleCheck /></el-icon> Discord ✓
                </el-button>
                <el-button size="small" @click="launchDiscord(emu.index)">启动 Discord</el-button>
                <el-button
                  v-if="discordEmail && discordPassword"
                  type="primary" size="small"
                  @click="loginDiscord(emu.index)"
                >登录 Discord</el-button>

                <el-button
                  v-if="!emu.autoRunning && emu.discordInstalled"
                  type="success" size="small"
                  @click="startAuto(emu.index)"
                >▶ 自动加好友</el-button>
                <el-button
                  v-else-if="emu.autoRunning"
                  type="warning" size="small"
                  @click="stopAuto(emu.index)"
                >■ 停止自动</el-button>
              </template>

              <el-button size="small" type="danger" plain @click="deleteEmulator(emu.index)">删除</el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-empty v-if="emulators.length === 0 && !loading" description="暂无模拟器，在上方设置数量后点击「应用」创建" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  Loading, WarningFilled, Monitor, User, VideoPlay, VideoPause, Refresh,
  ChatDotRound, Setting, Key, Promotion, CircleCheck
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import { config } from '@/config'

const loading = ref(true)
const backendAvailable = ref(false)
const emulators = ref([])
const targetCount = ref(3)
const emuLoading = ref(false)

const apkDownloaded = ref(false)
const apkLoading = ref(false)
const discordEmail = ref('')
const discordPassword = ref('')
const apkInput = ref(null)

const accounts = ref([])
const accountsText = ref('')
const showAccountsPanel = ref(false)

const friends = ref([])
const friendsText = ref('')
const showFriendsPanel = ref(false)

const autoConfig = ref({ intervalSeconds: 900, delayMinSeconds: 60, delayMaxSeconds: 800 })

let healthCheckTimer = null

const emuApi = axios.create({ baseURL: '/emu-api', timeout: 15000 })

const sortedEmulators = computed(() => [...emulators.value].sort((a, b) => a.index - b.index))

onMounted(async () => {
  await checkService()
  if (backendAvailable.value) {
    await Promise.all([
      fetchEmulators(),
      loadAccounts(),
      loadFriends(),
      loadAutoConfig(),
      checkApkStatus()
    ])
  }
  startHealthCheck()
})

onUnmounted(() => {
  if (healthCheckTimer) { clearInterval(healthCheckTimer); healthCheckTimer = null }
})

async function checkService() {
  loading.value = true
  const ok = await checkBackend()
  backendAvailable.value = ok
  loading.value = false
}

async function checkBackend() {
  try {
    const resp = await emuApi.get('/emulators', { timeout: 3000 })
    return Array.isArray(resp.data) || resp.status < 500
  } catch { return false }
}

function startHealthCheck() {
  healthCheckTimer = setInterval(async () => {
    const ok = await checkBackend()
    if (!ok && backendAvailable.value) {
      backendAvailable.value = false
      ElMessage.warning('模拟器后端服务已断开')
    } else if (ok && !backendAvailable.value) {
      backendAvailable.value = true
      await fetchEmulators()
      ElMessage.success('模拟器后端已重新连接')
    }
  }, 15000)
}

async function fetchEmulators() {
  try {
    const resp = await emuApi.get('/emulators')
    emulators.value = Array.isArray(resp.data) ? resp.data : []
  } catch { emulators.value = [] }
}

async function checkApkStatus() {
  try {
    const resp = await emuApi.get('/discord/apk-status')
    apkDownloaded.value = resp.data.downloaded
  } catch {}
}

async function downloadApk() {
  apkLoading.value = true
  try {
    await emuApi.post('/discord/download')
    ElMessage.success('APK 下载中...')
    setTimeout(async () => {
      apkDownloaded.value = true
      apkLoading.value = false
      ElMessage.success('APK 下载完成')
    }, 30000)
  } catch (e) {
    apkLoading.value = false
    ElMessage.error('下载失败: ' + (e.response?.data?.message || e.message))
  }
}

function triggerApkUpload() { apkInput.value?.click() }

async function handleApkUpload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const formData = new FormData()
  formData.append('file', file)
  try {
    await emuApi.post('/discord/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    apkDownloaded.value = true
    ElMessage.success('APK 上传成功')
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.response?.data?.message || e.message))
  }
  event.target.value = ''
}

async function installAllDiscord() {
  try {
    await emuApi.post('/discord/installAll')
    ElMessage.success('已开始安装 Discord 到所有模拟器')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('安装失败: ' + (e.response?.data?.message || e.message))
  }
}

async function loginAllDiscord() {
  if (!discordEmail.value || !discordPassword.value) {
    ElMessage.warning('请输入邮箱和密码')
    return
  }
  try {
    const promises = emulators.value
      .filter(e => e.status === 'RUNNING' && e.discordInstalled)
      .map(e => emuApi.post(`/discord/login/${e.index}`, {
        email: discordEmail.value, password: discordPassword.value
      }))
    await Promise.all(promises)
    ElMessage.success('已发送登录指令')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('登录失败: ' + (e.response?.data?.message || e.message))
  }
}

async function loadAccounts() {
  try {
    const resp = await emuApi.get('/data/accounts')
    accounts.value = resp.data || []
    accountsText.value = accounts.value.map(a => `${a.email}|${a.password}`).join('\n')
  } catch {}
}

async function saveAccounts() {
  const lines = accountsText.value.split('\n').map(l => l.trim()).filter(Boolean)
  const parsed = []
  for (const line of lines) {
    const [email, password] = line.split('|')
    if (email && password) parsed.push({ email: email.trim(), password: password.trim() })
  }
  try {
    await emuApi.post('/data/accounts', parsed)
    accounts.value = parsed
    ElMessage.success('账号已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function loadFriends() {
  try {
    const resp = await emuApi.get('/data/friends')
    friends.value = resp.data || []
    friendsText.value = friends.value.map(f => f.username).join('\n')
  } catch {}
}

async function saveFriends() {
  const list = friendsText.value.split('\n').map(l => l.trim()).filter(Boolean)
  const parsed = list.map(username => ({ username }))
  try {
    await emuApi.post('/data/friends', parsed)
    friends.value = parsed
    ElMessage.success('好友清单已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function loadAutoConfig() {
  try {
    const resp = await emuApi.get('/data/autoconfig')
    if (resp.data) autoConfig.value = resp.data
  } catch {}
}

async function saveAutoConfig() {
  try {
    await emuApi.post('/data/autoconfig', autoConfig.value)
    ElMessage.success('自动加好友配置已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function startAutoAll() {
  try {
    await emuApi.post('/autoadd/startAll')
    ElMessage.success('已开始自动加好友')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.response?.data?.message || e.message))
  }
}

async function stopAutoAll() {
  try {
    await emuApi.post('/autoadd/stopAll')
    ElMessage.success('已停止所有自动加好友')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.response?.data?.message || e.message))
  }
}

async function startAuto(index) {
  try {
    await emuApi.post(`/autoadd/${index}/start`)
    ElMessage.success(`模拟器 #${index} 已开始自动加好友`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  }
}

async function stopAuto(index) {
  try {
    await emuApi.post(`/autoadd/${index}/stop`)
    ElMessage.success(`模拟器 #${index} 已停止自动加好友`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  }
}

async function applyCount() {
  emuLoading.value = true
  try {
    const resp = await emuApi.post('/emulators/count', { count: targetCount.value })
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success(`已设置 ${targetCount.value} 台模拟器`)
  } catch (e) {
    ElMessage.error('设置失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
  }
}

async function startAll() {
  try {
    await ElMessageBox.confirm('确定要启动所有模拟器吗？', '确认', { type: 'warning' })
    emuLoading.value = true
    const resp = await emuApi.post('/emulators/startAll', null, { params: { count: targetCount.value } })
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success('启动指令已发送')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
  }
}

async function stopAll() {
  try {
    await ElMessageBox.confirm('确定要停止所有模拟器吗？', '确认', { type: 'warning' })
    emuLoading.value = true
    const resp = await emuApi.post('/emulators/stopAll')
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success('停止指令已发送')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
  }
}

async function restartAll() {
  try {
    await ElMessageBox.confirm('确定要重启所有模拟器吗？', '确认', { type: 'warning' })
    emuLoading.value = true
    for (const emu of emulators.value.filter(e => e.status === 'RUNNING')) {
      try { await emuApi.post(`/emulators/${emu.index}/restart`) } catch {}
    }
    ElMessage.success('重启指令已发送')
    await fetchEmulators()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('重启失败')
  } finally {
    emuLoading.value = false
  }
}

async function startEmulator(index) {
  try {
    const resp = await emuApi.post(`/emulators/${index}/start`)
    ElMessage.success(`模拟器 #${index} 启动中...`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  }
}

async function stopEmulator(index) {
  try {
    const resp = await emuApi.post(`/emulators/${index}/stop`)
    ElMessage.success(`模拟器 #${index} 已停止`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
  } catch (e) {
    ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  }
}

async function restartEmulator(index) {
  try {
    const resp = await emuApi.post(`/emulators/${index}/restart`)
    ElMessage.success(`模拟器 #${index} 重启中...`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
  } catch (e) {
    ElMessage.error('重启失败: ' + (e.response?.data?.message || e.message))
  }
}

async function deleteEmulator(index) {
  try {
    await ElMessageBox.confirm(`确定要删除模拟器 #${index} 吗？`, '确认', { type: 'warning' })
    const resp = await emuApi.delete(`/emulators/${index}`)
    if (resp.data?.success) {
      ElMessage.success('删除成功')
      emulators.value = emulators.value.filter(e => e.index !== index)
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

async function installDiscord(index) {
  try {
    await emuApi.post(`/discord/install/${index}`)
    ElMessage.success(`模拟器 #${index} 开始安装 Discord`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('安装失败: ' + (e.response?.data?.message || e.message))
  }
}

async function launchDiscord(index) {
  try {
    const resp = await emuApi.post(`/discord/launch/${index}`)
    ElMessage.success(resp.data?.result || 'Discord 启动指令已发送')
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  }
}

async function loginDiscord(index) {
  if (!discordEmail.value || !discordPassword.value) {
    ElMessage.warning('请先在左侧配置邮箱和密码')
    return
  }
  try {
    await emuApi.post(`/discord/login/${index}`, {
      email: discordEmail.value, password: discordPassword.value
    })
    ElMessage.success(`模拟器 #${index} 登录中...`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('登录失败: ' + (e.response?.data?.message || e.message))
  }
}

function statusText(status) {
  return { RUNNING: '运行中', STOPPED: '已停止', CREATING: '创建中', ERROR: '错误' }[status] || status || '未知'
}

function statusTagType(status) {
  return { RUNNING: 'success', STOPPED: 'info', CREATING: 'warning', ERROR: 'danger' }[status] || 'info'
}

function formatCountdown(timestamp) {
  if (!timestamp || timestamp <= 0) return '-'
  const diff = Math.max(0, Math.floor((timestamp - Date.now()) / 1000))
  if (diff <= 0) return '即将'
  if (diff < 60) return `${diff}秒后`
  if (diff < 3600) return `${Math.floor(diff / 60)}分${diff % 60}秒`
  return `${Math.floor(diff / 3600)}小时后`
}
</script>

<style scoped>
.emulator-view {
  padding: 16px;
  height: 100%;
  overflow-y: auto;
  background: var(--color-bg, #f5f7fa);
}

.content { max-width: 1600px; }

.panel { margin-bottom: 16px; }
.panel-header { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.panel-body { display: flex; flex-direction: column; gap: 10px; }

.form-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.form-row.inline label { min-width: 70px; font-size: 13px; }
.form-row label { min-width: 70px; font-size: 13px; color: #606266; }

.action-row { display: flex; gap: 8px; flex-wrap: wrap; }

.hint { font-size: 12px; color: #909399; }
.hint-sm { font-size: 11px; color: #c0c4cc; margin-left: 8px; }

.emu-card { margin-bottom: 12px; transition: all 0.2s; }
.emu-card:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.1); }

.emu-header { margin-bottom: 10px; }
.emu-title { display: flex; justify-content: space-between; align-items: center; }
.emu-name { font-weight: 600; font-size: 14px; }
.emu-ports { display: flex; gap: 8px; font-size: 12px; color: #909399; }

.emu-info { margin-bottom: 10px; }
.no-discord { color: #909399; font-size: 13px; text-align: center; padding: 8px; }

.auto-result {
  margin-top: 8px;
  padding: 4px 8px;
  background: #f0f9ff;
  border-radius: 4px;
  font-size: 12px;
  color: #409eff;
}

.emu-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  padding: 6px 10px;
  background: #fef0f0;
  border-radius: 4px;
  font-size: 12px;
  color: #f56c6c;
}

.emu-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  padding-top: 10px;
  border-top: 1px solid var(--color-border, #ebeef5);
}

.loading-wrap,
.error-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 60px 24px;
  color: var(--color-text-2, #606266);
}

.error-wrap h3 { color: #f56c6c; }
.error-wrap pre {
  background: #2c3e50;
  color: #f1f5f9;
  padding: 10px 16px;
  border-radius: 6px;
  font-size: 13px;
}
</style>
