<template>
  <div class="stats-page">
    <!-- 筛选条件区 -->
    <div class="filter-section">
      <div class="filter-header">
        <el-icon class="filter-icon"><Filter /></el-icon>
        <span class="filter-title">筛选条件</span>
        <span class="filter-range" v-if="dateLabel">{{ dateLabel }}</span>
      </div>
      <div class="filter-row">
        <div class="filter-group">
          <span class="filter-label">日期范围</span>
          <div class="date-presets">
            <el-button
              v-for="p in datePresets"
              :key="p.value"
              :class="['preset-btn', { active: filters.datePreset === p.value }]"
              size="small"
              @click="selectPreset(p.value)"
            >{{ p.label }}</el-button>
          </div>
          <div class="custom-date">
            <el-date-picker
              v-model="filters.dateRange"
              type="daterange"
              size="small"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              @change="onCustomDateChange"
            />
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">账号来源</span>
          <el-select
            v-model="filters.accountIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择账号"
            size="default"
            style="min-width: 180px"
            @change="applyFilters"
          >
            <el-option
              v-for="a in filterData.accounts"
              :key="a.id"
              :label="a.label"
              :value="a.id"
            />
          </el-select>
        </div>
        <div class="filter-group">
          <span class="filter-label">销售人员</span>
          <el-select
            v-model="filters.agentIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择销售人员"
            size="default"
            style="min-width: 180px"
            @change="applyFilters"
          >
            <el-option
              v-for="a in filterData.agents"
              :key="a.id"
              :label="a.label"
              :value="a.id"
            />
          </el-select>
        </div>
      </div>
    </div>

    <!-- 统计内容区 -->
    <div class="stats-section" v-loading="loading">
      <!-- 漏斗状态 -->
      <div class="stat-group">
        <div class="stat-group-title">漏斗状态</div>
        <div class="stat-grid funnel-grid">
          <div class="stat-card funnel-card prospect">
            <div class="stat-num">{{ formatNum(stats.funnel?.prospect) }}</div>
            <div class="stat-label">通过客户</div>
          </div>
          <div class="stat-card funnel-card new">
            <div class="stat-num">{{ formatNum(stats.funnel?.new) }}</div>
            <div class="stat-label">回复客户</div>
          </div>
          <div class="stat-card funnel-card converted">
            <div class="stat-num">{{ formatNum(stats.funnel?.converted) }}</div>
            <div class="stat-label">注册客户</div>
          </div>
        </div>
      </div>

      <!-- 非漏斗状态 -->
      <div class="stat-group">
        <div class="stat-group-title">非漏斗状态</div>
        <div class="stat-grid non-funnel-grid">
          <div class="stat-card non-funnel-card churned">
            <div class="stat-num">{{ formatNum(stats.nonFunnel?.churned) }}</div>
            <div class="stat-label">流失客户</div>
          </div>
          <div class="stat-card non-funnel-card archived">
            <div class="stat-num">{{ formatNum(stats.nonFunnel?.archived) }}</div>
            <div class="stat-label">归档客户</div>
          </div>
        </div>
      </div>

      <!-- 互动指标 -->
      <div class="stat-group">
        <div class="stat-group-title">互动指标</div>
        <div class="stat-grid interaction-grid">
          <div class="stat-card interaction-card">
            <div class="stat-num">{{ formatNum(stats.interaction?.activeCustomers) }}</div>
            <div class="stat-label">日均活跃客户</div>
          </div>
          <div class="stat-card interaction-card">
            <div class="stat-num">{{ formatNum(stats.interaction?.visitedCustomers) }}</div>
            <div class="stat-label">拜访客户数</div>
          </div>
          <div class="stat-card interaction-card">
            <div class="stat-num">{{ formatNum(stats.interaction?.sentCount) }}</div>
            <div class="stat-label">发送句数</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 趋势分析卡片 -->
    <div class="chart-card">
      <div class="chart-card-header">
        <h3 class="chart-card-title">趋势分析</h3>
      </div>
      <div class="chart-card-body">
        <div class="chart-block">
          <div class="chart-block-title">获客趋势</div>
          <v-chart
            v-if="trends.acquisitionTrend?.length"
            class="chart-box"
            :option="acquisitionChartOption"
            autoresize
          />
          <el-empty v-else description="暂无数据" :image-size="60" />
        </div>
        <div class="chart-block">
          <div class="chart-block-title">转化漏斗</div>
          <v-chart
            v-if="trends.conversionTrend?.length"
            class="chart-box"
            :option="conversionChartOption"
            autoresize
          />
          <el-empty v-else description="暂无数据" :image-size="60" />
        </div>
        <div class="chart-block">
          <div class="chart-block-title">风险状态</div>
          <v-chart
            v-if="trends.riskTrend?.length"
            class="chart-box"
            :option="riskChartOption"
            autoresize
          />
          <el-empty v-else description="暂无数据" :image-size="60" />
        </div>
      </div>
    </div>

    <!-- 活跃客户趋势卡片 -->
    <div class="chart-card">
      <div class="chart-card-header">
        <div>
          <h3 class="chart-card-title">活跃客户趋势</h3>
          <span class="chart-subtitle">口径：客户当天发送消息数 ≥ 3 即计为活跃客户</span>
        </div>
      </div>
      <div class="chart-card-body">
        <v-chart
          v-if="trends.activeCustomerTrend?.length"
          class="chart-box chart-box--lg"
          :option="activeCustomerChartOption"
          autoresize
        />
        <el-empty v-else description="暂无数据" :image-size="60" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { Filter } from '@element-plus/icons-vue'
