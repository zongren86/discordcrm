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
        <el-button type="warning" @click="openAgentDialog">
          <el-icon><Monitor /></el-icon>
          <span>代理模式添加</span>
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

      <div class="table-wrap">
        <el-table v-loading="loading" :data="pagedAccounts" stripe style="width: 100%;" height="100%">
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
              <span class="agent-name">{{ row.agentName }}</span>
            </div>
            <span v-else class="no-agent">-</span>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.source === 'AGENT'" type="success" size="small">代理</el-tag>
            <el-tag v-else-if="row.source === 'BATCH'" type="warning" size="small">批量</el-tag>
            <el-tag v-else-if="row.source === 'MANUAL'" type="info" size="small">手工</el-tag>
            <el-tag v-else type="info" size="small">—</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="账号编号" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.accountCustomNo != null" type="success" size="small" effect="light">
              #{{ row.accountCustomNo }}
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

        <el-table-column prop="remark" label="备注" min-width="160">
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="420" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button v-if="row.accountType === 'USER'" size="small" type="primary" link @click="openRefreshToken(row)">
                <el-icon><Key /></el-icon> 更新Token
              </el-button>
              <el-button v-if="row.accountType === 'USER'" size="small" type="primary" link @click="refreshAvatar(row)">
                <el-icon><Picture /></el-icon> 更新头像
              </el-button>
              <el-button v-if="row.accountType === 'USER'" size="small" type="primary" link @click="syncAccount(row)">
                <el-icon><Refresh /></el-icon> 同步好友
              </el-button>
              <el-button v-if="row.browserProfilePath && (row.discordAgentServerId || row.agentServerId)" size="small" type="success" link @click="launchBrowser(row)">
                <el-icon><Monitor /></el-icon> 唤起
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

      <el-empty v-if="!loading && filteredAccounts.length === 0" description="暂无账号" style="padding:60px 0;" />
    </div>

    <!-- 添加/编辑账号（手工添加） -->
    <el-dialog v-model="botDialog.visible" :title="botDialog.editId ? '编辑账号' : '手工添加账号'" width="560px" @close="resetBotDialog">
      <template v-if="!botDialog.editId">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px;">
          <template #title>
            粘贴格式：<b>用户名|邮箱|ID|Token</b>，使用竖线分隔。示例：<code style="user-select:all;">张三|zhangsan@example.com|123456789012345678|mfa.xxxxxx</code>
          </template>
        </el-alert>
        <el-input
          v-model="botDialog.pasteText"
          type="textarea"
          :rows="3"
          placeholder="点击此处后粘贴（Ctrl/⌘+V），将自动解析到下方字段"
          @paste="onPasteAccountText"
        />
        <div style="margin: 8px 0 0; font-size:12px; color: var(--color-text-3);">
          解析后会自动填写下方字段。相同 ID 再次保存会<strong>更新已有账号</strong>，不存在则新增。
        </div>
        <el-divider style="margin: 16px 0 8px;">解析结果（可手动调整）</el-divider>
      </template>
      <el-form :model="botDialog.form" label-width="90px">
        <el-form-item v-if="!botDialog.editId" label="Discord ID" required>
          <el-input v-model="botDialog.form.discordId" placeholder="从粘贴文本自动解析，或手动输入" />
        </el-form-item>
        <el-form-item label="账号名称" required>
          <el-input v-model="botDialog.form.nickname" placeholder="请输入账号名称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="botDialog.form.email" placeholder="邮箱（选填）" />
        </el-form-item>
        <el-form-item label="Token" required>
          <el-input v-model="botDialog.form.token" type="password" show-password placeholder="请输入 Token" @input="onTokenInput" />
        </el-form-item>
        <el-form-item label="所属商户">
          <el-select v-model="botDialog.form.merchantId" placeholder="请选择商户" filterable clearable style="width:100%;" :loading="merchantsLoading" :disabled="merchantDisabled">
            <el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
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

    <!-- 代理模式添加账号：选择代理节点 -->
    <el-dialog v-model="agentDialog.visible" title="代理模式添加账号" width="520px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:16px;">
        <template #title>
          通过已部署的 crm_agent 代理服务器，自动打开浏览器让您在 Discord 上登录，登录成功后自动采集账号信息保存到系统。
        </template>
      </el-alert>
      <el-form :model="agentDialog.form" label-width="100px">
        <el-form-item label="选择代理" required>
          <el-select v-model="agentDialog.form.agentServerId" placeholder="请选择代理节点" filterable style="width:100%;" :loading="agentDialog.loadingServers">
            <el-option v-for="s in agentDialog.servers" :key="s.id" :label="`${s.name} (${s.status === 'ONLINE' ? '在线' : '离线'})`" :value="s.id" :disabled="s.status !== 'ONLINE'" />
          </el-select>
          <div v-if="agentDialog.servers.length === 0 && !agentDialog.loadingServers" style="margin-top:8px;font-size:12px;color:var(--color-text-3);">
            还没有代理节点？请到左侧菜单「代理管理」先新增一个。
          </div>
          <div v-if="agentDialog.onlineCount === 0 && agentDialog.servers.length > 0" style="margin-top:8px;font-size:12px;color:var(--color-warning);">
            当前没有在线的代理节点，请先启动 crm_agent 程序。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="agentDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="agentDialog.starting" :disabled="!agentDialog.form.agentServerId" @click="startAgentCapture">
          <el-icon><Monitor /></el-icon> 启动浏览器监控
        </el-button>
      </template>
    </el-dialog>

    <!-- 代理采集进度 -->
    <el-dialog v-model="agentResultDialog.visible" title="浏览器监控中" width="560px" :close-on-click-modal="false">
      <div class="agent-progress">
        <el-steps :active="agentResultDialog.step" align-center>
          <el-step title="任务创建" />
          <el-step title="浏览器已启动" />
          <el-step title="等待登录" />
          <el-step title="采集完成" />
        </el-steps>
        <div style="margin-top:20px;">
          <el-alert v-if="agentResultDialog.status === 'RUNNING'" type="info" show-icon :closable="false">
            <template #title>
              请在 <b>{{ agentResultDialog.agentName }}</b> 代理弹出的浏览器中登录 Discord 账号。
              <br />登录成功后系统会自动检测并保存，无需手动操作。
              <br /><span style="font-size:12px;opacity:.75;">超时时间 5 分钟</span>
            </template>
          </el-alert>
          <el-alert v-else-if="agentResultDialog.status === 'SUCCESS'" type="success" show-icon :closable="false">
            <template #title>
              <b>✅ 采集成功！</b>
              <br />用户：<b>{{ agentResultDialog.result?.username || '-' }}</b>
              <br />Discord ID：<code>{{ agentResultDialog.result?.discordId || '-' }}</code>
              <br />Email：{{ agentResultDialog.result?.email || '-' }}
            </template>
          </el-alert>
          <el-alert v-else-if="agentResultDialog.status === 'FAILED'" type="error" show-icon :closable="false">
            <template #title>
              <b>❌ 采集失败</b>
              <br />原因：{{ agentResultDialog.error || '超时或用户信息不完整' }}
            </template>
          </el-alert>
          <el-alert v-else-if="agentResultDialog.status === 'CANCELLED'" type="warning" show-icon :closable="false">
            <template #title>
              <b>🚫 任务已取消</b>
              <br />您取消了登录或关闭了浏览器。
            </template>
          </el-alert>
        </div>
        <div style="margin-top:16px;text-align:center;">
          <el-progress v-if="agentResultDialog.status === 'RUNNING'" :percentage="agentResultDialog.progress" :indeterminate="true" />
        </div>
      </div>
      <template #footer>
        <template v-if="agentResultDialog.status === 'RUNNING'">
          <el-button @click="cancelAgentCapture" type="danger" :loading="agentResultDialog.cancelling">取消登录</el-button>
          <el-button @click="closeAgentResultDialog">关闭弹窗</el-button>
        </template>
        <template v-else-if="agentResultDialog.status === 'CANCELLED'">
          <el-button @click="closeAgentResultDialog">关闭</el-button>
          <el-button type="primary" @click="continueAgentAdd">继续添加</el-button>
        </template>
        <template v-else-if="agentResultDialog.status === 'FAILED'">
          <el-button @click="closeAgentResultDialog">关闭</el-button>
          <el-button type="primary" @click="continueAgentAdd">继续添加</el-button>
        </template>
        <template v-else>
          <el-button @click="closeAgentResultDialog">关闭</el-button>
          <el-button v-if="agentResultDialog.status === 'SUCCESS'" type="primary" @click="closeAgentResultDialog">完成</el-button>
        </template>
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
import { Plus, Upload, Refresh, Edit, Delete, Search, OfficeBuilding, Download, Key, Picture, Monitor } from '@element-plus/icons-vue'
import { listAccounts, createAccount, upsertAccountByDiscordId, updateAccount, deleteAccount, batchImport, syncAccountFriends, listMerchants, refreshAccountToken, refreshAccountAvatar, listAgentServers, createAgentTask, getAgentTask, cancelAgentTask } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useAccountsStore } from '@/stores/accounts'

