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
              </div>
              <div class="form-row inline-row">
                <div class="inline-item">
                  <label>CPU</label>
                  <el-select v-model="emuConfig.cpuCores" size="small" style="width: 90px">
                    <el-option v-for="n in 8" :key="n" :label="String(n)" :value="n" />
                  </el-select>
                </div>
                <div class="inline-item">
                  <label>内存</label>
                  <el-select v-model="emuConfig.memoryGb" size="small" style="width: 90px">
                    <el-option v-for="n in 8" :key="n" :label="n + 'G'" :value="n" />
                  </el-select>
                </div>
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
            </div>
          </el-card>
        </el-col>

        <!-- 右侧：配置面板 -->
        <el-col :span="16">
          <el-card class="panel" shadow="hover">
            <template #header>
              <div class="panel-header">
                <el-icon><Key /></el-icon>
                <span>Discord 账号管理（{{ addedAccounts.length }} 个）</span>
                <el-button type="primary" size="small" @click="showAccountDialog = true">
                  添加账号
                </el-button>
              </div>
            </template>
            <div class="panel-body">
              <div v-if="addedAccounts.length === 0" class="empty-hint">
                暂未添加账号，点击上方"添加账号"按钮开始
              </div>
              <div v-else class="account-list">
                <div v-for="acc in addedAccounts" :key="acc.id" class="account-item">
                  <div class="account-info">
                    <el-tag size="small" :type="acc.status === 'ADDED' ? 'success' : 'warning'">
                      {{ acc.statusText }}
                    </el-tag>
                    <span class="account-name">{{ acc.accountName || acc.discordName || '-' }}</span>
                    <span class="account-email" v-if="acc.email">{{ acc.email }}</span>
                  </div>
                  <el-button type="danger" size="small" link @click="removeAccount(acc.id)">删除</el-button>
                </div>
              </div>
            </div>
          </el-card>

          <el-card class="panel" shadow="hover" style="margin-top: 16px">
            <template #header>
              <div class="panel-header">
                <el-icon><User /></el-icon>
                <span>服务器管理（{{ addedServers.length }} 个）</span>
                <el-button type="primary" size="small" @click="showServerDialog = true">
                  添加服务器
                </el-button>
              </div>
            </template>
            <div class="panel-body">
              <div v-if="addedServers.length === 0" class="empty-hint">
                暂未添加服务器，点击上方"添加服务器"按钮开始
              </div>
              <div v-else class="server-list">
                <div v-for="srv in addedServers" :key="srv.id" class="server-item">
                  <div class="server-info">
                    <el-tag size="small" :type="srv.status === 'ADDED' ? 'success' : 'warning'">
                      {{ srv.statusText }}
                    </el-tag>
                    <span class="server-name">{{ srv.serverName || srv.name || '-' }}</span>
                    <span class="server-count" v-if="srv.memberCount">{{ srv.memberCount }} 成员</span>
                  </div>
                  <div class="server-actions">
                    <el-button type="primary" size="small" link @click="syncFriends(srv)">同步成员</el-button>
                    <el-button type="danger" size="small" link @click="removeServer(srv.id)">删除</el-button>
                  </div>
                </div>
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

      <!-- 批量操作工具栏 -->
      <div class="batch-toolbar">
        <div class="batch-info">
          <el-checkbox 
            :model-value="isAllSelected" 
            :indeterminate="isIndeterminate"
            @change="handleSelectAll"
          >
            全选
          </el-checkbox>
          <span class="batch-count">已选择 {{ selectedEmulators.length }} 个模拟器</span>
          <el-button link type="primary" size="small" @click="clearSelection">清空选择</el-button>
        </div>
        <div class="batch-actions">
          <el-button type="success" size="small" @click="batchAction('start')" :disabled="!canBatchStart">
            <el-icon><VideoPlay /></el-icon> 批量启动
          </el-button>
          <el-button type="danger" size="small" @click="batchAction('stop')" :disabled="!canBatchStop">
            <el-icon><VideoPause /></el-icon> 批量停止
          </el-button>
          <el-button type="warning" size="small" @click="batchAction('restart')" :disabled="!canBatchRestart">
            <el-icon><Refresh /></el-icon> 批量重启
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('installDiscord')" :disabled="!canBatchInstall">
            批量安装 Discord
          </el-button>
          <el-button type="success" size="small" @click="batchAction('startAuto')" :disabled="!canBatchStartAuto">
            <el-icon><VideoPlay /></el-icon> 批量启动加好友
          </el-button>
          <el-button type="warning" size="small" @click="batchAction('stopAuto')" :disabled="!canBatchStopAuto">
            <el-icon><VideoPause /></el-icon> 批量停止加好友
          </el-button>
          <el-button type="danger" size="small" @click="batchAction('delete')" plain :disabled="selectedEmulators.length === 0">
            批量删除
          </el-button>
        </div>
      </div>

      <!-- 模拟器列表 -->
      <el-table 
        v-if="emulators.length > 0" 
        :data="sortedEmulators" 
        style="margin-top: 16px; width: 100%"
        @selection-change="handleSelectionChange"
        :row-class-name="rowClassName"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column label="索引" width="80">
          <template #default="{ row }">
            <span class="emu-name">#{{ row.index }}</span>
          </template>
        </el-table-column>
        <el-table-column label="名称" width="120">
          <template #default="{ row }">{{ row.name || `模拟器${row.index}` }}</template>
        </el-table-column>
        <el-table-column label="CPU/内存" width="100">
          <template #default="{ row }">
            <span v-if="row.cpuCores || row.memoryGb">{{ row.cpuCores || '-' }}核 / {{ row.memoryGb || '-' }}G</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="ADB端口" width="100">
          <template #default="{ row }">{{ row.adbPort || '-' }}</template>
        </el-table-column>
        <el-table-column label="分辨率" width="120">
          <template #default="{ row }">{{ row.resolution || '-' }}</template>
        </el-table-column>
        <el-table-column label="登录账号" width="150">
          <template #default="{ row }">
            <span v-if="row.discordAccount">{{ row.discordAccount }}</span>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="Discord状态" width="150">
          <template #default="{ row }">
            <div v-if="row.discordInstalled">
              <el-tag type="success" size="small" style="margin-right: 4px">已安装</el-tag>
              <span v-if="row.discordLoggedIn">已登录</span>
              <span v-else style="color: #f56c6c">未登录</span>
            </div>
            <el-tag v-else-if="row.status === 'RUNNING'" type="warning" size="small">未安装</el-tag>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="加好友状态" width="160">
          <template #default="{ row }">
            <div v-if="row.autoRunning" style="color: #67c23a">
              运行中 · 已添加 {{ row.addedCount || 0 }}
            </div>
            <div v-else-if="row.discordInstalled && row.status === 'RUNNING'">
              已添加 {{ row.addedCount || 0 }} · {{ formatCountdown(row.nextAddAt) }}
            </div>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="错误信息" width="200">
          <template #default="{ row }">
            <span v-if="row.lastError" style="color: #f56c6c; font-size: 12px">{{ row.lastError }}</span>
            <span v-else-if="row.autoLastResult" style="color: #409eff; font-size: 12px">{{ row.autoLastResult }}</span>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status !== 'RUNNING'">
              <el-button type="success" size="small" @click="startEmulator(row.index)">启动</el-button>
            </template>
            <template v-else>
              <el-button type="danger" size="small" @click="stopEmulator(row.index)">停止</el-button>
              <el-button type="warning" size="small" @click="restartEmulator(row.index)">重启</el-button>
            </template>
            <template v-if="row.status === 'RUNNING'">
              <el-button v-if="!row.discordInstalled" size="small" @click="installDiscord(row.index)">安装DS</el-button>
              <el-button v-else size="small" disabled>
                <el-icon color="#67c23a"><CircleCheck /></el-icon>
              </el-button>
              <el-button size="small" @click="launchDiscord(row.index)">启动DS</el-button>
              <el-button
                v-if="!row.autoRunning && row.discordInstalled"
                type="success" size="small"
                @click="startAuto(row.index)"
              >▶ 加好友</el-button>
              <el-button
                v-else-if="row.autoRunning"
                type="warning" size="small"
                @click="stopAuto(row.index)"
              >■ 停止</el-button>
            </template>
            <el-button size="small" type="danger" plain @click="deleteEmulator(row.index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else-if="!loading" description="暂无模拟器，在上方设置数量后点击「应用」创建" />
    </div>

    <!-- 添加账号弹窗 -->
    <el-dialog v-model="showAccountDialog" title="添加 Discord 账号" width="600px">
      <div class="dialog-section">
        <h4>已添加的账号</h4>
        <div v-if="addedAccounts.length === 0" class="empty-hint">暂无已添加账号</div>
        <div v-else class="dialog-list">
          <div v-for="acc in addedAccounts" :key="acc.id" class="dialog-item">
            <span>{{ acc.accountName || acc.discordName }}</span>
            <el-button type="danger" size="small" link @click="removeAccount(acc.id)">移除</el-button>
          </div>
        </div>
      </div>
      <el-divider />
      <div class="dialog-section">
        <div class="dialog-header">
          <h4>可添加的账号</h4>
          <el-input v-model="accountSearch" size="small" placeholder="搜索账号" clearable style="width: 200px" />
        </div>
        <div v-if="availableAccountsLoading" class="loading-hint">加载中...</div>
        <div v-else-if="filteredAvailableAccounts.length === 0" class="empty-hint">
          没有可添加的账号
        </div>
        <div v-else class="dialog-list">
          <div v-for="acc in filteredAvailableAccounts" :key="acc.id" class="dialog-item">
            <div class="item-info">
              <span class="item-name">{{ acc.accountName || acc.discordName }}</span>
              <span class="item-email" v-if="acc.email">{{ acc.email }}</span>
              <el-tag v-if="!acc.canAdd" size="small" type="warning">已占用</el-tag>
            </div>
            <el-button type="primary" size="small" :disabled="!acc.canAdd" @click="addAccount(acc.id)">
              添加
            </el-button>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 添加服务器弹窗 -->
    <el-dialog v-model="showServerDialog" title="添加服务器" width="600px">
      <div class="dialog-section">
        <h4>已添加的服务器</h4>
        <div v-if="addedServers.length === 0" class="empty-hint">暂无已添加服务器</div>
        <div v-else class="dialog-list">
          <div v-for="srv in addedServers" :key="srv.id" class="dialog-item">
            <span>{{ srv.serverName }}</span>
            <el-button type="danger" size="small" link @click="removeServer(srv.id)">移除</el-button>
          </div>
        </div>
      </div>
      <el-divider />
      <div class="dialog-section">
        <div class="dialog-header">
          <h4>可添加的服务器</h4>
          <el-input v-model="serverSearch" size="small" placeholder="搜索服务器" clearable style="width: 200px" />
        </div>
        <div v-if="availableServersLoading" class="loading-hint">加载中...</div>
        <div v-else-if="filteredAvailableServers.length === 0" class="empty-hint">
          没有可添加的服务器
        </div>
        <div v-else class="dialog-list">
          <div v-for="srv in filteredAvailableServers" :key="srv.id" class="dialog-item">
            <div class="item-info">
              <span class="item-name">{{ srv.name || srv.guildId }}</span>
              <span class="item-count" v-if="srv.memberCount">{{ srv.memberCount }} 成员</span>
              <el-tag v-if="srv.accountOccupied" size="small" type="warning">账号已占用</el-tag>
            </div>
            <el-button type="primary" size="small" :disabled="srv.accountOccupied" @click="addServer(srv)">
              添加
            </el-button>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import {
  Loading, WarningFilled, VideoPlay, VideoPause, Refresh,
  ChatDotRound, Setting, Key, Promotion, CircleCheck, User
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
const apkInput = ref(null)

const emuConfig = ref({ cpuCores: 1, memoryGb: 1 })
const autoConfig = ref({ intervalSeconds: 900, delayMinSeconds: 60, delayMaxSeconds: 800 })

const selectedEmulators = ref([])

// 账号管理
const addedAccounts = ref([])
const availableAccounts = ref([])
const availableAccountsLoading = ref(false)
const accountSearch = ref('')
const showAccountDialog = ref(false)

// 服务器管理
const addedServers = ref([])
const availableServers = ref([])
const availableServersLoading = ref(false)
const serverSearch = ref('')
const showServerDialog = ref(false)

// API 基础 URL
const API_BASE = '/api/emu'

const isAllSelected = computed(() => 
  emulators.value.length > 0 && selectedEmulators.value.length === emulators.value.length
)

const isIndeterminate = computed(() => 
  selectedEmulators.value.length > 0 && selectedEmulators.value.length < emulators.value.length
)

const canBatchStart = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status !== 'RUNNING'
  })
)

