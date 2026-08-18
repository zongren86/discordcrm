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
      <!-- 物理模拟器连接状态提示 -->
      <el-alert
        v-if="!physicalStatus.available && !loading"
        type="warning"
        :closable="false"
        show-icon
        title="未检测到本地模拟器"
        :description="physicalStatus.message"
        style="margin-bottom: 12px"
      >
        <template #default>
          <el-button type="primary" size="small" @click="checkPhysicalStatus">重新检测</el-button>
          <el-button type="primary" size="small" @click="syncPhysical" style="margin-left: 8px">同步数据</el-button>
        </template>
      </el-alert>

      <el-row :gutter="12">
        <!-- 左侧：控制面板 -->
        <el-col :span="6">
          <el-card class="panel" shadow="hover">
            <template #header>
              <div class="panel-header">
                <el-icon><Setting /></el-icon>
                <span>模拟器控制</span>
                <el-button type="primary" size="small" link @click="syncPhysical" :disabled="emuLoading">
                  🔄 同步
                </el-button>
              </div>
            </template>
            <div class="panel-body">
              <div class="form-row">
                <label>数量</label>
                <el-input-number v-model="targetCount" :min="1" :max="50" size="small" />
              </div>
              <div class="form-row inline-row">
                <label>CPU</label>
                <el-select v-model="emuConfig.cpuCores" size="small" style="width: 70px">
                  <el-option v-for="n in 8" :key="n" :label="String(n)" :value="n" />
                </el-select>
                <label>内存</label>
                <el-select v-model="emuConfig.memoryGb" size="small" style="width: 70px">
                  <el-option v-for="n in 8" :key="n" :label="n + 'G'" :value="n" />
                </el-select>
                <el-button type="primary" size="small" @click="applyCount" :disabled="emuLoading">
                  应用
                </el-button>
              </div>
              <div class="action-row">
                <el-button type="primary" @click="startAll" :disabled="emuLoading || !physicalStatus.available" size="small">
                  全部启动
                </el-button>
                <el-button type="primary" @click="stopAll" :disabled="emuLoading || !physicalStatus.available" size="small">
                  全部停止
                </el-button>
                <el-button type="primary" @click="restartAll" :disabled="emuLoading || !physicalStatus.available" size="small">
                  全部重启
                </el-button>
              </div>
            </div>
          </el-card>

          <el-card class="panel" shadow="hover" style="margin-top: 8px">
            <template #header>
              <div class="panel-header">
                <el-icon><ChatDotRound /></el-icon>
                <span>Discord 管理</span>
              </div>
            </template>
            <div class="panel-body">
              <div class="form-row">
                <label>APK</label>
                <el-tag :type="apkDownloaded ? 'success' : 'warning'" size="small">
                  {{ apkDownloaded ? '已下载' : '未下载' }}
                </el-tag>
                <el-button size="small" @click="downloadApk" :disabled="apkLoading">下载</el-button>
                <el-button size="small" @click="triggerApkUpload" :disabled="apkLoading">上传</el-button>
                <input ref="apkInput" type="file" accept=".apk" @change="handleApkUpload" style="display:none" />
              </div>
              <div class="form-row">
                <el-button type="primary" size="small" @click="installAllDiscord" :disabled="emuLoading">
                  全部安装 Discord
                </el-button>
              </div>
            </div>
          </el-card>

          <!-- 自动加好友配置移到左侧 -->
          <el-card class="panel" shadow="hover" style="margin-top: 8px">
            <template #header>
              <div class="panel-header">
                <el-icon><Promotion /></el-icon>
                <span>自动加好友配置</span>
              </div>
            </template>
            <div class="panel-body">
              <div class="form-row">
                <label>间隔</label>
                <el-input-number v-model="autoConfig.intervalMinutes" :min="1" :max="9999" size="small" style="width: 100px" />
                <span class="unit">分钟</span>
              </div>
              <div class="form-row">
                <label>延迟</label>
                <el-input-number v-model="autoConfig.delayMinMinutes" :min="0" :max="9999" size="small" style="width: 80px" />
                <span>~</span>
                <el-input-number v-model="autoConfig.delayMaxMinutes" :min="0" :max="9999" size="small" style="width: 80px" />
                <span class="unit">分钟</span>
              </div>
              <div class="form-row">
                <el-button type="primary" size="small" @click="saveAutoConfig">保存配置</el-button>
                <span class="hint-sm">间隔+随机延迟(下限~上限)后添加下一个好友</span>
              </div>
              <div class="form-row" style="margin-top: 8px">
                <el-button type="primary" size="small" @click="startAutoAll">
                  全部开始
                </el-button>
                <el-button type="primary" size="small" @click="stopAutoAll">
                  全部停止
                </el-button>
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- 右侧：配置面板 -->
        <el-col :span="18">
          <el-card class="panel" shadow="hover">
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
                    <span class="server-name">{{ srv.serverName || srv.name || '-' }}</span>
                    <span class="server-count" v-if="srv.memberCount">{{ srv.memberCount }} 成员</span>
                  </div>
                  <div class="server-actions">
                    <el-button type="primary" size="small" link @click="syncFriends(srv)">同步成员</el-button>
                    <el-button type="primary" size="small" link @click="removeServer(srv.id)">删除</el-button>
                  </div>
                </div>
              </div>
            </div>
          </el-card>

          <!-- 好友号池 -->
          <el-card class="panel" shadow="hover" style="margin-top: 8px">
            <template #header>
              <div class="panel-header">
                <el-icon><Avatar /></el-icon>
                <span>好友号池</span>
                <el-button size="small" @click="loadFriendPoolStats" :disabled="friendPoolLoading">
                  刷新
                </el-button>
              </div>
            </template>
            <div class="panel-body">
              <div v-if="friendPoolLoading" class="loading-hint">加载中...</div>
              <div v-else>
                <!-- 统计卡片 -->
                <el-row :gutter="6" class="friend-pool-stats">
                  <el-col :span="4">
                    <div class="stat-card">
                      <div class="stat-value">{{ friendPoolStats.total || 0 }}</div>
                      <div class="stat-label">总数</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-card stat-pending">
                      <div class="stat-value">{{ friendPoolStats.pending || 0 }}</div>
                      <div class="stat-ratio">{{ getRatio(friendPoolStats.pending) }}%</div>
                      <div class="stat-label">待添加</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-card stat-assigned">
                      <div class="stat-value">{{ friendPoolStats.assigned || 0 }}</div>
                      <div class="stat-ratio">{{ getRatio(friendPoolStats.assigned) }}%</div>
                      <div class="stat-label">已分配</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-card stat-success">
                      <div class="stat-value">{{ friendPoolStats.success || 0 }}</div>
                      <div class="stat-ratio">{{ getRatio(friendPoolStats.success) }}%</div>
                      <div class="stat-label">成功</div>
                    </div>
                  </el-col>
                  <el-col :span="4">
                    <div class="stat-card stat-failed">
                      <div class="stat-value">{{ friendPoolStats.failed || 0 }}</div>
                      <div class="stat-ratio">{{ getRatio(friendPoolStats.failed) }}%</div>
                      <div class="stat-label">失败</div>
                    </div>
                  </el-col>
                </el-row>

                <!-- 状态筛选 -->
                <div class="friend-pool-filter">
                  <el-radio-group v-model="friendPoolFilter" size="small" @change="loadFriendPool">
                    <el-radio-button label="">全部</el-radio-button>
                    <el-radio-button label="PENDING">待添加</el-radio-button>
                    <el-radio-button label="ASSIGNED">已分配</el-radio-button>
                    <el-radio-button label="SUCCESS">成功</el-radio-button>
                    <el-radio-button label="FAILED">失败</el-radio-button>
                  </el-radio-group>
                </div>

                <!-- 好友列表 -->
                <div v-if="friendPool.length === 0" class="empty-hint">
                  暂无好友，请到服务器管理中同步成员
                </div>
                <el-table v-else :data="friendPool.slice(0, 50)" size="small" style="width: 100%">
                  <el-table-column prop="username" label="用户名" width="150" />
                  <el-table-column prop="globalName" label="全局名称" width="150" />
                  <el-table-column label="状态" width="100">
                    <template #default="{ row }">
                      <el-tag :type="friendStatusTag(row.status)" size="small">
                        {{ row.statusText }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="lastError" label="错误信息" show-overflow-tooltip />
                </el-table>
                <div v-if="friendPool.length > 50" class="more-hint">
                  仅显示前50条，共 {{ friendPool.length }} 条记录
                </div>
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
          <el-button type="primary" size="small" @click="batchAction('start')" :disabled="!canBatchStart || !physicalStatus.available">
            批量启动
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('stop')" :disabled="!canBatchStop || !physicalStatus.available">
            批量停止
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('restart')" :disabled="!canBatchRestart || !physicalStatus.available">
            批量重启
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('installDiscord')" :disabled="!canBatchInstall || !physicalStatus.available">
            批量安装 Discord
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('startAuto')" :disabled="!canBatchStartAuto">
            批量启动加好友
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('stopAuto')" :disabled="!canBatchStopAuto">
            批量停止加好友
          </el-button>
          <el-button type="primary" size="small" @click="batchAction('delete')" :disabled="selectedEmulators.length === 0 || !physicalStatus.available">
            批量删除
          </el-button>
        </div>
      </div>

      <!-- 模拟器列表 -->
      <el-table 
        v-if="emulators.length > 0" 
        :data="sortedEmulators" 
        style="margin-top: 8px; width: 100%"
        @selection-change="handleSelectionChange"
        :row-class-name="rowClassName"
        size="small"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column label="索引" width="60">
          <template #default="{ row }">
            <span class="emu-name">#{{ row.index }}</span>
          </template>
        </el-table-column>
        <el-table-column label="名称" width="100">
          <template #default="{ row }">{{ row.name || `模拟器${row.index}` }}</template>
        </el-table-column>
        <el-table-column label="CPU/内存" width="100">
          <template #default="{ row }">
            <span v-if="row.cpuCores || row.memoryGb">{{ row.cpuCores || '-' }}核/{{ row.memoryGb || '-' }}G</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="ADB端口" width="80">
          <template #default="{ row }">{{ row.adbPort || '-' }}</template>
        </el-table-column>
        <el-table-column label="分辨率" width="100">
          <template #default="{ row }">{{ row.resolution || '-' }}</template>
        </el-table-column>
        <el-table-column label="登录账号" width="120">
          <template #default="{ row }">
            <span v-if="row.discordAccount">{{ row.discordAccount }}</span>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="Discord状态" width="150">
          <template #default="{ row }">
            <div v-if="row.discordInstalled">
              <el-tag type="success" size="small" style="margin-right: 2px">已安装</el-tag>
              <span v-if="row.discordLoggedIn" style="color: #67c23a;font-size:12px">已登录</span>
              <span v-else style="color: #f56c6c;font-size:12px">未登录</span>
              <el-tag v-if="row.discordLoggedIn && row.discordOnHome" type="success" size="small" style="margin-left: 2px">首页</el-tag>
            </div>
            <el-tag v-else-if="row.status === 'RUNNING'" type="warning" size="small">未安装</el-tag>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="加好友状态" width="140">
          <template #default="{ row }">
            <div v-if="row.autoRunning" style="color: #67c23a">
              运行中·已添加 {{ row.addedCount || 0 }}
            </div>
            <div v-else-if="row.discordInstalled && row.status === 'RUNNING'">
              已添加 {{ row.addedCount || 0 }} · {{ formatCountdown(row.nextAddAt) }}
            </div>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="错误信息" width="180">
          <template #default="{ row }">
            <span v-if="row.lastError" style="color: #f56c6c; font-size: 12px">{{ row.lastError }}</span>
            <span v-else-if="row.autoLastResult" style="color: #409eff; font-size: 12px">{{ row.autoLastResult }}</span>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status !== 'RUNNING'">
              <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="startEmulator(row.index)">
                {{ isOperating(row.index) ? '启动中...' : '启动' }}
              </el-button>
            </template>
            <template v-else>
              <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="stopEmulator(row.index)">
                {{ isOperating(row.index) ? '停止中...' : '停止' }}
              </el-button>
              <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="restartEmulator(row.index)">
                {{ isOperating(row.index) ? '重启中...' : '重启' }}
              </el-button>
            </template>
            <template v-if="row.status === 'RUNNING'">
              <el-button v-if="!row.discordInstalled" size="small" link type="primary" @click="installDiscord(row.index)">安装DS</el-button>
              <el-button v-else size="small" link type="primary" disabled>
                已安装
              </el-button>
              <el-button size="small" link type="primary" @click="launchDiscord(row.index)">启动DS</el-button>
              <el-button
                v-if="!row.autoRunning && row.discordInstalled"
                size="small" link type="primary"
                @click="startAuto(row.index)"
              >加好友</el-button>
              <el-button
                v-else-if="row.autoRunning"
                size="small" link type="primary"
                @click="stopAuto(row.index)"
              >停止</el-button>
            </template>
            <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="deleteEmulator(row.index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else-if="!loading" description="暂无模拟器，在上方设置数量后点击「应用」创建" />
    </div>

    <!-- 添加服务器弹窗 -->
    <el-dialog v-model="showServerDialog" title="添加服务器" width="800px" :close-on-click-modal="false">
      <div class="dialog-section">
        <h4>已添加的服务器</h4>
        <div v-if="addedServers.length === 0" class="empty-hint">暂无已添加服务器</div>
        <el-table v-else :data="addedServers" size="small" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="serverName" label="服务器名称" width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="60">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click="removeServer(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-divider />
      <div class="dialog-section">
        <div class="dialog-header">
          <h4>可添加的服务器</h4>
          <div class="filter-row">
            <el-select v-model="selectedAccountId" size="small" placeholder="账号筛选（可选）" clearable style="width: 150px">
              <el-option 
                v-for="acc in addedAccounts" 
                :key="acc.discordAccountId" 
                :label="acc.accountName || acc.discordName" 
                :value="acc.discordAccountId" 
              />
            </el-select>
            <el-input v-model="serverSearch" size="small" placeholder="搜索服务器名称" clearable style="width: 180px" />
          </div>
        </div>
        <div v-if="availableServersLoading" class="loading-hint">加载中...</div>
        <div v-else-if="filteredAvailableServers.length === 0" class="empty-hint">
          没有可添加的服务器
        </div>
        <el-table v-else :data="filteredAvailableServers" size="small" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="accountName" label="Discord账号" width="120" />
          <el-table-column prop="name" label="服务器名称" width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click="addServer(row)">添加</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import {
  Loading, WarningFilled, VideoPlay, VideoPause, Refresh,
  ChatDotRound, Setting, Key, Promotion, CircleCheck, User, Avatar
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from 'axios'
import { config } from '@/config'

const loading = ref(true)
const backendAvailable = ref(false)
const emulators = ref([])
const targetCount = ref(3)
const emuLoading = ref(false)

// 物理模拟器连接状态
const physicalStatus = ref({ available: false, message: '检测中...' })

const apkDownloaded = ref(false)
const apkLoading = ref(false)
const apkInput = ref(null)

const emuConfig = ref({ cpuCores: 1, memoryGb: 1 })
// 自动加好友配置：单位改为分钟
const autoConfig = ref({ intervalMinutes: 15, delayMinMinutes: 1, delayMaxMinutes: 10 })

// 操作中的模拟器索引列表（用于按钮禁用状态）
const operatingEmulators = ref(new Set())

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
const selectedAccountId = ref(null)  // 选中的账号ID，用于筛选服务器

// 好友号池
const friendPool = ref([])
const friendPoolStats = ref({ total: 0, pending: 0, assigned: 0, success: 0, failed: 0 })
const friendPoolLoading = ref(false)
const friendPoolFilter = ref('')

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
    (String(acc.id).includes(keyword)) ||
    (acc.accountName && acc.accountName.toLowerCase().includes(keyword)) ||
    (acc.email && acc.email.toLowerCase().includes(keyword)) ||
    (acc.discordName && acc.discordName.toLowerCase().includes(keyword))
  )
})