const auth = useAuthStore()
const accountsStore = useAccountsStore()

/** 平台管理员才可以选择/切换商户；商户身份用户默认填自己的 merchantId 并禁用 */
const isPlatformAdmin = computed(() => auth.agent?.role === 'PLATFORM_ADMIN')
const merchantDisabled = computed(() => !isPlatformAdmin.value)
const defaultMerchantId = computed(() => auth.agent?.merchantId ?? null)

const allAccounts = ref([])
const loading = ref(false)
const filters = reactive({ keyword: '', status: null })
const merchants = ref([])
const merchantsLoading = ref(false)

const pagination = reactive({
  page: 1,
  size: 100,
  get total() { return filteredAccounts.value.length }
})

const pagedAccounts = computed(() => {
  const start = (pagination.page - 1) * pagination.size
  return filteredAccounts.value.slice(start, start + pagination.size)
})

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
    // 同步到 Pinia 全局 store，供其他页面（如消息中心）使用
    accountsStore.accounts = [...allAccounts.value]
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
    await syncAccountFriends(acc.id)
    ElMessage.success('同步好友成功')
    await fetchAccounts()
  } catch (e) {}
}

async function refreshAvatar(acc) {
  try {
    await refreshAccountAvatar(acc.id)
    ElMessage.success('头像更新成功')
    await fetchAccounts()
  } catch (e) {}
}