const canBatchStop = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING'
  })
)

const canBatchRestart = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING'
  })
)

const canBatchInstall = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING' && !emu.discordInstalled
  })
)

const canBatchStartAuto = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING' && emu.discordInstalled && !emu.autoRunning
  })
)

const canBatchStopAuto = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING' && emu.autoRunning
  })
)

// 过滤可用账号
const filteredAvailableAccounts = computed(() => {
  if (!accountSearch.value) return availableAccounts.value
  const keyword = accountSearch.value.toLowerCase()
  return availableAccounts.value.filter(acc => 
    (acc.accountName && acc.accountName.toLowerCase().includes(keyword)) ||
    (acc.email && acc.email.toLowerCase().includes(keyword)) ||
    (acc.discordName && acc.discordName.toLowerCase().includes(keyword))
  )
})

// 过滤可用服务器
const filteredAvailableServers = computed(() => {
  if (!serverSearch.value) return availableServers.value
  const keyword = serverSearch.value.toLowerCase()
  return availableServers.value.filter(srv => 
    (srv.name && srv.name.toLowerCase().includes(keyword)) ||
    (srv.guildId && srv.guildId.toLowerCase().includes(keyword))
  )
})

let healthCheckTimer = null

const emuApi = axios.create({ baseURL: '/emu-api', timeout: 15000 })

