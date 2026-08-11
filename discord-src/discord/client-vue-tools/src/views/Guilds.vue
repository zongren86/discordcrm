<template>
  <div class="guilds-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">服务器列表</h2>
        <p class="page-desc">管理 Discord 服务器配置，抓取服务器成员数据</p>
      </div>
      <div class="header-actions">
        <el-select v-model="filters.discordAccountId" placeholder="选择 Discord 账号" clearable style="width: 240px" @change="loadServers">
          <el-option v-for="a in accountOptions" :key="a.id" :label="a.name || a.discordBotName || a.discordBotId || ('账号' + a.id)" :value="a.id" />
        </el-select>
        <el-button type="primary" @click="openEditDialog()"><el-icon><Plus /></el-icon> 新增服务器</el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table :data="guildServers.servers" v-loading="guildServers.loading" stripe style="width: 100%" :header-cell-style="{ background: 'var(--color-bg-2)', color: 'var(--color-text)' }">
        <el-table-column label="服务器" min-width="220">
          <template #default="{ row }">
            <div class="server-cell">
              <img v-if="row.iconUrl" :src="row.iconUrl" class="server-icon" />
              <div v-else class="server-icon placeholder">{{ (row.name || '?').charAt(0).toUpperCase() }}</div>
              <div class="server-info">
                <div class="server-name">{{ row.name || '未命名服务器' }}</div>
                <div class="server-sub" v-if="row.guildId">Guild ID: {{ row.guildId }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="所属账号" width="180">
          <template #default="{ row }"><el-tag size="small" type="info" effect="plain">{{ row.accountName || row.accountDiscordBotName || '-' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="channelId" label="Channel ID" width="180">
          <template #default="{ row }"><span v-if="row.channelId" class="mono">{{ row.channelId }}</span><span v-else class="text-muted">-</span></template>
        </el-table-column>
        <el-table-column label="成员数" width="100" align="center">
          <template #default="{ row }"><span class="member-count">{{ row.memberCount || 0 }}</span></template>
        </el-table-column>
        <el-table-column label="最后抓取" width="160">
          <template #default="{ row }"><span v-if="row.lastFetchAt" class="text-muted">{{ formatTime(row.lastFetchAt) }}</span><span v-else class="text-muted">从未</span></template>
        </el-table-column>
        <el-table-column label="操作" width="310" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openMemberDialog(row)"><el-icon><User /></el-icon> 成员明细</el-button>
            <el-button size="small" link type="primary" @click="openEditDialog(row)"><el-icon><Edit /></el-icon> 编辑</el-button>
            <el-button size="small" link :type="fetchingServerId === row.id ? 'warning' : 'success'" :loading="fetchingServerId === row.id" @click="fetchingServerId === row.id ? openProgressDialog(row, fetchingTaskId) : openSyncDialog(row)">
              <el-icon v-if="fetchingServerId !== row.id"><Download /></el-icon>{{ fetchingServerId === row.id ? '同步中' : '同步' }}
            </el-button>
            <el-button size="small" link @click="openProgressDialog(row)"><el-icon><DataLine /></el-icon> 进度</el-button>
            <el-button size="small" link type="danger" @click="confirmDelete(row)"><el-icon><Delete /></el-icon></el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty v-if="!loading" description="暂无服务器，点击右上角新增" /></template>
      </el-table>
    </div>

    <el-dialog v-model="editDialog.visible" :title="editDialog.isEdit ? '编辑服务器' : '新增服务器'" width="560px" :close-on-click-modal="false">
      <el-form :model="editDialog.form" label-width="120px" label-position="left">
        <el-form-item label="所属账号" required>
          <el-select v-model="editDialog.form.discordAccountId" placeholder="选择 Discord 账号" style="width: 100%">
            <el-option v-for="a in accountOptions" :key="a.id" :label="a.name || a.discordBotName || a.discordBotId || ('账号' + a.id)" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务器 URL">
          <el-input v-model="editDialog.form.guildUrl" type="textarea" :rows="2" placeholder="https://discord.com/channels/guildId/channelId" />
          <el-button type="primary" plain @click="parseUrl" style="width: 100%; margin-top: 8px;"><el-icon><MagicStick /></el-icon> 解析</el-button>
        </el-form-item>
        <el-form-item label="Guild ID"><el-input v-model="editDialog.form.guildId" placeholder="服务器 ID" /></el-form-item>
        <el-form-item label="Channel ID"><el-input v-model="editDialog.form.channelId" placeholder="频道 ID" /></el-form-item>
        <el-form-item label="服务器名称"><el-input v-model="editDialog.form.name" placeholder="服务器名称（可选）" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveServer">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="syncDialog.visible" title="同步服务器成员" width="620px" :close-on-click-modal="false">
      <div v-if="syncDialog.server" class="sync-info">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="所属账号">{{ syncDialog.server.accountName || syncDialog.server.accountDiscordBotName }}</el-descriptions-item>
          <el-descriptions-item label="Bot Token"><el-input v-model="syncDialog.token" placeholder="请输入完整的 Discord Token" size="small" clearable /></el-descriptions-item>
          <el-descriptions-item label="Guild ID"><span class="mono">{{ syncDialog.server.guildId }}</span></el-descriptions-item>
          <el-descriptions-item label="Channel ID"><span class="mono">{{ syncDialog.server.channelId || '-' }}</span></el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">抓取配置（商户配置）</el-divider>
        <el-form :model="syncDialog.config" label-width="140px">
          <el-form-item label="获取数量上限"><el-input-number v-model="syncDialog.config.fetchLimit" :min="100" :max="100000" :step="1000" /></el-form-item>
          <el-form-item label="请求间隔(秒)"><el-input-number v-model="syncDialog.config.requestInterval" :min="1" :max="60" :step="1" /></el-form-item>
          <el-form-item label="每次请求数"><el-input-number v-model="syncDialog.config.requestCount" :min="10" :max="1000" :step="50" /></el-form-item>
          <el-form-item label="最大下钻深度"><el-input-number v-model="syncDialog.config.maxDepth" :min="1" :max="20" /></el-form-item>
          <el-form-item label="最大请求数"><el-input-number v-model="syncDialog.config.maxRequests" :min="1" :max="10000" :step="100" /></el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="syncDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="syncDialog.fetching" @click="startFetch">{{ syncDialog.fetching ? '启动中...' : '开始同步' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="progressDialog.visible" title="数据采集进度" width="600px" @close="stopProgressPolling">
      <div v-if="progressDialog.server" class="progress-content">
        <div class="progress-header">
          <el-icon class="progress-icon" :class="progressStatusClass"><component :is="progressStatusIcon" /></el-icon>
          <div class="progress-header-info">
            <div class="progress-title">{{ progressStatusText }}</div>
            <div class="progress-desc"><el-icon><Monitor /></el-icon> 服务器: <strong>{{ progressDialog.server.name || '未命名' }}</strong><span class="mono-text">(Guild ID: {{ progressDialog.server.guildId }})</span></div>
          </div>
        </div>
        <div class="progress-bar-section" v-if="progressTask">
          <div class="progress-bar-label">采集进度</div>
          <el-progress :percentage="progressPercentage" :stroke-width="12" :color="progressStatusClass === 'failed' ? '#f56c6c' : progressStatusClass === 'completed' ? '#67c23a' : '#409eff'" />
        </div>
        <div class="progress-stats-enhanced" v-if="progressTask">
          <div class="stat-card">
            <div class="stat-icon request-icon"><el-icon><Connection /></el-icon></div>
            <div class="stat-info">
              <div class="stat-values"><span class="stat-current">{{ progressTask.requestsSent || 0 }}</span><span class="stat-sep">/</span><span class="stat-total">{{ progressTask.maxRequests || '-' }}</span></div>
              <div class="stat-label">已请求 / 总请求数</div>
            </div>
          </div>
          <div class="stat-card">
            <div class="stat-icon fetch-icon"><el-icon><User /></el-icon></div>
            <div class="stat-info">
              <div class="stat-values"><span class="stat-current">{{ progressTask.membersUnique || 0 }}</span><span class="stat-sep">/</span><span class="stat-total">{{ progressTask.maxMembers || 0 }}</span></div>
              <div class="stat-label">已采集 / 总采集数</div>
            </div>
          </div>
        </div>
        <div class="progress-detail-grid" v-if="progressTask">
          <div class="detail-item"><span class="detail-label">采集状态</span><el-tag :type="progressStatusClass === 'failed' ? 'danger' : progressStatusClass === 'completed' ? 'success' : 'warning'" size="small">{{ progressStatusText }}</el-tag></div>
          <div class="detail-item"><span class="detail-label">当前前缀</span><span class="detail-value mono-text">{{ progressTask.currentPrefix || '-' }}</span></div>
          <div class="detail-item"><span class="detail-label">前缀进度</span><span class="detail-value">{{ progressTask.prefixesDone || 0 }} / {{ progressTask.prefixesTotal || '-' }}</span></div>
          <div class="detail-item"><span class="detail-label">已完成批次</span><span class="detail-value">{{ progressTask.completedPrefixCount || 0 }}</span></div>
        </div>
        <div v-if="progressDialog.isFetching" class="progress-tip"><el-icon class="is-loading"><Loading /></el-icon> 数据采集中，进度每 2 秒自动刷新...</div>
        <div v-else-if="progressTask && (progressTask.status === 'COMPLETED' || progressTask.status === 'DONE')" class="progress-tip completed"><el-icon><CircleCheck /></el-icon> 数据采集已完成</div>
      </div>
    </el-dialog>

    <el-dialog v-model="memberDialog.visible" :title="memberDialogTitle" width="720px" :close-on-click-modal="false" @close="stopMemberAutoRefresh">
      <div class="member-dialog-toolbar">
        <el-input v-model="memberDialog.search" size="small" placeholder="搜索成员昵称 / ID" :prefix-icon="Search" clearable style="width: 260px" @keyup.enter="searchMembers" @clear="searchMembers" />
        <el-tag size="small" type="info" effect="plain">共 {{ memberDialog.total }} 名成员</el-tag>
      </div>
      <el-table :data="memberDialog.members" v-loading="memberDialog.loading" stripe size="small" height="380" style="width: 100%">
        <el-table-column label="成员" min-width="180">
          <template #default="{ row }">
            <div class="member-cell-simple">
              <span class="member-name-text">{{ row.displayName || row.globalName || row.username || '未知成员' }}</span>
              <el-tag v-if="row.isBot" size="small" type="warning" effect="plain" style="margin-left:6px">BOT</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="用户名" width="140"><template #default="{ row }"><span class="mono">{{ row.username || '-' }}</span></template></el-table-column>
        <el-table-column label="昵称" width="140"><template #default="{ row }"><span>{{ row.nick || '-' }}</span></template></el-table-column>
        <el-table-column label="ID" width="180">
          <template #default="{ row }">
            <div class="id-cell">
              <span class="mono">{{ row.userId || '-' }}</span>
              <el-button size="small" link type="primary" :icon="CopyDocument" @click="copyText(row.userId)" title="复制 ID" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="加入时间" width="120"><template #default="{ row }"><span class="text-muted">{{ formatDate(row.joinedAt) }}</span></template></el-table-column>
        <template #empty><el-empty description="暂无成员数据，请先同步" :image-size="80" /></template>
      </el-table>
      <div class="member-dialog-pagination">
        <el-pagination v-model:current-page="memberDialog.page" v-model:page-size="memberDialog.size" :total="memberDialog.total" :page-sizes="[20, 50, 100, 200]" layout="total, sizes, prev, pager, next, jumper" background @size-change="onMemberPageSizeChange" @current-change="onMemberPageChange" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Download, DataLine, MagicStick, Loading, CircleCheck, Monitor, Connection, User, Search, CopyDocument } from '@element-plus/icons-vue'
