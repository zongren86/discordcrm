<template>
  <div class="ai-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">AI 配置</h2>
        <p class="page-desc">每个商户独立的AI配置：模型、API、Prompt设定</p>
      </div>
      <div class="header-actions">
        <el-select
          v-if="isPlatformAdmin"
          v-model="selectedMerchantId"
          placeholder="选择商户"
          filterable
          style="width: 240px; margin-right: 12px;"
          @change="fetchList"
        >
          <el-option
            v-for="m in merchants"
            :key="m.id"
            :label="m.name"
            :value="m.id"
          />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <div class="page-body">
      <div v-if="isPlatformAdmin && !selectedMerchantId" class="empty-state">
        <el-empty description="请先选择一个商户" />
      </div>
      <div v-else class="feature-grid">
        <div v-for="f in features" :key="f.key" class="feature-card">
          <div class="feature-head">
            <div class="feature-title">
              <el-icon class="feature-icon"><component :is="f.icon" /></el-icon>
              <span>{{ f.label }}</span>
            </div>
            <el-switch v-model="f.enabled" @change="toggleEnabled(f)" />
          </div>
          <div class="feature-body">
            <div class="provider-row">
              <el-select v-model="f.provider" placeholder="选择AI提供商" @change="onProviderChange(f)">
                <el-option label="DeepSeek" value="deepseek" />
                <el-option label="阿里云百炼" value="qwen" />
                <el-option label="OpenAI" value="openai" />
                <el-option label="自定义" value="custom" />
              </el-select>
              <el-input v-model="f.model" placeholder="模型名称，如 deepseek-chat" />
            </div>
            <div class="endpoint-row">
              <el-input v-model="f.apiEndpoint" placeholder="API地址（可选）" />
            </div>
            <div class="api-key-row">
              <el-input v-model="f.apiKey" type="password" show-password placeholder="API Key" />
            </div>
            <div class="params-row">
              <el-input-number v-model="f.temperature" :min="0" :max="2" :step="0.1" size="small" />
              <span class="param-label">Temperature</span>
              <el-input-number v-model="f.maxTokens" :min="64" :max="32768" :step="64" size="small" />
              <span class="param-label">MaxTokens</span>
            </div>
            <div class="prompt-row">
              <el-input v-model="f.systemPrompt" type="textarea" :rows="3" :placeholder="f.promptPlaceholder" />
            </div>
            <div class="flags-row">
              <el-checkbox v-model="f.thinking">深度思考</el-checkbox>
              <el-checkbox v-model="f.webSearch">联网搜索</el-checkbox>
            </div>
            <div class="save-row">
              <el-button type="primary" size="small" :loading="f.saving" @click="saveFeature(f)">保存</el-button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, ChatLineSquare, MagicStick, EditPen } from '@element-plus/icons-vue'
import { listAISettings, getAISettingByFeature, saveAISetting, listMerchants } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const merchants = ref([])
const selectedMerchantId = ref(null)

const isPlatformAdmin = computed(() => auth.agent?.role === 'PLATFORM_ADMIN')

const effectiveMerchantId = computed(() => {
  if (isPlatformAdmin.value) return selectedMerchantId.value
  return auth.agent?.merchantId || null
})

const featureDefs = [
  { key: 'translate', label: '翻译（入站英文→中文 / 出站中文→英文）', icon: ChatLineSquare, promptPlaceholder: '你是专业的翻译助手，将用户输入翻译为目标语言，只输出译文。' },
  { key: 'reply_suggest', label: 'AI推荐回复（多语气建议）', icon: MagicStick, promptPlaceholder: '你是专业的客服助手，请根据客户历史消息生成3条不同语气的回复建议（友好/专业/轻松）。' },
  { key: 'summary', label: '会话摘要 / 客户画像', icon: EditPen, promptPlaceholder: '请根据对话历史总结客户关键信息、需求、阶段与下一步建议，输出简洁要点。' }
]

const features = reactive([])

function initFeatures() {
  features.splice(0, features.length)
  for (const d of featureDefs) {
    features.push({
      key: d.key, label: d.label, icon: d.icon,
      promptPlaceholder: d.promptPlaceholder,
      enabled: false, provider: '', model: '', apiEndpoint: '', apiKey: '',
      temperature: 0.7, maxTokens: 1024, systemPrompt: d.promptPlaceholder,
      thinking: false, webSearch: false, id: null, saving: false
    })
  }
}