const sortedEmulators = computed(() => [...emulators.value].sort((a, b) => a.index - b.index))

function handleSelectionChange(selection) {
  selectedEmulators.value = selection.map(item => item.index)
}

function rowClassName({ row }) {
  return selectedEmulators.value.includes(row.index) ? 'selected-row' : ''
}

function handleSelectAll(value) {
  if (value) {
    selectedEmulators.value = emulators.value.map(e => e.index)
  } else {
    selectedEmulators.value = []
  }
}

function clearSelection() {
  selectedEmulators.value = []
}

async function batchAction(action) {
  if (selectedEmulators.value.length === 0) return
  
  const actionMap = {
    start: { confirm: '确定要批量启动选中的模拟器吗？', method: 'start' },
    stop: { confirm: '确定要批量停止选中的模拟器吗？', method: 'stop' },
    restart: { confirm: '确定要批量重启选中的模拟器吗？', method: 'restart' },
    installDiscord: { confirm: '确定要批量安装 Discord 到选中的模拟器吗？', method: 'install' },
    startAuto: { confirm: '确定要批量启动自动加好友吗？', method: 'startAuto' },
    stopAuto: { confirm: '确定要批量停止自动加好友吗？', method: 'stopAuto' },
    delete: { confirm: '确定要批量删除选中的模拟器吗？此操作不可恢复！', method: 'delete' }
  }
  
  const actionConfig = actionMap[action]
  if (!actionConfig) return
  
  try {
    await ElMessageBox.confirm(actionConfig.confirm, '确认', { type: 'warning' })
    
    const results = []
    for (const index of selectedEmulators.value) {
      try {
        let resp
        switch (actionConfig.method) {
          case 'start':
            resp = await emuApi.post(`/emulators/${index}/start`)
            break
          case 'stop':
            resp = await emuApi.post(`/emulators/${index}/stop`)
            break
          case 'restart':
            resp = await emuApi.post(`/emulators/${index}/restart`)
            break
          case 'install':
            resp = await emuApi.post(`/discord/install/${index}`)
            break
          case 'startAuto':
            resp = await emuApi.post(`/autoadd/${index}/start`)
            break
          case 'stopAuto':
            resp = await emuApi.post(`/autoadd/${index}/stop`)
            break
          case 'delete':
            resp = await emuApi.delete(`/emulators/${index}`)
            break
        }
        results.push({ index, success: true })
      } catch (e) {
        results.push({ index, success: false, error: e.response?.data?.message || e.message })
      }
    }
    
    const successCount = results.filter(r => r.success).length
    const failCount = results.filter(r => !r.success).length
    
    if (failCount === 0) {
      ElMessage.success(`批量操作完成，${successCount}个模拟器操作成功`)
    } else {
      ElMessage.warning(`批量操作完成，${successCount}个成功，${failCount}个失败`)
      const failed = results.filter(r => !r.success).map(r => `#${r.index}`).join(', ')
      ElMessage.error(`失败的模拟器: ${failed}`)
    }
    
    if (action === 'delete') {
      emulators.value = emulators.value.filter(e => !selectedEmulators.value.includes(e.index))
      selectedEmulators.value = []
    }
    
    await fetchEmulators()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量操作失败')
    }
  }
}

