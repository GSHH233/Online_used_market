<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { aiApi } from '../../api/modules/ai'
import { useAuthStore } from '../../stores/auth'

type PublishAssistResult = {
  assistId?: string
  traceId?: string
  optimizeResult?: {
    optimizedTitle?: string
    optimizedDescription?: string
    tags?: string[]
    riskWarnings?: string[]
  }
  riskScore?: number
  riskWarnings?: string[]
  priceSuggestion?: string
  canPublish?: boolean
  trace?: Array<{ step?: string; label?: string; status?: string; message?: string }>
  riskEvidence?: Array<{
    ruleCode?: string
    hitText?: string
    sourceField?: string
    riskLevel?: string
    deductScore?: number
    message?: string
  }>
  sources?: Array<{
    docId?: number
    chunkId?: number
    docTitle?: string
    snippet?: string
    sourceType?: string
    sourceTrustLevel?: string
    score?: number
  }>
  retrievals?: Array<{
    round?: number
    stage?: string
    purpose?: string
    query?: string
    sourceType?: string
    requestedTopK?: number
    hitCount?: number
    retrievalMode?: string
    vectorHitCount?: number
    keywordHitCount?: number
    rewriteUsed?: boolean
    rewriteTrigger?: string
    rerankUsed?: boolean
    rerankCandidateCount?: number
    selectedTopK?: number
    dynamicTopKApplied?: boolean
    rewriteElapsedMs?: number
    retrievalElapsedMs?: number
    branchRetrievalElapsedMs?: number
    rerankElapsedMs?: number
    facetCoverage?: number
    evidenceCoverage?: number
    outcome?: string
    fallbackSourceUsed?: boolean
    debugInfo?: string
  }>
}

const form = reactive({
  title: '二手 RTX3060 显卡',
  description: '有跑分截图，温度偏高，无原盒，正常个人自用出售。',
  brand: 'NVIDIA',
  conditionText: '正常使用',
  categoryName: '显卡',
  expectedPrice: 1200,
})

const loading = ref(false)
const error = ref('')
const result = ref<PublishAssistResult | null>(null)
const showRaw = ref(false)
const auth = useAuthStore()
const router = useRouter()
const PRODUCT_DRAFT_KEY = 'ai_publish_product_draft'
const ASSIST_PREFILL_KEY = 'ai_publish_assist_prefill'
const prefillNotice = ref('')
const feedbackNotice = ref('')

const examples = [
  {
    label: '手机无发票',
    title: 'iPhone 14 全新未拆封',
    description: '个人闲置，无发票，盒子还在，想写全新未拆封。',
    brand: 'Apple',
    conditionText: '未拆封',
    categoryName: '手机',
    expectedPrice: 4200,
  },
  {
    label: '显卡跑分',
    title: '二手 RTX3060 显卡',
    description: '有跑分截图，温度偏高，无原盒，正常个人自用出售。',
    brand: 'NVIDIA',
    conditionText: '正常使用',
    categoryName: '显卡',
    expectedPrice: 1200,
  },
  {
    label: '相机快门',
    title: '佳能 R6 机身',
    description: '快门次数约 8 万，有轻微使用痕迹，缺一块备用电池。',
    brand: 'Canon',
    conditionText: '轻微使用痕迹',
    categoryName: '相机',
    expectedPrice: 7800,
  },
]

const optimize = computed(() => result.value?.optimizeResult || {})
const warnings = computed(() => {
  const set = new Set<string>()
  for (const item of result.value?.riskWarnings || []) {
    if (item) set.add(item)
  }
  for (const item of optimize.value.riskWarnings || []) {
    if (item) set.add(item)
  }
  return Array.from(set)
})
const evidence = computed(() => result.value?.riskEvidence || [])
const sources = computed(() => result.value?.sources || [])
const retrievals = computed(() => result.value?.retrievals || [])
const trace = computed(() => result.value?.trace || [])
const tags = computed(() => optimize.value.tags || [])
const decisionText = computed(() => {
  if (!result.value) return '等待生成'
  return result.value.canPublish ? '建议发布' : '建议先修改后发布'
})
const decisionClass = computed(() => (result.value?.canPublish ? 'ok' : 'risk'))

async function run() {
  loading.value = true
  error.value = ''
  showRaw.value = false
  feedbackNotice.value = ''
  try {
    result.value = (await aiApi.publishAssist({ ...form })) as PublishAssistResult
  } catch (e: any) {
    error.value = e?.message || '发布助手执行失败'
  } finally {
    loading.value = false
  }
}

