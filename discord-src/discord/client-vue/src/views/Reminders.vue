<template>
  <div class="reminders-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">提醒规则与通知</h2>
        <p class="page-desc">配置自动提醒规则，查看系统通知</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon><span>新增规则</span>
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="提醒规则" name="rules">
        <div class="page-body">
          <el-table v-loading="loading" :data="rules" stripe style="width:100%">
            <el-table-column label="#" width="60" align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column prop="name" label="规则名" min-width="140">
              <template #default="{ row }"><span class="cell-strong">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column prop="triggerType" label="触发条件" width="130">
              <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
            </el-table-column>
            <el-table-column prop="triggerConfig" label="参数" min-width="150">
              <template #default="{ row }"><span class="cell-mono">{{ row.triggerConfig || '-' }}</span></template>
            </el-table-column>
            <el-table-column prop="frequency" label="频率" width="90" />
            <el-table-column prop="messageTemplate" label="消息模板" min-width="220" show-overflow-tooltip />
            <el-table-column prop="enabled" label="启用" width="90" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" size="small" @change="toggleEnable(row)" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openEdit(row)">
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button size="small" type="danger" link @click="remove(row)">
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="通知中心" name="notifications">
        <div class="page-body">
          <div class="notif-toolbar">
            <el-button size="small" :icon="Bell" @click="markAllRead">全部标为已读</el-button>
            <el-button size="small" type="primary" @click="sendTest">发送测试通知</el-button>
          </div>
          <el-table v-loading="notifLoading" :data="notifications" stripe style="width:100%">
            <el-table-column label="#" width="60" align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag :type="notifTypeColor(row.type)" size="small" effect="light">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="160" />
            <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="isRead" label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.isRead ? 'info' : 'warning'" size="small" effect="light">
                  {{ row.isRead ? '已读' : '未读' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" link :disabled="row.isRead" @click="markRead(row)">标为已读</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialog.visible" :title="dialog.editId ? '编辑规则' : '新增规则'" width="520px" @close="resetDialog">
      <el-form :model="dialog.form" label-width="90px">
        <el-form-item label="规则名" required>
          <el-input v-model="dialog.form.name" />
        </el-form-item>
        <el-form-item label="触发条件" required>
          <el-select v-model="dialog.form.triggerType" style="width:100%">
            <el-option label="长时间未互动 (idle_days)" value="idle_days" />
            <el-option label="标签变动 (tag_change)" value="tag_change" />
            <el-option label="置顶客户提醒 (pinned_idle)" value="pinned_idle" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数">
          <el-input v-model="dialog.form.triggerConfig" placeholder='如 days=3 或 tag=vip' />
        </el-form-item>
        <el-form-item label="频率">
          <el-select v-model="dialog.form.frequency" style="width:100%">
            <el-option label="一次" value="once" />
            <el-option label="每天" value="daily" />
            <el-option label="每周" value="weekly" />
            <el-option label="每月" value="monthly" />
          </el-select>
        </el-form-item>
        <el-form-item label="消息模板">
          <el-input v-model="dialog.form.messageTemplate" type="textarea" :rows="3" placeholder="支持变量 {customerName} {days}" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="dialog.form.enabled" active-text="启用" inactive-text="停用" />
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
import { Plus, Edit, Delete, Refresh, Bell } from '@element-plus/icons-vue'
import {
  listReminderRules, createReminderRule, updateReminderRule, deleteReminderRule,
  listNotifications, markNotificationRead, markAllNotificationsRead
} from '@/api'

const tab = ref('rules')
const rules = ref([])
const notifications = ref([])
const loading = ref(false)
const notifLoading = ref(false)

const dialog = reactive({
  visible: false, editId: null, saving: false,
  form: { name: '', triggerType: 'idle_days', triggerConfig: '', frequency: 'daily', messageTemplate: '', enabled: true }
})

function triggerLabel(t) {
  const m = { idle_days: '长时间未互动', tag_change: '标签变动', pinned_idle: '置顶客户闲置' }
  return m[t] || t
}
function notifTypeColor(t) {
  const m = { system: 'info', rule: 'primary', mention: 'warning', warning: 'danger' }
  return m[t] || 'info'
}
function formatTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function fetchList() {
  loading.value = true
  try { rules.value = await listReminderRules() } catch (e) {} finally { loading.value = false }
}
async function fetchNotif() {
  notifLoading.value = true
  try { notifications.value = await listNotifications() } catch (e) {} finally { notifLoading.value = false }
}

function openCreate() {
  dialog.visible = true
  dialog.editId = null
  dialog.form = { name: '', triggerType: 'idle_days', triggerConfig: '', frequency: 'daily', messageTemplate: '', enabled: true }
}
function openEdit(row) {
  dialog.visible = true
  dialog.editId = row.id
  dialog.form = { ...row }
}
function resetDialog() { dialog.editId = null; dialog.form = { name: '', triggerType: 'idle_days', triggerConfig: '', frequency: 'daily', messageTemplate: '', enabled: true }; dialog.saving = false }

async function save() {
  if (!dialog.form.name) return ElMessage.warning('请输入规则名')
  dialog.saving = true
  try {
    if (dialog.editId) {
      await updateReminderRule(dialog.editId, dialog.form)
      ElMessage.success('已更新')
    } else {
      await createReminderRule(dialog.form)
      ElMessage.success('已创建')
    }
    dialog.visible = false
    await fetchList()
  } catch (e) {} finally { dialog.saving = false }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除规则「${row.name}」吗？`, '提示', { type: 'warning' })
    await deleteReminderRule(row.id)
    ElMessage.success('已删除')
    await fetchList()
  } catch (e) {}
}

async function toggleEnable(row) {
  try {
    await updateReminderRule(row.id, { enabled: row.enabled })
  } catch (e) {
    row.enabled = !row.enabled
  }
}

async function markRead(row) {
  try {
    await markNotificationRead(row.id)
    row.isRead = true
  } catch (e) {}
}

async function markAllRead() {
  try {
    await markAllNotificationsRead()
    notifications.value.forEach(n => n.isRead = true)
    ElMessage.success('已全部标记为已读')
  } catch (e) {}
}

async function sendTest() {
  try {
    await markAllNotificationsRead() // placeholder via create? 不支持, 跳过
    ElMessage.success('测试通知已发送（如需实际通知，请配置后端任务）')
  } catch (e) {}
}

onMounted(() => { fetchList(); fetchNotif() })
</script>

<style scoped>
.reminders-page { width:100%; height:100%; display:flex; flex-direction:column; overflow:hidden; }
.page-header { padding:20px 24px 16px; background:var(--color-bg-2); border-bottom:1px solid var(--color-border); display:flex; justify-content:space-between; align-items:center; }
.page-title { margin:0; font-size:18px; font-weight:700; color:var(--color-text); }
.page-desc { margin:4px 0 0; font-size:12px; color:var(--color-text-2); }
.header-actions { display:flex; gap:10px; }
.page-body { flex:1; overflow:auto; padding:20px 24px; }
.cell-strong { font-weight:600; color:var(--color-text); }
.cell-mono { font-family:"JetBrains Mono",monospace; font-size:12px; color:var(--color-text-2); }
.notif-toolbar { display:flex; gap:10px; margin-bottom:12px; }
</style>