function openAddAccount() {
  botDialog.visible = true
  botDialog.editId = null
  botDialog.pasteText = ''
  botDialog.form = {
    nickname: '',
    token: '',
    accountType: 'USER',
    email: '',
    remark: '',
    merchantId: merchantDisabled.value ? defaultMerchantId.value : null,
    discordId: ''
  }
}

function openEdit(acc) {
  botDialog.visible = true
  botDialog.editId = acc.id
  botDialog.pasteText = ''
  botDialog.form = {
    nickname: acc.name || acc.discordName || acc.nickname || '',
    token: '',
    accountType: 'USER',
    email: acc.email || '',
    remark: acc.remark || '',
    // 商户身份：仅可编辑自己商户下的账号，保持原 merchantId（禁用不可改）
    merchantId: merchantDisabled.value ? (acc.merchantId || defaultMerchantId.value) : (acc.merchantId || null),
    discordId: acc.discordId || ''
  }
}

// ===== 代理模式添加账号 =====
const agentDialog = reactive({
  visible: false,
  loadingServers: false,
  starting: false,
  servers: [],
  form: { agentServerId: null },
  onlineCount: computed(() => agentDialog.servers.filter(s => s.status === 'ONLINE').length)
})

const agentResultDialog = reactive({
  visible: false,
  agentName: '',
  taskId: null,
  status: '',
  result: null,
  error: '',
  step: 0,
  progress: 0,
  cancelling: false,
  _pollTimer: null,
})