function applyExample(example: (typeof examples)[number]) {
  form.title = example.title
  form.description = example.description
  form.brand = example.brand
  form.conditionText = example.conditionText
  form.categoryName = example.categoryName
  form.expectedPrice = example.expectedPrice
  error.value = ''
}

function conditionLevelFromText(value: string) {
  const text = value.toLowerCase()
  if (text.includes('全新') || text.includes('未拆')) return 10
  if (text.includes('轻微') || text.includes('正常')) return 8
  if (text.includes('明显') || text.includes('瑕疵')) return 6
  return 8
}

async function goPublishWithDraft() {
  const optimizedTitle = optimize.value.optimizedTitle || form.title
  const optimizedDescription = optimize.value.optimizedDescription || form.description
  localStorage.setItem(PRODUCT_DRAFT_KEY, JSON.stringify({
    title: optimizedTitle,
    description: optimizedDescription,
    price: form.expectedPrice ? String(form.expectedPrice) : '',
    conditionLevel: conditionLevelFromText(form.conditionText),
    aiDecision: decisionText.value,
    riskScore: result.value?.riskScore,
    priceSuggestion: result.value?.priceSuggestion || '',
    riskWarnings: warnings.value,
    assistId: result.value?.assistId,
    traceId: result.value?.traceId,
  }))
  if (result.value?.assistId) {
    try {
      await aiApi.publishAssistFeedback(result.value.assistId, {
        adoptionType: 'APPLIED',
        adoptedFields: ['title', 'description'],
        reason: 'user_clicked_go_publish_with_ai_draft',
      })
      feedbackNotice.value = '已记录本次 AI 建议采纳'
    } catch {
      feedbackNotice.value = '文案已带入发布页，但采纳反馈暂未写入'
    }
  }
  router.push('/products/publish')
}

function loadAssistPrefill() {
  const raw = localStorage.getItem(ASSIST_PREFILL_KEY)
  if (!raw) return
  try {
    const draft = JSON.parse(raw)
    form.title = draft.title || form.title
    form.description = draft.description || form.description
    form.categoryName = draft.categoryName || form.categoryName
    form.conditionText = draft.conditionText || form.conditionText
    form.expectedPrice = Number(draft.expectedPrice || form.expectedPrice)
    prefillNotice.value = '已带入发布表单里的商品信息'
    localStorage.removeItem(ASSIST_PREFILL_KEY)
  } catch {
    localStorage.removeItem(ASSIST_PREFILL_KEY)
  }
}

function scoreText(score?: number) {
  if (score == null) return '暂无评分'
  return `${score}/100`
}

function scoreClass(score?: number) {
  if (score == null) return ''
  if (score >= 80) return 'ok'
  if (score >= 60) return 'warn'
  return 'risk'
}

function sourceTitle(source: any) {
  return source?.docTitle || `知识文档 #${source?.docId || '-'}`
}

function sourceMeta(source: any) {
  const parts = [source?.sourceType, source?.sourceTrustLevel, source?.score != null ? `score=${Number(source.score).toFixed(3)}` : '']
  return parts.filter(Boolean).join(' · ')
}

function retrievalModeText(mode?: string) {
  const labels: Record<string, string> = {
    hybrid: '混合检索',
    'vector-only': '向量检索',
    'keyword-only': '关键词检索',
  }
  return labels[mode || ''] || mode || '未知模式'
}

function retrievalStageText(stage?: string) {
  const labels: Record<string, string> = {
    optimize: '文案优化',
    risk: '风险评估',
    pricing: '价格建议',
    guardrail: '入口防护',
  }
  return labels[stage || ''] || stage || '检索'
}

function retrievalOutcomeText(item: NonNullable<PublishAssistResult['retrievals']>[number]) {
  const hitCount = item.hitCount ?? 0
  const vectorHits = item.vectorHitCount ?? 0
  const keywordHits = item.keywordHitCount ?? 0
  const fallbackText = item.fallbackSourceUsed ? '；未命中时已补充内置规则来源' : ''
  return `命中 ${hitCount} 条；向量 ${vectorHits} 条，关键词 ${keywordHits} 条${fallbackText}`
}

function retrievalMeta(item: NonNullable<PublishAssistResult['retrievals']>[number]) {
  const values = [
    item.sourceType ? `过滤：${item.sourceType}` : '',
    item.rewriteUsed ? '已改写' : '未改写',
    item.rerankUsed ? `已重排${item.rerankCandidateCount != null ? `(${item.rerankCandidateCount})` : ''}` : '',
    item.retrievalElapsedMs != null ? `${item.retrievalElapsedMs}ms` : '',
  ]
  return values.filter(Boolean).join(' · ')
}