// 过滤可用服务器
const filteredAvailableServers = computed(() => {
  let servers = availableServers.value
  if (selectedAccountId.value) {
    servers = servers.filter(s => s.discordAccountId === selectedAccountId.value)
  }
  if (!serverSearch.value) return servers
  const keyword = serverSearch.value.toLowerCase()
  return servers.filter(srv => 
    (srv.name && srv.name.toLowerCase().includes(keyword)) ||
    (srv.guildId && srv.guildId.toLowerCase().includes(keyword))
  )
})

// 计算占比（2位小数）
function getRatio(count) {
  const total = friendPoolStats.value.total || 0
  if (total === 0) return '0.00'
  return ((count / total) * 100).toFixed(2)
}

let healthCheckTimer = null

const emuApi = axios.create({ baseURL: '/emu-api', timeout: 60000 })
emuApi.interceptors.request.use(config => {
  const token = localStorage.getItem('crm_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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
      loadAddedServers(),
      loadAvailableServers(),
      loadAutoConfig(),
      checkApkStatus(),
      loadFriendPoolStats(),
      loadFriendPool(),
      checkPhysicalStatus()
    ])
  }
  startHealthCheck()
})

onUnmounted(() => {
  if (healthCheckTimer) { clearInterval(healthCheckTimer); healthCheckTimer = null }
})

