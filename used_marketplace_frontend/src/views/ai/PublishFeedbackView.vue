<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { BarChart3, ClipboardList, RefreshCw, Search } from 'lucide-vue-next'
import { aiApi } from '../../api/modules/ai'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const metrics = ref<any>(null)
const records = ref<any[]>([])
const record = ref<any>(null)
const metricsLoading = ref(false)
const listLoading = ref(false)
const recordLoading = ref(false)
const outcomeLoading = ref(false)
const error = ref('')
const success = ref('')

const query = reactive({
  assistId: '',
})

const filters = reactive({
  userId: '',
  productId: '',
  riskLevel: '',
  adoptionType: '',
  from: '',
  to: '',
  limit: 20,
})

const outcome = reactive({
  productId: '',
  outcomeType: 'PASSED',
  outcomeSource: 'MANUAL_REVIEW',
  reasonCode: '',
  reason: '',
  isFinal: false,
})

const statItems = computed(() => [
  { label: '生成次数', value: metrics.value?.generatedCount ?? '-' },
  { label: '反馈次数', value: metrics.value?.feedbackCount ?? '-' },
  { label: '建议采纳率', value: formatRate(metrics.value?.adoptionRate) },
  { label: '完整采纳率', value: formatRate(metrics.value?.fullAdoptionRate) },
  { label: '风险命中率', value: formatRate(metrics.value?.riskPrecision) },
  { label: '误报率', value: formatRate(metrics.value?.falsePositiveRate) },
])

const requestPreview = computed(() => formatPreview(record.value?.requestJson))
const suggestionPreview = computed(() => formatPreview(record.value?.suggestionJson))
const evidencePreview = computed(() => formatPreview(record.value?.evidenceJson))
const selectedId = computed(() => record.value?.assistId || '')

function formatRate(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return `${Math.round(Number(value) * 100)}%`
}

function formatTime(value: unknown) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

function shortId(value: unknown) {
  const text = String(value || '')
  if (text.length <= 18) return text || '-'
  return `${text.slice(0, 10)}...${text.slice(-6)}`
}

function adoptionText(value: unknown) {
  const text = String(value || '').toUpperCase()
  if (text === 'APPLIED') return '完整采纳'
  if (text === 'PARTIAL') return '部分采纳'
  if (text === 'IGNORED') return '未采纳'
  return '未反馈'
}

function riskText(value: unknown) {
  const text = String(value || '').toUpperCase()
  if (text === 'HIGH') return '高风险'
  if (text === 'MEDIUM') return '中风险'
  if (text === 'LOW') return '低风险'
  return '未知'
}

function riskClass(value: unknown) {
  const text = String(value || '').toUpperCase()
  if (text === 'HIGH') return 'risk-high'
  if (text === 'MEDIUM') return 'risk-medium'
  if (text === 'LOW') return 'risk-low'
  return 'risk-empty'
}

function tryParse(value: unknown) {
  if (!value) return null
  if (typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}

function formatPreview(value: unknown) {
  const parsed = tryParse(value)
  if (!parsed) return '暂无'
  return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2)
}

function cleanParams() {
  return Object.entries(filters).reduce<Record<string, string | number>>((acc, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') acc[key] = value
    return acc
  }, {})
}

async function loadMetrics() {
  metricsLoading.value = true
  error.value = ''
  try {
    metrics.value = await aiApi.publishAssistMetrics()
  } catch (e: any) {
    error.value = e?.message || '加载发布反馈指标失败'
  } finally {
    metricsLoading.value = false
  }
}

async function loadRecords() {
  listLoading.value = true
  error.value = ''
  try {
    const data = await aiApi.publishAssistRecords(cleanParams())
    records.value = Array.isArray(data) ? data : []
    if (!record.value && records.value.length) {
      await selectRecord(records.value[0])
    }
  } catch (e: any) {
    error.value = e?.message || '加载发布助手记录失败'
  } finally {
    listLoading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadMetrics(), loadRecords()])
}

async function searchRecord() {
  if (!query.assistId.trim()) return
  await selectRecord({ assistId: query.assistId.trim() })
}