onMounted(loadAssistPrefill)
</script>

<template>
  <div class="page-shell">
    <section class="hero-card topbar">
      <div>
        <p class="eyebrow">发布前检查</p>
        <h2>先看风险，再决定怎么写</h2>
        <p class="muted">输入商品情况，系统会给出可发布性、风险提醒和更稳妥的标题描述。</p>
        <p v-if="feedbackNotice" class="success-msg">{{ feedbackNotice }}</p>
      </div>
      <button class="primary-btn" :disabled="loading" @click="run">
        {{ loading ? '检查中...' : '检查发布风险' }}
      </button>
    </section>

    <div class="grid two-col">
      <section class="panel">
        <h3>商品信息</h3>
        <p v-if="prefillNotice" class="success-msg">{{ prefillNotice }}</p>
        <div class="example-row" aria-label="常用示例">
          <button
            v-for="item in examples"
            :key="item.label"
            class="quick-chip"
            type="button"
            @click="applyExample(item)"
          >
            {{ item.label }}
          </button>
        </div>
        <label>标题<input v-model="form.title" /></label>
        <label>描述<textarea v-model="form.description" rows="6" /></label>
        <div class="grid two-col compact-grid">
          <label>品牌<input v-model="form.brand" /></label>
          <label>分类<input v-model="form.categoryName" /></label>
        </div>
        <div class="grid two-col compact-grid">
          <label>成色<input v-model="form.conditionText" /></label>
          <label>期望价格<input v-model.number="form.expectedPrice" type="number" min="0" /></label>
        </div>
      </section>

      <section class="panel result-panel">
        <h3>检查结果</h3>
        <p v-if="error" class="error">{{ error }}</p>

        <div v-if="!result && !loading && !error" class="empty-state">
          填写商品信息后，先检查风险再发布。
        </div>

        <div v-if="loading" class="empty-state">
          正在查规则、看风险、整理建议...
        </div>

        <div v-if="result" class="assist-result">
          <div class="decision-row">
            <div>
              <span class="eyebrow">发布结论</span>
              <strong :class="['decision', decisionClass]">{{ decisionText }}</strong>
            </div>
            <div>
              <span class="eyebrow">风险评分</span>
              <strong :class="['score', scoreClass(result.riskScore)]">{{ scoreText(result.riskScore) }}</strong>
            </div>
          </div>

          <section class="result-block">
            <h4>优化标题</h4>
            <p class="answer-text">{{ optimize.optimizedTitle || '暂无标题建议' }}</p>
            <div v-if="tags.length" class="tag-row">
              <span v-for="tag in tags" :key="tag" class="tag">{{ tag }}</span>
            </div>
          </section>

          <section class="result-block">
            <h4>优化描述</h4>
            <p class="answer-text multiline">{{ optimize.optimizedDescription || '暂无描述建议' }}</p>
          </section>

          <section class="result-block">
            <h4>价格建议</h4>
            <p class="answer-text multiline">{{ result.priceSuggestion || '暂无价格建议' }}</p>
          </section>

          <section class="result-block">
            <h4>风险提示</h4>
            <ul v-if="warnings.length" class="clean-list">
              <li v-for="item in warnings" :key="item">{{ item }}</li>
            </ul>
            <p v-else class="muted">暂未发现明确风险提示。</p>
          </section>

          <section class="result-block publish-link-block">
            <div>
              <h4>下一步</h4>
              <p class="muted">确认文案和风险提示后，可以把优化后的标题、描述和价格带到发布表单。</p>
            </div>
            <button class="primary-btn" type="button" @click="goPublishWithDraft">带到发布表单</button>
          </section>

          <section v-if="evidence.length" class="result-block">
            <h4>命中规则</h4>
            <div class="evidence-list">
              <article v-for="item in evidence" :key="`${item.ruleCode}-${item.hitText}`" class="mini-card">
                <div class="mini-card-title">
                  <span>{{ item.ruleCode || '规则' }}</span>
                  <span class="tag">{{ item.riskLevel || 'UNKNOWN' }}</span>
                </div>
                <p>{{ item.message || item.hitText || '命中风险规则' }}</p>
                <small v-if="item.sourceField">字段：{{ item.sourceField }}</small>
              </article>
            </div>
          </section>

          <section v-if="auth.isInternal && retrievals.length" class="result-block">
            <h4>知识检索链路</h4>
            <div class="retrieval-list">
              <article v-for="item in retrievals" :key="`${item.round}-${item.purpose}-${item.query}`" class="retrieval-card">
                <div class="mini-card-title">
                  <strong>{{ retrievalStageText(item.stage) }} · {{ item.purpose || '知识检索' }}</strong>
                  <span class="tag">第{{ item.round || 1 }}轮 · {{ retrievalModeText(item.retrievalMode) }}</span>
                </div>
                <p>{{ retrievalOutcomeText(item) }}</p>
                <small>{{ retrievalMeta(item) }}</small>
                <small v-if="item.query" class="retrieval-query">查询：{{ item.query }}</small>
                <small v-if="item.debugInfo" class="retrieval-debug">{{ item.debugInfo }}</small>
              </article>
            </div>
          </section>

          <section v-if="sources.length" class="result-block">
            <h4>参考知识</h4>
            <div class="source-list">
              <article v-for="source in sources" :key="`${source.docId}-${source.chunkId}`" class="source-card">
                <strong>{{ sourceTitle(source) }}</strong>
                <p>{{ source.snippet || '暂无片段' }}</p>
                <small v-if="auth.isInternal">{{ sourceMeta(source) }}</small>
              </article>
            </div>
          </section>

          <section v-if="auth.isInternal && trace.length" class="result-block">
            <h4>处理流程</h4>
            <div class="trace-list">
              <span v-for="item in trace" :key="`${item.step}-${item.label}`" class="trace-step">
                {{ item.label || item.step }} · {{ item.status || 'UNKNOWN' }}
              </span>
            </div>
          </section>

          <button v-if="auth.isInternal" class="ghost-btn" type="button" @click="showRaw = !showRaw">
            {{ showRaw ? '隐藏原始响应' : '查看原始响应' }}
          </button>
          <pre v-if="auth.isInternal && showRaw" class="raw-json">{{ result }}</pre>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.compact-grid {
  gap: 12px;
}