async function fetchMerchants() {
  try {
    const res = await listMerchants()
    merchants.value = Array.isArray(res) ? res : []
    if (merchants.value.length > 0 && !selectedMerchantId.value) {
      selectedMerchantId.value = merchants.value[0].id
    }
  } catch (e) {}
}

async function fetchList() {
  loading.value = true
  try {
    if (isPlatformAdmin.value && !selectedMerchantId.value) {
      initFeatures()
      return
    }
    initFeatures()
    const list = await listAISettings(effectiveMerchantId.value)
    const map = {}
    for (const s of list) map[s.feature] = s
    for (const f of features) {
      const s = map[f.key]
      if (s) {
        f.id = s.id
        f.enabled = !!s.enabled
        f.provider = s.provider || ''
        f.model = s.model || ''
        f.apiEndpoint = s.apiEndpoint || ''
        f.apiKey = s.apiKey || ''
        f.temperature = s.temperature ?? 0.7
        f.maxTokens = s.maxTokens ?? 1024
        f.systemPrompt = s.systemPrompt || f.promptPlaceholder
        f.thinking = !!s.thinking
        f.webSearch = !!s.webSearch
      }
    }
  } catch (e) {
    initFeatures()
  } finally {
    loading.value = false
  }
}

function toggleEnabled(f) {
  // 不立即保存，让用户手动点击保存
}

function onProviderChange(f) {
  const defaults = {
    deepseek: { endpoint: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
    qwen: { endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
    openai: { endpoint: 'https://api.openai.com/v1', model: 'gpt-4o-mini' }
  }
  const d = defaults[f.provider]
  if (d) {
    if (!f.apiEndpoint) f.apiEndpoint = d.endpoint
    if (!f.model) f.model = d.model
  }
}

async function saveFeature(f) {
  f.saving = true
  try {
    await saveAISetting({
      id: f.id,
      feature: f.key,
      enabled: f.enabled,
      provider: f.provider,
      model: f.model,
      apiEndpoint: f.apiEndpoint,
      apiKey: f.apiKey,
      temperature: f.temperature,
      maxTokens: f.maxTokens,
      systemPrompt: f.systemPrompt,
      thinking: f.thinking,
      webSearch: f.webSearch
    }, effectiveMerchantId.value)
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    f.saving = false
  }
}

onMounted(async () => {
  if (isPlatformAdmin.value) {
    await fetchMerchants()
  }
  await fetchList()
})
</script>

<style scoped>
.ai-page { width:100%; height:100%; display:flex; flex-direction:column; overflow:hidden; }
.page-header { padding:20px 24px 16px; background:var(--color-bg-2); border-bottom:1px solid var(--color-border); display:flex; justify-content:space-between; align-items:center; }
.page-title { margin:0; font-size:18px; font-weight:700; color:var(--color-text); }
.page-desc { margin:4px 0 0; font-size:12px; color:var(--color-text-2); }
.page-body { flex:1; overflow:auto; padding:20px 24px; }

.feature-grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(380px, 1fr)); gap:16px; }
.feature-card { background:var(--color-bg-2); border:1px solid var(--color-border); border-radius:12px; overflow:hidden; }
.feature-head { padding:14px 18px; border-bottom:1px solid var(--color-border); display:flex; justify-content:space-between; align-items:center; }
.feature-title { display:flex; align-items:center; gap:8px; color:var(--color-text); font-size:14px; font-weight:600; }
.feature-icon { color:var(--color-primary); font-size:18px; }
.feature-body { padding:16px; display:flex; flex-direction:column; gap:10px; }

.provider-row, .endpoint-row, .api-key-row { display:grid; grid-template-columns: 1fr 1.3fr; gap:8px; }
.params-row { display:flex; align-items:center; gap:10px; color:var(--color-text-2); font-size:12px; }
.param-label { color:var(--color-text-2); }
.prompt-row :deep(.el-textarea__inner) { min-height: 60px !important; }
.flags-row { display:flex; gap:16px; color:var(--color-text-2); font-size:12px; }
.save-row { display:flex; justify-content:flex-end; margin-top:4px; }
</style>
