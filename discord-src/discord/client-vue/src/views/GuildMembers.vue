<template>
  <div class="guild-members-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">服务器成员</h2>
        <p class="page-desc">管理 Discord 服务器成员数据，筛选和查看成员详情</p>
      </div>
      <div class="header-actions">
        <el-button
          link
          type="primary"
          @click="filterCollapsed = !filterCollapsed"
        >
          <el-icon><component :is="filterCollapsed ? ArrowDown : ArrowUp" /></el-icon>
          {{ filterCollapsed ? '展开筛选' : '收起筛选' }}
        </el-button>
      </div>
    </div>

    <div class="page-body">
      <div class="filter-bar" :class="{ collapsed: filterCollapsed }">
        <div class="filter-controls">
          <el-select
            v-model="filters.guildServerId"
            placeholder="服务器"
            clearable
            filterable
            style="width: 220px"
          >
            <el-option
              v-for="s in serverOptions"
              :key="s.id"
              :label="s.name || ('服务器 ' + s.id)"
              :value="s.id"
            />
          </el-select>

          <el-select
            v-model="filters.discordAccountId"
            placeholder="Discord账号"
            clearable
            filterable
            style="width: 200px"
          >
            <el-option
              v-for="a in accountOptions"
              :key="a.id"
              :label="a.name || a.discordName || ('账号 ' + a.id)"
              :value="a.id"
            />
          </el-select>

          <el-input
            v-model="filters.keyword"
            placeholder="用户名/ID"
            clearable
            style="width: 200px"
            :prefix-icon="Search"
            @keyup.enter="handleSearch"
          />

          <el-select
            v-model="filters.discordStatus"
            placeholder="Discord状态"
            clearable
            style="width: 140px"
          >
            <el-option label="全部" value="" />
            <el-option label="在线" value="online" />
            <el-option label="空闲" value="idle" />
            <el-option label="请勿打扰" value="dnd" />
            <el-option label="离线" value="offline" />
          </el-select>

          <el-select
            v-model="filters.friendStatus"
            placeholder="添加状态"
            clearable
            style="width: 140px"
          >
            <el-option label="全部" :value="null" />
            <el-option label="待添加" :value="0" />
            <el-option label="已分配" :value="1" />
            <el-option label="添加成功" :value="2" />
            <el-option label="添加失败" :value="3" />
          </el-select>

          <el-date-picker
            v-model="filters.fetchDateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="获取开始日期"
            end-placeholder="获取结束日期"
            value-format="YYYY-MM-DD"
            style="width: 280px"
          />

          <el-date-picker
            v-model="filters.passDateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="通过开始日期"
            end-placeholder="通过结束日期"
            value-format="YYYY-MM-DD"
            style="width: 280px"
          />

          <div class="filter-actions">
            <el-button type="primary" @click="handleSearch">
              <el-icon><Search /></el-icon> 查询
            </el-button>
            <el-button @click="handleReset">
              <el-icon><Refresh /></el-icon> 重置
            </el-button>
          </div>
        </div>
      </div>

      <div class="table-wrap">
        <el-table
          v-loading="loading"
          :data="tableData"
          stripe
          style="width: 100%"
          height="100%"
          :header-cell-style="{ background: 'var(--color-bg-2)', color: 'var(--color-text)' }"
        >
        <el-table-column prop="guildServerName" label="服务器名称" min-width="160">
          <template #default="{ row }">
            <span v-if="row.guildServerName">{{ row.guildServerName }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="discordAccountName" label="Discord账号" min-width="140">
          <template #default="{ row }">
            <el-tag v-if="row.discordAccountName" size="small" type="info" effect="plain">
              {{ row.discordAccountName }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="username" label="用户名" min-width="140">
          <template #default="{ row }">
            <span v-if="row.username">{{ row.username }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="nick" label="昵称" min-width="120">
          <template #default="{ row }">
            <span v-if="row.nick">{{ row.nick }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="userId" label="ID" min-width="160">
          <template #default="{ row }">
            <span v-if="row.userId" class="mono">{{ row.userId }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="discordStatus" label="Discord状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.discordStatus === 'online'" size="small" type="success">在线</el-tag>
            <el-tag v-else-if="row.discordStatus === 'idle'" size="small" type="warning">空闲</el-tag>
            <el-tag v-else-if="row.discordStatus === 'dnd'" size="small" type="danger">请勿打扰</el-tag>
            <el-tag v-else-if="row.discordStatus === 'offline'" size="small" type="info">离线</el-tag>
            <el-tag v-else size="small">未知</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="friendStatusText" label="添加状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.friendStatus === 0" size="small" type="info">待添加</el-tag>
            <el-tag v-else-if="row.friendStatus === 1" size="small" type="warning">已分配</el-tag>
            <el-tag v-else-if="row.friendStatus === 2" size="small" type="success">添加成功</el-tag>
            <el-tag v-else-if="row.friendStatus === 3" size="small" type="danger">添加失败</el-tag>
            <el-tag v-else size="small">-</el-tag>
          </template>
        </el-table-column>

        <el-table-column v-if="showErrorColumn" prop="lastError" label="添加结果说明" min-width="200">
          <template #default="{ row }">
            <span v-if="row.friendStatus === 3 && row.lastError" class="error-text">{{ row.lastError }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="assignedAccountName" label="添加账号" min-width="140">
          <template #default="{ row }">
            <el-tag v-if="row.assignedAccountName" size="small" type="info" effect="plain">
              {{ row.assignedAccountName }}
            </el-tag>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="passDate" label="通过日期" width="120">
          <template #default="{ row }">
            <span v-if="row.passDate">{{ formatDate(row.passDate) }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="joinedAt" label="加入时间" width="120">
          <template #default="{ row }">
            <span v-if="row.joinedAt">{{ formatDate(row.joinedAt) }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column prop="lastFetchedAt" label="获取时间" width="160">
          <template #default="{ row }">
            <span v-if="row.lastFetchedAt">{{ formatDateTime(row.lastFetchedAt) }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty v-if="!loading" description="暂无成员数据" />
        </template>
      </el-table>
      </div>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[20, 50, 100, 200, 500]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'

const loading = ref(false)
const tableData = ref([])
const filterCollapsed = ref(false)

const auth = useAuthStore()

const serverOptions = ref([])
const accountOptions = ref([])

// 当存在失败记录时显示"添加结果说明"列
const showErrorColumn = computed(() => {
  return tableData.value.some(row => row.friendStatus === 3 && row.lastError)
})

const filters = reactive({
  guildServerId: null,
  discordAccountId: null,
  keyword: '',
  discordStatus: '',
  friendStatus: null,
  fetchDateRange: null,
  passDateRange: null
})

const pagination = reactive({
  page: 0,
  size: 20,
  total: 0
})

function formatDate(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return String(dateStr)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return String(dateStr)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function fetchServerOptions() {
  try {
    const data = await api.get('/guild-members/servers')
    serverOptions.value = data || []
  } catch (e) {
    serverOptions.value = []
  }
}

async function fetchAccountOptions() {
  try {
    const data = await api.get('/guild-members/accounts')
    accountOptions.value = data || []
  } catch (e) {
    accountOptions.value = []
  }
}

async function fetchMembers() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.size
    }
    if (filters.guildServerId) params.guildServerId = filters.guildServerId
    if (filters.discordAccountId) params.discordAccountId = filters.discordAccountId
    if (filters.keyword) params.keyword = filters.keyword
    if (filters.discordStatus) params.discordStatus = filters.discordStatus
    if (filters.friendStatus !== null && filters.friendStatus !== '') params.friendStatus = filters.friendStatus
    if (filters.fetchDateRange && filters.fetchDateRange.length === 2) {
      params.fetchDateFrom = filters.fetchDateRange[0]
      params.fetchDateTo = filters.fetchDateRange[1]
    }
    if (filters.passDateRange && filters.passDateRange.length === 2) {
      params.passDateFrom = filters.passDateRange[0]
      params.passDateTo = filters.passDateRange[1]
    }

    const data = await api.get('/guild-members', { params })
    tableData.value = data.content || data.list || []
    pagination.total = data.totalElements || data.total || 0
  } catch (e) {
    ElMessage.error('获取服务器成员列表失败')
    tableData.value = []
    pagination.total = 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.page = 0
  fetchMembers()
}

function handleReset() {
  filters.guildServerId = null
  filters.discordAccountId = null
  filters.keyword = ''
  filters.discordStatus = ''
  filters.friendStatus = null
  filters.fetchDateRange = null
  filters.passDateRange = null
  pagination.page = 0
  fetchMembers()
}

function handleSizeChange(size) {
  pagination.size = size
  pagination.page = 0
  fetchMembers()
}

function handleCurrentChange(page) {
  pagination.page = page - 1
  fetchMembers()
}

onMounted(async () => {
  await Promise.all([
    fetchServerOptions(),
    fetchAccountOptions()
  ])

  // 普通用户自动选择第一个分配的账号
  if (auth.agent?.role && auth.agent.role !== 'PLATFORM_ADMIN' && auth.agent.role !== 'MERCHANT_ADMIN') {
    if (accountOptions.value.length > 0 && !filters.discordAccountId) {
      filters.discordAccountId = accountOptions.value[0].id
    }
    if (serverOptions.value.length > 0 && !filters.guildServerId) {
      filters.guildServerId = serverOptions.value[0].id
    }
  }

  fetchMembers()
})
</script>

<style scoped>
.guild-members-page {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-bg);
  overflow: hidden;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 16px;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border);
}

.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--color-text);
}

.page-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--color-text-2);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-body {
  flex: 1;
  min-height: 0;
  padding: 20px 24px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filter-bar {
  margin-bottom: 16px;
  padding: 16px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  transition: all var(--transition-normal);
}

.filter-bar.collapsed {
  padding: 8px 16px;
}

.filter-bar.collapsed .filter-controls > *:not(.filter-actions) {
  display: none;
}

.filter-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.mono {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
}

.text-muted {
  color: var(--color-text-3);
  font-size: 12px;
}

.error-text {
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.4;
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
</style>