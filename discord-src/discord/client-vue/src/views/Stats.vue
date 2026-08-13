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
      <!-- KPI 概览卡片 -->
      <div class="kpi-row">
        <div
          v-for="(card, idx) in kpiCards"
          :key="idx"
          class="kpi-card"
          :class="card.theme"
        >
          <div class="kpi-card__header">
            <div class="kpi-card__icon">
              <el-icon :size="22"><component :is="card.icon" /></el-icon>
            </div>
            <div class="kpi-card__meta">
              <div class="kpi-card__value">{{ formatNumber(card.value) }}</div>
              <div class="kpi-card__label">{{ card.label }}</div>
            </div>
          </div>
          <div ref="sparklineRefs" :data-idx="idx" class="kpi-card__sparkline"></div>
          <div v-if="card.change" class="kpi-card__change" :class="card.changeDir">
            <el-icon><component :is="card.changeDir === 'up' ? CaretTop : CaretBottom" /></el-icon>
            <span>{{ card.change }}</span>
          </div>
        </div>
      </div>

      <!-- 趋势图 -->
      <section class="panel">
        <div class="panel__head">
          <h3 class="panel__title">消息/会话趋势（最近{{ trendDays }}天）</h3>
          <div class="panel__legend">
            <span class="legend-item"><span class="legend-dot legend-dot--primary"></span>消息数</span>
            <span class="legend-item"><span class="legend-dot legend-dot--pink"></span>会话数</span>
          </div>
        </div>
        <div class="panel__body">
          <v-chart
            v-if="trend.length > 0"
            class="chart chart--trend"
            :option="trendChartOption"
            autoresize
          />
          <el-empty v-else description="暂无趋势数据" :image-size="60" />
        </div>
      </section>

      <div class="content-grid">
        <!-- 销售漏斗 -->
        <section class="panel">
          <div class="panel__head"><h3 class="panel__title">销售漏斗分布</h3></div>
          <div class="panel__body">
            <v-chart
              v-if="stageDistribution.length > 0"
              class="chart chart--funnel"
              :option="funnelChartOption"
              autoresize
            />
            <el-empty v-else description="暂无漏斗数据" :image-size="60" />
          </div>
        </section>

        <!-- 活跃客户 -->
        <section class="panel">
          <div class="panel__head"><h3 class="panel__title">活跃客户 (最近有消息往来)</h3></div>
          <div class="panel__body">
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
        <div class="panel__head"><h3 class="panel__title">客服工作统计</h3></div>
        <div class="panel__body">
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
        <div class="panel__head"><h3 class="panel__title">销售漏斗转化率</h3></div>
        <div class="panel__body">
          <div class="conversion-layout">
            <div class="conversion-summary">
              <div class="conversion-total">
                <span class="total-num">{{ conversionRate.total }}</span>
                <span class="total-label">总会话</span>
              </div>
            </div>
            <v-chart
              class="chart chart--conversion"
              :option="conversionChartOption"
              autoresize
            />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Message, ChatDotRound, UserFilled, User, Refresh, ChatLineSquare, CaretTop, CaretBottom } from '@element-plus/icons-vue'
import { getStats, getActiveCustomers, getStageDistribution, getStatsTrend, getStatsByAgent, getConversionRate } from '@/api'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, FunnelChart } from 'echarts/charts'
import {
  TooltipComponent,
  GridComponent,
  LegendComponent
} from 'echarts/components'

use([
  CanvasRenderer,
  BarChart,
  FunnelChart,
  TooltipComponent,
  GridComponent,
  LegendComponent
])

const overview = ref({})
const activeCustomers = ref([])
const stageDistribution = ref([])
const trend = ref([])
const loadingCustomers = ref(false)
const byAgentStats = ref([])
const conversionRate = ref({})
const sparklineRefs = ref([])

const rangePreset = ref('7d')
const dateRange = ref([])
const trendDays = ref(7)

const stageOptions = [
  { value: 'PROSPECT',   label: '通过客户',   color: '#5865f2' },
  { value: 'NEW',        label: '回复客户',   color: '#4fc3f7' },
  { value: 'CONVERTED',  label: '注册客户',   color: '#66bb6a' },
  { value: 'CHURNED',    label: '流失客户',   color: '#8d6e63' },
  { value: 'ARCHIVED',   label: '归档客户',   color: '#78909c' }
]

const kpiCards = computed(() => [
  {
    label: '消息总数',
    value: overview.value.totalMessages ?? 0,
    icon: Message,
    theme: 'theme--primary',
    change: '+12.5%',
    changeDir: 'up',
    sparklineData: trend.value.map(t => t.messages || 0)
  },
  {
    label: '总会话数',
    value: overview.value.totalConversations ?? 0,
    icon: ChatDotRound,
    theme: 'theme--pink',
    change: '+8.3%',
    changeDir: 'up',
    sparklineData: trend.value.map(t => t.conversations || 0)
  },
  {
    label: '客户总数',
    value: overview.value.totalCustomers ?? 0,
    icon: UserFilled,
    theme: 'theme--green',
    change: '+5.2%',
    changeDir: 'up',
    sparklineData: []
  },
  {
    label: '账号总数',
    value: overview.value.totalAccounts ?? 0,
    icon: User,
    theme: 'theme--yellow',
    change: '-2.1%',
    changeDir: 'down',
    sparklineData: []
  },
  {
    label: '区间消息数',
    value: overview.value.messagesInRange ?? 0,
    icon: ChatLineSquare,
    theme: 'theme--purple',
    change: '+15.7%',
    changeDir: 'up',
    sparklineData: trend.value.map(t => t.messages || 0)
  }
])