import { getStatsDashboard, getStatsDashboardTrends, getStatsDashboardFilters } from '@/api'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import {
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DataZoomComponent
} from 'echarts/components'

use([
  CanvasRenderer,
  LineChart,
  BarChart,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DataZoomComponent
])

const datePresets = [
  { label: '全部', value: 'all' },
  { label: '今天', value: 'today' },
  { label: '本周', value: 'week' },
  { label: '本月', value: 'month' },
  { label: '上月', value: 'lastmonth' },
  { label: '最近7天', value: '7d' },
  { label: '最近30天', value: '30d' },
]

const loading = ref(false)
const filterData = reactive({ accounts: [], agents: [] })
const stats = ref({ funnel: {}, nonFunnel: {}, interaction: {} })
const trends = reactive({
  acquisitionTrend: [],
  conversionTrend: [],
  riskTrend: [],
  activeCustomerTrend: []
})

const filters = reactive({
  datePreset: '30d',
  dateRange: [],
  accountIds: [],
  agentIds: []
})

const dateLabel = computed(() => {
  if (filters.datePreset === 'custom' && filters.dateRange?.length === 2) {
    return `${filters.dateRange[0]} ~ ${filters.dateRange[1]}`
  }
  const p = datePresets.find(x => x.value === filters.datePreset)
  return p ? p.label : ''
})

function selectPreset(val) {
  filters.datePreset = val
  filters.dateRange = []
  applyFilters()
}

function onCustomDateChange(val) {
  if (val && val.length === 2) {
    filters.datePreset = 'custom'
    applyFilters()
  }
}

function getQueryParams() {
  const params = {}
  if (filters.datePreset === 'custom') {
    if (filters.dateRange?.length === 2) {
      params.dateFrom = filters.dateRange[0]
      params.dateTo = filters.dateRange[1]
    }
  } else {
    params.datePreset = filters.datePreset
  }
  if (filters.accountIds.length) {
    params.accountIds = filters.accountIds.join(',')
  }
  if (filters.agentIds.length) {
    params.agentIds = filters.agentIds.join(',')
  }
  return params
}

let debounceTimer = null
function debouncedApply() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(applyFilters, 300)
}

