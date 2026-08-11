<template>
  <div class="stats-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">客户统计 / 仪表盘</h2>
        <p class="page-desc">消息、会话、客户与销售漏斗概览</p>
      </div>
      <div class="header-actions">
        <div class="range-group">
          <el-select v-model="rangePreset" size="small" style="width:130px" @change="applyPreset">
            <el-option label="今日" value="today" />
            <el-option label="最近7天" value="7d" />
            <el-option label="最近30天" value="30d" />
            <el-option label="自定义" value="custom" />
          </el-select>
          <div v-if="rangePreset === 'custom'" class="custom-range">
            <el-date-picker v-model="dateRange" type="daterange" size="small" value-format="YYYY-MM-DD" range-separator="至" />
          </div>
        </div>
        <el-button size="small" :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <div class="page-body">
      <!-- 概览卡片 -->
      <div class="cards-row">
        <div class="stat-card card-primary">
          <div class="stat-icon"><el-icon><Message /></el-icon></div>
          <div>
            <div class="stat-num">{{ overview.totalMessages ?? 0 }}</div>
            <div class="stat-label">消息总数</div>
          </div>
        </div>
        <div class="stat-card card-pink">
          <div class="stat-icon"><el-icon><ChatDotRound /></el-icon></div>
          <div>
            <div class="stat-num">{{ overview.totalConversations ?? 0 }}</div>
            <div class="stat-label">总会话数</div>
          </div>
        </div>
        <div class="stat-card card-green">
          <div class="stat-icon"><el-icon><UserFilled /></el-icon></div>
          <div>
            <div class="stat-num">{{ overview.totalCustomers ?? 0 }}</div>
            <div class="stat-label">客户总数</div>
          </div>
        </div>
        <div class="stat-card card-yellow">
          <div class="stat-icon"><el-icon><User /></el-icon></div>
          <div>
            <div class="stat-num">{{ overview.totalAccounts ?? 0 }}</div>
            <div class="stat-label">账号总数</div>
          </div>
        </div>
        <div class="stat-card card-purple">
          <div class="stat-icon"><el-icon><ChatLineSquare /></el-icon></div>
          <div>
            <div class="stat-num">{{ overview.messagesInRange ?? 0 }}</div>
            <div class="stat-label">区间消息数</div>
          </div>
        </div>
      </div>

      <!-- 趋势图 -->
      <section class="panel">
        <div class="panel-head">
          <h3 class="panel-title">消息/会话趋势（最近{{ trendDays }}天）</h3>
        </div>
        <div class="panel-body">
          <div v-if="trend.length > 0" class="trend-chart">
            <div class="trend-y">
              <div class="trend-max">{{ Math.max(...trend.map(t => Math.max(t.messages || 0, t.conversations || 0)), 1) }}</div>
              <div class="trend-0">0</div>
            </div>
            <div class="trend-content">
              <div class="trend-bars">
                <div v-for="day in trend" :key="day.date" class="trend-col">
                  <div class="trend-bar-wrap">
                    <div class="trend-bar bar-msg" :style="{ height: pct(day.messages, trend) + '%' }">
                      <span class="bar-val" v-if="day.messages">{{ day.messages }}</span>
                    </div>
                    <div class="trend-bar bar-conv" :style="{ height: pct(day.conversations, trend) + '%' }">
                      <span class="bar-val" v-if="day.conversations">{{ day.conversations }}</span>
                    </div>
                  </div>
                  <div class="trend-date">{{ shortDate(day.date) }}</div>
                </div>
              </div>
              <div class="trend-legend">
                <span class="lg-item"><span class="lg-dot lg-msg"></span>消息数</span>
                <span class="lg-item"><span class="lg-dot lg-conv"></span>会话数</span>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无趋势数据" :image-size="60" />
        </div>
      </section>

      <div class="content-row">
        <!-- 销售漏斗分布 -->
        <section class="panel">
          <div class="panel-head"><h3 class="panel-title">销售漏斗分布</h3></div>
          <div class="panel-body" v-if="stageDistribution.length > 0">
            <div class="funnel-chart">
              <div v-for="(stage, idx) in stageDistribution" :key="stage.stage"
                class="funnel-bar" :style="{ width: barWidth(stage.count) + '%', zIndex: stageDistribution.length - idx }">
                <div class="funnel-label">
                  <span class="stage-label-text">{{ stageLabel(stage.stage) }}</span>
                  <span class="stage-count">{{ stage.count }}</span>
                </div>
                <div class="funnel-bar-fill" :style="{ background: stageColor(stage.stage) }"></div>
              </div>
            </div>
          </div>
          <el-empty v-else description="暂无漏斗数据" :image-size="60" />
        </section>

        <!-- 活跃客户 -->
        <section class="panel">
          <div class="panel-head"><h3 class="panel-title">活跃客户 (最近有消息往来)</h3></div>
          <div class="panel-body">
            <el-table v-loading="loadingCustomers" :data="activeCustomers" stripe style="width:100%" size="small">
              <el-table-column label="#" width="50" align="center"><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column>
              <el-table-column label="客户">
                <template #default="{ row }">
                  <div class="cust-cell">
                    <el-avatar :size="28" :src="getAvatar(row)">{{ initialOf(row) }}</el-avatar>
                    <div class="cust-meta">
                      <div class="cust-name">{{ row.nickname || row.globalName || row.username || '客户' + row.discordUserId }}</div>
                      <div class="cust-sub">@{{ row.username }} · {{ row.discordUserId }}</div>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="messageCount" label="消息数" width="90" align="right">
                <template #default="{ row }"><b>{{ row.messageCount ?? 0 }}</b></template>
              </el-table-column>
              <el-table-column prop="lastMessageTime" label="最后活跃" width="170" align="right">
                <template #default="{ row }">{{ formatTime(row.lastMessageTime) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </div>

      <!-- 客服工作统计 -->
      <section class="panel" v-if="byAgentStats.length > 0">
        <div class="panel-head"><h3 class="panel-title">客服工作统计</h3></div>
        <div class="panel-body">
          <el-table :data="byAgentStats" stripe style="width:100%" size="small">
            <el-table-column prop="agentName" label="客服" min-width="140">
              <template #default="{ row }">
                <div class="cust-cell">
                  <div class="cust-meta">
                    <div class="cust-name">{{ row.agentName }}</div>
                    <div class="cust-sub">{{ row.role || '-' }}</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="conversationCount" label="会话数" width="100" align="right">
              <template #default="{ row }"><b>{{ row.conversationCount ?? 0 }}</b></template>
            </el-table-column>
            <el-table-column prop="messageCount" label="消息数" width="100" align="right">
              <template #default="{ row }"><b>{{ row.messageCount ?? 0 }}</b></template>
            </el-table-column>
            <el-table-column label="平均每会话" width="120" align="right">
              <template #default="{ row }">
                {{ row.conversationCount ? ((row.messageCount || 0) / row.conversationCount).toFixed(1) : '-' }}
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <!-- 销售转化率 -->
      <section class="panel" v-if="conversionRate && conversionRate.total > 0">
        <div class="panel-head"><h3 class="panel-title">销售漏斗转化率</h3></div>
        <div class="panel-body">
          <div class="conversion-summary">
            <div class="conversion-total">
              <span class="total-num">{{ conversionRate.total }}</span>
              <span class="total-label">总会话</span>
            </div>
            <div class="conversion-stages">
              <div v-for="stage in stageOptions" :key="stage.value" class="conv-stage-row">
                <div class="conv-stage-name" :style="{ color: stage.color }">{{ stage.label }}</div>
                <div class="conv-stage-bar">
                  <div class="conv-stage-bar-fill" :style="{ width: (conversionRate.rates?.[stage.value] || 0) + '%', background: stage.color }"></div>
                </div>
                <div class="conv-stage-count">{{ conversionRate.stageCounts?.[stage.value] || 0 }}</div>
                <div class="conv-stage-rate">{{ (conversionRate.rates?.[stage.value] || 0).toFixed(1) }}%</div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Message, ChatDotRound, UserFilled, User, Refresh, ChatLineSquare } from '@element-plus/icons-vue'