async function launchBrowser(account) {
  const agentId = account.discordAgentServerId || account.agentServerId
  if (!agentId || !account.browserProfilePath) {
    ElMessage.warning('该账号未关联浏览器 profile')
    return
  }
  try {
    ElMessage.info('正在唤起浏览器...请在代理弹出的窗口查看')
    await createAgentTask(agentId, 'LAUNCH_BROWSER', {
      browserProfilePath: account.browserProfilePath,
      accountId: account.id,
    })
    // agent 会在下次 poll 时拿到这个任务并打开浏览器
  } catch (e) {
    ElMessage.error('唤起失败: ' + (e?.response?.data?.error || e?.message || '未知错误'))
  }
}

async function openAgentDialog() {
  agentDialog.visible = true
  agentDialog.loadingServers = true
  agentDialog.form.agentServerId = null
  try {
    agentDialog.servers = await listAgentServers() || []
  } catch (e) {
    ElMessage.error('加载代理节点失败')
    agentDialog.servers = []
  } finally {
    agentDialog.loadingServers = false
  }
}

async function startAgentCapture() {
  if (!agentDialog.form.agentServerId) return
  agentDialog.starting = true
  console.log('[AgentCapture] 开始创建任务，agentServerId=', agentDialog.form.agentServerId)
  try {
    const resp = await createAgentTask(agentDialog.form.agentServerId, 'CAPTURE_DISCORD_ACCOUNT')
    console.log('[AgentCapture] 任务创建成功 resp=', resp)
    const taskId = resp.id
    const agent = agentDialog.servers.find(s => s.id === agentDialog.form.agentServerId)

    agentDialog.visible = false
    agentResultDialog.visible = true
    agentResultDialog.taskId = taskId
    agentResultDialog.agentName = agent?.name || ''
    agentResultDialog.status = 'RUNNING'
    agentResultDialog.step = 1
    agentResultDialog.result = null
    agentResultDialog.error = ''
    agentResultDialog.progress = 0

    await new Promise(r => setTimeout(r, 1500))
    await pollAgentTask()
    agentResultDialog._pollTimer = setInterval(pollAgentTask, 2000)
  } catch (e) {
    console.error('[AgentCapture] 创建失败，完整错误=', e)
    console.error('[AgentCapture] response=', e?.response)
    console.error('[AgentCapture] response.data=', e?.response?.data)
    console.error('[AgentCapture] response.status=', e?.response?.status)
    ElMessage.error(e?.response?.data?.error || e?.response?.data?.message || e?.message || '创建任务失败')
  } finally {
    agentDialog.starting = false
  }
}

