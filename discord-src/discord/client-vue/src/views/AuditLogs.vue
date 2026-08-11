<template>
  <div class="audit-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-desc">系统操作日志与安全告警</p>
      </div>
      <div class="header-actions">
        <el-select v-model="filters.module" size="small" clearable placeholder="模块" style="width:130px">
          <el-option v-for="m in filterModules" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select v-model="filters.action" size="small" clearable placeholder="动作" style="width:130px">
          <el-option v-for="a in filterActions" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input v-model="filters.operator" size="small" clearable placeholder="操作人" style="width:140px" />
        <el-date-picker v-model="dateRange" type="daterange" size="small" value-format="YYYY-MM-DD" range-separator="至" />
        <el-button size="small" type="primary" @click="apply">筛选</el-button>
        <el-button size="small" @click="reset">重置</el-button>
        <el-button size="small" :icon="Download" :loading="exporting" @click="doExport">导出JSON</el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table v-loading="loading" :data="list" stripe style="width:100%">
        <el-table-column label="#" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="120" />
        <el-table-column prop="operatorRole" label="角色" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.operatorRole" size="small" effect="light">{{ row.operatorRole }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="action" label="动作" width="100">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" size="small" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="resourceType" label="类型" width="110" />
        <el-table-column prop="resourceId" label="资源ID" width="110" />
        <el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="140">
          <template #default="{ row }"><span class="cell-mono">{{ row.ip || '-' }}</span></template>
        </el-table-column>
        <el-table-column prop="result" label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="light">{{ row.result }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { listAuditLogs, exportAuditLogs, getAuditFilters } from '@/api'

const list = ref([])
const loading = ref(false)
const exporting = ref(false)
const dateRange = ref([])
const filters = reactive({ module: '', action: '', operator: '' })
const filterModules = ref([])
const filterActions = ref([])

function actionType(a) {
  const map = { CREATE: 'success', UPDATE: 'warning', DELETE: 'danger', LOGIN: 'info', LOGOUT: 'info', EXPORT: '' }
  return map[a] || 'info'
}
function formatTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function fetchFilters() {
  try {
    const f = await getAuditFilters()
    filterModules.value = f?.modules || []
    filterActions.value = f?.actions || []
  } catch (e) {}
}

async function apply() {
  loading.value = true
  try {
    const params = {}
    if (filters.module) params.module = filters.module
    if (filters.action) params.action = filters.action
    if (filters.operator) params.operator = filters.operator
    if (dateRange.value?.length === 2) {
      params.dateFrom = dateRange.value[0]
      params.dateTo = dateRange.value[1]
    }
    list.value = await listAuditLogs(params)
  } catch (e) {
    ElMessage.warning('加载日志失败')
  } finally {
    loading.value = false
  }
}

function reset() {
  filters.module = ''
  filters.action = ''
  filters.operator = ''
  dateRange.value = []
  apply()
}

async function doExport() {
  exporting.value = true
  try {
    const params = {}
    if (filters.module) params.module = filters.module
    if (filters.action) params.action = filters.action
    if (filters.operator) params.operator = filters.operator
    if (dateRange.value?.length === 2) {
      params.dateFrom = dateRange.value[0]
      params.dateTo = dateRange.value[1]
    }
    const data = await exportAuditLogs(params)
    const blob = new Blob([typeof data === 'string' ? data : JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `audit-logs-${Date.now()}.json`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出')
  } catch (e) {
    ElMessage.warning('导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(() => { fetchFilters(); apply() })
</script>

<style scoped>
.audit-page { width:100%; height:100%; display:flex; flex-direction:column; overflow:hidden; }
.page-header { padding:20px 24px 16px; background:var(--color-bg-2); border-bottom:1px solid var(--color-border); display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px; }
.page-title { margin:0; font-size:18px; font-weight:700; color:var(--color-text); }
.page-desc { margin:4px 0 0; font-size:12px; color:var(--color-text-2); }
.header-actions { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
.page-body { flex:1; overflow:auto; padding:20px 24px; }
.cell-mono { font-family:"JetBrains Mono",monospace; font-size:12px; color:var(--color-text-2); }
</style>
