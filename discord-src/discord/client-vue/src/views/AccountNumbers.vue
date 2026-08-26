<template>
  <div class="account-numbers-page">
    <div class="page-header">
      <div class="page-title-wrap">
        <h2 class="page-title">账号编号管理</h2>
        <p class="page-desc">管理 Discord 账号编号绑定关系</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreateDialog">
          <el-icon><Plus /></el-icon>
          <span>新增账号及编号</span>
        </el-button>
        <el-button type="success" @click="openGenerateDialog">
          <el-icon><Plus /></el-icon>
          <span>新增编号</span>
        </el-button>
      </div>
    </div>

    <div class="page-body">
      <div class="filter-bar">
        <div class="filter-controls">
          <el-input v-model="filters.keyword" size="default" placeholder="搜索自定义编号、绑定账号或ID"
            :prefix-icon="Search" clearable style="width: 240px;" @keyup.enter="fetchData" />
          <el-date-picker
            v-model="filters.dateRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            style="width: 360px;"
          />
          <el-button type="primary" @click="fetchData">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>

      <div class="table-wrap">
        <el-table v-loading="loading" :data="tableData" stripe style="width: 100%;" height="100%">
          <el-table-column label="操作" width="200" fixed="left">
            <template #default="{ row }">
              <div class="action-cell">
                <el-button size="small" type="primary" link @click="openBindDialog(row)">
                  绑定账号
                </el-button>
                <el-button size="small" type="primary" link @click="handleUnbind(row)">
                  解绑账号
                </el-button>
                <el-button size="small" type="primary" link @click="openHistoryDialog(row)">
                  绑定历史
                </el-button>
                <el-button size="small" type="primary" link @click="handleDelete(row)">
                  删除
                </el-button>
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="id" label="ID" width="80" align="center" />
          <el-table-column prop="customNo" label="编号" width="100" align="center" />

          <el-table-column prop="accountName" label="用户名" min-width="200">
            <template #default="{ row }">
              <span v-if="row.accountName" class="bound-account">{{ row.accountName }}</span>
              <el-tag v-else type="info" size="small">未绑定</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="accountEmail" label="账号邮箱" min-width="200">
            <template #default="{ row }">
              <span v-if="row.accountEmail">{{ row.accountEmail }}</span>
              <span v-else style="color: var(--color-text-3);">-</span>
            </template>
          </el-table-column>

          <el-table-column prop="creatorName" label="创建人" width="120">
            <template #default="{ row }">
              <span v-if="row.creatorName">{{ row.creatorName }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>

          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">
              {{ formatDateTime(row.createdAt) }}
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
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>

      <el-empty v-if="!loading && tableData.length === 0" description="暂无数据" style="padding:60px 0;" />
    </div>

    <!-- 新增账号及编号弹窗 -->
    <el-dialog v-model="createDialog.visible" title="新增账号及编号" width="500px" @close="resetCreateDialog">
      <div class="dialog-tip">请输入账号，一行一个：</div>
      <el-input
        v-model="createDialog.accountsText"
        type="textarea"
        :rows="8"
        placeholder="account1@example.com&#10;account2@example.com&#10;..."
      />
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新增编号（按数量生成）弹窗 -->
    <el-dialog v-model="generateDialog.visible" title="新增编号" width="420px" @close="resetGenerateDialog">
      <div class="dialog-tip">请输入要生成的编号数量，生成的编号用户名和邮箱为空，后续可通过绑定功能关联账号。</div>
      <el-input-number
        v-model="generateDialog.quantity"
        :min="1"
        :max="500"
        :step="1"
        style="width: 100%; margin-top: 12px;"
        placeholder="请输入数量"
      />
      <template #footer>
        <el-button @click="generateDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleGenerate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 绑定账号弹窗 -->
    <el-dialog v-model="bindDialog.visible" title="绑定账号" width="500px" @close="resetBindDialog">
      <div class="bind-current">
        <div class="current-label">当前绑定账号：</div>
        <div class="current-value">{{ bindDialog.currentAccount || '未绑定' }}</div>
      </div>
      <el-divider />
      <div class="bind-new">
        <div class="new-label">新账号：</div>
        <el-select
          v-model="bindDialog.discordAccountId"
          filterable
          clearable
          placeholder="搜索并选择未绑定账号，或自行输入"
          style="width: 100%; margin-bottom: 12px;"
          @change="onSelectAccount"
          @search="onSearchAccount"
        >
          <el-option
            v-for="acc in unboundAccounts"
            :key="acc.id"
            :label="acc.name + (acc.email ? ' (' + acc.email + ')' : '')"
            :value="acc.id"
          />
        </el-select>
        <el-input
          v-model="bindDialog.newAccount"
          placeholder="或自行输入完整账号"
          style="margin-bottom: 12px;"
        />
        <el-input
          v-model="bindDialog.changeReason"
          placeholder="更换原因（可选）"
          :rows="2"
          type="textarea"
        />
      </div>
      <template #footer>
        <el-button @click="bindDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleBind">确定</el-button>
      </template>
    </el-dialog>

    <!-- 绑定历史弹窗 -->
    <el-dialog v-model="historyDialog.visible" title="绑定历史" width="700px" @close="historyDialog.visible = false">
      <el-table :data="historyList" stripe style="width: 100%;" max-height="400">
        <el-table-column prop="id" label="ID" width="60" align="center" />
        <el-table-column prop="oldAccount" label="修改前账号" min-width="120">
          <template #default="{ row }">
            <span>{{ row.oldAccount || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="newAccount" label="修改后账号" min-width="120">
          <template #default="{ row }">
            <span>{{ row.newAccount || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="changeReason" label="修改原因" min-width="120">
          <template #default="{ row }">
            <span>{{ row.changeReason || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="修改人" width="100">
          <template #default="{ row }">
            <span>{{ row.operatorName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="changedAt" label="修改时间" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.changedAt) }}
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="historyList.length === 0" description="暂无历史记录" style="padding:30px 0;" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listAccountNumbers,
  batchCreateAccountNumbers,
  generateAccountNumbers,
  bindAccountNumber,
  getAccountNumberHistory,
  listUnboundAccounts,
  unbindAccountNumber,
  deleteAccountNumber
} from '@/api'
import { useAccountsStore } from '@/stores/accounts'

const accountsStore = useAccountsStore()

const loading = ref(false)
const tableData = ref([])
const historyList = ref([])

const filters = reactive({
  keyword: '',
  dateRange: null
})

const pagination = reactive({
  page: 1,
  size: 100,
  total: 0
})

const createDialog = reactive({
  visible: false,
  accountsText: ''
})

const generateDialog = reactive({
  visible: false,
  quantity: 1
})

const bindDialog = reactive({
  visible: false,
  currentAccount: '',
  newAccount: '',
  discordAccountId: null,
  changeReason: '',
  currentRow: null
})

const historyDialog = reactive({
  visible: false
})

const unboundAccounts = ref([])

function formatDateTime(dateStr) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

async function fetchData() {
  loading.value = true
  try {
    const params = {
      page: pagination.page - 1,
      size: pagination.size
    }
    if (filters.keyword) {
      params.keyword = filters.keyword
    }
    if (filters.dateRange && filters.dateRange.length === 2) {
      params.startTime = filters.dateRange[0].getTime()
      params.endTime = filters.dateRange[1].getTime()
    }
    const data = await listAccountNumbers(params)
    tableData.value = data.content
    pagination.total = data.totalElements
  } catch (e) {
    ElMessage.error('获取数据失败')
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.keyword = ''
  filters.dateRange = null
  pagination.page = 1
  fetchData()
}

function handleSizeChange(size) {
  pagination.size = size
  pagination.page = 1
  fetchData()
}

function handleCurrentChange(page) {
  pagination.page = page
  fetchData()
}

function openCreateDialog() {
  createDialog.visible = true
}

function resetCreateDialog() {
  createDialog.accountsText = ''
}

function openGenerateDialog() {
  generateDialog.visible = true
  generateDialog.quantity = 1
}

function resetGenerateDialog() {
  generateDialog.quantity = 1
}

async function handleGenerate() {
  const qty = generateDialog.quantity
  if (!qty || qty < 1) {
    ElMessage.warning('请输入有效的数量')
    return
  }
  try {
    await generateAccountNumbers(qty)
    ElMessage.success(`成功生成 ${qty} 条编号`)
    generateDialog.visible = false
    resetGenerateDialog()
    fetchData()
  } catch (e) {
    ElMessage.error('生成失败')
  }
}

async function handleCreate() {
  const lines = createDialog.accountsText
    .split('\n')
    .map(line => line.trim())
    .filter(line => line.length > 0)

  if (lines.length === 0) {
    ElMessage.warning('请至少输入一个账号')
    return
  }

  try {
    await batchCreateAccountNumbers(lines)
    ElMessage.success(`成功创建 ${lines.length} 条记录`)
    createDialog.visible = false
    resetCreateDialog()
    fetchData()
  } catch (e) {
    ElMessage.error('创建失败')
  }
}

async function openBindDialog(row) {
  bindDialog.visible = true
  bindDialog.currentAccount = row.accountName || row.boundAccount
  bindDialog.newAccount = row.boundAccount || row.accountName || ''
  bindDialog.discordAccountId = row.discordAccountId || null
  bindDialog.changeReason = ''
  bindDialog.currentRow = row

  try {
    unboundAccounts.value = await listUnboundAccounts('')
  } catch (e) {
    unboundAccounts.value = []
  }
}

function resetBindDialog() {
  bindDialog.newAccount = ''
  bindDialog.discordAccountId = null
  bindDialog.changeReason = ''
  bindDialog.currentRow = null
}

function onSearchAccount(query) {
  if (query) {
    listUnboundAccounts(query).then(data => {
      unboundAccounts.value = data
    })
  } else {
    listUnboundAccounts('').then(data => {
      unboundAccounts.value = data
    })
  }
}

function onSelectAccount(accountId) {
  const acc = unboundAccounts.value.find(a => a.id === accountId)
  if (acc) {
    bindDialog.newAccount = acc.name
  }
}

async function handleBind() {
  if (!bindDialog.newAccount) {
    ElMessage.warning('请输入新账号')
    return
  }

  try {
    await bindAccountNumber(bindDialog.currentRow.id, {
      newAccount: bindDialog.newAccount,
      discordAccountId: bindDialog.discordAccountId,
      changeReason: bindDialog.changeReason
    })
    ElMessage.success('绑定成功')
    bindDialog.visible = false
    resetBindDialog()
    fetchData()
    // 同步账号 store，供消息中心等页面使用
    accountsStore.fetchAccounts()
  } catch (e) {
    ElMessage.error('绑定失败')
  }
}

async function openHistoryDialog(row) {
  historyDialog.visible = true
  try {
    historyList.value = await getAccountNumberHistory(row.id)
  } catch (e) {
    historyList.value = []
    ElMessage.error('获取历史记录失败')
  }
}

async function handleUnbind(row) {
  try {
    await ElMessageBox.confirm(`确定要解绑账号"${row.accountName || row.boundAccount || '-'}"吗？`, '提示', { type: 'warning' })
    await unbindAccountNumber(row.id)
    ElMessage.success('解绑成功')
    fetchData()
    // 同步账号 store
    accountsStore.fetchAccounts()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('解绑失败')
    }
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除账号编号"${row.id}"吗？此操作不可恢复。`, '提示', { type: 'error' })
    await deleteAccountNumber(row.id)
    ElMessage.success('删除成功')
    fetchData()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.account-numbers-page {
  padding: 20px;
  height: 100%;
  width: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  flex-shrink: 0;
}

.page-title-wrap {
  display: flex;
  flex-direction: column;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}

.page-desc {
  font-size: 13px;
  color: var(--color-text-3);
  margin: 4px 0 0 0;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.page-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.filter-bar {
  margin-bottom: 16px;
  flex-shrink: 0;
}

.filter-controls {
  display: flex;
  gap: 12px;
  align-items: center;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  width: 100%;
}

.pagination-wrap {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}

.dialog-tip {
  margin-bottom: 12px;
  color: var(--color-text-3);
  font-size: 13px;
}

.bind-current {
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.current-label {
  font-size: 13px;
  color: var(--color-text-3);
  margin-bottom: 4px;
}

.current-value {
  font-weight: 500;
}

.bound-account {
  color: var(--color-primary);
}

</style>