import { getStats, getActiveCustomers, getStageDistribution, getStatsTrend, getStatsByAgent, getConversionRate } from '@/api'

const overview = ref({})
const activeCustomers = ref([])
const stageDistribution = ref([])
const trend = ref([])
const loadingCustomers = ref(false)
const byAgentStats = ref([])
const conversionRate = ref({})

const rangePreset = ref('7d')
const dateRange = ref([])
const trendDays = ref(7)

const stageOptions = [
  { value: 'PROSPECT',   label: '通过客户',   color: '#5865f2' },
  { value: 'NEW',        label: '回复客户',   color: '#4fc3f7' },
  { value: 'ACTIVE',     label: '换包客户',   color: '#ffb74d' },
  { value: 'CONVERTED',  label: '注册客户',   color: '#66bb6a' },
  { value: 'PAYING',     label: '付费客户',   color: '#ef5350' },
  { value: 'DORMANT',    label: '休眠客户',   color: '#90a4ae' },
  { value: 'CHURNED',    label: '流失客户',   color: '#8d6e63' },
  { value: 'ARCHIVED',   label: '归档客户',   color: '#78909c' }
]

function stageLabel(v) { return stageOptions.find(s => s.value === v)?.label || v }
function stageColor(v) { return stageOptions.find(s => s.value === v)?.color || '#5865f2' }
function barWidth(count) {
  const max = stageDistribution.value.reduce((m, s) => Math.max(m, s.count || 0), 0)
  if (max === 0) return 30
  return Math.max(30, (count / max) * 100)
}
function pct(value, list) {
  const max = list.reduce((m, s) => Math.max(m, Math.max(s.messages || 0, s.conversations || 0)), 0)
  if (max === 0) return 0
  return Math.max(2, (value / max) * 100)
}
function shortDate(d) {
  if (!d) return ''
  const parts = d.split('-')
  return parts.length === 3 ? `${parts[1]}/${parts[2]}` : d
}
function formatTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function getAvatar(row) { return row.avatarUrl || '' }
function initialOf(row) {
  const n = row.nickname || row.globalName || row.username || '?'
  return n.charAt(0).toUpperCase()
}