import { useAccountsStore } from '@/stores/accounts'
import { useGuildServersStore } from '@/stores/guildServers'

const accounts = useAccountsStore()
const guildServers = useGuildServersStore()
const filters = reactive({ discordAccountId: null })
const loading = computed(() => guildServers.loading)
const accountOptions = computed(() => accounts.accounts || [])

const editDialog = reactive({ visible: false, isEdit: false, form: { id: null, discordAccountId: null, guildUrl: '', guildId: '', channelId: '', name: '' } })
const syncDialog = reactive({ visible: false, fetching: false, server: null, token: '', config: { fetchLimit: 10000, requestInterval: 3, requestCount: 100, maxDepth: 5, maxRequests: 1000 } })
const progressDialog = reactive({ visible: false, server: null, isFetching: false, timer: null })
const memberDialog = reactive({ visible: false, server: null, members: [], total: 0, loading: false, search: '', page: 0, size: 50, totalPages: 0 })

const memberDialogTitle = computed(() => { const name = memberDialog.server?.name || memberDialog.server?.guildId || '服务器'; return `成员明细 - ${name}` })
const progressTask = ref(null)
const progressTaskId = ref(null)
const fetchingServerId = ref(null)
const fetchingTaskId = ref(null)

const progressPercentage = computed(() => {
  if (!progressTask.value) return 0
  const total = progressTask.value.prefixesTotal || 0
  const done = progressTask.value.prefixesDone || 0
  if (total > 0) return Math.round((done / total) * 100)
  const maxRequests = progressTask.value.maxRequests || 1
  const requestsSent = progressTask.value.requestsSent || 0
  return Math.min(100, Math.round((requestsSent / maxRequests) * 100))
})

