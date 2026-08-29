<template>
  <div class="agent-servers-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">代理节点管理</h2>
        <p class="page-desc">管理 crm_agent 代理服务器节点，用于浏览器自动登录采集 Discord 用户</p>
      </div>
      <div class="header-actions">
        <el-button @click="openPackageDialog">
          <el-icon><Download /></el-icon> 下载Agent包
        </el-button>
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon> 新增节点
        </el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table :data="servers" v-loading="loading" stripe style="width: 100%">
        <el-table-column label="节点名称" min-width="180">
          <template #default="{ row }">
            <span class="node-name">{{ row.name }}</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" effect="plain" size="small">
              {{ row.status === 'ONLINE' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="服务器地址" min-width="200">
          <template #default="{ row }">
            <span class="address">{{ row.serverAddress || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="Node版本" width="120">
          <template #default="{ row }">{{ row.nodeVersion || '-' }}</template>
        </el-table-column>

        <el-table-column label="浏览器" width="120">
          <template #default="{ row }">{{ row.browserType || '-' }}</template>
        </el-table-column>

        <el-table-column label="最后心跳" width="180">
          <template #default="{ row }">
            <span v-if="row.lastSeenAt">{{ formatTime(row.lastSeenAt) }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>

        <el-table-column label="备注" min-width="200">
          <template #default="{ row }">
            <span class="notes-text">{{ row.notes || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="warning" @click="resetToken(row)">重置Token</el-button>
            <el-button size="small" link type="danger" @click="confirmDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增弹窗 -->
    <el-dialog v-model="createDialogVisible" title="新增代理节点" width="480px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="节点名称" required>
          <el-input v-model="createForm.name" placeholder="如: crm-agent-01" maxlength="128" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.notes" type="textarea" :rows="2" placeholder="可选" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 创建成功显示 token -->
    <el-dialog v-model="tokenDialogVisible" title="节点创建成功" width="520px" :close-on-click-modal="false">
      <el-alert type="warning" show-icon :closable="false" style="margin-bottom: 16px">
        <template #title>
          <b>Token 只显示这一次！</b>请立即复制并妥善保管，后续无法再查看原文。如不慎泄漏或丢失，请在列表中点击"重置Token"。
        </template>
      </el-alert>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="节点名称">{{ createdResult?.name }}</el-descriptions-item>
        <el-descriptions-item label="认证Token">
          <el-input :model-value="createdResult?.token" readonly>
            <template #append>
              <el-button @click="copyCreatedToken">复制</el-button>
            </template>
          </el-input>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 20px; text-align: right">
        <el-button type="primary" @click="tokenDialogVisible = false">我已复制</el-button>
      </div>
    </el-dialog>

    <!-- 重置 token 弹窗 -->
    <el-dialog v-model="resetTokenDialogVisible" title="Token 已重置" width="520px" :close-on-click-modal="false">
      <el-alert type="warning" show-icon :closable="false" style="margin-bottom: 16px">
        <template #title>
          <b>新 token 只显示这一次！</b>请立即更新到代理服务器的配置中。
        </template>
      </el-alert>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="节点名称">{{ resetTokenResult?.name }}</el-descriptions-item>
        <el-descriptions-item label="新Token">
          <el-input :model-value="resetTokenResult?.token" readonly>
            <template #append>
              <el-button @click="copyResetToken">复制</el-button>
            </template>
          </el-input>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 20px; text-align: right">
        <el-button type="primary" @click="resetTokenDialogVisible = false">我已复制</el-button>
      </div>
    </el-dialog>

    <!-- 下载 Agent 包弹窗 -->
    <el-dialog v-model="packageDialogVisible" title="下载 crm_agent 安装包" width="640px">
      <div class="package-section">
        <div class="package-download-row">
          <div class="package-download-info">
            <div class="pkg-title">crm_agent v{{ packageInfo?.version || '0.1.0' }}</div>
            <div class="pkg-meta">
              <el-tag size="small" type="info" effect="plain">Node >= {{ packageInfo?.requiresNode || '18' }}</el-tag>
              <el-tag size="small" type="info" effect="plain">Playwright {{ packageInfo?.requiresPlaywright || 'chromium' }}</el-tag>
              <el-tag size="small" type="success" effect="plain">ZIP 源码包</el-tag>
            </div>
          </div>
          <el-button type="success" @click="downloadZip" :loading="downloading">
            <el-icon><Download /></el-icon> 立即下载
          </el-button>
        </div>

        <el-divider content-position="left">安装步骤</el-divider>

        <el-steps direction="vertical" :active="0" simple>
          <el-step v-for="(s, i) in packageInfo?.steps || []" :key="i"
            :title="s.title" :description="s.desc" :status="'wait'" />
        </el-steps>

        <el-divider content-position="left">配置模板</el-divider>
        <pre class="config-preview">{{ packageInfo?.configTemplate || '' }}</pre>
      </div>
      <template #footer>
        <el-button @click="packageDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Download } from '@element-plus/icons-vue'
import {
  listAgentServers,
  createAgentServer,
  resetAgentServerToken,
  deleteAgentServer
} from '@/api'

const servers = ref([])
const loading = ref(false)

const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', notes: '' })

const tokenDialogVisible = ref(false)
const createdResult = ref(null)

const resetTokenDialogVisible = ref(false)
const resetTokenResult = ref(null)

const packageDialogVisible = ref(false)
const packageInfo = ref(null)
const downloading = ref(false)

const _pollTimer = ref(null)

async function loadList(silent = false) {
  if (!silent) loading.value = true
  try {
    servers.value = await listAgentServers()
  } catch (e) {
    if (!silent) ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  createForm.value = { name: '', notes: '' }
  createDialogVisible.value = true
}

async function doCreate() {
  if (!createForm.value.name?.trim()) {
    ElMessage.warning('请输入节点名称')
    return
  }
  creating.value = true
  try {
    const resp = await createAgentServer(createForm.value.name.trim(), createForm.value.notes)
    createDialogVisible.value = false
    createdResult.value = resp
    tokenDialogVisible.value = true
    await loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '创建失败')
  } finally {
    creating.value = false
  }
}

async function resetToken(row) {
  try {
    await ElMessageBox.confirm(`确定重置节点 "${row.name}" 的 token 吗？旧 token 将立即失效。`, '确认重置', { type: 'warning' })
  } catch { return }
  try {
    resetTokenResult.value = await resetAgentServerToken(row.id)
    resetTokenDialogVisible.value = true
    await loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '重置失败')
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除节点 "${row.name}" 吗？此操作不可恢复。`, '确认删除', { type: 'warning' })
  } catch { return }
  try {
    await deleteAgentServer(row.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '删除失败')
  }
}

async function copyCreatedToken() {
  if (!createdResult.value?.token) return
  await navigator.clipboard.writeText(createdResult.value.token)
  ElMessage.success('已复制到剪贴板')
}
async function copyResetToken() {
  if (!resetTokenResult.value?.token) return
  await navigator.clipboard.writeText(resetTokenResult.value.token)
  ElMessage.success('已复制到剪贴板')
}

function formatTime(isoStr) {
  if (!isoStr) return ''
  const d = new Date(isoStr)
  if (isNaN(d)) return isoStr
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function openPackageDialog() {
  packageDialogVisible.value = true
  try {
    const resp = await fetch('/api/agent-servers/package-info', {
      headers: { 'Authorization': 'Bearer ' + localStorage.getItem('crm_token') }
    })
    if (resp.ok) packageInfo.value = await resp.json()
  } catch (e) { /* ignore */ }
}

async function downloadZip() {
  downloading.value = true
  try {
    const resp = await fetch('/api/agent-servers/package', {
      headers: { 'Authorization': 'Bearer ' + localStorage.getItem('crm_token') }
    })
    if (!resp.ok) throw new Error('下载失败')
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = packageInfo.value?.filename || 'crm_agent-v0.1.0.zip'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('下载失败: ' + e.message)
  } finally {
    downloading.value = false
  }
}

onMounted(() => {
  loadList()
  // 每 10s 静默刷新状态
  _pollTimer.value = setInterval(() => loadList(true), 10000)
})

onBeforeUnmount(() => {
  if (_pollTimer.value) {
    clearInterval(_pollTimer.value)
    _pollTimer.value = null
  }
})
</script>

<style scoped>
.agent-servers-page { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-title { font-size: 20px; margin: 0 0 4px 0; color: var(--color-text, #fff); }
.page-desc { font-size: 13px; color: var(--color-text-2, #aaa); margin: 0; }
.page-body { background: var(--color-bg-2, #2a2a3a); border-radius: 8px; padding: 16px; }
.node-name { font-weight: 500; }
.address { font-family: monospace; color: var(--color-text-2, #aaa); font-size: 13px; }
.text-muted { color: var(--color-text-3, #666); }
.notes-text { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.package-section { padding: 4px 0; }
.package-download-row { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: var(--el-fill-color-light, #f5f7fa); border-radius: 8px; margin-bottom: 4px; }
.package-download-info { display: flex; flex-direction: column; gap: 6px; }
.pkg-title { font-size: 16px; font-weight: 600; }
.pkg-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.config-preview { background: var(--el-fill-color-light, #f5f7fa); border-radius: 6px; padding: 12px; font-size: 12px; font-family: Menlo, Consolas, monospace; white-space: pre-wrap; word-break: break-all; margin: 0; max-height: 280px; overflow: auto; color: var(--el-text-color-primary, #303133); }
</style>