function applyPreset() {
  if (rangePreset.value === 'custom') {
    return
  }
  const map = { today: 1, '7d': 7, '30d': 30 }
  trendDays.value = map[rangePreset.value] || 7
  dateRange.value = []
  refreshAll()
}

async function refreshAll() {
  const params = {}
  if (rangePreset.value === 'custom' && dateRange.value?.length === 2) {
    params.dateFrom = dateRange.value[0]
    params.dateTo = dateRange.value[1]
  }
  try {
    const res = await getStats(params)
    if (res) {
      overview.value = {
        totalMessages: res.totalMessages ?? 0,
        totalConversations: res.totalConversations ?? 0,
        totalCustomers: res.totalCustomers ?? 0,
        totalAccounts: res.totalAccounts ?? 0,
        messagesInRange: res.messagesInRange ?? 0
      }
    }
  } catch (e) {}

  try {
    const sd = await getStageDistribution()
    stageDistribution.value = Array.isArray(sd) ? sd : []
  } catch (e) {}

  try {
    const tr = await getStatsTrend(trendDays.value)
    trend.value = Array.isArray(tr) ? tr : []
  } catch (e) {}

  loadingCustomers.value = true
  try {
    const res = await getActiveCustomers()
    activeCustomers.value = Array.isArray(res) ? res : (res?.data || [])
  } catch (e) {
    ElMessage.warning('加载活跃客户失败')
  } finally {
    loadingCustomers.value = false
  }

  try {
    const params = rangePreset.value === 'custom' && dateRange.value?.length === 2
      ? { dateFrom: dateRange.value[0], dateTo: dateRange.value[1] } : {}
    const byAgent = await getStatsByAgent(params.dateFrom, params.dateTo)
    byAgentStats.value = Array.isArray(byAgent) ? byAgent : []
  } catch (e) {}

  try {
    const cr = await getConversionRate()
    conversionRate.value = cr || {}
  } catch (e) {}
}

onMounted(refreshAll)
</script>

<style scoped>
.stats-page { width: 100%; height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.page-header { padding: 20px 24px 16px; background: var(--color-bg-2); border-bottom: 1px solid var(--color-border); display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; }
.page-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-text-2); }
.header-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.range-group { display: flex; gap: 8px; align-items: center; }
.custom-range { display: inline-block; }

.page-body { flex: 1; overflow: auto; padding: 20px 24px; display: flex; flex-direction: column; gap: 20px; }

