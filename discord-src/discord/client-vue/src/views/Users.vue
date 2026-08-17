<template>
  <div class="users-page">
    <div class="page-header">
      <div class="page-title-wrap">
        <h2 class="page-title">用户管理</h2>
        <p class="page-desc">管理平台用户账号与权限</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span>新增用户</span>
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table v-loading="loading" :data="list" stripe style="width:100%">
        <el-table-column label="#" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="displayName" label="姓名" min-width="120">
          <template #default="{ row }">
            <span class="cell-strong">{{ row.displayName || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="登录账号" min-width="140">
          <template #default="{ row }">
            <span>{{ row.username || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180">
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </el-table-column>
        <el-table-column prop="notes" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.notes || '-' }}</template>
        </el-table-column>
        <el-table-column label="身份" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.role === 'PLATFORM_ADMIN'" type="warning" size="small" effect="light">平台</el-tag>
            <el-tag v-else type="primary" size="small" effect="light">商户</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="merchantName" label="所属商户" min-width="140">
          <template #default="{ row }">{{ row.merchantName || '-' }}</template>
        </el-table-column>
        <el-table-column label="分配角色" min-width="320">
          <template #default="{ row }">
            <div v-if="row.roleIds && row.roleIds.length > 0" class="role-tags">
              <el-tag v-for="id in row.roleIds" :key="id" type="success" size="small" effect="plain" class="role-tag-item">
                {{ getRoleName(id) }}
              </el-tag>
            </div>
            <span v-else class="cell-hint">未分配</span>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="启用状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="light">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" type="primary" link @click="openEdit(row)">
                <el-icon><Edit /></el-icon> 编辑
              </el-button>
              <el-button size="small" type="primary" link @click="openAssignRoles(row)">
                <el-icon><User /></el-icon> 分配角色
              </el-button>
              <el-button size="small" type="primary" link @click="openAccountNumbers(row)">
                <el-icon><Link /></el-icon> 关联账号
              </el-button>
              <el-button size="small" type="primary" link @click="openResetPwd(row)">
                <el-icon><Key /></el-icon> 重置密码
              </el-button>
              <el-button size="small" type="primary" link :disabled="row.username === 'admin'" @click="remove(row)">
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增用户 -->
    <el-dialog v-model="createDialog.visible" title="新增用户" width="580px" @close="resetCreateDialog">
      <el-form :model="createDialog.form" label-width="100px">
        <el-form-item label="登录账号" required>
          <el-input v-model="createDialog.form.username" placeholder="请输入登录账号" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="createDialog.form.displayName" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input v-model="createDialog.form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="createDialog.form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createDialog.form.notes" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
        <el-form-item v-if="!isMerchantUser" label="身份" required>
          <el-radio-group v-model="createDialog.form.identity" @change="onCreateIdentityChange">
            <el-radio value="PLATFORM">平台</el-radio>
            <el-radio value="MERCHANT">商户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!isMerchantUser && createDialog.form.identity === 'MERCHANT'" label="商户" required>
          <el-select v-model="createDialog.form.merchantId" placeholder="请选择商户" style="width:100%" :loading="merchantsLoading">
            <el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isMerchantUser" label="身份">
          <el-tag type="primary" size="small">商户</el-tag>
        </el-form-item>
        <el-form-item v-if="isMerchantUser" label="商户">
          <el-tag type="primary" size="small">{{ auth.agent?.merchantName || '-' }}</el-tag>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="saveCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户 -->
    <el-dialog v-model="editDialog.visible" title="编辑用户" width="580px" @close="resetEditDialog">
      <el-form :model="editDialog.form" label-width="100px">
        <el-form-item label="登录账号">
          <el-input :model-value="editDialog.form.username" disabled />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="editDialog.form.displayName" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editDialog.form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editDialog.form.notes" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
        <el-form-item v-if="!isMerchantUser" label="身份" required>
          <el-radio-group v-model="editDialog.form.identity" @change="onEditIdentityChange">
            <el-radio value="PLATFORM">平台</el-radio>
            <el-radio value="MERCHANT">商户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!isMerchantUser && editDialog.form.identity === 'MERCHANT'" label="商户" required>
          <el-select v-model="editDialog.form.merchantId" placeholder="请选择商户" style="width:100%" :loading="merchantsLoading">
            <el-option v-for="m in merchants" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isMerchantUser" label="身份">
          <el-tag type="primary" size="small">商户</el-tag>
        </el-form-item>
        <el-form-item v-if="isMerchantUser" label="商户">
          <el-tag type="primary" size="small">{{ auth.agent?.merchantName || '-' }}</el-tag>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="editDialog.form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="pwdDialog.visible" title="重置密码" width="440px" @close="resetPwdDialog">
      <el-form :model="pwdDialog.form" label-width="80px">
        <el-form-item label="登录账号">
          <el-input :model-value="pwdDialog.username" disabled />
        </el-form-item>
        <el-form-item label="新密码" required>
          <el-input v-model="pwdDialog.form.password" type="password" show-password placeholder="请输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="pwdDialog.saving" @click="saveResetPwd">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色 -->
    <el-dialog v-model="assignRolesDialog.visible" title="分配角色" width="600px" @close="resetAssignRolesDialog">
      <div class="assign-roles-container">
        <div class="user-info-row">
          <span class="label">用户：</span>
          <span class="value">{{ assignRolesDialog.username }}</span>
        </div>
        
        <div class="section-title">已分配角色</div>
        <div class="assigned-roles" v-if="assignRolesDialog.selectedRoleIds.length > 0">
          <el-tag 
            v-for="roleId in assignRolesDialog.selectedRoleIds" 
            :key="roleId" 
            class="role-tag"
            closable
            @close="removeRole(roleId)"
          >
            {{ getRoleName(roleId) }}
          </el-tag>
        </div>
        <el-empty v-else description="暂无分配角色" :image-size="60" />

        <div class="section-title" style="margin-top: 20px;">添加角色</div>
        <div class="role-list" v-if="availableRoles.length > 0">
          <div 
            v-for="role in availableRoles" 
            :key="role.id" 
            class="role-list-item"
          >
            <span class="role-name">{{ role.name }}</span>
            <el-button type="primary" size="small" link @click="addRole(role.id)">
              <el-icon><Plus /></el-icon> 添加
            </el-button>
          </div>
        </div>
        <el-empty v-else description="所有角色均已分配" :image-size="60" />
      </div>
      <template #footer>
        <el-button @click="assignRolesDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="assignRolesDialog.saving" @click="saveAssignRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- 账号编号管理 -->
    <el-dialog v-model="accountNumbersDialog.visible" title="账号编号管理" width="700px" @close="resetAccountNumbersDialog">
      <div class="account-numbers-section">
        <div class="section-header">
          <span class="section-title">关联的账号编号列表</span>
          <el-button type="primary" size="small" @click="openLinkNumbers">关联账号编号</el-button>
        </div>
        <el-table :data="accountNumbersList" stripe style="width: 100%;">
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button size="small" type="danger" link @click="handleUnlinkNumber(row)">删除</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="number" label="编号" width="100" align="center" />
          <el-table-column prop="boundAccount" label="账号" min-width="180">
            <template #default="{ row }">
              <span>{{ row.boundAccount || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="linkedAt" label="关联时间" width="180">
            <template #default="{ row }">
              {{ formatDateTime(row.linkedAt) }}
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="accountNumbersList.length === 0" description="暂无关联的账号编号" style="padding: 30px 0;" />
      </div>
    </el-dialog>

    <!-- 关联账号编号弹窗 -->
    <el-dialog v-model="linkNumbersDialog.visible" title="关联账号编号" width="500px" @close="resetLinkNumbersDialog">
      <div class="link-tip">
        请输入要关联的编号范围，支持以下格式：
        <div class="format-examples">
          <span>单个编号：<code>1</code></span>
          <span>多个编号：<code>1, 3, 5</code></span>
          <span>连续范围：<code>1-5</code> 或 <code>1~5</code></span>
          <span>混合使用：<code>1-5, 10, 15-20</code></span>
        </div>
      </div>
      <el-input v-model="linkNumbersDialog.range" placeholder="请输入编号范围，如：1-5, 10, 15-20" />
      <template #footer>
        <el-button @click="linkNumbersDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleLinkNumbers">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Refresh, Key, User, Link } from '@element-plus/icons-vue'
import { api, getUserAccountNumbers, batchLinkAccountNumbers, unlinkAccountNumber } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const isMerchantUser = computed(() => auth.agent?.role !== 'PLATFORM_ADMIN')

const list = ref([])
const loading = ref(false)
const merchants = ref([])
const merchantsLoading = ref(false)
const customRoles = ref([])

const platformRoles = [
  { value: 'PLATFORM_ADMIN', label: '平台管理员' }
]

const merchantRoles = [
  { value: 'MERCHANT_ADMIN', label: '商户管理员' },
  { value: 'MANAGER', label: '主管' },
  { value: 'SALES', label: '销售' },
  { value: 'SERVICE', label: '客服' }
]

function roleLabel(role) {
  const all = [...platformRoles, ...merchantRoles]
  const item = all.find(r => r.value === role)
  return item ? item.label : (role || '-')
}

function roleTagType(role) {
  const map = {
    PLATFORM_ADMIN: 'danger',
    MERCHANT_ADMIN: 'warning',
    MANAGER: 'primary',
    SALES: 'success',
    SERVICE: 'info'
  }
  return map[role] || 'info'
}

function getRoleName(id) {
  if (!id) return ''
  const r = customRoles.value.find(x => x.id === id)
  return r ? r.name : `角色#${id}`
}

async function fetchCustomRoles() {
  try {
    const res = await api.get('/roles')
    customRoles.value = Array.isArray(res) ? res : []
  } catch (e) {
    customRoles.value = []
  }
}

async function fetchList() {
  loading.value = true
  try {
    const res = await api.get('/users')
    list.value = Array.isArray(res) ? res : []
  } catch (e) {
    ElMessage.warning('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

async function fetchMerchants() {
  merchantsLoading.value = true
  try {
    const res = await api.get('/roles/merchants')
    merchants.value = Array.isArray(res) ? res : []
  } catch (e) {} finally {
    merchantsLoading.value = false
  }
}

const createDialog = reactive({
  visible: false,
  saving: false,
  form: {
    username: '',
    displayName: '',
    password: '',
    email: '',
    notes: '',
    identity: 'MERCHANT',
    merchantId: null
  }
})

function onCreateIdentityChange(val) {
  if (val === 'PLATFORM') {
    createDialog.form.merchantId = null
  }
}

function openCreate() {
  createDialog.visible = true
  if (isMerchantUser.value) {
    createDialog.form = {
      username: '',
      displayName: '',
      password: '',
      email: '',
      notes: '',
      identity: 'MERCHANT',
      merchantId: auth.agent?.merchantId || null
    }
  } else {
    createDialog.form = {
      username: '',
      displayName: '',
      password: '',
      email: '',
      notes: '',
      identity: 'MERCHANT',
      merchantId: null
    }
  }
}

function resetCreateDialog() {
  if (isMerchantUser.value) {
    createDialog.form = {
      username: '',
      displayName: '',
      password: '',
      email: '',
      notes: '',
      identity: 'MERCHANT',
      merchantId: auth.agent?.merchantId || null
    }
  } else {
    createDialog.form = {
      username: '',
      displayName: '',
      password: '',
      email: '',
      notes: '',
      identity: 'MERCHANT',
      merchantId: null
    }
  }
  createDialog.saving = false
}

async function saveCreate() {
  if (!createDialog.form.username) { ElMessage.warning('请输入登录账号'); return }
  if (!createDialog.form.displayName) { ElMessage.warning('请输入姓名'); return }
  if (!createDialog.form.password) { ElMessage.warning('请输入密码'); return }
  if (!isMerchantUser.value && createDialog.form.identity === 'MERCHANT' && !createDialog.form.merchantId) {
    ElMessage.warning('请选择商户'); return
  }
  createDialog.saving = true
  try {
    const payload = {
      username: createDialog.form.username,
      displayName: createDialog.form.displayName,
      password: createDialog.form.password,
      email: createDialog.form.email,
      notes: createDialog.form.notes,
      role: createDialog.form.identity === 'PLATFORM' ? 'PLATFORM_ADMIN' : 'MERCHANT_ADMIN',
      roleIds: []
    }
    if (isMerchantUser.value) {
      payload.merchantId = auth.agent?.merchantId
    } else if (createDialog.form.identity === 'MERCHANT') {
      payload.merchantId = createDialog.form.merchantId
    }
    await api.post('/users', payload)
    ElMessage.success('已创建')
    createDialog.visible = false
    await fetchList()
  } catch (e) {} finally { createDialog.saving = false }
}

const editDialog = reactive({
  visible: false,
  editId: null,
  saving: false,
  form: {
    username: '',
    displayName: '',
    email: '',
    notes: '',
    identity: 'MERCHANT',
    merchantId: null,
    enabled: true
  }
})

function onEditIdentityChange(val) {
  if (val === 'PLATFORM') {
    editDialog.form.merchantId = null
  }
}

function openEdit(row) {
  editDialog.visible = true
  editDialog.editId = row.id
  const isPlatform = row.role === 'PLATFORM_ADMIN'
  editDialog.form = {
    username: row.username || '',
    displayName: row.displayName || '',
    email: row.email || '',
    notes: row.notes || '',
    identity: isPlatform ? 'PLATFORM' : 'MERCHANT',
    merchantId: row.merchantId || null,
    enabled: row.enabled !== false
  }
}

function resetEditDialog() {
  editDialog.editId = null
  editDialog.form = { username: '', displayName: '', email: '', notes: '', identity: 'MERCHANT', merchantId: null, enabled: true }
  editDialog.saving = false
}

async function saveEdit() {
  if (!editDialog.form.displayName) { ElMessage.warning('请输入姓名'); return }
  if (!isMerchantUser.value && editDialog.form.identity === 'MERCHANT' && !editDialog.form.merchantId) {
    ElMessage.warning('请选择商户'); return
  }
  editDialog.saving = true
  try {
    const payload = {
      displayName: editDialog.form.displayName,
      email: editDialog.form.email,
      notes: editDialog.form.notes,
      enabled: editDialog.form.enabled
    }
    if (isMerchantUser.value) {
      payload.merchantId = auth.agent?.merchantId
    } else if (editDialog.form.identity === 'MERCHANT') {
      payload.merchantId = editDialog.form.merchantId
    }
    await api.put(`/users/${editDialog.editId}`, payload)
    ElMessage.success('已更新')
    editDialog.visible = false
    await fetchList()
  } catch (e) {} finally { editDialog.saving = false }
}

const pwdDialog = reactive({
  visible: false,
  editId: null,
  username: '',
  saving: false,
  form: { password: '' }
})

function openResetPwd(row) {
  pwdDialog.visible = true
  pwdDialog.editId = row.id
  pwdDialog.username = row.username || ''
  pwdDialog.form.password = ''
}

function resetPwdDialog() {
  pwdDialog.editId = null
  pwdDialog.username = ''
  pwdDialog.form.password = ''
  pwdDialog.saving = false
}

async function saveResetPwd() {
  if (!pwdDialog.form.password) { ElMessage.warning('请输入新密码'); return }
  pwdDialog.saving = true
  try {
    await api.put(`/users/${pwdDialog.editId}`, { password: pwdDialog.form.password })
    ElMessage.success('密码已重置')
    pwdDialog.visible = false
  } catch (e) {} finally { pwdDialog.saving = false }
}

async function remove(row) {
  try {
    const displayText = row.displayName || row.username || '未知用户'
    await ElMessageBox.confirm(`确定要删除用户「${displayText}」吗？此操作不可恢复。`, '提示', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await api.delete(`/users/${row.id}`)
    ElMessage.success('已删除')
    await fetchList()
  } catch (e) {}
}

// 分配角色相关
const assignRolesDialog = reactive({
  visible: false,
  userId: null,
  username: '',
  selectedRoleIds: [],
  saving: false,
  targetRole: '',
  targetMerchantId: null
})

const availableRoles = computed(() => {
  const targetRole = assignRolesDialog.targetRole
  const targetMerchantId = assignRolesDialog.targetMerchantId
  
  return customRoles.value.filter(role => {
    if (assignRolesDialog.selectedRoleIds.includes(role.id)) return false
    
    if (targetRole === 'PLATFORM_ADMIN') {
      return role.roleType === 'PLATFORM'
    } else {
      if (role.roleType === 'PLATFORM') return false
      if (role.roleType === 'MERCHANT') {
        if (!role.merchantIds || role.merchantIds.length === 0) return true
        return role.merchantIds.includes(targetMerchantId) || role.merchantId === targetMerchantId
      }
      return true
    }
  })
})

function openAssignRoles(row) {
  assignRolesDialog.visible = true
  assignRolesDialog.userId = row.id
  assignRolesDialog.username = row.displayName || row.username || ''
  assignRolesDialog.selectedRoleIds = [...(row.roleIds || [])]
  assignRolesDialog.targetRole = row.role || ''
  assignRolesDialog.targetMerchantId = row.merchantId || null
}

function resetAssignRolesDialog() {
  assignRolesDialog.userId = null
  assignRolesDialog.username = ''
  assignRolesDialog.selectedRoleIds = []
  assignRolesDialog.saving = false
  assignRolesDialog.targetRole = ''
  assignRolesDialog.targetMerchantId = null
}

function addRole(roleId) {
  if (!assignRolesDialog.selectedRoleIds.includes(roleId)) {
    assignRolesDialog.selectedRoleIds.push(roleId)
  }
}

function removeRole(roleId) {
  const index = assignRolesDialog.selectedRoleIds.indexOf(roleId)
  if (index > -1) {
    assignRolesDialog.selectedRoleIds.splice(index, 1)
  }
}

async function saveAssignRoles() {
  assignRolesDialog.saving = true
  try {
    await api.put(`/users/${assignRolesDialog.userId}/roles`, assignRolesDialog.selectedRoleIds)
    ElMessage.success('角色已分配')
    assignRolesDialog.visible = false
    await fetchList()
  } catch (e) {
    ElMessage.error('分配角色失败')
  } finally {
    assignRolesDialog.saving = false
  }
}

// 账号编号管理相关
const accountNumbersDialog = reactive({
  visible: false,
  userId: null,
  username: ''
})

const accountNumbersList = ref([])

const linkNumbersDialog = reactive({
  visible: false,
  range: ''
})

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

async function openAccountNumbers(row) {
  accountNumbersDialog.visible = true
  accountNumbersDialog.userId = row.id
  accountNumbersDialog.username = row.displayName || row.username || ''
  await fetchAccountNumbers(row.id)
}

function resetAccountNumbersDialog() {
  accountNumbersDialog.userId = null
  accountNumbersDialog.username = ''
  accountNumbersList.value = []
}

async function fetchAccountNumbers(userId) {
  try {
    accountNumbersList.value = await getUserAccountNumbers(userId)
  } catch (e) {
    accountNumbersList.value = []
    ElMessage.error('获取账号编号失败')
  }
}

function openLinkNumbers() {
  linkNumbersDialog.visible = true
  linkNumbersDialog.range = ''
}

function resetLinkNumbersDialog() {
  linkNumbersDialog.range = ''
}

async function handleLinkNumbers() {
  if (!linkNumbersDialog.range.trim()) {
    ElMessage.warning('请输入编号范围')
    return
  }
  try {
    await batchLinkAccountNumbers(accountNumbersDialog.userId, linkNumbersDialog.range)
    ElMessage.success('关联成功')
    linkNumbersDialog.visible = false
    await fetchAccountNumbers(accountNumbersDialog.userId)
  } catch (e) {
    // 错误信息已在 http 拦截器中处理
  }
}

async function handleUnlinkNumber(row) {
  try {
    await ElMessageBox.confirm('确定要删除此关联吗？', '提示', { type: 'warning' })
    await unlinkAccountNumber(accountNumbersDialog.userId, row.accountNumberId)
    ElMessage.success('删除成功')
    await fetchAccountNumbers(accountNumbersDialog.userId)
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

onMounted(() => {
  fetchList()
  fetchMerchants()
  fetchCustomRoles()
})
</script>

<style scoped>
.users-page {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg-2);
}

.page-title-wrap { min-width: 0; }
.page-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-text-2); }
.header-actions { display: flex; gap: 10px; }

.page-body {
  flex: 1;
  overflow: auto;
  padding: 20px 24px;
}

.cell-strong {
  font-weight: 600;
  color: var(--color-text);
}

.cell-hint { font-size: 12px; color: var(--color-text-3); }
.form-tip { font-size: 12px; color: var(--color-text-3); margin-top: 4px; }
.role-tags { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.role-tag-item { white-space: nowrap; height: 24px; }

/* 分配角色弹窗样式 */
.assign-roles-container {
  padding: 10px 0;
}

.user-info-row {
  display: flex;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--color-border);
  margin-bottom: 16px;
}

.user-info-row .label {
  color: var(--color-text-2);
  font-size: 14px;
  margin-right: 8px;
}

.user-info-row .value {
  color: var(--color-text);
  font-weight: 600;
  font-size: 14px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-2);
  margin-bottom: 12px;
}

.assigned-roles {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 40px;
  padding: 8px;
  background: var(--color-bg);
  border-radius: 6px;
  border: 1px solid var(--color-border);
}

.role-tag {
  margin: 0;
}

.role-list {
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid var(--color-border);
  border-radius: 6px;
}

.role-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border);
  transition: background 0.15s;
}

.role-list-item:last-child {
  border-bottom: none;
}

.role-list-item:hover {
  background: var(--color-bg);
}

.role-name {
  font-size: 14px;
  color: var(--color-text);
  font-weight: 500;
}

/* 账号编号管理样式 */
.account-numbers-section {
  padding: 10px 0;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-header .section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 0;
}

.link-tip {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 13px;
  color: var(--color-text-2);
}

.format-examples {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.format-examples code {
  background: var(--color-bg);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  margin: 0 4px;
}

.action-cell {
  display: flex;
  flex-wrap: nowrap;
  white-space: nowrap;
  align-items: center;
  gap: 0;
}
</style>
