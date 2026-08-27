<template>
  <div class="roles-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色管理</h2>
        <p class="page-desc">创建/编辑角色，配置功能权限和适用商户</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon><span>新增角色</span>
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table v-loading="loading" :data="list" stripe style="width:100%">
        <el-table-column label="#" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="name" label="角色名" min-width="140">
          <template #default="{ row }">
            <span class="cell-strong">{{ row.name || '-' }}</span>
            <el-tag v-if="row.builtin" size="small" type="info" effect="plain" style="margin-left:6px">系统</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" width="160">
          <template #default="{ row }"><span class="cell-mono">{{ row.code || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="身份" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.roleType === 'PLATFORM'" type="warning" size="small" effect="light">平台</el-tag>
            <el-tag v-else type="primary" size="small" effect="light">商户</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isPlatform" label="适用商户" min-width="180">
          <template #default="{ row }">
            <span v-if="row.roleType !== 'PLATFORM' && row.merchantIds && row.merchantIds.length > 0" class="merchant-tags">
              <el-tag v-for="id in row.merchantIds.slice(0, 3)" :key="id" size="small" effect="light" style="margin:2px 4px 2px 0">
                {{ getMerchantName(id) }}
              </el-tag>
              <el-tag v-if="row.merchantIds.length > 3" size="small" effect="plain" style="margin:2px 0">+{{ row.merchantIds.length - 3 }}</el-tag>
            </span>
            <span v-else-if="row.roleType === 'PLATFORM'" class="cell-hint">平台级</span>
            <span v-else class="cell-hint">未配置</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="160">
          <template #default="{ row }">{{ row.description || '-' }}</template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="light">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" type="primary" link @click="openEdit(row)">
                <el-icon><Edit /></el-icon> 编辑
              </el-button>
              <el-button v-if="isPlatform && row.roleType !== 'PLATFORM'" size="small" type="primary" link @click="openMerchants(row)">
                <el-icon><OfficeBuilding /></el-icon> 适用商户
              </el-button>
              <el-button size="small" type="primary" link @click="openPerm(row)">
                <el-icon><Lock /></el-icon> 分配权限
              </el-button>
              <el-button size="small" type="primary" link @click="remove(row)">
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="formDialog.visible" :title="formDialog.editId ? '编辑角色' : '新增角色'" width="480px" @close="resetFormDialog">
      <el-form :model="formDialog.form" label-width="80px">
        <el-form-item label="角色名" required>
          <el-input v-model="formDialog.form.name" placeholder="请输入角色名" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="formDialog.form.code" placeholder="英文/下划线，如 custom_sales" :disabled="!!formDialog.editId" />
        </el-form-item>
        <el-form-item label="身份" required>
          <el-select v-model="formDialog.form.roleType" placeholder="选择身份" style="width:100%" :disabled="!isPlatform">
            <el-option label="商户" value="MERCHANT" />
            <el-option label="平台" value="PLATFORM" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="formDialog.form.description" type="textarea" :rows="3" placeholder="角色描述" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="formDialog.form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="formDialog.saving" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 适用商户配置 -->
    <el-dialog v-model="merchantDialog.visible" :title="`适用商户 - ${merchantDialog.roleName || ''}`" width="560px" @close="resetMerchantDialog">
      <div class="merchant-config-wrap">
        <div class="section-title">已配置适用商户</div>
        <div v-loading="merchantDialog.loading" class="merchant-list">
          <div v-if="merchantDialog.merchantIds.length === 0" class="merchant-empty">
            <el-empty description="暂未配置适用商户" :image-size="60" />
          </div>
          <div v-else class="merchant-items">
            <div v-for="id in merchantDialog.merchantIds" :key="id" class="merchant-item">
              <div class="merchant-info">
                <el-icon class="merchant-icon"><OfficeBuilding /></el-icon>
                <span class="merchant-name">{{ getMerchantName(id) }}</span>
              </div>
              <el-button size="small" type="danger" link @click="removeMerchant(id)">
                <el-icon><Delete /></el-icon> 移除
              </el-button>
            </div>
          </div>
        </div>

        <div class="add-merchant-section">
          <div class="section-title">添加适用商户</div>
          <div class="add-merchant-row">
            <el-select
              v-model="merchantDialog.newMerchantId"
              placeholder="选择商户添加"
              filterable
              style="flex: 1;"
            >
              <el-option
                v-for="m in availableMerchants"
                :key="m.id"
                :label="m.name"
                :value="m.id"
              />
            </el-select>
            <el-button type="primary" :disabled="!merchantDialog.newMerchantId" @click="addMerchant">
              <el-icon><Plus /></el-icon> 添加
            </el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="merchantDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="merchantDialog.saving" @click="saveMerchants">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限配置（树形结构） -->
    <el-dialog v-model="permDialog.visible" :title="`配置权限 - ${permDialog.roleName || ''}`" width="700px" @close="resetPermDialog">
      <div v-loading="permDialog.loading" class="perm-wrap">
        <div class="perm-toolbar">
          <div class="perm-stats">
            <span>已选功能：<strong>{{ selectedPermCount }}</strong></span>
          </div>
          <div class="perm-actions">
            <el-button size="small" @click="expandAll">展开全部</el-button>
            <el-button size="small" @click="collapseAll">折叠全部</el-button>
            <el-button size="small" type="primary" plain @click="checkAll">全选</el-button>
            <el-button size="small" type="info" plain @click="uncheckAll">清空</el-button>
          </div>
        </div>
        <el-tree
          ref="permTreeRef"
          :data="catalog"
          show-checkbox
          node-key="code"
          :default-checked-keys="permDialog.keys"
          :props="{ label: 'name', children: 'children' }"
          :expand-on-click-node="false"
          :check-strictly="false"
        >
          <template #default="{ node, data }">
            <span class="tree-node">
              <span>{{ node.label }}</span>
            </span>
          </template>
        </el-tree>
      </div>
      <template #footer>
        <el-button @click="permDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="permDialog.saving" @click="savePerm">保存权限</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Refresh, Lock, OfficeBuilding } from '@element-plus/icons-vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const isPlatform = computed(() => auth.agent?.accountType === 0 && auth.agent?.merchantId == null)