.cards-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; }
.stat-card { background: var(--color-bg-2); border: 1px solid var(--color-border); border-radius: 12px; padding: 18px 20px; display: flex; align-items: center; gap: 16px; position: relative; overflow: hidden; }
.stat-card::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; }
.card-primary::before { background: var(--color-primary); }
.card-pink::before { background: var(--color-pink); }
.card-green::before { background: var(--color-green); }
.card-yellow::before { background: var(--color-yellow); }
.card-purple::before { background: #9b59b6; }

.stat-icon { width: 48px; height: 48px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 24px; color: #fff; flex-shrink: 0; }
.card-primary .stat-icon { background: linear-gradient(135deg, #5865f2, #7782f5); }
.card-pink .stat-icon { background: linear-gradient(135deg, #eb459e, #f377ba); }
.card-green .stat-icon { background: linear-gradient(135deg, #23a559, #4fc97c); }
.card-yellow .stat-icon { background: linear-gradient(135deg, #f0b232, #f5cc6a); }
.card-purple .stat-icon { background: linear-gradient(135deg, #8e44ad, #bb6bd9); }

.stat-num { font-size: 28px; font-weight: 800; color: var(--color-text); line-height: 1.1; }
.stat-label { font-size: 12px; color: var(--color-text-2); margin-top: 4px; }

.panel { background: var(--color-bg-2); border: 1px solid var(--color-border); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; }
.panel-head { padding: 14px 18px; border-bottom: 1px solid var(--color-border); }
.panel-title { margin: 0; font-size: 14px; font-weight: 600; color: var(--color-text); }
.panel-body { flex: 1; padding: 16px; min-height: 0; overflow: auto; }

/* Trend chart */
.trend-chart { display: flex; gap: 10px; height: 260px; }
.trend-y { display: flex; flex-direction: column; justify-content: space-between; font-size: 11px; color: var(--color-text-3); padding-right: 6px; }
.trend-content { flex: 1; display: flex; flex-direction: column; }
.trend-bars { flex: 1; display: flex; align-items: flex-end; gap: 8px; }
.trend-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend-bar-wrap { flex: 1; display: flex; align-items: flex-end; gap: 3px; width: 100%; justify-content: center; }
.trend-bar { width: 40%; border-radius: 3px 3px 0 0; position: relative; min-height: 2px; transition: all 0.3s; }
.trend-bar.bar-msg { background: linear-gradient(180deg, #5865f2, #7782f5); }
.trend-bar.bar-conv { background: linear-gradient(180deg, #eb459e, #f377ba); }
.trend-bar:hover { filter: brightness(1.2); }
.bar-val { position: absolute; top: -14px; left: 50%; transform: translateX(-50%); font-size: 10px; color: var(--color-text-2); white-space: nowrap; }
.trend-date { margin-top: 6px; font-size: 10px; color: var(--color-text-3); }
.trend-legend { display: flex; gap: 16px; justify-content: center; padding-top: 8px; font-size: 11px; color: var(--color-text-2); }
.lg-item { display: flex; align-items: center; gap: 5px; }
.lg-dot { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
.lg-dot.lg-msg { background: #5865f2; }
.lg-dot.lg-conv { background: #eb459e; }

/* 漏斗图 */
.funnel-chart { display: flex; flex-direction: column; gap: 8px; padding: 10px 0; align-items: center; }
.funnel-bar { position: relative; display: flex; align-items: center; justify-content: center; height: 42px; min-width: 30%; transition: all 0.3s ease; }
.funnel-bar-fill { position: absolute; inset: 0; border-radius: 8px; opacity: 0.85; z-index: 0; }
.funnel-label { position: relative; z-index: 1; display: flex; align-items: center; gap: 10px; color: #fff; font-size: 13px; font-weight: 600; text-shadow: 0 1px 2px rgba(0,0,0,0.4); }
.funnel-label .stage-count { background: rgba(0,0,0,0.3); padding: 2px 8px; border-radius: 10px; font-size: 11px; }
.funnel-label .stage-label-text { min-width: 72px; text-align: center; }

.content-row { display: grid; grid-template-columns: 1fr 1.2fr; gap: 16px; }

.cust-cell { display: flex; align-items: center; gap: 8px; }
.cust-meta { min-width: 0; }
.cust-name { font-size: 13px; font-weight: 600; color: var(--color-text); }
.cust-sub { font-size: 11px; color: var(--color-text-3); font-family: "JetBrains Mono", monospace; margin-top: 2px; }

@media (max-width: 1100px) {
  .content-row { grid-template-columns: 1fr; }
}

/* ==== 转化率 ==== */
.conversion-summary { display: flex; gap: 30px; }
.conversion-total { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 20px 30px; background: linear-gradient(135deg, var(--color-primary), var(--color-pink)); border-radius: 12px; color: #fff; min-width: 140px; }
.conversion-total .total-num { font-size: 40px; font-weight: 800; line-height: 1; }
.conversion-total .total-label { font-size: 13px; opacity: 0.9; margin-top: 6px; }
.conversion-stages { flex: 1; display: flex; flex-direction: column; gap: 8px; }
.conv-stage-row { display: grid; grid-template-columns: 80px 1fr 60px 70px; gap: 10px; align-items: center; }
.conv-stage-name { font-size: 12px; font-weight: 600; }
.conv-stage-bar { height: 8px; background: var(--color-bg-3); border-radius: 4px; overflow: hidden; }
.conv-stage-bar-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; }
.conv-stage-count { font-size: 12px; color: var(--color-text-2); text-align: right; }
.conv-stage-rate { font-size: 13px; font-weight: 700; color: var(--color-text); text-align: right; }
</style>
