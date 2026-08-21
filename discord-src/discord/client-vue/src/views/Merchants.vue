<template>
  <div class="merchants-page">
    <div class="page-header">
      <div class="page-title-wrap">
        <h2 class="page-title">商户管理</h2>
        <p class="page-desc">管理平台商户信息</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span>新增商户</span>
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table
        v-loading="loading"
        :data="list"
        stripe
        style="width: 100%;"
      >
        <el-table-column label="#" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140">
          <template #default="{ row }">
            <span class="cell-strong">{{ row.name || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" width="140">
          <template #default="{ row }">
            <span class="cell-mono">{{ row.code || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="120">
          <template #default="{ row }">{{ row.contactName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="150">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.status === 'ACTIVE' ? 'success' : 'info'"
              size="small"
              effect="light"
            >
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" type="primary" link @click="openEdit(row)">
                <el-icon><Edit /></el-icon> 编辑
              </el-button>
              <el-button size="small" type="primary" link @click="remove(row)">
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增 / 编辑商户 -->
    <el-dialog
      v-model="dialog.visible"
      :title="dialog.editId ? '编辑商户' : '新增商户'"
      width="480px"
      @close="resetDialog"
    >
      <el-form :model="dialog.form" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="dialog.form.name" placeholder="请输入商户名称" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="dialog.form.code" placeholder="请输入商户编码" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="dialog.form.contactName" placeholder="请输入联系人" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="dialog.form.phone" placeholder="请输入联系电话" />
        </el-form-item>
        <el-form-item v-if="dialog.editId" label="状态">
          <el-switch
            v-model="dialog.form.status"
            active-value="ACTIVE"
            inactive-value="INACTIVE"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Refresh } from '@element-plus/icons-vue'
import {
  listMerchants, createMerchant, updateMerchant, deleteMerchant
} from '@/api'

const list = ref([])
const loading = ref(false)

const dialog = reactive({
  visible: false,
  editId: null,
  saving: false,
  form: {
    name: '',
    code: '',
    contactName: '',
    phone: '',
    status: 'ACTIVE'
  }
})

function statusLabel(status) {
  return status === 'ACTIVE' ? '正常' : '停用'
}

function formatTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function fetchList() {
  loading.value = true
  try {
    const res = await listMerchants()
    list.value = Array.isArray(res) ? res : (res?.data || [])
  } catch (e) {
    ElMessage.warning('加载商户列表失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialog.visible = true
  dialog.editId = null
  dialog.form = { name: '', code: '', contactName: '', phone: '', status: 'ACTIVE' }
}

function openEdit(row) {
  dialog.visible = true
  dialog.editId = row.id
  dialog.form = {
    name: row.name || '',
    code: row.code || '',
    contactName: row.contactName || '',
    phone: row.phone || '',
    status: row.status || 'ACTIVE'
  }
}

function resetDialog() {
  dialog.editId = null
  dialog.form = { name: '', code: '', contactName: '', phone: '', status: 'ACTIVE' }
  dialog.saving = false
}

async function save() {
  if (!dialog.form.name) {
    ElMessage.warning('请输入商户名称')
    return
  }
  if (!dialog.form.code) {
    ElMessage.warning('请输入商户编码')
    return
  }
  dialog.saving = true
  try {
    if (dialog.editId) {
      await updateMerchant(dialog.editId, { ...dialog.form })
      ElMessage.success('已更新')
    } else {
      await createMerchant({ ...dialog.form })
      ElMessage.success('已创建')
    }
    dialog.visible = false
    await fetchList()
  } catch (e) {
    // 错误已由 http 拦截器提示
  } finally {
    dialog.saving = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除商户「${row.name || row.code}」吗？此操作不可恢复。`,
      '提示',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await deleteMerchant(row.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (e) {
    // 用户取消或请求失败
  }
}

onMounted(() => {
  fetchList()
})
</script>

<style scoped>
.merchants-page {
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

.cell-mono {
  font-family: "JetBrains Mono", monospace;
  font-size: 12px;
  color: var(--color-text-2);
}
</style>