onMounted(async () => {
  await checkService()
  if (backendAvailable.value) {
    await Promise.all([
      fetchEmulators(),
      loadAddedAccounts(),
      loadAvailableAccounts(),
      loadAddedServers(),
      loadAvailableServers(),
      loadAutoConfig(),
      checkApkStatus()
    ])
  }
  startHealthCheck()
})

onUnmounted(() => {
  if (healthCheckTimer) { clearInterval(healthCheckTimer); healthCheckTimer = null }
})

// 监听弹窗打开时加载数据
watch(showAccountDialog, async (val) => {
  if (val) {
    await loadAvailableAccounts()
  }
})

watch(showServerDialog, async (val) => {
  if (val) {
    await loadAvailableServers()
  }
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

// ========== 账号管理 ==========

async function loadAddedAccounts() {
  try {
    const resp = await axios.get(`${API_BASE}/accounts/added`)
    addedAccounts.value = resp.data || []
  } catch { addedAccounts.value = [] }
}

async function loadAvailableAccounts() {
  availableAccountsLoading.value = true
  try {
    const resp = await axios.get(`${API_BASE}/accounts/available`, {
      params: { keyword: accountSearch.value || undefined }
    })
    availableAccounts.value = resp.data || []
  } catch { availableAccounts.value = [] }
  finally { availableAccountsLoading.value = false }
}

async function addAccount(discordAccountId) {
  try {
    await axios.post(`${API_BASE}/accounts/add`, { discordAccountId })
    ElMessage.success('账号已添加')
    await Promise.all([loadAddedAccounts(), loadAvailableAccounts()])
  } catch (e) {
    ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message))
  }
}