async function pollAgentTask() {
  if (!agentResultDialog.taskId) return
  try {
    const resp = await getAgentTask(agentResultDialog.taskId)
    const status = resp.status
    if (status === 'RUNNING') {
      agentResultDialog.step = 2
      agentResultDialog.progress = Math.min(90, agentResultDialog.progress + 5)
    } else if (status === 'SUCCESS') {
      agentResultDialog.step = 4
      agentResultDialog.progress = 100
      agentResultDialog.status = 'SUCCESS'
      try {
        const parsed = typeof resp.result === 'string' ? JSON.parse(resp.result) : resp.result
        agentResultDialog.result = parsed
      } catch {
        agentResultDialog.result = { username: '(已保存)', discordId: '-' }
      }
      stopAgentPolling()
      await fetchAccounts()
      const u = agentResultDialog.result?.username || '新账号'
      ElMessage.success('✅ 代理采集成功：' + u + '，已添加到账号列表')
      // 3 秒后自动关闭弹窗（提示账号已保存）
      setTimeout(() => { agentResultDialog.visible = false }, 3000)
    } else if (status === 'FAILED') {
      agentResultDialog.status = 'FAILED'
      agentResultDialog.step = 4
      try {
        const parsed = typeof resp.result === 'string' ? JSON.parse(resp.result) : resp.result
        agentResultDialog.error = parsed?.error || resp.result || '未知错误'
      } catch {
        agentResultDialog.error = resp.result || '未知错误'
      }
      stopAgentPolling()
    } else if (status === 'CANCELLED') {
      agentResultDialog.status = 'CANCELLED'
      agentResultDialog.step = 3
      agentResultDialog.error = '任务已取消'
      stopAgentPolling()
    }
  } catch (e) {}
}

function stopAgentPolling() {
  if (agentResultDialog._pollTimer) {
    clearInterval(agentResultDialog._pollTimer)
    agentResultDialog._pollTimer = null
  }
}

async function cancelAgentCapture() {
  if (!agentResultDialog.taskId || agentResultDialog.status !== 'RUNNING') return
  agentResultDialog.cancelling = true
  try {
    await cancelAgentTask(agentResultDialog.taskId)
    ElMessage.info('已通知 agent 停止，请稍等...')
    // 等下一轮 poll 到 CANCELLED 状态
  } catch (e) {
    ElMessage.warning('取消请求失败，但 agent 会在下次轮询时发现任务已取消')
  } finally {
    agentResultDialog.cancelling = false
  }
}

function closeAgentResultDialog() {
  stopAgentPolling()
  agentResultDialog.visible = false
}

// CANCELLED 后点"继续添加" → 关闭监控弹窗 → 打开代理添加弹窗
async function continueAgentAdd() {
  closeAgentResultDialog()
  agentDialog.visible = true
  agentDialog.loadingServers = true
  agentDialog.form.agentServerId = null
  try {
    agentDialog.servers = await listAgentServers() || []
  } catch (e) {
    agentDialog.servers = []
  } finally {
    agentDialog.loadingServers = false
  }
}

const botDialog = reactive({
  visible: false,
  editId: null,
  saving: false,
  pasteText: '',
  form: {
    nickname: '',
    token: '',
    accountType: 'USER',
    email: '',
    remark: '',
    merchantId: merchantDisabled.value ? defaultMerchantId.value : null,
    discordId: ''
  }
})

function resetBotDialog() {
  botDialog.editId = null
  botDialog.pasteText = ''
  botDialog.form = {
    nickname: '',
    token: '',
    accountType: 'USER',
    email: '',
    remark: '',
    merchantId: merchantDisabled.value ? defaultMerchantId.value : null,
    discordId: ''
  }
  botDialog.saving = false
}

/** 粘贴事件：从剪贴板获取文本并解析 */
function onPasteAccountText(e) {
  let text = ''
  try {
    text = (e.clipboardData || window.clipboardData).getData('text') || ''
  } catch (err) { text = '' }
  if (!text) return
  // 直接用剪贴板文本解析，不依赖 pasteText 的 v-model 更新
  const raw = text.trim()
  const firstLine = raw.split(/\r?\n/).map(l => l.trim()).find(l => l) || raw
  const parts = firstLine.split(/[|｜\t]/).map(s => s.trim())
  const [username, email, discordId, ...rest] = parts
  const token = rest.length > 0 ? rest.join('|') : ''
  if (username) botDialog.form.nickname = username
  if (email) botDialog.form.email = email
  if (discordId) botDialog.form.discordId = discordId
  if (token) botDialog.form.token = token
  if (username && !botDialog.form.accountType) botDialog.form.accountType = 'USER'
  // 清空 pasteText 输入框，防止再次触发解析
  botDialog.pasteText = ''
  // 校验
  if (!discordId) {
    ElMessage.warning('未解析到 Discord ID，请检查格式：用户名|邮箱|ID|Token')
  } else if (!token) {
    ElMessage.warning('未解析到 Token，请检查格式')
  } else {
    ElMessage.success('解析成功，请确认字段后保存')
  }
}