async function applyFilters() {
  loading.value = true
  try {
    const params = getQueryParams()
    const [dashRes, trendRes] = await Promise.all([
      getStatsDashboard(params),
      getStatsDashboardTrends(params)
    ])
    if (dashRes) {
      stats.value = dashRes
    }
    if (trendRes) {
      trends.acquisitionTrend = trendRes.acquisitionTrend || []
      trends.conversionTrend = trendRes.conversionTrend || []
      trends.riskTrend = trendRes.riskTrend || []
      trends.activeCustomerTrend = trendRes.activeCustomerTrend || []
    }
  } catch (e) {
    ElMessage.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

async function loadFilters() {
  try {
    const res = await getStatsDashboardFilters()
    if (res) {
      filterData.accounts = res.accounts || []
      filterData.agents = res.agents || []
    }
  } catch (e) {}
}

function formatNum(n) {
  if (n == null) return '0'
  return new Intl.NumberFormat().format(n)
}

function getChartColors() {
  const isDark = document.documentElement.classList.contains('dark')
  return {
    textColor: isDark ? '#e0e0e0' : '#333333',
    textColorSecondary: isDark ? '#999999' : '#666666',
    textColorTertiary: isDark ? '#666666' : '#999999',
    gridLine: isDark ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.06)',
    axisLine: isDark ? 'rgba(255,255,255,0.2)' : '#e0e0e0'
  }
}

const acquisitionChartOption = computed(() => {
  if (!trends.acquisitionTrend.length) return null
  const c = getChartColors()
  const dates = trends.acquisitionTrend.map(t => shortDate(t.date))
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 }
    },
    legend: { data: ['回复客户', '通过客户'], top: 0, textStyle: { color: c.textColorSecondary } },
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: c.axisLine } },
      axisTick: { show: false },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.gridLine, type: 'dashed' } },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    series: [
      {
        name: '回复客户',
        type: 'line',
        smooth: true,
        data: trends.acquisitionTrend.map(t => t.new),
        itemStyle: { color: '#5865f2' },
        lineStyle: { width: 2 },
        areaStyle: { color: 'rgba(88,101,242,0.1)' }
      },
      {
        name: '通过客户',
        type: 'line',
        smooth: true,
        data: trends.acquisitionTrend.map(t => t.prospect),
        itemStyle: { color: '#4fc3f7' },
        lineStyle: { width: 2 },
        areaStyle: { color: 'rgba(79,195,247,0.1)' }
      }
    ]
  }
})

const conversionChartOption = computed(() => {
  if (!trends.conversionTrend.length) return null
  const c = getChartColors()
  const dates = trends.conversionTrend.map(t => shortDate(t.date))
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 }
    },
    legend: { data: ['注册客户'], top: 0, textStyle: { color: c.textColorSecondary } },
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: c.axisLine } },
      axisTick: { show: false },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.gridLine, type: 'dashed' } },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    series: [
      {
        name: '注册客户',
        type: 'line',
        smooth: true,
        data: trends.conversionTrend.map(t => t.converted),
        itemStyle: { color: '#23a559' },
        lineStyle: { width: 2 },
        areaStyle: { color: 'rgba(35,165,89,0.15)' }
      }
    ]
  }
})

const riskChartOption = computed(() => {
  if (!trends.riskTrend.length) return null
  const c = getChartColors()
  const dates = trends.riskTrend.map(t => shortDate(t.date))
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 }
    },
    legend: { data: ['归档客户', '流失客户'], top: 0, textStyle: { color: c.textColorSecondary } },
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: c.axisLine } },
      axisTick: { show: false },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.gridLine, type: 'dashed' } },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    series: [
      {
        name: '归档客户',
        type: 'line',
        smooth: true,
        data: trends.riskTrend.map(t => t.archived),
        itemStyle: { color: '#f0b232' },
        lineStyle: { width: 2 },
        areaStyle: { color: 'rgba(240,178,50,0.1)' }
      },
      {
        name: '流失客户',
        type: 'line',
        smooth: true,
        data: trends.riskTrend.map(t => t.churned),
        itemStyle: { color: '#ef5350' },
        lineStyle: { width: 2 },
        areaStyle: { color: 'rgba(239,83,80,0.1)' }
      }
    ]
  }
})

const activeCustomerChartOption = computed(() => {
  if (!trends.activeCustomerTrend.length) return null
  const c = getChartColors()
  const dates = trends.activeCustomerTrend.map(t => shortDate(t.date))
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(31,35,40,0.9)',
      borderColor: 'transparent',
      textStyle: { color: '#fff', fontSize: 12 }
    },
    legend: { data: ['活跃客户数'], top: 0, textStyle: { color: c.textColorSecondary } },
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: c.axisLine } },
      axisTick: { show: false },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.gridLine, type: 'dashed' } },
      axisLabel: { color: c.textColorTertiary, fontSize: 10 }
    },
    series: [
      {
        name: '活跃客户数',
        type: 'bar',
        data: trends.activeCustomerTrend.map(t => t.activeCustomers),
        barWidth: '50%',
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: '#4fc3f7'
        }
      }
    ]
  }
})