// 监听弹窗打开时加载数据
watch(showServerDialog, async (val) => {
  if (val) {
    await Promise.all([loadAddedServers(), loadAvailableServers()])
  }
})

// 监听账号选择变化，重新加载服务器列表（账号筛选变为前端过滤）
watch(selectedAccountId, async () => {
  // 筛选由前端 computed 处理，无需重新加载
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
    // 同时检查物理状态
    await checkPhysicalStatus()
  }, 15000)
}

async function checkPhysicalStatus() {
  try {
    const resp = await emuApi.get('/emulators/physical-status')
    physicalStatus.value = resp.data
  } catch {
    physicalStatus.value = { available: false, message: '无法检测物理模拟器状态' }
  }
}

async function syncPhysical() {
  emuLoading.value = true
  try {
    const resp = await emuApi.post('/emulators/sync')
    ElMessage.success(resp.data.message || '同步完成')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
  }
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
    const resp = await emuApi.get('/accounts/added')
    addedAccounts.value = resp.data || []
  } catch { addedAccounts.value = [] }
}

async function loadAvailableAccounts() {
  availableAccountsLoading.value = true
  try {
    const resp = await emuApi.get('/accounts/available', {
      params: { keyword: accountSearch.value || undefined }
    })
    availableAccounts.value = resp.data || []
  } catch { availableAccounts.value = [] }
  finally { availableAccountsLoading.value = false }
}

