<template>
  <div class="customers-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">客户管理</h2>
        <p class="page-desc">管理所有客户资料、批量操作与消息群发</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Download" @click="handleExport">导出数据</el-button>
        <el-button type="primary" :icon="Promotion" @click="showBatchSend = true">
          批量发送消息
        </el-button>
      </div>
    </div>

    <div class="page-body">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-input v-model="filters.keyword" size="default" placeholder="搜索昵称/ID/备注" :prefix-icon="Search"
          clearable style="width: 240px" @clear="loadData" @keyup.enter="loadData" />
        <el-select v-model="filters.accountId" size="default" placeholder="Discord账号" clearable filterable style="width: 180px" @change="loadData">
          <el-option :value="0" label="全部账号" />
          <el-option v-for="a in accountOptions" :key="a.id" :value="a.id" :label="a.name || ('账号 ' + a.id)" />
        </el-select>
        <el-select v-model="filters.stage" size="default" placeholder="销售阶段" clearable style="width: 160px" @change="loadData">
          <el-option v-for="s in stageOptions" :key="s.value" :value="s.value" :label="s.label" />
        </el-select>
        <el-input v-model="filters.tag" size="default" placeholder="按标签筛选" clearable style="width: 160px" @clear="loadData" />
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
      </div>

      <!-- 批量操作条 -->
      <div v-if="selectedIds.length > 0" class="batch-bar">
        <span>已选 <b>{{ selectedIds.length }}</b> 个客户</span>
        <el-select v-model="selectedStage" size="small" placeholder="批量更新阶段" clearable style="width: 160px">
          <el-option v-for="s in stageOptions" :key="s.value" :value="s.value" :label="s.label" />
        </el-select>
        <el-button size="small" :disabled="!selectedStage" @click="handleBatchStage">更新阶段</el-button>
        <el-button size="small" @click="batchDialogVisible = true">批量添加标签</el-button>
        <el-button size="small" type="danger" @click="selectedIds = []">取消选择</el-button>
      </div>

      <!-- 客户列表 -->
      <div class="table-wrap">
        <el-table
          v-loading="loading"
          :data="pagedCustomers"
          stripe
          @selection-change="handleSelectionChange"
          style="width: 100%;"
          height="100%"
        >
          <el-table-column type="selection" width="48" />
        <el-table-column label="客户" min-width="240">
          <template #default="{ row }">
            <div class="cust-cell">
              <el-avatar :size="40" :src="row.avatarUrl" class="cust-avatar">
                {{ initialOf(row) }}
              </el-avatar>
              <div class="cust-meta">
                <div class="cust-name">
                  {{ row.globalName || row.username || ('用户' + row.discordUserId) }}
                </div>
                <div class="cust-sub">@{{ row.username || '-' }} · ID: {{ row.discordUserId || '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="销售阶段" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.stage" :type="stageTagType(row.stage)" effect="light" class="stage-tag-sm">
              {{ stageLabel(row.stage) }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="Discord账号" width="150">
          <template #default="{ row }">
            <span>{{ row.discordAccountName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="160">
          <template #default="{ row }">
            <template v-if="parseTags(row.tags).length">
              <el-tag v-for="t in parseTags(row.tags)" :key="t" size="small" class="tag-item">{{ t }}</el-tag>
            </template>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="200">
          <template #default="{ row }">
            <span class="remark-text">{{ row.remark || row.notes || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后活跃" width="180">
          <template #default="{ row }">
            {{ formatTime(row.lastMessageAt) }}
          </template>
        </el-table-column>
        <el-table-column label="首次互动" width="180">
          <template #default="{ row }">
            {{ formatTime(row.firstSeenAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openConversation(row)">对话</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[20, 50, 100, 200, 500]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="pagination.page = 1"
        />
      </div>
    </div>

    <!-- 批量发送消息对话框 -->
    <el-dialog v-model="showBatchSend" title="批量发送消息" width="520px">
      <el-form label-position="top">
        <el-form-item label="已选客户数量">
          <span>{{ selectedIds.length }} 个客户</span>
        </el-form-item>
        <el-form-item label="消息内容（中文将自动翻译为英文）">
          <el-input v-model="batchSendContent" type="textarea" :rows="4"
            placeholder="输入要发送的消息内容" />
        </el-form-item>
        <el-form-item>
          <el-alert v-if="containsChinese(batchSendContent)" type="info"
            title="检测到中文，发送时将自动翻译为英文" show-icon :closable="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showBatchSend = false">取消</el-button>
        <el-button type="primary" :disabled="!batchSendContent.trim() || selectedIds.length === 0"
          :loading="batchSending" @click="handleBatchSend">
          发送 ({{ selectedIds.length }})
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量标签对话框 -->
    <el-dialog v-model="batchDialogVisible" title="批量添加标签" width="400px">
      <el-input v-model="batchTagInput" placeholder="输入多个标签用逗号分隔，如:VIP,重要,跟进" />
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!batchTagInput" @click="handleBatchTags">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Refresh, Download, Promotion
} from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import {
  listCustomers as listCustomersApi,
  listCustomerAccounts,
  batchTags, batchStage as batchStageApi, exportCustomers,
  batchSendMessage
} from '@/api'

const router = useRouter()

const loading = ref(false)
const customers = ref([])
const accountOptions = ref([])
const filters = ref({ keyword: '', stage: '', tag: '', accountId: 0 })
const selectedIds = ref([])
const selectedStage = ref('')
const batchDialogVisible = ref(false)
const batchTagInput = ref('')
const showBatchSend = ref(false)
const batchSendContent = ref('')
const batchSending = ref(false)

const stageOptions = [
  { value: 'PROSPECT',   label: '通过客户',   type: 'info' },
  { value: 'NEW',        label: '回复客户',   type: 'primary' },
  { value: 'CONVERTED',  label: '注册客户',   type: 'success' },
  { value: 'CHURNED',    label: '流失客户',   type: 'danger' },
  { value: 'ARCHIVED',   label: '归档客户',   type: 'info' }
]

const filteredCustomers = computed(() => {
  let list = customers.value
  if (filters.value.stage) {
    list = list.filter(c => c.stage === filters.value.stage)
  }
  // accountId为0或null表示"全部账号"，不进行前端过滤
  if (filters.value.accountId && filters.value.accountId !== 0) {
    list = list.filter(c => c.discordAccountId === filters.value.accountId)
  }
  if (filters.value.tag) {
    list = list.filter(c => {
      const tags = c.tags || ''
      return tags.toLowerCase().includes(filters.value.tag.toLowerCase())
    })
  }
  const kw = filters.value.keyword.trim().toLowerCase()
  if (!kw) return list
  return list.filter(c => {
    const name = (c.globalName || c.username || c.discordUserId || '').toLowerCase()
    const remark = (c.remark || c.notes || '').toLowerCase()
    return name.includes(kw) || remark.includes(kw)
  })
})

const pagination = reactive({
  page: 1,
  size: 100,
  get total() { return filteredCustomers.value.length }
})

const pagedCustomers = computed(() => {
  const start = (pagination.page - 1) * pagination.size
  return filteredCustomers.value.slice(start, start + pagination.size)
})

function parseTags(tags) {
  if (!tags) return []
  return tags.split(',').map(t => t.trim()).filter(Boolean)
}

function stageLabel(v) {
  return stageOptions.find(s => s.value === v)?.label || v
}
function stageTagType(v) {
  return stageOptions.find(s => s.value === v)?.type || 'info'
}

function initialOf(row) {
  const n = row?.globalName || row?.username || '?'
  return String(n).charAt(0).toUpperCase()
}

function formatTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function containsChinese(text) {
  return /[\u4e00-\u9fa5]/.test(text || '')
}

async function loadData() {
  loading.value = true
  try {
    const params = {}
    if (filters.value.keyword) params.keyword = filters.value.keyword
    if (filters.value.stage) params.stage = filters.value.stage
    if (filters.value.tag) params.tag = filters.value.tag
    // accountId为0表示"全部账号"，不传递给后端（不传即返回所有）
    if (filters.value.accountId && filters.value.accountId !== 0) params.accountId = filters.value.accountId
    const res = await listCustomersApi(params)
    customers.value = Array.isArray(res) ? res : (res?.data || [])
  } catch (e) {
    ElMessage.error('加载客户列表失败')
  } finally {
    loading.value = false
  }
}

async function loadAccountOptions() {
  try {
    const res = await listCustomerAccounts()
    accountOptions.value = Array.isArray(res) ? res : (res?.data || [])
  } catch (e) {
    console.warn('加载账号列表失败', e)
  }
}

function handleSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.conversationId).filter(Boolean)
}

async function handleBatchStage() {
  if (!selectedIds.value.length || !selectedStage.value) return
  try {
    await batchStageApi(selectedIds.value, selectedStage.value)
    ElMessage.success(`已更新 ${selectedIds.value.length} 条记录`)
    selectedStage.value = ''
    loadData()
  } catch (e) {
    ElMessage.error('批量更新失败')
  }
}

async function handleBatchTags() {
  if (!selectedIds.value.length || !batchTagInput.value) return
  const tags = batchTagInput.value.split(',').map(t => t.trim()).filter(Boolean)
  try {
    // 使用用户ID批量更新
    const userIds = customers.value
      .filter(c => selectedIds.value.includes(c.conversationId))
      .map(c => c.userId)
      .filter(Boolean)
    await batchTags(userIds, tags, 'add')
    ElMessage.success('标签已添加')
    batchTagInput.value = ''
    batchDialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error('批量添加标签失败')
  }
}

async function handleBatchSend() {
  if (!selectedIds.value.length || !batchSendContent.value.trim()) return
  batchSending.value = true
  try {
    const res = await batchSendMessage(selectedIds.value, batchSendContent.value)
    if (res) {
      ElMessage.success(`发送完成：成功 ${res.success} 条，失败 ${res.failed} 条`)
    }
    showBatchSend.value = false
    batchSendContent.value = ''
  } catch (e) {
    ElMessage.error('发送失败')
  } finally {
    batchSending.value = false
  }
}

async function handleExport() {
  try {
    const blob = await exportCustomers(false)
    const url = URL.createObjectURL(new Blob([blob]))
    const a = document.createElement('a')
    a.href = url
    a.download = `customers_${Date.now()}.json`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

function openConversation(row) {
  if (row.conversationId) {
    router.push('/chat?convId=' + row.conversationId)
  }
}

onMounted(() => {
  loadData()
  loadAccountOptions()
})
</script>

<style scoped>
.customers-page {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.page-header {
  padding: 20px 24px 16px;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-title { margin: 0; font-size: 14px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 14px; color: var(--color-text-2); }
.header-actions { display: flex; gap: 8px; }

.page-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filter-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  width: 100%;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}

.batch-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 10px 14px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-primary);
  border-radius: 8px;
  font-size: 14px;
  color: var(--color-text);
}

.cust-cell { display: flex; align-items: center; gap: 10px; }
.cust-avatar { flex-shrink: 0; }
.cust-meta { min-width: 0; }
.cust-name { font-size: 14px; font-weight: 600; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 200px; }
.cust-sub { font-size: 14px; color: var(--color-text-3); margin-top: 2px; font-family: "JetBrains Mono", monospace; }

.stage-tag-sm { font-size: 14px; }
.tag-item { margin-right: 4px; margin-bottom: 2px; }
.text-muted { color: var(--color-text-3); font-size: 14px; }
.remark-text { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: inline-block; }

:deep(.el-table) {
  background: transparent;
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: var(--color-bg-2);
  --el-table-row-hover-bg-color: var(--color-bg-hover);
  --el-table-border-color: var(--color-border);
  color: var(--color-text);
}
:deep(.el-table th.el-table__cell) { background: var(--color-bg-2); color: var(--color-text); font-weight: 600; }
:deep(.el-table td.el-table__cell) { color: var(--color-text); }
</style>
