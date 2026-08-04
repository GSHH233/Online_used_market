<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCircle2, Link2, RefreshCw, Search, XCircle } from 'lucide-vue-next'
import { aiApi } from '../../api/modules/ai'
import { useAuthStore } from '../../stores/auth'

type KnowledgeGap = {
  id: number
  query?: string
  reasonCode?: string
  status?: string
  priority?: string
  occurrenceCount?: number
  recurrenceCount?: number
  linkedDocId?: number
  linkedTaskId?: number
  latestTraceId?: string
  latestSessionId?: string
  firstSeenAt?: string
  lastSeenAt?: string
  resolvedAt?: string
  metadataJson?: string
}

type Regression = {
  id?: number
  gapId?: number
  passed?: boolean
  retrievalHit?: boolean
  sourceHit?: boolean
  rejectResolved?: boolean
  latencyMs?: number
  traceId?: string
  errorMsg?: string
  detailsJson?: string
  createdTime?: string
}

const auth = useAuthStore()
const filters = reactive({
  status: 'OPEN',
  priority: '',
  limit: 30,
})
const linkForm = reactive({
  gapId: '',
  docId: '',
  taskId: '',
})

const loading = ref(false)
const actionLoading = ref(false)
const error = ref('')
const success = ref('')
const metrics = ref<any>(null)
const gaps = ref<KnowledgeGap[]>([])
const selected = ref<KnowledgeGap | null>(null)
const regressions = ref<Regression[]>([])

const openGaps = computed(() => metrics.value?.openGaps ?? gaps.value.filter((item) => item.status !== 'RESOLVED' && item.status !== 'DISMISSED').length)
const resolutionRate = computed(() => formatRate(metrics.value?.gapResolutionRate))
const refusalRate = computed(() => formatRate(metrics.value?.refusalRate))
const averageAge = computed(() => formatMs(metrics.value?.averageOpenGapAgeMs))

function formatRate(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${Math.round(Number(value) * 100)}%`
}

function formatMs(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const ms = Number(value)
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}min`
  return `${Math.round(ms / 3_600_000)}h`
}

function statusClass(status?: string) {
  const normalized = String(status || '').toUpperCase()
  if (normalized === 'RESOLVED') return 'status-success'
  if (normalized.includes('FAILED') || normalized === 'OPEN') return 'status-failed'
  if (normalized === 'REGRESSING') return 'status-running'
  if (normalized === 'DISMISSED') return 'status-denied'
  return ''
}

function reasonText(reason?: string) {
  const map: Record<string, string> = {
    NO_RETRIEVAL: '召回为空',
    LOW_CONFIDENCE: '低置信度',
    MISSING_FIELDS: '缺少字段',
    USER_UNRESOLVED: '用户反馈未解决',
  }
  return map[String(reason || '').toUpperCase()] || reason || '未知原因'
}

function compact(value?: string) {
  if (!value) return '—'
  return value.length > 120 ? `${value.slice(0, 120)}...` : value
}

async function load() {
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    const [metricData, gapData] = await Promise.all([
      aiApi.knowledgeGapMetrics(),
      aiApi.knowledgeGaps({
        status: filters.status,
        priority: filters.priority,
        limit: filters.limit,
      }),
    ])
    metrics.value = metricData
    gaps.value = Array.isArray(gapData) ? gapData as KnowledgeGap[] : []
    if (!selected.value && gaps.value.length) selectGap(gaps.value[0])
  } catch (e: any) {
    error.value = e?.message || '加载知识缺口失败'
  } finally {
    loading.value = false
  }
}

async function selectGap(gap: KnowledgeGap) {
  selected.value = gap
  linkForm.gapId = String(gap.id)
  linkForm.docId = gap.linkedDocId ? String(gap.linkedDocId) : ''
  linkForm.taskId = gap.linkedTaskId ? String(gap.linkedTaskId) : ''
  regressions.value = []
  try {
    const data = await aiApi.knowledgeGapRegressions(gap.id, { limit: 10 })
    regressions.value = Array.isArray(data) ? data as Regression[] : []
  } catch {
    regressions.value = []
  }
}