const list = ref([])
const loading = ref(false)
const catalog = ref([])
const allMerchants = ref([])
const permTreeRef = ref(null)

const formDialog = reactive({ visible: false, editId: null, saving: false, form: { name: '', code: '', description: '', enabled: true, roleType: 'MERCHANT' } })
const permDialog = reactive({ visible: false, roleId: null, roleName: '', loading: false, saving: false, keys: [] })

const merchantDialog = reactive({
  visible: false,
  roleId: null,
  roleName: '',
  loading: false,
  saving: false,
  merchantIds: [],
  newMerchantId: null
})

const availableMerchants = computed(() => {
  return allMerchants.value.filter(m => !merchantDialog.merchantIds.includes(m.id))
})

const hasMerchantRole = computed(() => {
  return list.value.some(r => r.roleType !== 'PLATFORM')
})

const selectedPermCount = computed(() => {
  return permDialog.keys.length
})

function getMerchantName(id) {
  const m = allMerchants.value.find(x => x.id === id)
  return m ? m.name : `商户#${id}`
}

function permTypeLabel(type) {
  return type === 'MENU_1' ? '一级菜单' : type === 'MENU_2' ? '二级菜单' : type === 'MENU_3' ? '三级菜单' : type === 'BUTTON' ? '按钮' : type === 'MENU' ? '菜单' : '功能'
}
function permTypeTagType(type) {
  return type === 'MENU_1' ? '' : type === 'MENU_2' ? 'success' : type === 'MENU_3' ? 'warning' : 'info'
}

function collectAllCodes(nodes, codes = []) {
  for (const node of nodes) {
    codes.push(node.code)
    if (node.children && node.children.length) {
      collectAllCodes(node.children, codes)
    }
  }
  return codes
}

function getLeafCodes(nodes = catalog.value, codes = []) {
  for (const node of nodes) {
    if (node.children && node.children.length > 0) {
      getLeafCodes(node.children, codes)
    } else {
      codes.push(node.code)
    }
  }
  return codes
}