async function addAccount(discordAccountId) {
  try {
    await emuApi.post('/accounts/add', { discordAccountId })
    ElMessage.success('账号已添加')
    await Promise.all([loadAddedAccounts(), loadAvailableAccounts()])
  } catch (e) {
    ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message))
  }
}

async function removeAccount(bindingId) {
  try {
    await emuApi.delete(`/accounts/${bindingId}`)
    ElMessage.success('账号已移除')
    const removedAccount = addedAccounts.value.find(a => a.id === bindingId)
    if (removedAccount && selectedAccountId.value === removedAccount.discordAccountId) {
      selectedAccountId.value = null
    }
    await Promise.all([loadAddedAccounts(), loadAvailableAccounts()])
  } catch (e) {
    ElMessage.error('移除失败: ' + (e.response?.data?.message || e.message))
  }
}

// ========== 服务器管理 ==========

async function loadAddedServers() {
  try {
    const resp = await emuApi.get('/servers/added')
    addedServers.value = resp.data || []
  } catch { addedServers.value = [] }
}

async function loadAvailableServers() {
  availableServersLoading.value = true
  try {
    const resp = await emuApi.get('/servers/available')
    availableServers.value = resp.data || []
  } catch { availableServers.value = [] }
  finally { availableServersLoading.value = false }
}