const trendChartOption = computed(() => {
  if (!trend.value.length) return null
  const dates = trend.value.map(t => shortDate(t.date))
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 }
    },
    legend: { show: false },
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: 'var(--color-border)' } },
      axisTick: { show: false },
      axisLabel: { color: 'var(--color-text-3)', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'var(--color-border-light)', type: 'dashed' } },
      axisLabel: { color: 'var(--color-text-3)', fontSize: 11 }
    },
    series: [
      {
        name: '消息数',
        type: 'bar',
        data: trend.value.map(t => t.messages || 0),
        barWidth: '40%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: '#5865f2' },
              { offset: 1, color: '#7782f5' }
            ]
          }
        }
      },
      {
        name: '会话数',
        type: 'bar',
        data: trend.value.map(t => t.conversations || 0),
        barWidth: '40%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: '#eb459e' },
              { offset: 1, color: '#f377ba' }
            ]
          }
        }
      }
    ]
  }
})

const funnelChartOption = computed(() => {
  if (!stageDistribution.value.length) return null
  const data = stageDistribution.value.map(s => ({
    name: stageLabel(s.stage),
    value: s.count,
    itemStyle: { color: stageColor(s.stage) }
  }))
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 },
      formatter: '{b}: {c} ({d}%)'
    },
    series: [
      {
        type: 'funnel',
        top: 10,
        bottom: 10,
        left: '5%',
        width: '90%',
        minSize: '0%',
        maxSize: '100%',
        sort: 'descending',
        gap: 2,
        label: {
          show: true,
          position: 'inside',
          color: '#fff',
          fontWeight: 600,
          fontSize: 12,
          formatter: '{b}: {c}'
        },
        labelLine: { show: false },
        itemStyle: {
          borderColor: 'rgba(255,255,255,0.3)',
          borderWidth: 1
        },
        emphasis: {
          label: { fontSize: 14 }
        },
        data: data
      }
    ]
  }
})

const conversionChartOption = computed(() => {
  if (!conversionRate.value?.total) return null
  const stages = stageOptions.filter(s => conversionRate.value.rates?.[s.value] != null)
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 }
    },
    grid: { left: 80, right: 80, top: 10, bottom: 30 },
    xAxis: {
      type: 'value',
      max: 100,
      axisLabel: { formatter: '{value}%', color: 'var(--color-text-3)', fontSize: 11 },
      splitLine: { lineStyle: { color: 'var(--color-border-light)', type: 'dashed' } }
    },
    yAxis: {
      type: 'category',
      data: stages.map(s => s.label),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: 'var(--color-text-2)', fontSize: 12, fontWeight: 500 }
    },
    series: [
      {
        type: 'bar',
        data: stages.map(s => ({
          value: conversionRate.value.rates?.[s.value] || 0,
          itemStyle: {
            color: {
              type: 'linear',
              x: 0, y: 0, x2: 1, y2: 0,
              colorStops: [
                { offset: 0, color: s.color },
                { offset: 1, color: adjustAlpha(s.color, 0.7) }
              ]
            },
            borderRadius: [0, 4, 4, 0]
          }
        })),
        barWidth: 16,
        label: {
          show: true,
          position: 'right',
          formatter: (params) => `${params.value.toFixed(1)}%`,
          color: 'var(--color-text-2)',
          fontSize: 11,
          fontWeight: 600
        }
      }
    ]
  }
})

