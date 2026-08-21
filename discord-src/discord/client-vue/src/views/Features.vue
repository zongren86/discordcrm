<template>
  <div class="features-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">功能管理</h2>
        <p class="page-desc">管理系统菜单、页面和按钮权限的树形结构</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openDialog(null, null)">
          <el-icon><Plus /></el-icon><span>新增顶级菜单</span>
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table
        v-loading="loading"
        :data="tableData"
        stripe
        style="width:100%"
        row-key="id"
        :tree-props="{ children: 'children' }"
        default-expand-all
      >
        <el-table-column fixed="left" label="操作" width="220">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" type="primary" link @click="openDialog(null, row)">新增子项</el-button>
              <el-button size="small" type="primary" link @click="openDialog(row, null)">编辑</el-button>
              <el-button size="small" type="primary" link @click="handleDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="功能名称" min-width="180" />
        <el-table-column prop="code" label="权限代码" width="180">
          <template #default="{ row }">
            <span class="cell-mono">{{ row.code || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="typeTagType(row.type)" size="small">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="按钮位置" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.btnType" type="warning" size="small">{{ btnTypeLabel(row.btnType) }}</el-tag>
            <span v-else class="cell-hint">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="routePath" label="路由路径" min-width="180" show-overflow-tooltip />
        <el-table-column prop="icon" label="图标" width="100" />
        <el-table-column prop="sortOrder" label="排序" width="80" />
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑功能' : `新增${parentRow ? '子' : '顶级'}功能`" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="功能名称" required>
          <el-input v-model="form.name" placeholder="如：账号管理" />
        </el-form-item>
        <el-form-item label="权限代码" required>
          <el-input v-model="form.code" placeholder="如：account.view" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width:100%" @change="onTypeChange">
            <el-option label="一级菜单" value="MENU_1" :disabled="!!parentRow" />
            <el-option label="二级菜单" value="MENU_2" />
            <el-option label="三级菜单/页面" value="MENU_3" />
            <el-option label="按钮/操作" value="BUTTON" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.type === 'BUTTON'" label="按钮位置">
          <el-select v-model="form.btnType" style="width:100%" placeholder="选择按钮所在位置" clearable>
            <el-option label="页签 Tab" value="TAB" />
            <el-option label="工具栏按钮" value="TOOLBAR" />
            <el-option label="操作列按钮" value="ROW_ACTION" />
          </el-select>
        </el-form-item>
        <el-form-item label="路由路径">
          <el-input v-model="form.routePath" placeholder="如：/system/users" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" placeholder="Element Plus 图标名" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item v-if="parentRow" label="上级功能">
          <el-input :model-value="parentRow.name" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api } from '@/api'

const loading = ref(false), saving = ref(false)
const tableData = ref([])
const dialogVisible = ref(false)
const editing = ref(null)
const parentRow = ref(null)
const form = ref({ type: 'MENU_1', sortOrder: 0 })

function typeLabel(type) {
  return type === 'MENU_1' ? '一级菜单' : type === 'MENU_2' ? '二级菜单' : type === 'MENU_3' ? '三级菜单' : type === 'BUTTON' ? '按钮' : type
}
function typeTagType(type) {
  return type === 'MENU_1' ? '' : type === 'MENU_2' ? 'success' : type === 'MENU_3' ? 'warning' : 'info'
}
function btnTypeLabel(btnType) {
  return btnType === 'TAB' ? '页签' : btnType === 'TOOLBAR' ? '工具栏' : btnType === 'ROW_ACTION' ? '操作列' : btnType
}
function onTypeChange(val) {
  if (val !== 'BUTTON') form.value.btnType = null
}

async function fetchList() {
  loading.value = true
  try {
    const res = await api.get('/system/features/tree')
    tableData.value = Array.isArray(res) ? res : []
  } catch (e) {} finally { loading.value = false }
}

function openDialog(row, parent) {
  editing.value = row
  parentRow.value = parent
  if (row) {
    form.value = { ...row }
  } else {
    const defaultType = parent
      ? (parent.type === 'MENU_1' ? 'MENU_2' : parent.type === 'MENU_2' ? 'MENU_3' : 'BUTTON')
      : 'MENU_1'
    form.value = {
      type: defaultType,
      sortOrder: 0,
      parentId: parent?.id ?? null,
      btnType: null
    }
  }
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.value.name) return ElMessage.warning('请填写功能名称')
  if (!form.value.code) return ElMessage.warning('请填写权限代码')
  if (!form.value.type) return ElMessage.warning('请选择类型')
  saving.value = true
  try {
    if (editing.value) {
      await api.put(`/system/features/${editing.value.id}`, form.value)
      ElMessage.success('更新成功')
    } else {
      await api.post('/system/features', form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await fetchList()
  } catch (e) {} finally { saving.value = false }
}

async function handleDelete(row) {
  const hasChildren = row.children && row.children.length > 0
  const tip = hasChildren ? `「${row.name}」下有子功能，删除将同时删除所有子项，` : `确认删除功能「${row.name}」？`
  try {
    await ElMessageBox.confirm(tip + (hasChildren ? '确认继续？' : ''), '提示', { type: 'warning' })
    await api.delete(`/system/features/${row.id}`)
    ElMessage.success('删除成功')
    await fetchList()
  } catch (e) {}
}

onMounted(fetchList)
</script>

<style scoped>
.features-page { width:100%; height:100%; display:flex; flex-direction:column; overflow:hidden; }
.page-header { padding:20px 24px 16px; background:var(--color-bg-2); border-bottom:1px solid var(--color-border); display:flex; justify-content:space-between; align-items:center; }
.page-title { margin:0; font-size:18px; font-weight:700; color:var(--color-text); }
.page-desc { margin:4px 0 0; font-size:12px; color:var(--color-text-2); }
.header-actions { display:flex; gap:10px; }
.page-body { flex:1; overflow:auto; padding:20px 24px; }
.cell-mono { font-family:"JetBrains Mono",monospace; font-size:12px; color:var(--color-text-2); }
.cell-hint { font-size:12px; color:var(--color-text-3); }
.op-links { display:flex; gap:4px; white-space:nowrap; }
</style>