.example-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 12px;
}

.quick-chip {
  background: #fffdf8;
  border: 1px solid rgba(143, 47, 28, 0.22);
  border-radius: 999px;
  color: var(--ink-700);
  font-size: 13px;
  padding: 8px 12px;
}

.quick-chip:hover {
  background: rgba(143, 47, 28, 0.92);
  border-color: var(--accent);
  color: #fff;
}

.result-panel {
  min-height: 520px;
}

.empty-state {
  border: 1px dashed rgba(17, 24, 39, 0.2);
  border-radius: 8px;
  color: #6b7280;
  padding: 24px;
  text-align: center;
}

.assist-result {
  display: grid;
  gap: 16px;
}

.decision-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.decision-row > div,
.result-block {
  border: 1px solid rgba(17, 24, 39, 0.12);
  border-radius: 8px;
  padding: 14px;
}

.decision,
.score {
  display: block;
  font-size: 22px;
  line-height: 1.25;
  margin-top: 4px;
}

.ok {
  color: #047857;
}

.warn {
  color: #b45309;
}

.risk {
  color: #b91c1c;
}

.result-block h4 {
  margin: 0 0 10px;
}

.publish-link-block {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.publish-link-block p {
  margin: 0;
}

.answer-text {
  color: #1f2937;
  line-height: 1.7;
  margin: 0;
}

.multiline {
  white-space: pre-wrap;
}

.tag-row,
.trace-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.tag,
.trace-step {
  background: #f3f4f6;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 999px;
  color: #374151;
  font-size: 12px;
  padding: 4px 8px;
}

.clean-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 18px;
}

.evidence-list,
.source-list,
.retrieval-list {
  display: grid;
  gap: 10px;
}

.mini-card,
.source-card,
.retrieval-card {
  background: #fafafa;
  border: 1px solid rgba(17, 24, 39, 0.1);
  border-radius: 8px;
  padding: 12px;
}

.mini-card-title {
  align-items: center;
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.mini-card p,
.source-card p {
  color: #374151;
  line-height: 1.6;
  margin: 8px 0;
}

.mini-card small,
.source-card small {
  color: #6b7280;
}

.retrieval-card p {
  color: #374151;
  line-height: 1.6;
  margin: 8px 0 4px;
}

.retrieval-query,
.retrieval-debug {
  display: block;
  line-height: 1.5;
  margin-top: 6px;
  overflow-wrap: anywhere;
}

.ghost-btn {
  background: #fff;
  border: 1px solid rgba(17, 24, 39, 0.2);
  border-radius: 8px;
  cursor: pointer;
  padding: 10px 14px;
}

.raw-json {
  max-height: 260px;
}

@media (max-width: 820px) {
  .decision-row {
    grid-template-columns: 1fr;
  }

  .publish-link-block {
    align-items: stretch;
    display: grid;
  }
}
</style>