/** 手动输入解析（用户可能通过拖拽等方式填入文本） */
function parsePasteText() {
  const raw = (botDialog.pasteText || '').trim()
  if (!raw) return
  const firstLine = raw.split(/\r?\n/).map(l => l.trim()).find(l => l) || raw
  const parts = firstLine.split(/[|｜\t]/).map(s => s.trim())
  const [username, email, discordId, ...rest] = parts
  const token = rest.length > 0 ? rest.join('|') : ''
  if (username) botDialog.form.nickname = username
  if (email) botDialog.form.email = email
  if (discordId) botDialog.form.discordId = discordId
  if (token) botDialog.form.token = token
  if (username && !botDialog.form.accountType) botDialog.form.accountType = 'USER'
  // 解析成功后清空粘贴文本
  botDialog.pasteText = ''
  if (!discordId) {
    ElMessage.warning('未解析到 Discord ID')
  } else if (!token) {
    ElMessage.warning('未解析到 Token')
  } else {
    ElMessage.success('解析成功')
  }
}

async function saveBot() {
  if (!botDialog.editId) {
    if (!botDialog.form.discordId) { ElMessage.warning('请粘贴文本或手动填写 Discord ID'); return }
    if (!botDialog.form.nickname) { ElMessage.warning('账号名称不能为空'); return }
    if (!botDialog.form.token) { ElMessage.warning('Token 不能为空'); return }
  } else {
    if (!botDialog.form.nickname) { ElMessage.warning('请输入账号名称'); return }
    if (!botDialog.form.token) { ElMessage.warning('请输入 Token'); return }
  }
  botDialog.saving = true
  try {
    if (botDialog.editId) {
      await updateAccount(botDialog.editId, {
        nickname: botDialog.form.nickname,
        token: botDialog.form.token,
        remark: botDialog.form.remark,
        merchantId: botDialog.form.merchantId
      })
      ElMessage.success('更新成功')
    } else {
      const res = await upsertAccountByDiscordId({
        username: botDialog.form.nickname,
        email: botDialog.form.email,
        discordId: botDialog.form.discordId,
        token: botDialog.form.token,
        accountType: botDialog.form.accountType,
        remark: botDialog.form.remark,
        merchantId: botDialog.form.merchantId
      })
      if (res && res.created) {
        ElMessage.success(res.message || '新增成功')
      } else {
        ElMessage.success(res?.message || '更新成功')
      }
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
    await ElMessageBox.confirm(`确定要删除账号「${dispName}」吗？此操作将删除关联数据，不可恢复。`, '提示', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消'
    })
    await deleteAccount(acc.id)
    ElMessage.success('删除成功')
    // 强制刷新列表，确保获取最新数据
    await fetchAccounts()
  } catch (e) {
    // 如果是用户取消操作，不显示错误
    if (e !== 'cancel' && e !== 'close') {
      console.error('删除账号失败:', e)
      // 错误已在 http.js 拦截器中处理
    }
  }
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
  flex-shrink: 0;
}
.page-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-text-2); }
.header-actions { display: flex; gap: 10px; }
.page-body {
  flex: 1; min-height: 0; overflow: hidden; padding: 20px 24px;
  display: flex; flex-direction: column;
}
.filter-bar {
  display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px;
  flex-shrink: 0;
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
.filter-controls { display: flex; gap: 10px; align-items: center; flex-shrink: 0; }
.accounts-table { border-radius: 8px; overflow: hidden; }
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  width: 100%;
}
.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}
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
</style>