const statusMap = { PENDING: { icon: Loading, text: '等待开始', class: 'pending' }, RUNNING: { icon: Loading, text: '抓取进行中', class: 'running' }, COMPLETED: { icon: CircleCheck, text: '采集完成', class: 'completed' }, FAILED: { icon: Loading, text: '抓取失败', class: 'failed' }, DONE: { icon: CircleCheck, text: '采集完成', class: 'completed' }, ERROR: { icon: Loading, text: '抓取失败', class: 'failed' } }
const progressStatus = computed(() => { const status = progressTask.value?.status || 'PENDING'; return statusMap[status] || statusMap.PENDING })
const progressStatusIcon = computed(() => progressStatus.value.icon)
const progressStatusText = computed(() => progressStatus.value.text)
const progressStatusClass = computed(() => progressStatus.value.class)

async function loadServers() { try { await guildServers.fetchServers(filters.discordAccountId) } catch (e) { ElMessage.error('加载服务器列表失败') } }

function openEditDialog(server = null) {
  editDialog.isEdit = !!server
  if (server) { editDialog.form = { id: server.id, discordAccountId: server.discordAccountId, guildUrl: server.guildUrl || '', guildId: server.guildId || '', channelId: server.channelId || '', name: server.name || '' } }
  else { editDialog.form = { id: null, discordAccountId: filters.discordAccountId || (accountOptions.value[0]?.id) || null, guildUrl: '', guildId: '', channelId: '', name: '' } }
  editDialog.visible = true
}