async function selectRecord(item: any) {
  if (!item?.assistId) return
  recordLoading.value = true
  error.value = ''
  success.value = ''
  try {
    record.value = await aiApi.publishAssistRecordAdmin(item.assistId)
    query.assistId = record.value?.assistId || item.assistId
    if (record.value?.productId) {
      outcome.productId = String(record.value.productId)
    }
  } catch (e: any) {
    error.value = e?.message || '查询发布助手记录失败'
  } finally {
    recordLoading.value = false
  }
}

function resetFilters() {
  filters.userId = ''
  filters.productId = ''
  filters.riskLevel = ''
  filters.adoptionType = ''
  filters.from = ''
  filters.to = ''
  filters.limit = 20
  record.value = null
  void loadRecords()
}

async function submitOutcome() {
  if (!outcome.productId) return
  outcomeLoading.value = true
  error.value = ''
  success.value = ''
  try {
    await aiApi.recordRiskOutcome(outcome.productId, {
      outcomeType: outcome.outcomeType,
      outcomeSource: outcome.outcomeSource,
      reasonCode: outcome.reasonCode || undefined,
      reason: outcome.reason || undefined,
      isFinal: outcome.isFinal,
    })
    success.value = '风险结果已回流'
    await Promise.all([loadMetrics(), loadRecords()])
    if (record.value?.assistId) {
      await selectRecord(record.value)
    }
  } catch (e: any) {
    error.value = e?.message || '回流风险结果失败'
  } finally {
    outcomeLoading.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div class="page-shell">
    <section class="hero-card topbar publish-feedback-hero">
      <div>
        <p class="eyebrow">Publish Feedback Loop</p>
        <h2>发布建议采纳与风险回流</h2>
        <p class="muted">内部人员可按用户、商品、风险等级和采纳状态筛选发布助手记录，再把人工审核结果回流到 AI 链路。</p>
      </div>
      <button class="primary-btn" :disabled="metricsLoading || listLoading" type="button" @click="loadAll">
        <RefreshCw :size="17" aria-hidden="true" />
        {{ metricsLoading || listLoading ? '刷新中...' : '刷新数据' }}
      </button>
    </section>

    <section class="panel">
      <div class="stat-grid">
        <div v-for="item in statItems" :key="item.label" class="stat-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </div>
    </section>

    <section class="panel filter-panel">
      <div class="section-title">
        <div>
          <h3>发布助手记录</h3>
          <p class="muted">默认展示最近记录；需要定位某个用户或商品时再筛选。</p>
        </div>
        <div class="inline-actions">
          <button class="secondary-btn" type="button" :disabled="listLoading" @click="loadRecords">
            <Search :size="16" aria-hidden="true" />
            筛选
          </button>
          <button class="secondary-btn" type="button" :disabled="listLoading" @click="resetFilters">重置</button>
        </div>
      </div>

      <div class="filter-grid">
        <label>用户 ID<input v-model="filters.userId" placeholder="可选" /></label>
        <label>商品 ID<input v-model="filters.productId" placeholder="可选" /></label>
        <label>
          风险等级
          <select v-model="filters.riskLevel">
            <option value="">全部</option>
            <option value="LOW">低风险</option>
            <option value="MEDIUM">中风险</option>
            <option value="HIGH">高风险</option>
          </select>
        </label>
        <label>
          采纳状态
          <select v-model="filters.adoptionType">
            <option value="">全部</option>
            <option value="APPLIED">完整采纳</option>
            <option value="PARTIAL">部分采纳</option>
            <option value="IGNORED">未采纳</option>
          </select>
        </label>
        <label>开始时间<input v-model="filters.from" type="datetime-local" /></label>
        <label>结束时间<input v-model="filters.to" type="datetime-local" /></label>
        <label>返回条数<input v-model.number="filters.limit" min="1" max="100" type="number" /></label>
      </div>
    </section>

    <div class="grid two-col feedback-grid">
      <section class="panel record-list-panel">
        <div class="section-title compact-title">
          <div>
            <h3>最近记录</h3>
            <p class="muted">点击一条记录查看请求、建议和证据详情。</p>
          </div>
          <span class="record-count">{{ records.length }} 条</span>
        </div>

        <div class="exact-query">
          <input v-model="query.assistId" placeholder="精确查询 assistId" @keyup.enter="searchRecord" />
          <button class="secondary-btn" :disabled="recordLoading || !query.assistId.trim()" type="button" @click="searchRecord">
            <ClipboardList :size="16" aria-hidden="true" />
            查询
          </button>
        </div>

        <div v-if="records.length" class="record-list">
          <button
            v-for="item in records"
            :key="item.assistId"
            class="record-row"
            :class="{ active: selectedId === item.assistId }"
            type="button"
            @click="selectRecord(item)"
          >
            <span class="row-main">
              <strong>{{ shortId(item.assistId) }}</strong>
              <small>{{ formatTime(item.createdTime) }}</small>
            </span>
            <span class="row-meta">
              <span>用户 {{ item.userId || '-' }}</span>
              <span>商品 {{ item.productId || '未绑定' }}</span>
            </span>
            <span class="row-tags">
              <em :class="riskClass(item.riskLevel)">{{ riskText(item.riskLevel) }}</em>
              <em>{{ adoptionText(item.adoptionType) }}</em>
            </span>
          </button>
        </div>
        <p v-else class="muted empty-state">{{ listLoading ? '记录加载中...' : '暂无符合条件的记录' }}</p>
      </section>

      <section class="panel">
        <h3>风险结果回流</h3>
        <p class="muted">选中已绑定商品的记录后会自动填充 Product ID，也可以手动输入。</p>
        <label>Product ID<input v-model="outcome.productId" placeholder="商品 ID" /></label>
        <div class="grid two-col compact-grid">
          <label>
            结果
            <select v-model="outcome.outcomeType">
              <option value="PENDING">待复核</option>
              <option value="PASSED">通过</option>
              <option value="REJECTED">拒绝发布</option>
              <option value="TAKEN_DOWN">下架</option>
              <option value="COMPLAINT_CONFIRMED">投诉确认</option>
            </select>
          </label>
          <label>
            来源
            <select v-model="outcome.outcomeSource">
              <option value="MANUAL_REVIEW">人工审核</option>
              <option value="AUTO_REVIEW">自动审核</option>
              <option value="COMPLAINT">投诉</option>
            </select>
          </label>
        </div>
        <label>原因码<input v-model="outcome.reasonCode" placeholder="可选，例如 invoice_missing" /></label>
        <label>说明<textarea v-model="outcome.reason" rows="3" placeholder="记录人工判断依据" /></label>
        <label v-if="auth.normalizedRole === 'ADMIN'" class="checkbox-line">
          <input v-model="outcome.isFinal" type="checkbox" />
          标记为最终风险结果
        </label>
        <button class="primary-btn full-width" :disabled="outcomeLoading || !outcome.productId" type="button" @click="submitOutcome">
          {{ outcomeLoading ? '提交中...' : '提交回流' }}
        </button>
      </section>
    </div>

    <section class="panel">
      <div class="section-title compact-title">
        <div>
          <h3>选中记录详情</h3>
          <p class="muted">详情用于追踪当时的输入、模型建议和命中的风险证据。</p>
        </div>
        <span v-if="recordLoading" class="record-count">加载中</span>
      </div>

      <div v-if="record" class="record-detail">
        <div class="detail-summary">
          <div>
            <span>Assist ID</span>
            <strong>{{ record.assistId }}</strong>
          </div>
          <div>
            <span>Trace ID</span>
            <strong>{{ record.traceId || '-' }}</strong>
          </div>
          <div>
            <span>用户 / 商品</span>
            <strong>{{ record.userId || '-' }} / {{ record.productId || '未绑定' }}</strong>
          </div>
          <div>
            <span>状态</span>
            <strong>{{ riskText(record.riskLevel) }} / {{ adoptionText(record.adoptionType) }}</strong>
          </div>
        </div>

        <div class="grid three-col">
          <div>
            <h4>请求</h4>
            <pre>{{ requestPreview }}</pre>
          </div>
          <div>
            <h4>建议</h4>
            <pre>{{ suggestionPreview }}</pre>
          </div>
          <div>
            <h4>证据</h4>
            <pre>{{ evidencePreview }}</pre>
          </div>
        </div>
      </div>
      <p v-else class="muted empty-state">从左侧选择一条发布助手记录后查看详情。</p>
    </section>

    <p v-if="success" class="success-msg">{{ success }}</p>
    <p v-if="error" class="error">{{ error }}</p>
  </div>
</template>

<style scoped>
.publish-feedback-hero {
  align-items: flex-start;
}

.primary-btn,
.secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  justify-content: center;
}

.section-title {
  align-items: flex-start;
  display: flex;
  gap: 14px;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-title h3,
.section-title p {
  margin: 0;
}

.section-title h3 {
  margin-bottom: 4px;
}

.compact-title {
  align-items: center;
}

.inline-actions,
.exact-query {
  display: flex;
  gap: 10px;
}

.inline-actions {
  flex-wrap: wrap;
}

.exact-query {
  margin-bottom: 14px;
}

.exact-query input {
  min-width: 0;
}

.filter-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.feedback-grid {
  align-items: start;
  grid-template-columns: minmax(0, 1.15fr) minmax(320px, 0.85fr);
}

.record-list-panel {
  min-height: 420px;
}

.record-count {
  background: var(--accent-soft);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--accent);
  font-size: 13px;
  font-weight: 750;
  padding: 6px 10px;
}

.record-list {
  display: grid;
  gap: 10px;
  max-height: 560px;
  overflow: auto;
  padding-right: 4px;
}

.record-row {
  background: rgba(255, 253, 247, 0.72);
  border: 1px solid var(--line);
  border-radius: 8px;
  color: var(--ink-900);
  display: grid;
  gap: 8px;
  padding: 12px;
  text-align: left;
}

.record-row.active {
  background: var(--accent-soft);
  border-color: var(--accent);
}

.row-main,
.row-meta,
.row-tags {
  align-items: center;
  display: flex;
  gap: 8px;
  justify-content: space-between;
}

.row-main small,
.row-meta {
  color: var(--ink-500);
  font-size: 13px;
}

.row-meta,
.row-tags {
  flex-wrap: wrap;
  justify-content: flex-start;
}

.row-tags em {
  background: rgba(255, 253, 247, 0.72);
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 12px;
  font-style: normal;
  font-weight: 750;
  padding: 4px 8px;
}

.row-tags .risk-high {
  background: #f8e5df;
  border-color: #e4b2a4;
  color: var(--failed);
}

.row-tags .risk-medium {
  background: #f7efd9;
  border-color: #d9c181;
  color: var(--denied);
}

.row-tags .risk-low {
  background: #e4efe5;
  border-color: #aac7ae;
  color: var(--success);
}

.detail-summary {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 16px;
}

.detail-summary div {
  background: rgba(255, 253, 247, 0.68);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 12px;
}

.detail-summary span {
  color: var(--ink-500);
  font-size: 12px;
}

.detail-summary strong {
  font-size: 14px;
  overflow-wrap: anywhere;
}

.checkbox-line {
  align-items: center;
  display: flex;
  gap: 8px;
}

.checkbox-line input {
  width: auto;
}

.compact-grid {
  gap: 10px;
}

.empty-state {
  border: 1px dashed var(--line-strong);
  border-radius: 8px;
  margin: 0;
  padding: 18px;
}

h4 {
  margin: 0 0 8px;
}

@media (max-width: 980px) {
  .filter-grid,
  .feedback-grid,
  .detail-summary {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 720px) {
  .publish-feedback-hero,
  .section-title,
  .inline-actions,
  .exact-query,
  .row-main {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-grid,
  .feedback-grid,
  .detail-summary {
    grid-template-columns: 1fr;
  }
}
</style>