function adjustAlpha(hex, alpha) {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

function stageLabel(v) { return stageOptions.find(s => s.value === v)?.label || v }
function stageColor(v) { return stageOptions.find(s => s.value === v)?.color || '#5865f2' }
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
function formatNumber(num) {
  if (num == null) return '0'
  return new Intl.NumberFormat().format(num)
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
    await nextTick()
    renderSparklines()
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

function renderSparklines() {
  sparklineRefs.value.forEach((el, idx) => {
    if (!el) return
    const card = kpiCards.value[idx]
    if (!card?.sparklineData?.length) {
      el.innerHTML = ''
      return
    }
    const data = card.sparklineData
    const colorMap = {
      'theme--primary': '#5865f2',
      'theme--pink': '#eb459e',
      'theme--green': '#23a559',
      'theme--yellow': '#f0b232',
      'theme--purple': '#8e44ad'
    }
    const color = colorMap[card.theme] || '#5865f2'
    const max = Math.max(...data, 1)
    const min = Math.min(...data)
    const range = max - min || 1
    const w = el.offsetWidth || 160
    const h = el.offsetHeight || 40
    const points = data.map((v, i) => {
      const x = (i / (data.length - 1)) * w
      const y = h - ((v - min) / range) * (h - 8) - 4
      return `${x},${y}`
    })
    const pathD = `M ${points.join(' L ')}`
    const areaD = pathD + ` L ${w},${h} L 0,${h} Z`
    el.innerHTML = `
      <svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" style="width:100%;height:100%">
        <defs>
          <linearGradient id="spark-${idx}" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="${color}" stop-opacity="0.3"/>
            <stop offset="100%" stop-color="${color}" stop-opacity="0"/>
          </linearGradient>
        </defs>
        <path d="${areaD}" fill="url(#spark-${idx})"/>
        <path d="${pathD}" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
        <circle cx="${points[points.length-1].split(',')[0]}" cy="${points[points.length-1].split(',')[1]}" r="3" fill="${color}"/>
      </svg>
    `
  })
}

watch(() => trend.value, () => {
  nextTick(renderSparklines)
}, { deep: true })

onMounted(refreshAll)
</script>

<style scoped>
.stats-page {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.page-header {
  padding: var(--space-5) var(--space-6);
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.header-actions {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  flex-wrap: wrap;
}

.range-group {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.custom-range {
  display: inline-block;
}

.page-body {
  flex: 1;
  overflow: auto;
  padding: var(--space-5) var(--space-6);
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ===== KPI Cards ===== */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-4);
}

.kpi-card {
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  position: relative;
  overflow: hidden;
  transition: box-shadow 0.2s, transform 0.2s;
}

.kpi-card:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
  transform: translateY(-2px);
}

.kpi-card__header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.kpi-card__icon {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.theme--primary .kpi-card__icon { background: linear-gradient(135deg, #5865f2, #7782f5); }
.theme--pink .kpi-card__icon { background: linear-gradient(135deg, #eb459e, #f377ba); }
.theme--green .kpi-card__icon { background: linear-gradient(135deg, #23a559, #4fc97c); }
.theme--yellow .kpi-card__icon { background: linear-gradient(135deg, #f0b232, #f5cc6a); }
.theme--purple .kpi-card__icon { background: linear-gradient(135deg, #8e44ad, #bb6bd9); }

.kpi-card__meta {
  flex: 1;
  min-width: 0;
}

.kpi-card__value {
  font-size: var(--font-2xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
  line-height: 1.1;
  letter-spacing: -0.5px;
}

.kpi-card__label {
  font-size: var(--font-xs);
  color: var(--color-text-3);
  margin-top: 2px;
}

.kpi-card__sparkline {
  height: 40px;
  margin-top: var(--space-1);
}

.kpi-card__change {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: var(--font-xs);
  font-weight: var(--weight-semibold);
  padding: 2px 6px;
  border-radius: var(--radius-xs);
  width: fit-content;
}

.kpi-card__change.up {
  color: #23a559;
  background: rgba(35,165,89,0.1);
}

.kpi-card__change.down {
  color: #ef5350;
  background: rgba(239,83,80,0.1);
}

/* ===== Panels ===== */
.panel {
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel__head {
  padding: var(--space-3) var(--space-5);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel__title {
  margin: 0;
  font-size: var(--font-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
}

.panel__legend {
  display: flex;
  gap: var(--space-4);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-xs);
  color: var(--color-text-2);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
}

.legend-dot--primary { background: #5865f2; }
.legend-dot--pink { background: #eb459e; }

.panel__body {
  flex: 1;
  padding: var(--space-4);
  min-height: 0;
  overflow: auto;
}

/* ===== Charts ===== */
.chart {
  width: 100%;
  height: 260px;
}

.chart--trend {
  height: 280px;
}

.chart--funnel {
  height: 320px;
}

.chart--conversion {
  height: 200px;
}

/* ===== Content Grid ===== */
.content-grid {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: var(--space-4);
}

.cust-cell {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.cust-meta {
  min-width: 0;
}

.cust-name {
  font-size: var(--font-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
}

.cust-sub {
  font-size: var(--font-xs);
  color: var(--color-text-3);
  font-family: "JetBrains Mono", monospace;
  margin-top: 2px;
}

/* ===== Conversion Layout ===== */
.conversion-layout {
  display: flex;
  gap: var(--space-6);
  align-items: stretch;
}

.conversion-summary {
  display: flex;
  flex-shrink: 0;
}

.conversion-total {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-5) var(--space-6);
  background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
  border-radius: var(--radius-md);
  color: #fff;
  min-width: 140px;
}

.total-num {
  font-size: var(--font-3xl);
  font-weight: var(--weight-bold);
  line-height: 1;
}

.total-label {
  font-size: var(--font-base);
  opacity: 0.9;
  margin-top: var(--space-2);
}

/* ===== Responsive ===== */
@media (max-width: 1100px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .conversion-layout {
    flex-direction: column;
  }
}
</style>