async function parseUrl() {
  const url = editDialog.form.guildUrl
  if (!url) { ElMessage.warning('请输入服务器 URL'); return }
  try {
    const result = await guildServers.resolveLink(url)
    if (result.success) { editDialog.form.guildId = result.guildId || editDialog.form.guildId; editDialog.form.channelId = result.channelId || editDialog.form.channelId; ElMessage.success('URL 解析成功') }
    else { ElMessage.error(result.message || '解析失败') }
  } catch (e) { ElMessage.error('解析失败') }
}

async function saveServer() {
  if (!editDialog.form.discordAccountId) { ElMessage.warning('请选择所属账号'); return }
  try {
    const savedServer = await guildServers.saveServer(editDialog.form)
    ElMessage.success('保存成功'); editDialog.visible = false
    if (filters.discordAccountId && savedServer && savedServer.discordAccountId !== filters.discordAccountId) { filters.discordAccountId = null }
    await loadServers()
  } catch (e) { ElMessage.error('保存失败') }
}

async function confirmDelete(server) {
  try {
    await ElMessageBox.confirm(`确定删除服务器「${server.name || server.guildId}」及其所有成员数据？`, '删除确认', { type: 'warning' })
    await guildServers.deleteServer(server.id); ElMessage.success('删除成功'); await loadServers()
  } catch (e) {}
}

function openSyncDialog(server) {
  syncDialog.server = server
  syncDialog.token = server.accountBotToken || server.botToken || ""
  guildServers.loadMerchantConfig().then(config => { if (config) { syncDialog.config = { fetchLimit: config.fetchLimit || 10000, requestInterval: config.requestInterval || 3, requestCount: config.requestCount || 100, maxDepth: config.maxDepth || 5, maxRequests: config.maxRequests || 1000 } } }).catch(() => {})
  syncDialog.visible = true
}