async function addServer(server) {
  try {
    await emuApi.post('/servers/add', {
      serverId: server.id,
      discordAccountId: server.discordAccountId || null
    })
    ElMessage.success('服务器已添加，正在同步成员...')
    await loadAddedServers()
    await loadAvailableServers()
    // 自动同步好友数据
    if (server.serverId) {
      await syncFriends({ serverId: server.serverId, name: server.name })
    }
  } catch (e) {
    ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message))
  }
}

async function removeServer(bindingId) {
  try {
    await emuApi.delete(`/servers/${bindingId}`)
    ElMessage.success('服务器已移除')
    await Promise.all([loadAddedServers(), loadAvailableServers()])
  } catch (e) {
    ElMessage.error('移除失败: ' + (e.response?.data?.message || e.message))
  }
}

async function syncFriends(server) {
  try {
    const resp = await emuApi.post(`/servers/${server.serverId}/sync-friends`)
    const data = resp.data
    
    if (data.fetchStarted) {
      if (data.fetching) {
        // 正在抓取中
        ElMessageBox.alert(
          `<div style="line-height: 1.6;">
            <p><strong>${data.message}</strong></p>
            <p style="margin-top: 10px;">当前进度:</p>
            <ul style="margin: 5px 0; padding-left: 20px;">
              <li>已获取成员: ${data.progress?.membersUnique || 0}</li>
              <li>状态: ${data.progress?.status || '未知'}</li>
            </ul>
            <p style="margin-top: 10px; color: #909399;">请等待抓取完成后（通常需要几分钟），再点击同步</p>
          </div>`,
          '成员抓取进行中',
          { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了' }
        )
      } else {
        // 启动了新的抓取任务
        ElMessageBox.alert(
          `<div style="line-height: 1.6;">
            <p><strong>${data.message}</strong></p>
            ${data.diagnostic ? `
            <div style="margin-top: 10px; background: #f5f7fa; padding: 10px; border-radius: 4px; font-size: 12px;">
              <p>任务ID: ${data.diagnostic.taskId}</p>
              <p>预计时间: ${data.diagnostic.estimatedTime}</p>
            </div>` : ''}
          </div>`,
          '成员抓取已启动',
          { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了' }
        )
      }
    } else if (data.success) {
      // 同步成功
      ElMessageBox.alert(
        `<div style="line-height: 1.6;">
          <p><strong>${data.message}</strong></p>
          ${data.totalMembers ? `<p style="margin-top: 10px;">服务器总成员数: ${data.totalMembers}</p>` : ''}
        </div>`,
        '同步成功',
        { dangerouslyUseHTMLString: true, confirmButtonText: '确定' }
      )
      await loadFriendPoolStats()
      await loadFriendPool()
    } else {
      // 同步失败
      ElMessageBox.alert(
        `<div style="line-height: 1.6;">
          <p style="color: #f56c6c;"><strong>同步失败</strong></p>
          <p style="margin-top: 10px;">${data.message}</p>
          ${data.diagnostic ? `
          <div style="margin-top: 10px; background: #fef0f0; padding: 10px; border-radius: 4px; font-size: 12px; color: #606266;">
            <p>诊断信息:</p>
            <pre style="margin: 5px 0; white-space: pre-wrap;">${JSON.stringify(data.diagnostic, null, 2)}</pre>
          </div>` : ''}
          <p style="margin-top: 10px; color: #909399;">请检查: 1) Discord Token 是否有效 2) 服务器链接是否正确 3) 网络是否正常</p>
        </div>`,
        '同步失败',
        { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了', type: 'error' }
      )
    }
  } catch (e) {
    ElMessageBox.alert(
      `<div style="line-height: 1.6;">
        <p style="color: #f56c6c;"><strong>请求失败</strong></p>
        <p style="margin-top: 10px;">${e.response?.data?.message || e.message}</p>
        <p style="margin-top: 10px; color: #909399;">请检查后端服务是否正常运行</p>
      </div>`,
      '错误',
      { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了', type: 'error' }
    )
  }
}

// ========== 好友号池 ==========

async function loadFriendPool() {
  friendPoolLoading.value = true
  try {
    const resp = await emuApi.get('/friend-pool', {
      params: { status: friendPoolFilter.value || undefined }
    })
    friendPool.value = resp.data || []
  } catch { friendPool.value = [] }
  finally { friendPoolLoading.value = false }
}

async function loadFriendPoolStats() {
  try {
    const resp = await emuApi.get('/friend-pool/stats')
    friendPoolStats.value = resp.data || { total: 0, pending: 0, assigned: 0, success: 0, failed: 0 }
  } catch {}
}

function friendStatusTag(status) {
  const map = {
    'PENDING': 'info',
    'ASSIGNED': 'warning',
    'SUCCESS': 'success',
    'FAILED': 'danger'
  }
  return map[status] || 'info'
}

// ========== 模拟器操作 ==========

async function loadAutoConfig() {
  try {
    const resp = await emuApi.get('/data/autoconfig')
    if (resp.data) {
      // 将秒转换为分钟（如果后端返回的是秒）
      if (resp.data.intervalSeconds !== undefined) {
        autoConfig.value = {
          intervalMinutes: Math.round(resp.data.intervalSeconds / 60) || 1,
          delayMinMinutes: Math.round(resp.data.delayMinSeconds / 60) || 0,
          delayMaxMinutes: Math.round(resp.data.delayMaxSeconds / 60) || 10
        }
      } else {
        autoConfig.value = resp.data
      }
    }
  } catch {}
}

async function saveAutoConfig() {
  try {
    // 将分钟转换为秒发送到后端
    const configToSave = {
      intervalSeconds: autoConfig.value.intervalMinutes * 60,
      delayMinSeconds: autoConfig.value.delayMinMinutes * 60,
      delayMaxSeconds: autoConfig.value.delayMaxMinutes * 60
    }
    await emuApi.post('/data/autoconfig', configToSave)
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

function isOperating(index) {
  return operatingEmulators.value.has(index)
}

async function startEmulator(index) {
  operatingEmulators.value.add(index)
  try {
    const resp = await emuApi.post(`/emulators/${index}/start`)
    ElMessage.success(`模拟器 #${index} 启动指令已发送`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
    // 延迟3秒后刷新状态
    setTimeout(() => fetchEmulators(), 3000)
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  } finally {
    operatingEmulators.value.delete(index)
  }
}

async function stopEmulator(index) {
  operatingEmulators.value.add(index)
  try {
    const resp = await emuApi.post(`/emulators/${index}/stop`)
    ElMessage.success(`模拟器 #${index} 停止指令已发送`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
    setTimeout(() => fetchEmulators(), 3000)
  } catch (e) {
    ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  } finally {
    operatingEmulators.value.delete(index)
  }
}

async function restartEmulator(index) {
  operatingEmulators.value.add(index)
  try {
    const resp = await emuApi.post(`/emulators/${index}/restart`)
    ElMessage.success(`模拟器 #${index} 重启指令已发送`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
    setTimeout(() => fetchEmulators(), 3000)
  } catch (e) {
    ElMessage.error('重启失败: ' + (e.response?.data?.message || e.message))
  } finally {
    operatingEmulators.value.delete(index)
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
  padding: 12px;
  height: 100%;
  overflow-y: auto;
  background: var(--color-bg, #f5f7fa);
}

.content { max-width: 1600px; }

.panel { margin-bottom: 8px; }
.panel-header { display: flex; align-items: center; gap: 6px; font-weight: 600; font-size: 14px; }
.panel-body { display: flex; flex-direction: column; gap: 6px; }

.form-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.form-row.inline-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}

.inline-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.form-row.inline label,
.form-row label { min-width: auto; font-size: 12px; color: #606266; }

.action-row { display: flex; gap: 6px; flex-wrap: wrap; }

.hint { font-size: 12px; color: #909399; }
.hint-sm { font-size: 11px; color: #c0c4cc; margin-left: 6px; }
.unit { font-size: 12px; color: #909399; }

.batch-toolbar {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
}

.batch-info { display: flex; align-items: center; gap: 8px; }
.batch-count { font-size: 13px; color: #606266; }

.batch-actions { display: flex; gap: 6px; flex-wrap: wrap; }

.emu-name { font-weight: 600; font-size: 13px; }

:deep(.selected-row) {
  background-color: #ecf5ff !important;
}

:deep(.el-table .cell) {
  padding: 4px 8px;
  font-size: 13px;
}

:deep(.el-card__header) {
  padding: 8px 12px;
  font-size: 14px;
}

:deep(.el-card__body) {
  padding: 8px 12px;
}

.loading-wrap,
.error-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 24px;
  color: var(--color-text-2, #606266);
}

.error-wrap h3 { color: #f56c6c; }
.error-wrap pre {
  background: #2c3e50;
  color: #f1f5f9;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
}

/* 账号列表样式 */
.account-list,
.server-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.account-item,
.server-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: #f5f7fa;
  border-radius: 4px;
}

.account-info,
.server-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.account-name,
.server-name {
  font-weight: 500;
  font-size: 13px;
}

.account-number {
  color: #909399;
  font-size: 12px;
}

.server-account {
  color: #409eff;
  font-size: 12px;
}

.server-count {
  color: #67c23a;
  font-size: 12px;
}

.server-actions {
  display: flex;
  gap: 6px;
}

.empty-hint {
  text-align: center;
  color: #909399;
  padding: 16px;
  font-size: 13px;
}

/* 弹窗样式 */
.dialog-section {
  margin-bottom: 12px;
}

.dialog-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #303133;
}

.dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.filter-row {
  display: flex;
  gap: 8px;
}

.loading-hint {
  text-align: center;
  color: #909399;
  padding: 16px;
}

/* 好友号池样式 */
.friend-pool-stats {
  margin-bottom: 8px;
}

.stat-card {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px;
  text-align: center;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
}

.stat-ratio {
  font-size: 12px;
  color: #606266;
  margin: 2px 0;
}

.stat-label {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.stat-pending {
  background: #fdf6ec;
}
.stat-pending .stat-value {
  color: #e6a23c;
}
.stat-pending .stat-ratio {
  color: #e6a23c;
}

.stat-assigned {
  background: #faecd8;
}
.stat-assigned .stat-value {
  color: #e6a23c;
}
.stat-assigned .stat-ratio {
  color: #e6a23c;
}

.stat-success {
  background: #f0f9eb;
}
.stat-success .stat-value {
  color: #67c23a;
}
.stat-success .stat-ratio {
  color: #67c23a;
}

.stat-failed {
  background: #fef0f0;
}
.stat-failed .stat-value {
  color: #f56c6c;
}
.stat-failed .stat-ratio {
  color: #f56c6c;
}

.friend-pool-filter {
  margin-bottom: 8px;
}

.more-hint {
  text-align: center;
  color: #909399;
  font-size: 11px;
  margin-top: 4px;
}
</style>