async function removeAccount(bindingId) {
  try {
    await axios.delete(`${API_BASE}/accounts/${bindingId}`)
    ElMessage.success('账号已移除')
    await Promise.all([loadAddedAccounts(), loadAvailableAccounts()])
  } catch (e) {
    ElMessage.error('移除失败: ' + (e.response?.data?.message || e.message))
  }
}

// ========== 服务器管理 ==========

async function loadAddedServers() {
  try {
    const resp = await axios.get(`${API_BASE}/servers/added`)
    addedServers.value = resp.data || []
  } catch { addedServers.value = [] }
}

async function loadAvailableServers() {
  availableServersLoading.value = true
  try {
    const resp = await axios.get(`${API_BASE}/servers/available`, {
      params: { keyword: serverSearch.value || undefined }
    })
    availableServers.value = resp.data || []
  } catch { availableServers.value = [] }
  finally { availableServersLoading.value = false }
}

async function addServer(server) {
  try {
    await axios.post(`${API_BASE}/servers/add`, {
      serverId: server.id,
      discordAccountId: server.discordAccountId || null
    })
    ElMessage.success('服务器已添加')
    await Promise.all([loadAddedServers(), loadAvailableServers()])
  } catch (e) {
    ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message))
  }
}

async function removeServer(bindingId) {
  try {
    await axios.delete(`${API_BASE}/servers/${bindingId}`)
    ElMessage.success('服务器已移除')
    await Promise.all([loadAddedServers(), loadAvailableServers()])
  } catch (e) {
    ElMessage.error('移除失败: ' + (e.response?.data?.message || e.message))
  }
}