async function startFetch() {
  if (!syncDialog.server) return
  syncDialog.fetching = true
  try {
    const botToken = (syncDialog.token || '').trim()
    if (!botToken) { ElMessage.error('请输入完整的 Bot Token，无法同步'); syncDialog.fetching = false; return }
    const result = await guildServers.startFetch({ token: botToken, link: syncDialog.server.guildId, guildServerId: syncDialog.server.id, discordAccountId: syncDialog.server.discordAccountId, channelId: syncDialog.server.channelId, maxMembers: syncDialog.config.fetchLimit, pageDelay: syncDialog.config.requestInterval, maxDepth: syncDialog.config.maxDepth, maxRequests: syncDialog.config.maxRequests })
    if (result.success) {
      ElMessage.success('同步任务已启动')
      if (progressDialog.timer) { clearInterval(progressDialog.timer); progressDialog.timer = null }
      syncDialog.visible = false; await nextTick()
      fetchingServerId.value = syncDialog.server.id; fetchingTaskId.value = result.taskId
      openProgressDialog(syncDialog.server, result.taskId)
    } else { ElMessage.error(result.message || '启动同步失败') }
  } catch (e) { console.error('启动同步失败:', e); ElMessage.error('启动同步失败') } finally { syncDialog.fetching = false }
}

function openProgressDialog(server, taskId = null) {
  progressDialog.server = server; progressDialog.isFetching = true; progressDialog.visible = true
  if (!taskId && fetchingServerId.value === server.id && fetchingTaskId.value) { taskId = fetchingTaskId.value }
  progressTaskId.value = taskId; progressTask.value = null
  if (taskId) { fetchingServerId.value = server.id; fetchingTaskId.value = taskId; pollProgress(taskId) }
  else { loadLatestTaskStatus(server.id) }
}

async function loadLatestTaskStatus(serverId) {
  try {
    const tasks = await guildServers.getActiveTasks()
    let runningTask = null; let completedTask = null
    for (const [taskId, task] of Object.entries(tasks || {})) {
      if (task.guildServerId === serverId) { const isTerminal = task.status === 'COMPLETED' || task.status === 'DONE' || task.status === 'FAILED' || task.status === 'ERROR'; if (!isTerminal && !runningTask) { runningTask = { ...task, taskId } } if (!completedTask) { completedTask = { ...task, taskId } } }
    }
    let taskToShow = runningTask || completedTask
    if (!taskToShow) { const dbTask = await guildServers.getLatestTask(serverId); if (dbTask && dbTask.status) { taskToShow = { ...dbTask, taskId: 'db_' + serverId } } }
    if (taskToShow) {
      progressTask.value = taskToShow; progressTaskId.value = taskToShow.taskId
      const isTerminal = taskToShow.status === 'COMPLETED' || taskToShow.status === 'DONE' || taskToShow.status === 'FAILED' || taskToShow.status === 'ERROR'
      if (!isTerminal) { fetchingServerId.value = serverId; fetchingTaskId.value = taskToShow.taskId; pollProgress(taskToShow.taskId) }
    }
  } catch (e) { console.warn('获取最近任务失败', e) }
}

async function openMemberDialog(server) {
  memberDialog.server = server; memberDialog.visible = true; memberDialog.search = ''; memberDialog.members = []; memberDialog.total = 0; memberDialog.page = 0; memberDialog.size = 50; memberDialog.totalPages = 0; memberDialog.loading = true
  try { await loadMemberPage() } catch (e) { ElMessage.error('加载成员列表失败') } finally { memberDialog.loading = false }
  if (server && fetchingServerId.value === server.id) startMemberAutoRefresh(server.id)
}

let memberRefreshTimer = null
function startMemberAutoRefresh(serverId) {
  stopMemberAutoRefresh()
  memberRefreshTimer = setInterval(async () => {
    if (fetchingServerId.value !== serverId || !memberDialog.visible || memberDialog.server?.id !== serverId) { stopMemberAutoRefresh(); return }
    try { await loadMemberPage() } catch (e) {}
  }, 5000)
}
function stopMemberAutoRefresh() { if (memberRefreshTimer) { clearInterval(memberRefreshTimer); memberRefreshTimer = null } }