function getAllFeatureCodes() {
  return collectAllCodes(catalog.value)
}

function expandAll() {
  if (permTreeRef.value) {
    const allKeys = getAllFeatureCodes()
    for (const key of allKeys) {
      const node = permTreeRef.value.getNode(key)
      if (node) node.expanded = true
    }
  }
}

function collapseAll() {
  if (permTreeRef.value) {
    const allKeys = getAllFeatureCodes()
    for (const key of allKeys) {
      const node = permTreeRef.value.getNode(key)
      if (node) node.expanded = false
    }
  }
}

function checkAll() {
  if (permTreeRef.value) {
    const allCodes = getAllFeatureCodes()
    permTreeRef.value.setCheckedKeys(allCodes)
    permDialog.keys = [...allCodes]
  }
}

function uncheckAll() {
  if (permTreeRef.value) {
    permTreeRef.value.setCheckedKeys([])
    permDialog.keys = []
  }
}

async function fetchList() {
  loading.value = true
  try {
    const roles = await api.get('/roles')
    list.value = roles || []
  } catch (e) {} finally { loading.value = false }
}

async function fetchCatalog() {
  try {
    const res = await api.get('/roles/feature-catalog')
    catalog.value = Array.isArray(res) ? res : []
  } catch (e) {}
}

async function fetchMerchants() {
  try {
    const res = await api.get('/roles/merchants')
    allMerchants.value = Array.isArray(res) ? res : []
  } catch (e) {}
}

function openCreate() {
  formDialog.visible = true
  formDialog.editId = null
  formDialog.form = { name: '', code: '', description: '', enabled: true, roleType: 'MERCHANT' }
}

function openEdit(row) {
  formDialog.visible = true
  formDialog.editId = row.id
  formDialog.form = {
    name: row.name || '',
    code: row.code || '',
    description: row.description || '',
    enabled: row.enabled !== false,
    roleType: row.roleType || 'MERCHANT'
  }
}

function resetFormDialog() {
  formDialog.editId = null
  formDialog.form = { name: '', code: '', description: '', enabled: true, roleType: 'MERCHANT' }
  formDialog.saving = false
}