async function updateGap(gap: KnowledgeGap, payload: Record<string, string>) {
  if (payload.status === 'DISMISSED' && !window.confirm('忽略后该缺口不会继续进入补文档回归流程，确认忽略吗？')) return
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const updated = await aiApi.updateKnowledgeGap(gap.id, payload) as KnowledgeGap
    success.value = '缺口状态已更新'
    gaps.value = gaps.value.map((item) => item.id === gap.id ? updated : item)
    if (selected.value?.id === gap.id) selected.value = updated
  } catch (e: any) {
    error.value = e?.message || '更新失败'
  } finally {
    actionLoading.value = false
  }
}

async function linkDoc() {
  if (!linkForm.gapId || !linkForm.docId) return
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const updated = await aiApi.linkKnowledgeGapDoc(linkForm.gapId, {
      docId: Number(linkForm.docId),
      taskId: linkForm.taskId ? Number(linkForm.taskId) : undefined,
    }) as KnowledgeGap
    success.value = '已关联知识文档'
    gaps.value = gaps.value.map((item) => item.id === updated.id ? updated : item)
    selected.value = updated
  } catch (e: any) {
    error.value = e?.message || '关联文档失败'
  } finally {
    actionLoading.value = false
  }
}

async function regress(gap?: KnowledgeGap | null) {
  const target = gap || selected.value
  if (!target) return
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const regression = await aiApi.regressKnowledgeGap(target.id) as Regression
    success.value = regression.passed ? '回归通过，缺口已解决' : '回归未通过，请继续治理'
    await load()
    await selectGap(gaps.value.find((item) => item.id === target.id) || target)
  } catch (e: any) {
    error.value = e?.message || '回归失败'
  } finally {
    actionLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-shell">
    <section class="hero-card topbar">
      <div>
        <p class="eyebrow">Knowledge Gap Governance</p>
        <h2>知识缺口治理</h2>
        <p class="muted">把拒答、低置信度和用户反馈沉淀成可审核、可补文档、可回归的治理任务。</p>
      </div>
      <button class="primary-btn" :disabled="loading" type="button" @click="load">
        <RefreshCw :size="17" aria-hidden="true" />
        {{ loading ? '加载中...' : '刷新' }}
      </button>
    </section>

    <section class="panel">
      <div class="stat-grid">
        <div class="stat-card"><span>总缺口</span><strong>{{ metrics?.totalGaps ?? gaps.length }}</strong></div>
        <div class="stat-card"><span>未关闭</span><strong>{{ openGaps }}</strong></div>
        <div class="stat-card"><span>解决率</span><strong>{{ resolutionRate }}</strong></div>
        <div class="stat-card"><span>拒答率</span><strong>{{ refusalRate }}</strong></div>
        <div class="stat-card"><span>平均打开时长</span><strong>{{ averageAge }}</strong></div>
      </div>
    </section>

    <section class="panel filter-row">
      <label>
        状态
        <select v-model="filters.status">
          <option value="">全部</option>
          <option value="OPEN">OPEN</option>
          <option value="LINKED">LINKED</option>
          <option value="REGRESSING">REGRESSING</option>
          <option value="REGRESSION_FAILED">REGRESSION_FAILED</option>
          <option value="RESOLVED">RESOLVED</option>
          <option value="DISMISSED">DISMISSED</option>
        </select>
      </label>
      <label>
        优先级
        <select v-model="filters.priority">
          <option value="">全部</option>
          <option value="HIGH">HIGH</option>
          <option value="MEDIUM">MEDIUM</option>
          <option value="LOW">LOW</option>
        </select>
      </label>
      <label>
        数量
        <input v-model.number="filters.limit" type="number" min="1" max="100" />
      </label>
      <button class="secondary-btn" :disabled="loading" type="button" @click="load">
        <Search :size="16" aria-hidden="true" />筛选
      </button>
    </section>

    <div class="grid two-col governance-grid">
      <section class="panel">
        <h3>缺口列表</h3>
        <div v-if="gaps.length" class="timeline">
          <button
            v-for="gap in gaps"
            :key="gap.id"
            class="gap-card"
            :class="{ active: selected?.id === gap.id }"
            type="button"
            @click="selectGap(gap)"
          >
            <span class="gap-title">#{{ gap.id }} {{ compact(gap.query) }}</span>
            <span class="gap-meta">
              <b :class="statusClass(gap.status)">{{ gap.status || 'UNKNOWN' }}</b>
              {{ reasonText(gap.reasonCode) }}
              出现 {{ gap.occurrenceCount ?? 1 }} 次
            </span>
          </button>
        </div>
        <p v-else class="muted">暂无知识缺口。</p>
      </section>

      <section class="panel">
        <h3>处理面板</h3>
        <template v-if="selected">
          <div class="detail-block">
            <span class="muted">问题</span>
            <strong>{{ selected.query }}</strong>
            <p class="muted">Trace：{{ selected.latestTraceId || '暂无' }}</p>
            <p class="muted">会话：{{ selected.latestSessionId || '暂无' }}</p>
          </div>

          <div class="inline-actions action-wrap">
            <button class="secondary-btn" :disabled="actionLoading" type="button" @click="updateGap(selected, { priority: 'HIGH' })">
              标为高优先级
            </button>
            <button class="secondary-btn" :disabled="actionLoading" type="button" @click="regress(selected)">
              <CheckCircle2 :size="16" aria-hidden="true" />执行回归
            </button>
            <button
              v-if="auth.normalizedRole === 'ADMIN'"
              class="secondary-btn"
              :disabled="actionLoading"
              type="button"
              @click="updateGap(selected, { status: 'DISMISSED' })"
            >
              <XCircle :size="16" aria-hidden="true" />忽略
            </button>
          </div>

          <div class="link-doc-box">
            <label>关联 Doc ID<input v-model="linkForm.docId" placeholder="补充后的知识文档 ID" /></label>
            <label>关联 Task ID<input v-model="linkForm.taskId" placeholder="可选，入库任务 ID" /></label>
            <button class="primary-btn" :disabled="actionLoading || !linkForm.docId" type="button" @click="linkDoc">
              <Link2 :size="16" aria-hidden="true" />关联文档
            </button>
          </div>

          <div v-if="regressions.length" class="timeline compact">
            <div v-for="item in regressions" :key="item.id" class="timeline-item">
              <strong :class="item.passed ? 'status-success' : 'status-failed'">
                {{ item.passed ? 'PASS' : 'FAIL' }}
              </strong>
              <p>
                retrieval={{ item.retrievalHit }} / source={{ item.sourceHit }} /
                rejectResolved={{ item.rejectResolved }} / latency={{ item.latencyMs }}ms
              </p>
              <p v-if="item.errorMsg" class="error">{{ item.errorMsg }}</p>
            </div>
          </div>
          <p v-else class="muted">暂无回归记录。</p>
        </template>
        <p v-else class="muted">选择一个缺口后处理。</p>
      </section>
    </div>

    <p v-if="success" class="success-msg">{{ success }}</p>
    <p v-if="error" class="error">{{ error }}</p>
  </div>
</template>

<style scoped>
.filter-row {
  align-items: end;
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr 1fr 120px auto;
}

.filter-row label {
  margin: 0;
}

.primary-btn,
.secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
}

.governance-grid {
  align-items: start;
  grid-template-columns: minmax(0, 1.1fr) minmax(360px, 0.9fr);
}

.gap-card {
  background: rgba(255, 253, 247, 0.72);
  border: 1px solid var(--line);
  display: grid;
  gap: 8px;
  padding: 12px;
  text-align: left;
  width: 100%;
}

.gap-card.active {
  border-color: var(--accent);
  box-shadow: inset 3px 0 var(--accent);
}

.gap-title {
  font-weight: 800;
  line-height: 1.5;
}

.gap-meta {
  color: var(--ink-500);
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 8px;
}

.detail-block,
.link-doc-box {
  background: rgba(255, 253, 247, 0.68);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 8px;
  margin-bottom: 12px;
  padding: 12px;
}

.detail-block strong {
  line-height: 1.65;
}

.action-wrap {
  margin-bottom: 12px;
}

@media (max-width: 900px) {
  .filter-row,
  .governance-grid {
    grid-template-columns: 1fr;
  }
}
</style>