async function loadMemberPage() {
  if (!memberDialog.server) return
  memberDialog.loading = true
  try {
    const result = await guildServers.fetchMembersList(memberDialog.server.id, { page: memberDialog.page, size: memberDialog.size, keyword: memberDialog.search || undefined })
    memberDialog.members = result.list || []; memberDialog.total = result.total || 0; memberDialog.totalPages = result.totalPages || 0
  } catch (e) { console.warn('加载成员列表失败', e) } finally { memberDialog.loading = false }
}

function onMemberPageChange(page) { memberDialog.page = page - 1; loadMemberPage() }
function onMemberPageSizeChange(size) { memberDialog.size = size; memberDialog.page = 0; loadMemberPage() }
function searchMembers() { memberDialog.page = 0; loadMemberPage() }

function stopProgressPolling() {
  progressDialog.isFetching = false
  if (progressDialog.timer) { clearInterval(progressDialog.timer); progressDialog.timer = null }
  if (progressTask.value && progressTask.value.status !== 'COMPLETED' && progressTask.value.status !== 'DONE' && progressTask.value.status !== 'FAILED' && progressTask.value.status !== 'ERROR' && fetchingTaskId.value) startBackgroundPolling(fetchingTaskId.value)
}

function startBackgroundPolling(taskId) {
  if (progressDialog.timer) clearInterval(progressDialog.timer)
  progressDialog.timer = setInterval(async () => {
    try {
      const task = await guildServers.pollTask(taskId)
      if (task) {
        progressTask.value = task
        if (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'DONE' || task.status === 'ERROR') {
          clearInterval(progressDialog.timer); progressDialog.timer = null; fetchingServerId.value = null; fetchingTaskId.value = null
          await loadServers()
          if (task.guildServerId && memberDialog.visible && memberDialog.server?.id === task.guildServerId) { stopMemberAutoRefresh(); await loadMemberPage() }
          ElMessage.success('数据采集已完成')
        }
      }
    } catch (e) { console.warn('后台轮询失败', e) }
  }, 2000)
}

function pollProgress(taskId) {
  if (progressDialog.timer) clearInterval(progressDialog.timer)
  progressDialog.timer = setInterval(async () => {
    try {
      let task = await guildServers.pollTask(taskId)
      if (!task && progressDialog.server) { const dbTask = await guildServers.getLatestTask(progressDialog.server.id); if (dbTask && dbTask.status) { task = { ...dbTask, taskId } } }
      if (task) {
        progressTask.value = task
        if (task.guildServerId) fetchingServerId.value = task.guildServerId
        if (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'DONE' || task.status === 'ERROR') {
          stopProgressPolling(); fetchingServerId.value = null; fetchingTaskId.value = null
          await loadServers()
          if (task.guildServerId && memberDialog.visible && memberDialog.server?.id === task.guildServerId) { stopMemberAutoRefresh(); await loadMemberPage() }
        }
      }
    } catch (e) { console.warn('获取进度失败', e) }
  }, 2000)
}

function formatTime(t) { if (!t) return '-'; const d = new Date(t); if (isNaN(d.getTime())) return t; const pad = n => n.toString().padStart(2, '0'); return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}` }
function formatDate(t) { if (!t) return '-'; const d = new Date(t); if (isNaN(d.getTime())) return String(t); const pad = n => n.toString().padStart(2, '0'); return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}` }
async function copyText(text) { if (!text) { ElMessage.warning('没有可复制的内容'); return } try { await navigator.clipboard.writeText(text); ElMessage.success('已复制到剪贴板') } catch (e) { const textarea = document.createElement('textarea'); textarea.value = text; document.body.appendChild(textarea); textarea.select(); document.execCommand('copy'); document.body.removeChild(textarea); ElMessage.success('已复制到剪贴板') } }

onMounted(async () => {
  if (accounts.accounts.length === 0) { try { await accounts.fetchAccounts() } catch (e) {} }
  await loadServers()
  try {
    const tasks = await guildServers.getActiveTasks()
    if (tasks && typeof tasks === 'object') {
      for (const [taskId, taskState] of Object.entries(tasks)) {
        if (taskState && taskState.status === 'RUNNING' && taskState.guildServerId) { fetchingServerId.value = taskState.guildServerId; fetchingTaskId.value = taskId; startBackgroundPolling(taskId); break }
      }
    }
  } catch (e) { console.warn('获取活跃任务失败', e) }
})

onUnmounted(() => { stopProgressPolling() })
</script>