async function saveForm() {
  if (!formDialog.form.name) return ElMessage.warning('请输入角色名')
  if (!formDialog.form.code) return ElMessage.warning('请输入角色编码')
  formDialog.saving = true
  try {
    if (formDialog.editId) {
      await api.put(`/roles/${formDialog.editId}`, formDialog.form)
      ElMessage.success('已更新')
    } else {
      await api.post('/roles', formDialog.form)
      ElMessage.success('已创建')
    }
    formDialog.visible = false
    await fetchList()
  } catch (e) {} finally { formDialog.saving = false }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除角色「${row.name}」吗？`, '提示', { type: 'warning' })
    await api.delete(`/roles/${row.id}`)
    ElMessage.success('已删除')
    await fetchList()
  } catch (e) {}
}

async function openMerchants(row) {
  merchantDialog.visible = true
  merchantDialog.roleId = row.id
  merchantDialog.roleName = row.name
  merchantDialog.merchantIds = []
  merchantDialog.newMerchantId = null
  merchantDialog.loading = true
  try {
    const ids = await api.get(`/roles/${row.id}/merchant-ids`)
    merchantDialog.merchantIds = Array.isArray(ids) ? ids : []
  } catch (e) {
    merchantDialog.merchantIds = []
  } finally {
    merchantDialog.loading = false
  }
}

function resetMerchantDialog() {
  merchantDialog.roleId = null
  merchantDialog.roleName = ''
  merchantDialog.merchantIds = []
  merchantDialog.newMerchantId = null
  merchantDialog.saving = false
}

function addMerchant() {
  if (!merchantDialog.newMerchantId) return
  if (!merchantDialog.merchantIds.includes(merchantDialog.newMerchantId)) {
    merchantDialog.merchantIds.push(merchantDialog.newMerchantId)
  }
  merchantDialog.newMerchantId = null
}

function removeMerchant(id) {
  const idx = merchantDialog.merchantIds.findIndex(x => x === id)
  if (idx >= 0) merchantDialog.merchantIds.splice(idx, 1)
}

async function saveMerchants() {
  merchantDialog.saving = true
  try {
    const formData = { merchantIds: merchantDialog.merchantIds }
    await api.put(`/roles/${merchantDialog.roleId}`, formData)
    ElMessage.success('已保存')
    merchantDialog.visible = false
    await fetchList()
  } catch (e) {} finally { merchantDialog.saving = false }
}

async function openPerm(row) {
  permDialog.visible = true
  permDialog.roleId = row.id
  permDialog.roleName = row.name
  permDialog.keys = []
  permDialog.loading = true
  try {
    const featureCodes = await api.get(`/roles/${row.id}/features`)
    // 只保留叶子节点的 code，父节点状态由 Element Tree 自动计算
    const leafCodes = getLeafCodes()
    const leafFeatureCodes = (Array.isArray(featureCodes) ? featureCodes : []).filter(c => leafCodes.includes(c))
    permDialog.keys = leafFeatureCodes
    
    await nextTick()
    if (permTreeRef.value) {
      permTreeRef.value.setCheckedKeys(leafFeatureCodes)
    }
  } catch (e) {
    permDialog.keys = []
  } finally {
    permDialog.loading = false
  }
}

function resetPermDialog() { 
  permDialog.roleId = null
  permDialog.roleName = ''
  permDialog.keys = []
  permDialog.saving = false 
}

async function savePerm() {
  permDialog.saving = true
  try {
    if (!permTreeRef.value) {
      ElMessage.warning('权限树未就绪')
      return
    }
    // 只保存完全勾选的叶子节点，不保存半选父节点
    // getCheckedKeys(true) 只返回叶子节点，父节点状态由 Element Tree 自动计算
    const checkedLeafKeys = permTreeRef.value.getCheckedKeys(true) || []
    
    permDialog.keys = checkedLeafKeys
    await api.put(`/roles/${permDialog.roleId}/features`, { featureCodes: checkedLeafKeys })
    ElMessage.success('权限已更新')
    permDialog.visible = false
  } catch (e) {} finally { permDialog.saving = false }
}

onMounted(() => { fetchList(); fetchCatalog(); fetchMerchants() })
</script>

<style scoped>
.roles-page { width:100%; height:100%; display:flex; flex-direction:column; overflow:hidden; }
.page-header { padding:20px 24px 16px; background:var(--color-bg-2); border-bottom:1px solid var(--color-border); display:flex; justify-content:space-between; align-items:center; }
.page-title { margin:0; font-size:18px; font-weight:700; color:var(--color-text); }
.page-desc { margin:4px 0 0; font-size:12px; color:var(--color-text-2); }
.header-actions { display:flex; gap:10px; }
.page-body { flex:1; overflow:auto; padding:20px 24px; }
.cell-strong { font-weight:600; color:var(--color-text); }
.cell-mono { font-family:"JetBrains Mono",monospace; font-size:12px; color:var(--color-text-2); }
.cell-hint { font-size:12px; color:var(--color-text-3); }
.perm-wrap { min-height:300px; max-height:500px; overflow-y:auto; }
.perm-toolbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; padding-bottom:12px; border-bottom:1px solid var(--color-border); }
.perm-stats { font-size:13px; color:var(--color-text-2); }
.perm-actions { display:flex; gap:8px; }
.tree-node { display:flex; align-items:center; }
.merchant-tags { display: flex; flex-wrap: wrap; }
.merchant-config-wrap { min-height: 200px; }
.section-title { font-size: 13px; font-weight: 600; color: var(--color-text); margin-bottom: 10px; }
.merchant-list { min-height: 80px; }
.merchant-empty { padding: 20px 0; }
.merchant-items { display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }
.merchant-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; border-radius: 8px; background: var(--color-bg-3); }
.merchant-info { display: flex; align-items: center; gap: 8px; }
.merchant-icon { color: var(--color-primary); }
.merchant-name { font-weight: 600; color: var(--color-text); }
.add-merchant-section { border-top: 1px solid var(--color-border); padding-top: 16px; }
.add-merchant-row { display: flex; gap: 10px; }
</style>