function shortDate(d) {
  if (!d) return ''
  const parts = d.split('-')
  return parts.length === 3 ? `${parts[1]}/${parts[2]}` : d
}

onMounted(async () => {
  await loadFilters()
  applyFilters()
})

onActivated(() => {
  refreshCharts()
})

watch(() => document.documentElement.classList.contains('dark'), () => {
  refreshCharts()
})
</script>

<style scoped>
.stats-page {
  padding: 20px;
  background: var(--el-bg-color-page, #f5f7fa);
  min-height: 100%;
  overflow-y: auto;
  height: 100%;
  box-sizing: border-box;
}

.filter-section {
  background: var(--el-bg-color, #fff);
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 20px;
  border: 1px solid var(--el-border-color-lighter, #ebedf0);
}

.filter-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-icon {
  color: #5865f2;
  font-size: 18px;
}

.filter-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary, #1a1a1a);
}

.filter-range {
  color: var(--el-text-color-placeholder, #999);
  font-size: 13px;
  margin-left: auto;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: flex-end;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: var(--el-text-color-regular, #666);
  font-weight: 500;
}

.date-presets {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.preset-btn {
  border: 1px solid var(--el-border-color, #dcdfe6);
  background: var(--el-bg-color, #fff);
  color: var(--el-text-color-regular, #606266);
  border-radius: 4px;
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
}

.preset-btn:hover {
  color: #5865f2;
  border-color: #5865f2;
}

.preset-btn.active {
  background: #5865f2;
  color: #fff;
  border-color: #5865f2;
}

.custom-date {
  margin-top: 8px;
}

.stats-section {
  display: flex;
  flex-direction: column;
  gap: 20px;
  margin-bottom: 20px;
}

.stat-group {
  background: var(--el-bg-color, #fff);
  border-radius: 8px;
  padding: 16px 20px;
  border: 1px solid var(--el-border-color-lighter, #ebedf0);
}

.stat-group-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary, #333);
  margin-bottom: 12px;
  padding-left: 8px;
  border-left: 3px solid #5865f2;
}

.stat-grid {
  display: grid;
  gap: 12px;
}

.funnel-grid { grid-template-columns: repeat(3, 1fr); }
.non-funnel-grid { grid-template-columns: repeat(2, 1fr); }
.interaction-grid { grid-template-columns: repeat(3, 1fr); }

.stat-card {
  background: var(--el-fill-color-lighter, #f8f9fc);
  border-radius: 8px;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--el-border-color-lighter, #ebedf0);
  transition: all 0.2s;
}

.stat-card:hover {
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  transform: translateY(-1px);
}

.stat-num {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-text-color-primary, #1a1a1a);
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-placeholder, #999);
}

.funnel-card.prospect { border-left: 4px solid #5865f2; }
.funnel-card.new { border-left: 4px solid #4fc3f7; }
.funnel-card.converted { border-left: 4px solid #23a559; }
.non-funnel-card.churned { border-left: 4px solid #ef5350; }
.non-funnel-card.archived { border-left: 4px solid #f0b232; }
.interaction-card { border-left: 4px solid #8e44ad; }

.chart-card {
  background: var(--el-bg-color, #fff);
  border-radius: 8px;
  padding: 20px;
  border: 1px solid var(--el-border-color-lighter, #ebedf0);
  margin-bottom: 20px;
}

.chart-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.chart-card-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary, #1a1a1a);
  margin: 0;
}

.chart-subtitle {
  font-size: 12px;
  color: var(--el-text-color-placeholder, #999);
  margin-top: 4px;
  display: block;
}

.chart-card-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.chart-block {
  display: flex;
  flex-direction: column;
}

.chart-block-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary, #333);
  margin-bottom: 12px;
}

.chart-box {
  width: 100%;
  height: 220px;
}

.chart-box--lg {
  height: 280px;
}

@media (max-width: 768px) {
  .filter-row { flex-direction: column; }
  .funnel-grid, .non-funnel-grid, .interaction-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>