<style scoped>
.guilds-page { width: 100%; height: 100%; display: flex; flex-direction: column; background: var(--color-bg); overflow: hidden; }
.page-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px 16px; background: var(--color-bg-2); border-bottom: 1px solid var(--color-border); }
.page-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-text-2); }
.header-actions { display: flex; align-items: center; gap: 12px; }
.page-body { flex: 1; min-height: 0; padding: 20px 24px; overflow: auto; }
.server-cell { display: flex; align-items: center; gap: 10px; }
.server-icon { width: 40px; height: 40px; border-radius: 10px; object-fit: cover; background: var(--color-bg-3); }
.server-icon.placeholder { display: flex; align-items: center; justify-content: center; color: #fff; font-weight: 700; background: linear-gradient(135deg, var(--color-primary), var(--color-pink)); }
.server-info { flex: 1; min-width: 0; }
.server-name { font-size: 14px; font-weight: 600; color: var(--color-text); }
.server-sub { font-size: 11px; color: var(--color-text-3); font-family: monospace; }
.member-count { font-weight: 600; color: var(--color-primary); }
.mono { font-family: monospace; font-size: 12px; }
.text-muted { color: var(--color-text-3); font-size: 12px; }
.progress-header { display: flex; align-items: center; gap: 14px; margin-bottom: 20px; }
.progress-icon { font-size: 48px; padding: 12px; border-radius: 50%; }
.progress-icon.pending { color: var(--color-text-3); }
.progress-icon.running { color: var(--color-primary); animation: spin 2s linear infinite; }
.progress-icon.completed { color: #67c23a; }
.progress-icon.failed { color: #f56c6c; }
.progress-title { font-size: 16px; font-weight: 600; color: var(--color-text); }
.progress-desc { font-size: 12px; color: var(--color-text-3); margin-top: 4px; }
.progress-bar-section { margin-bottom: 20px; }
.progress-bar-label { font-size: 12px; color: var(--color-text-2); margin-bottom: 8px; font-weight: 500; }
.progress-stats-enhanced { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 20px; }
.stat-card { background: var(--color-bg-2); border-radius: 12px; padding: 16px; display: flex; align-items: center; gap: 14px; border: 1px solid var(--color-border); }
.stat-icon { width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 22px; flex-shrink: 0; }
.stat-icon.request-icon { background: linear-gradient(135deg, rgba(64,158,255,0.15), rgba(64,158,255,0.05)); color: #409eff; }
.stat-icon.fetch-icon { background: linear-gradient(135deg, rgba(103,194,58,0.15), rgba(103,194,58,0.05)); color: #67c23a; }
.stat-info { flex: 1; min-width: 0; }
.stat-values { display: flex; align-items: baseline; gap: 4px; }
.stat-current { font-size: 24px; font-weight: 700; color: var(--color-text); }
.stat-sep { font-size: 18px; color: var(--color-text-3); }
.stat-total { font-size: 16px; font-weight: 600; color: var(--color-text-3); }
.stat-label { font-size: 12px; color: var(--color-text-2); margin-top: 4px; }
.progress-detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 20px; margin-bottom: 16px; padding: 14px; background: var(--color-bg-2); border-radius: 10px; }
.detail-item { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px dashed var(--color-border); }
.detail-label { font-size: 12px; color: var(--color-text-2); }
.detail-value { font-size: 13px; color: var(--color-text); font-weight: 500; }
.progress-tip { margin-top: 12px; padding: 10px; background: var(--color-bg-2); border-radius: 8px; font-size: 12px; color: var(--color-text-2); display: flex; align-items: center; gap: 6px; }
.progress-tip.completed { background: linear-gradient(135deg, rgba(103,194,58,0.15), rgba(103,194,58,0.05)); color: #67c23a; border: 1px solid rgba(103,194,58,0.2); }
.member-dialog-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.member-dialog-pagination { display: flex; justify-content: flex-end; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.member-cell-simple { display: flex; align-items: center; }
.member-name-text { font-size: 13px; font-weight: 600; color: var(--color-text); }
.id-cell { display: flex; align-items: center; gap: 4px; }
.progress-header-info { flex: 1; }
.progress-desc strong { color: var(--color-text); font-weight: 600; }
.progress-desc .mono-text { color: var(--color-text-3); margin-left: 8px; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
