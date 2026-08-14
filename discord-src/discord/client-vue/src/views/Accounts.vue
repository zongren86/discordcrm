<template>
  <div class="accounts-page">
    <div class="page-header">
      <div class="page-title-wrap">
        <h2 class="page-title">Discord 账号管理</h2>
        <p class="page-desc">管理导入的 Discord USER / BOT 账号</p>
      </div>
      <div class="header-actions">
        <el-button type="success" @click="openBatchImport">
          <el-icon><Upload /></el-icon>
          <span>批量导入账号</span>
        </el-button>
        <el-button type="primary" @click="openAddAccount">
          <el-icon><Plus /></el-icon>
          <span>手工添加</span>
        </el-button>
      </div>
    </div>

    <div class="page-body">
      <div class="filter-bar">
        <div class="stat-cards">
          <div class="stat-card stat-total">
            <div class="stat-value">{{ allAccounts.length }}</div>
            <div class="stat-label">账号总数</div>
          </div>
          <div class="stat-card stat-active">
            <div class="stat-value">{{ allAccounts.filter(a => a.status === 'ACTIVE').length }}</div>
            <div class="stat-label">正常</div>
          </div>
          <div class="stat-card stat-inactive">
            <div class="stat-value">{{ allAccounts.filter(a => a.status === 'INACTIVE').length }}</div>
            <div class="stat-label">已停用</div>
          </div>
        </div>
        <div class="filter-controls">
          <el-input v-model="filters.keyword" size="default" placeholder="搜索账号、邮箱、备注或ID"
            :prefix-icon="Search" clearable style="width: 320px;" @keyup.enter="fetchAccounts" />
          <el-select v-model="filters.status" size="default" placeholder="状态" clearable style="width: 120px;">
            <el-option :value="null" label="全部状态" />
            <el-option value="ACTIVE" label="正常" />
            <el-option value="INACTIVE" label="已停用" />
          </el-select>
          <el-button type="primary" @click="fetchAccounts">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="filteredAccounts" stripe style="width: 100%;" class="accounts-table">
        <el-table-column prop="id" label="账号ID" width="90" align="center" />
        
        <el-table-column label="账号" min-width="240">
          <template #default="{ row }">
            <div class="account-cell">
              <el-avatar :size="40" :src="getAvatar(row)" class="account-avatar-cell">
                {{ initialOf(row) }}
              </el-avatar>
              <div class="account-cell-info">
                <div class="account-cell-name">
                  {{ row.name || row.discordName || '未命名' }}
                  <el-tag :type="row.accountType === 'USER' ? 'primary' : 'warning'" size="small" effect="light" style="margin-left:6px;">
                    {{ row.accountType === 'USER' ? 'USER' : 'BOT' }}
                  </el-tag>
                </div>
                <div class="account-cell-email">{{ row.email || '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="关联用户账号" min-width="140">
          <template #default="{ row }">
            <div v-if="row.agentName" class="agent-cell">
              <el-avatar :size="24" class="agent-avatar-mini" :style="{ background: 'linear-gradient(135deg, var(--color-primary), var(--color-pink))' }">
                {{ (row.agentName || '?').charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="agent-name">{{ row.agentName }}</span>
            </div>
            <span v-else class="no-agent">-</span>
          </template>
        </el-table-column>

        <el-table-column label="账号编号" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.accountNumberId" type="success" size="small" effect="light">
              #{{ row.accountNumberId }}
            </el-tag>
            <span v-else class="cell-hint">-</span>
          </template>
        </el-table-column>

        <el-table-column label="所属商户" width="140">
          <template #default="{ row }">
            <span v-if="getMerchantName(row.merchantId)" class="merchant-cell">
              <el-icon style="margin-right:4px;vertical-align:middle;"><OfficeBuilding /></el-icon>
              {{ getMerchantName(row.merchantId) }}
            </span>
            <span v-else class="cell-hint">-</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <div class="status-cell">
              <div :class="['status-dot', statusClass(row.status)]"></div>
              <span :class="['status-text', statusClass(row.status)]">{{ statusLabel(row.status) }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="hasUserAccounts" label="Token状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.accountType === 'USER'" 
              :type="row.tokenValid ? 'success' : 'danger'" 
              size="small"
              effect="light">
              {{ row.tokenValid ? '有效' : '已失效' }}
            </el-tag>
            <span v-else style="color: var(--color-text-3)">-</span>
          </template>
        </el-table-column>

        <el-table-column label="好友" width="80" align="center">
          <template #default="{ row }">{{ row.friendCount ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="消息" width="80" align="center">
          <template #default="{ row }">{{ row.messageCount ?? 0 }}</template>
        </el-table-column>

        <el-table-column prop="remark" label="备注" min-width="160">
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button v-if="row.accountType === 'USER'" size="small" type="primary" link @click="openRefreshToken(row)">
                <el-icon><Key /></el-icon> 更新Token
              </el-button>
              <el-button v-if="row.accountType === 'USER'" size="small" type="primary" link @click="syncAccount(row)">
                <el-icon><Refresh /></el-icon> 同步
              </el-button>
              <el-button size="small" type="primary" link @click="openEdit(row)">
                <el-icon><Edit /></el-icon> 编辑
              </el-button>
              <el-button size="small" type="primary" link @click="removeAccount(row)">
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && filteredAccounts.length === 0" description="暂无账号" style="padding:60px 0;" />
    </div>

    <!-- 添加/编辑账号（手工添加） -->
    <el-dialog v-model="botDialog.visible" :title="botDialog.editId ? '编辑账号' : '手工添加账号'" width="480px" @close="resetBotDialog">
      <el-form :model="botDialog.form" label-width="100px">
        <el-form-item label="账号名称" required>
          <el-input v-model="botDialog.form.nickname" placeholder="请输入账号名称" />
        </el-form-item>
        <el-form-item label="Token" required>
          <el-input v-model="botDialog.form.token" type="password" show-password placeholder="请输入 Token" />
        </el-form-item>
        <el-form-item label="账号类型">
          <el-select v-model="botDialog.form.accountType" placeholder="请选择账号类型" style="width:100%;">
            <el-option value="BOT" label="BOT" />
            <el-option value="USER" label="USER" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属商户">
          <el-select v-model="botDialog.form.merchantId" placeholder="请选择商户" filterable clearable style="width:100%;" :loading="merchantsLoading">
            <el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="botDialog.form.email" placeholder="邮箱（选填）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="botDialog.form.remark" type="textarea" :rows="2" placeholder="备注（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="botDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="botDialog.saving" @click="saveBot">保存</el-button>
      </template>
    </el-dialog>

    <!-- 刷新 Token -->
    <el-dialog v-model="refreshTokenDialog.visible" title="更新Token" width="480px" @close="resetRefreshTokenDialog">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px;">
        <template #title>
          通过邮箱密码重新登录获取新 Token，可在 Token 过期前主动续期
        </template>
      </el-alert>
      <el-form :model="refreshTokenDialog.form" label-width="80px">
        <el-form-item label="账号">
          <el-input :value="refreshTokenDialog.accountName" disabled />
        </el-form-item>
        <el-form-item label="邮箱" required>
          <el-input v-model="refreshTokenDialog.form.email" type="email" placeholder="请输入 Discord 账号邮箱" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="refreshTokenDialog.form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="refreshTokenDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="refreshTokenDialog.saving" @click="doRefreshToken">确认更新</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入 USER 账号 -->
    <el-dialog v-model="batchDialog.visible" title="批量导入 USER 账号（邮箱|密码 每行一个）" width="560px" @close="resetBatchDialog">
      <el-alert type="info" :closable="false" show-icon title="每行格式：邮箱|密码，例如 user1@example.com|mypassword123" style="margin-bottom:16px;" />
      <el-input v-model="batchDialog.text" type="textarea" :rows="12" placeholder="user1@example.com|pass1&#10;user2@example.com|pass2" />
      <div style="margin-top:10px; font-size:12px; color: var(--color-text-3);">说明：Discord 登录时如遇 2FA 或验证码，将跳过该账号。</div>
      <template #footer>
        <el-button @click="batchDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="batchDialog.saving" @click="runBatchImport">开始导入</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入结果 -->
    <el-dialog v-model="batchDialog.resultVisible" title="导入结果" width="720px" :close-on-click-modal="true">
      <div style="margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center;">
        <div style="display: flex; gap: 16px; font-size: 14px;">
          <span>总计: <b>{{ batchDialog.resultTotal }}</b></span>
          <span style="color: var(--color-success)">成功: <b>{{ batchDialog.resultSuccess }}</b></span>
          <span style="color: var(--color-danger)">失败: <b>{{ batchDialog.resultFailed }}</b></span>
        </div>
        <el-button
          v-if="batchDialog.resultFailed > 0"
          type="warning"
          size="small"
          @click="exportFailedAccounts"
        >
          <el-icon><Download /></el-icon>
          导出失败账号
        </el-button>
      </div>
      <el-table :data="batchDialog.results" max-height="400" size="small" border>
        <el-table-column prop="email" label="邮箱" width="200" />
        <el-table-column label="密码" width="120">
          <template #default="{ row }">
            <span v-if="!row.success && row.password" style="color: var(--color-danger); font-family: monospace;">{{ row.password }}</span>
            <span v-else style="color: var(--color-text-3)">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="accountName" label="账号名称" width="120">
          <template #default="{ row }">{{ row.accountName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="success" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="详情" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="{ color: row.success ? 'var(--color-text-2)' : 'var(--color-danger)' }">{{ row.message }}</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="batchDialog.resultVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload, Refresh, Edit, Delete, Search, OfficeBuilding, Download, Key } from '@element-plus/icons-vue'
import { listAccounts, createAccount, updateAccount, deleteAccount, batchImport, syncAccountRelationships, listMerchants, refreshAccountToken } from '@/api'

const allAccounts = ref([])
const loading = ref(false)
const filters = reactive({ keyword: '', status: null })
const merchants = ref([])
const merchantsLoading = ref(false)

async function fetchMerchants() {
  merchantsLoading.value = true
  try {
    const res = await listMerchants()
    merchants.value = Array.isArray(res) ? res : (res?.data || [])
  } catch (e) {} finally {
    merchantsLoading.value = false
  }
}

async function fetchAccounts() {
  loading.value = true
  try {
    const params = {}
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.status) params.status = filters.status
    const res = await listAccounts(params)
    allAccounts.value = Array.isArray(res) ? res : (res?.data || [])
  } catch (e) {
    allAccounts.value = []
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.keyword = ''
  filters.status = null
  fetchAccounts()
}

const filteredAccounts = computed(() => {
  return allAccounts.value
})

const hasUserAccounts = computed(() => {
  return allAccounts.value.some(acc => acc.accountType === 'USER')
})

function initialOf(acc) {
  const n = acc.name || acc.discordName || acc.nickname || acc.globalName || acc.username || '?'
  return n.charAt(0).toUpperCase()
}

function getAvatar(acc) {
  if (!acc.avatarUrl) return ''
  return acc.avatarUrl
}

function statusClass(status) {
  const map = {
    'ACTIVE': 'active',
    'INACTIVE': 'inactive',
    'TOKEN_EXPIRED': 'expired'
  }
  return map[status] || 'inactive'
}

function statusLabel(status) {
  const map = {
    'ACTIVE': '正常',
    'INACTIVE': '已停用',
    'TOKEN_EXPIRED': 'Token过期'
  }
  return map[status] || '未知'
}

async function syncAccount(acc) {
  try {
    await syncAccountRelationships(acc.id)
    ElMessage.success('同步请求已发送')
    await fetchAccounts()
  } catch (e) {}
}

function openAddAccount() {
  botDialog.visible = true
  botDialog.editId = null
  botDialog.form = { nickname: '', token: '', accountType: 'BOT', email: '', remark: '', merchantId: null }
}

function openEdit(acc) {
  botDialog.visible = true
  botDialog.editId = acc.id
  botDialog.form = {
    nickname: acc.name || acc.discordName || acc.nickname || '',
    token: '',
    accountType: acc.accountType || 'BOT',
    email: acc.email || '',
    remark: acc.remark || '',
    merchantId: acc.merchantId || null
  }
}

const botDialog = reactive({
  visible: false,
  editId: null,
  saving: false,
  form: { nickname: '', token: '', accountType: 'BOT', email: '', remark: '', merchantId: null }
})

function resetBotDialog() {
  botDialog.editId = null
  botDialog.form = { nickname: '', token: '', accountType: 'BOT', email: '', remark: '', merchantId: null }
  botDialog.saving = false
}

async function saveBot() {
  if (!botDialog.form.nickname) { ElMessage.warning('请输入账号名称'); return }
  if (!botDialog.form.token) { ElMessage.warning('请输入 Token'); return }
  botDialog.saving = true
  try {
    if (botDialog.editId) {
      await updateAccount(botDialog.editId, {
        nickname: botDialog.form.nickname,
        token: botDialog.form.token,
        remark: botDialog.form.remark,
        merchantId: botDialog.form.merchantId
      })
      ElMessage.success('已更新')
    } else {
      await createAccount({
        name: botDialog.form.nickname,
        token: botDialog.form.token,
        accountType: botDialog.form.accountType,
        email: botDialog.form.email,
        remark: botDialog.form.remark,
        merchantId: botDialog.form.merchantId
      })
      ElMessage.success('已添加')
    }
    botDialog.visible = false
    await fetchAccounts()
  } finally {
    botDialog.saving = false
  }
}

const refreshTokenDialog = reactive({
  visible: false,
  saving: false,
  accountId: null,
  accountName: '',
  form: {
    email: '',
    password: ''
  }
})

function openRefreshToken(account) {
  refreshTokenDialog.accountId = account.id
  refreshTokenDialog.accountName = account.name || account.discordName || '未命名'
  refreshTokenDialog.form.email = account.email || ''
  refreshTokenDialog.form.password = ''
  refreshTokenDialog.visible = true
}

function resetRefreshTokenDialog() {
  refreshTokenDialog.visible = false
  refreshTokenDialog.accountId = null
  refreshTokenDialog.accountName = ''
  refreshTokenDialog.form.email = ''
  refreshTokenDialog.form.password = ''
}

async function doRefreshToken() {
  if (!refreshTokenDialog.form.email || !refreshTokenDialog.form.password) {
    ElMessage.warning('请输入邮箱和密码')
    return
  }
  refreshTokenDialog.saving = true
  try {
    const res = await refreshAccountToken(
      refreshTokenDialog.accountId,
      refreshTokenDialog.form.email,
      refreshTokenDialog.form.password
    )
    if (res?.message) {
      ElMessage.success(res.message)
    } else {
      ElMessage.success('Token 延期成功')
    }
    resetRefreshTokenDialog()
    await fetchAccounts()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || 'Token 延期失败'
    ElMessage.error(msg)
  } finally {
    refreshTokenDialog.saving = false
  }
}

const batchDialog = reactive({
  visible: false,
  saving: false,
  text: '',
  resultVisible: false,
  results: [],
  resultTotal: 0,
  resultSuccess: 0,
  resultFailed: 0
})

function openBatchImport() {
  batchDialog.visible = true
  batchDialog.text = ''
  batchDialog.resultVisible = false
  batchDialog.results = []
}
function resetBatchDialog() {
  batchDialog.text = ''
  batchDialog.saving = false
  batchDialog.resultVisible = false
  batchDialog.results = []
}

async function runBatchImport() {
  const lines = batchDialog.text.split(/\r?\n/).map(s => s.trim()).filter(Boolean)
  if (lines.length === 0) { ElMessage.warning('请输入至少一行'); return }
  const items = []
  for (const line of lines) {
    const [email, password] = line.split('|').map(s => s?.trim())
    if (!email || !password) continue
    items.push({ email, password })
  }
  if (items.length === 0) { ElMessage.warning('未检测到有效的 邮箱|密码 组合'); return }
  batchDialog.saving = true
  try {
    const res = await batchImport({ items })
    const total = res?.total ?? items.length
    const ok = res?.success ?? 0
    const fail = res?.failed ?? (total - ok)
    const results = res?.results || []
    
    batchDialog.resultTotal = total
    batchDialog.resultSuccess = ok
    batchDialog.resultFailed = fail
    batchDialog.results = results
    
    if (fail === 0 && total > 0) {
      ElMessage.success(`导入完成，成功 ${ok} / ${total}`)
      batchDialog.visible = false
    } else {
      batchDialog.resultVisible = true
      if (ok > 0 && fail > 0) {
        ElMessage.warning(`部分导入成功，成功 ${ok}，失败 ${fail}`)
      } else {
        ElMessage.error(`全部导入失败 ${fail} / ${total}`)
      }
    }
    if (ok > 0) {
      batchDialog.visible = false
      await fetchAccounts()
    }
  } catch (e) {
    batchDialog.resultTotal = items.length
    batchDialog.resultSuccess = 0
    batchDialog.resultFailed = items.length
    batchDialog.results = items.map(i => ({
      email: i.email,
      password: i.password,
      success: false,
      message: '请求失败: ' + (e?.message || e),
      accountName: null
    }))
    batchDialog.resultVisible = true
    ElMessage.error('请求失败，已生成失败列表')
  } finally {
    batchDialog.saving = false
  }
}

function exportFailedAccounts() {
  const failed = batchDialog.results.filter(r => !r.success)
  if (failed.length === 0) {
    ElMessage.warning('没有失败的账号')
    return
  }
  const content = failed.map(r => `${r.email}|${r.password || ''}`).join('\n')
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `failed_accounts_${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${failed.length} 个失败账号`)
}

async function removeAccount(acc) {
  try {
    const dispName = acc.name || acc.discordName || acc.nickname || acc.username
    await ElMessageBox.confirm(`确定要删除账号「${dispName}」吗？此操作将删除关联数据。`, '提示', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
    await deleteAccount(acc.id)
    ElMessage.success('已删除')
    await fetchAccounts()
  } catch (e) {}
}

onMounted(() => {
  fetchAccounts()
  fetchMerchants()
})

watch(() => filters.status, () => {
  fetchAccounts()
})

function getMerchantName(merchantId) {
  if (!merchantId) return ''
  const m = merchants.value.find(x => x.id === merchantId)
  return m ? m.name : ''
}

let searchTimer = null
watch(() => filters.keyword, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    fetchAccounts()
  }, 400)
})
</script>

<style scoped>
.accounts-page {
  width: 100%; height: 100%; display: flex; flex-direction: column; overflow: hidden;
}
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px 24px 16px; border-bottom: 1px solid var(--color-border); background: var(--color-bg-2);
}
.page-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-text-2); }
.header-actions { display: flex; gap: 10px; }
.page-body { flex: 1; overflow: auto; padding: 20px 24px; }
.filter-bar {
  display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px;
}
.stat-cards {
  display: flex; gap: 12px;
}
.stat-card {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 10px 20px; border-radius: 8px; min-width: 120px;
  background: var(--color-bg-3);
}
.stat-value { font-size: 22px; font-weight: 700; color: var(--color-text); }
.stat-label { font-size: 12px; color: var(--color-text-3); margin-top: 2px; }
.stat-total { border-left: 3px solid var(--color-primary); }
.stat-active { border-left: 3px solid var(--color-green); }
.stat-inactive { border-left: 3px solid var(--color-text-3); }
.filter-controls { display: flex; gap: 10px; align-items: center; }
.accounts-table { border-radius: 8px; overflow: hidden; }
.account-cell { display: flex; align-items: center; gap: 10px; }
.account-avatar-cell {
  flex-shrink: 0; background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
  color: #fff; font-weight: 600;
}
.account-cell-info { min-width: 0; }
.account-cell-name { font-size: 14px; font-weight: 600; color: var(--color-text); }
.account-cell-email { font-size: 12px; color: var(--color-text-3); margin-top: 2px; }
.agent-cell { display: flex; align-items: center; gap: 6px; }
.agent-avatar-mini { color: #fff; font-size: 11px; }
.agent-name { font-size: 13px; color: var(--color-text); }
.no-agent { color: var(--color-text-3); }
.status-cell { display: flex; align-items: center; gap: 6px; justify-content: center; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.status-dot.active { background: var(--color-green); }
.status-dot.inactive { background: var(--color-text-3); }
.status-dot.expired { background: var(--color-yellow); }
.status-text { font-size: 12px; }
.status-text.active { color: var(--color-green); }
.status-text.inactive { color: var(--color-text-3); }
.status-text.expired { color: var(--color-yellow); }
.merchant-cell { font-size: 13px; color: var(--color-text); display: inline-flex; align-items: center; }
.cell-hint { font-size: 12px; color: var(--color-text-3); }
.action-cell { display: flex; flex-wrap: nowrap; white-space: nowrap; align-items: center; gap: 0; }
</style>