async function syncFriends(server) {
  try {
    const resp = await axios.post(`${API_BASE}/servers/${server.serverId}/sync-friends`)
    ElMessage.success(`已同步 ${resp.data.addedCount} 个好友到好友号池`)
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.response?.data?.message || e.message))
  }
}

// ========== 模拟器操作 ==========

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
    const resp = await emuApi.post('/emulators/count', {
      count: targetCount.value,
      cpuCores: emuConfig.value.cpuCores,
      memoryGb: emuConfig.value.memoryGb
    })
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success(`已设置 ${targetCount.value} 台模拟器 (${emuConfig.value.cpuCores}核, ${emuConfig.value.memoryGb}G)`)
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

.form-row.inline-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: nowrap;
}

.inline-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.form-row.inline label { min-width: 70px; font-size: 13px; }
.form-row label { min-width: 70px; font-size: 13px; color: #606266; }

.action-row { display: flex; gap: 8px; flex-wrap: wrap; }

.hint { font-size: 12px; color: #909399; }
.hint-sm { font-size: 11px; color: #c0c4cc; margin-left: 8px; }

.batch-toolbar {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.1);
}

.batch-info { display: flex; align-items: center; gap: 12px; }
.batch-count { font-size: 14px; color: #606266; }

.batch-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.emu-name { font-weight: 600; font-size: 14px; }

:deep(.selected-row) {
  background-color: #ecf5ff !important;
}

:deep(.el-table .cell) {
  padding: 8px 12px;
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

/* 账号列表样式 */
.account-list,
.server-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.account-item,
.server-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
}

.account-info,
.server-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.account-name,
.server-name {
  font-weight: 500;
}

.account-email {
  color: #909399;
  font-size: 12px;
}

.server-count {
  color: #67c23a;
  font-size: 12px;
}

.server-actions {
  display: flex;
  gap: 8px;
}

.empty-hint {
  text-align: center;
  color: #909399;
  padding: 20px;
  font-size: 13px;
}

/* 弹窗样式 */
.dialog-section {
  margin-bottom: 16px;
}

.dialog-section h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  color: #303133;
}

.dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.dialog-list {
  max-height: 200px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dialog-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;
}

.item-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}

.item-name {
  font-weight: 500;
}

.item-email {
  color: #909399;
  font-size: 12px;
}

.item-count {
  color: #67c23a;
  font-size: 12px;
}

.loading-hint {
  text-align: center;
  color: #909399;
  padding: 20px;
}
</style>
