<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCircle2, Play, RefreshCw, ShieldCheck, XCircle } from 'lucide-vue-next'
import { aiApi } from '../../api/modules/ai'
import { useAuthStore } from '../../stores/auth'

type AiopsEvent = {
  id: number
  eventKey?: string
  traceId?: string
  severity?: string
  source?: string
  title?: string
  summary?: string
  status?: string
  manualRequired?: boolean
  escalationReason?: string
  resolutionType?: string
  actions?: Array<Record<string, any>>
}

type Runbook = {
  actionCode?: string
  actionName?: string
  riskLevel?: string
  dryRun?: boolean
  status?: string
  message?: string
  verificationStatus?: string
  elapsedMs?: number
}

const auth = useAuthStore()
const filters = reactive({
  status: '',
  severity: '',
  limit: 20,
})
const createForm = reactive({
  eventKey: `manual-${Date.now()}`,
  traceId: `manual-trace-${Date.now()}`,
  severity: 'WARN',
  source: 'frontend',
  title: '接口 P95 延迟升高',
  summary: '模拟 AIOps 事件，用于验证诊断、审批、Runbook 和 MTTR 链路。',
  suggestion: '建议先 dry-run，再由管理员执行低风险修复。',
})
const runbookForm = reactive({
  eventId: '',
  actionCode: '',
  dryRun: true,
  reason: 'frontend-runbook-test',
  idempotencyKey: `frontend-${Date.now()}`,
})
const operationForm = reactive({
  actionCode: 'REPAIR_KNOWLEDGE_DOC',
  docId: 19,
  forceReindex: false,
  reason: '检测到知识库索引不一致，需要创建受控修复计划。',
  traceId: '',
})

const loading = ref(false)
const actionLoading = ref(false)
const error = ref('')
const success = ref('')
const events = ref<AiopsEvent[]>([])
const selected = ref<AiopsEvent | null>(null)
const mttr = ref<any>(null)
const runbooks = ref<Runbook[]>([])
const runbookResult = ref<any>(null)
const operation = ref<any>(null)

const statItems = computed(() => [
  { label: '已解决', value: mttr.value?.resolvedCount ?? '—' },
  { label: '自动修复成功率', value: formatRate(mttr.value?.autoRepairSuccessRate) },
  { label: '升级转人工率', value: formatRate(mttr.value?.escalationRate) },
  { label: '平均 MTTR', value: formatMs(mttr.value?.averageMttrMs) },
  { label: 'P95 MTTR', value: formatMs(mttr.value?.p95MttrMs) },
])

function formatRate(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${Math.round(Number(value) * 100)}%`
}

function formatMs(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const ms = Number(value)
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.round(ms / 60_000)}min`
}

function statusClass(status?: string) {
  const normalized = String(status || '').toUpperCase()
  if (normalized.includes('RESOLVED') || normalized === 'APPROVED' || normalized === 'SUCCESS') return 'status-success'
  if (normalized.includes('FAILED') || normalized === 'REJECTED') return 'status-failed'
  if (normalized.includes('VERIFY') || normalized.includes('RUNNING')) return 'status-running'
  if (normalized.includes('ESCALATED')) return 'status-denied'
  return ''
}

function selectEvent(event: AiopsEvent) {
  selected.value = event
  runbookForm.eventId = String(event.id)
  if (event.source === 'controlled_operation') loadOperation(event.id)
}

const isAdmin = computed(() => auth.normalizedRole === 'ADMIN')

async function loadOperation(operationId: number | string) {
  try {
    operation.value = await aiApi.controlledOperation(operationId)
  } catch (e: any) {
    error.value = e?.message || '加载受控操作失败'
  }
}

async function loadAll() {
  loading.value = true
  error.value = ''
  success.value = ''
  try {
    const [eventData, metricData, runbookData] = await Promise.all([
      aiApi.aiopsEvents(filters),
      aiApi.aiopsMttrMetrics(),
      aiApi.aiopsRunbooks(),
    ])
    events.value = Array.isArray(eventData) ? eventData as AiopsEvent[] : []
    mttr.value = metricData
    runbooks.value = Array.isArray(runbookData) ? runbookData as Runbook[] : []
    if (!selected.value && events.value.length) selectEvent(events.value[0])
    if (!runbookForm.actionCode && runbooks.value.length) runbookForm.actionCode = runbooks.value[0].actionCode || ''
  } catch (e: any) {
    error.value = e?.message || '加载 AIOps 数据失败'
  } finally {
    loading.value = false
  }
}

async function createEvent() {
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const created = await aiApi.createAiopsEvent({
      ...createForm,
      metadata: { source: 'frontend-console' },
    }) as AiopsEvent
    success.value = '事件已创建'
    await loadAll()
    selectEvent(events.value.find((item) => item.id === created.id) || created)
  } catch (e: any) {
    error.value = e?.message || '创建事件失败'
  } finally {
    actionLoading.value = false
  }
}

async function approveSelected() {
  if (!selected.value) return
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const updated = await aiApi.approveAiopsEvent(selected.value.id, { reason: 'frontend-approved' }) as AiopsEvent
    success.value = '事件已审批'
    await loadAll()
    selectEvent(events.value.find((item) => item.id === updated.id) || updated)
  } catch (e: any) {
    error.value = e?.message || '审批失败'
  } finally {
    actionLoading.value = false
  }
}

async function rejectSelected() {
  if (!selected.value) return
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const updated = await aiApi.rejectAiopsEvent(selected.value.id, { reason: 'frontend-rejected' }) as AiopsEvent
    success.value = '事件已拒绝'
    await loadAll()
    selectEvent(events.value.find((item) => item.id === updated.id) || updated)
  } catch (e: any) {
    error.value = e?.message || '拒绝失败'
  } finally {
    actionLoading.value = false
  }
}

async function executeRunbook() {
  if (!runbookForm.eventId || !runbookForm.actionCode) return
  runbookForm.dryRun = true
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    runbookResult.value = await aiApi.executeAiopsRunbook({
      eventId: Number(runbookForm.eventId),
      actionCode: runbookForm.actionCode,
      dryRun: runbookForm.dryRun,
      reason: runbookForm.reason,
      idempotencyKey: runbookForm.idempotencyKey || `frontend-${Date.now()}`,
      params: {},
    })
    success.value = 'Dry-run 已完成'
    await loadAll()
  } catch (e: any) {
    error.value = e?.message || '执行 Runbook 失败'
  } finally {
    actionLoading.value = false
  }
}

async function createOperationPlan() {
  actionLoading.value = true
  error.value = ''
  success.value = ''
  try {
    const payload: any = {
      actionCode: operationForm.actionCode,
      reason: operationForm.reason,
      traceId: operationForm.traceId || undefined,
      params: operationForm.actionCode === 'REPAIR_KNOWLEDGE_DOC'
        ? { docId: operationForm.docId, forceReindex: operationForm.forceReindex }
        : {},
    }
    operation.value = await aiApi.createOperationPlan(payload)
    success.value = `操作计划 #${operation.value.operationId} 已创建，等待管理员审批。`
    await loadAll()
  } catch (e: any) {
    error.value = e?.message || '创建操作计划失败'
  } finally {
    actionLoading.value = false
  }
}

async function approveOperation() {
  if (!operation.value) return
  actionLoading.value = true
  try {
    operation.value = await aiApi.approveOperation(operation.value.operationId, { reason: '管理员确认执行计划快照' })
    success.value = '操作计划已审批。'
    await loadAll()
  } catch (e: any) { error.value = e?.message || '审批失败' } finally { actionLoading.value = false }
}

async function rejectOperation() {
  if (!operation.value) return
  actionLoading.value = true
  try {
    operation.value = await aiApi.rejectOperation(operation.value.operationId, { reason: '管理员拒绝执行该计划' })
    success.value = '操作计划已拒绝。'
    await loadAll()
  } catch (e: any) { error.value = e?.message || '拒绝失败' } finally { actionLoading.value = false }
}

async function executeOperation() {
  if (!operation.value || operation.value.status !== 'APPROVED') return
  if (!window.confirm('将按已审批的只读参数快照执行修复并验证结果，确认继续吗？')) return
  actionLoading.value = true
  try {
    operation.value = await aiApi.executeOperation(operation.value.operationId)
    success.value = '操作已执行，结果与验证状态已写入事件链路。'
    await loadAll()
  } catch (e: any) { error.value = e?.message || '执行受控操作失败' } finally { actionLoading.value = false }
}

onMounted(loadAll)
</script>

<template>
  <div class="page-shell">
    <section class="hero-card topbar">
      <div>
        <p class="eyebrow">AIOps Remediation</p>
        <h2>AIOps 事件与修复闭环</h2>
        <p class="muted">查看告警事件、审批低风险修复、执行 Runbook，并跟踪修复后验证和 MTTR 指标。</p>
      </div>
      <button class="primary-btn" :disabled="loading" type="button" @click="loadAll">
        <RefreshCw :size="17" aria-hidden="true" />
        {{ loading ? '刷新中...' : '刷新' }}
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

    <section class="panel filter-row">
      <label>
        状态
        <select v-model="filters.status">
          <option value="">全部</option>
          <option value="OPEN">OPEN</option>
          <option value="APPROVED">APPROVED</option>
          <option value="VERIFYING">VERIFYING</option>
          <option value="RESOLVED">RESOLVED</option>
          <option value="ESCALATED">ESCALATED</option>
          <option value="REJECTED">REJECTED</option>
        </select>
      </label>
      <label>
        等级
        <select v-model="filters.severity">
          <option value="">全部</option>
          <option value="INFO">INFO</option>
          <option value="WARN">WARN</option>
          <option value="ERROR">ERROR</option>
          <option value="CRITICAL">CRITICAL</option>
        </select>
      </label>
      <label>
        数量
        <input v-model.number="filters.limit" type="number" min="1" max="100" />
      </label>
      <button class="secondary-btn" :disabled="loading" type="button" @click="loadAll">筛选</button>
    </section>

    <div class="grid two-col aiops-grid">
      <section class="panel">
        <h3>事件列表</h3>
        <div v-if="events.length" class="timeline">
          <button
            v-for="event in events"
            :key="event.id"
            class="event-card"
            :class="{ active: selected?.id === event.id }"
            type="button"
            @click="selectEvent(event)"
          >
            <span class="event-title">#{{ event.id }} {{ event.title || event.eventKey }}</span>
            <span class="event-meta">
              <b :class="statusClass(event.status)">{{ event.status || 'UNKNOWN' }}</b>
              {{ event.severity || 'INFO' }}
              {{ event.source || 'unknown' }}
            </span>
            <span class="muted">{{ event.summary || '暂无摘要' }}</span>
          </button>
        </div>
        <p v-else class="muted">暂无 AIOps 事件。</p>
      </section>

      <section class="panel">
        <h3>事件处置</h3>
        <template v-if="selected">
          <div class="detail-block">
            <strong>{{ selected.title || selected.eventKey }}</strong>
            <p>{{ selected.summary || '暂无摘要' }}</p>
            <p class="muted">Trace：{{ selected.traceId || '暂无' }}</p>
            <p v-if="selected.manualRequired" class="status-denied">需要人工：{{ selected.escalationReason || '未说明' }}</p>
          </div>
          <div class="inline-actions action-wrap">
            <button
              v-if="auth.normalizedRole === 'ADMIN'"
              class="secondary-btn"
              :disabled="actionLoading"
              type="button"
              @click="approveSelected"
            >
              <ShieldCheck :size="16" aria-hidden="true" />审批
            </button>
            <button
              v-if="auth.normalizedRole === 'ADMIN'"
              class="secondary-btn"
              :disabled="actionLoading"
              type="button"
              @click="rejectSelected"
            >
              <XCircle :size="16" aria-hidden="true" />拒绝
            </button>
          </div>

          <div class="runbook-box">
            <h4>受控操作计划</h4>
            <p class="muted">真实修复必须先固化参数快照，再由管理员审批；执行阶段不能修改目标文档或重建参数。</p>
            <label>
              动作
              <select v-model="operationForm.actionCode">
                <option value="REPAIR_KNOWLEDGE_DOC">修复单个知识文档索引</option>
                <option value="REPAIR_KNOWLEDGE_BATCH">批量 Repair（仅转人工）</option>
                <option value="MARK_EVENT_RESOLVED">人工标记事件已解决</option>
              </select>
            </label>
            <template v-if="operationForm.actionCode === 'REPAIR_KNOWLEDGE_DOC'">
              <label>文档 ID<input v-model.number="operationForm.docId" min="1" type="number" /></label>
              <label class="checkbox-line"><input v-model="operationForm.forceReindex" type="checkbox" />强制重建索引</label>
            </template>
            <label>原因<textarea v-model="operationForm.reason" rows="2" /></label>
            <label>关联 Trace（可选）<input v-model="operationForm.traceId" /></label>
            <button class="primary-btn" :disabled="actionLoading" type="button" @click="createOperationPlan">
              <ShieldCheck :size="16" aria-hidden="true" />创建待审批计划
            </button>
          </div>

          <div v-if="operation" class="runbook-box operation-box">
            <h4>操作 #{{ operation.operationId }} · {{ operation.status }}</h4>
            <p><b>风险：</b>{{ operation.riskLevel || 'UNKNOWN' }}</p>
            <p><b>动作：</b>{{ operation.actionCode }}</p>
            <p><b>参数快照：</b>{{ JSON.stringify(operation.params || {}) }}</p>
            <p v-if="operation.escalationReason" class="status-denied">转人工：{{ operation.escalationReason }}</p>
            <p v-if="operation.execution"><b>验证：</b>{{ operation.execution.verificationStatus || 'NOT_STARTED' }} · {{ operation.execution.message || '' }}</p>
            <div class="inline-actions action-wrap">
              <button v-if="isAdmin && operation.status === 'PENDING_APPROVAL'" class="secondary-btn" :disabled="actionLoading" type="button" @click="approveOperation">审批计划</button>
              <button v-if="isAdmin && operation.status === 'PENDING_APPROVAL'" class="secondary-btn" :disabled="actionLoading" type="button" @click="rejectOperation">拒绝计划</button>
              <button v-if="isAdmin && operation.status === 'APPROVED' && operation.riskLevel !== 'HIGH'" class="primary-btn" :disabled="actionLoading" type="button" @click="executeOperation">按快照执行</button>
            </div>
          </div>

          <details class="runbook-box">
            <summary>高级调试：仅 dry-run</summary>
            <label>
              动作
              <select v-model="runbookForm.actionCode">
                <option v-for="item in runbooks" :key="item.actionCode" :value="item.actionCode">
                  {{ item.actionName || item.actionCode }} · {{ item.riskLevel || 'UNKNOWN' }}
                </option>
              </select>
            </label>
            <label>幂等键<input v-model="runbookForm.idempotencyKey" /></label>
            <label>原因<input v-model="runbookForm.reason" /></label>
            <p class="muted">此区域只能执行 dry-run；真实修复请使用上方受控操作计划。</p>
            <button class="secondary-btn" :disabled="actionLoading || !runbookForm.actionCode" type="button" @click="executeRunbook">
              <Play :size="16" aria-hidden="true" />
              执行 dry-run
            </button>
          </details>

          <div v-if="selected.actions?.length" class="timeline compact">
            <div v-for="action in selected.actions" :key="action.id" class="timeline-item">
              <strong :class="statusClass(action.status)">{{ action.actionType || action.actionCode || action.id }}</strong>
              <p>status={{ action.status }} / verify={{ action.verificationStatus || '—' }}</p>
            </div>
          </div>
        </template>
        <p v-else class="muted">选择一个事件后处置。</p>
      </section>
    </div>

    <div class="grid two-col">
      <section class="panel">
        <h3>创建模拟事件</h3>
        <label>事件 Key<input v-model="createForm.eventKey" /></label>
        <label>Trace ID<input v-model="createForm.traceId" /></label>
        <div class="grid two-col">
          <label>等级<select v-model="createForm.severity"><option>INFO</option><option>WARN</option><option>ERROR</option><option>CRITICAL</option></select></label>
          <label>来源<input v-model="createForm.source" /></label>
        </div>
        <label>标题<input v-model="createForm.title" /></label>
        <label>摘要<textarea v-model="createForm.summary" rows="3" /></label>
        <label>建议<textarea v-model="createForm.suggestion" rows="2" /></label>
        <button class="secondary-btn" :disabled="actionLoading" type="button" @click="createEvent">创建事件</button>
      </section>

      <section class="panel">
        <h3>最近 Runbook 结果</h3>
        <pre>{{ runbookResult || '暂无结果' }}</pre>
        <p class="muted">
          INTERNAL 用户只能 dry-run；真实执行需要 ADMIN 权限，后端会再次校验。
        </p>
      </section>
    </div>

    <p v-if="success" class="success-msg">{{ success }}</p>
    <p v-if="error" class="error">{{ error }}</p>
  </div>
</template>

<style scoped>
.primary-btn,
.secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
}

.filter-row {
  align-items: end;
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr 1fr 120px auto;
}

.filter-row label {
  margin: 0;
}

.aiops-grid {
  align-items: start;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.9fr);
}

.event-card {
  background: rgba(255, 253, 247, 0.72);
  border: 1px solid var(--line);
  display: grid;
  gap: 7px;
  padding: 12px;
  text-align: left;
  width: 100%;
}

.event-card.active {
  border-color: var(--accent);
  box-shadow: inset 3px 0 var(--accent);
}

.event-title {
  font-weight: 800;
}

.event-meta {
  color: var(--ink-500);
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 8px;
}

.detail-block,
.runbook-box {
  background: rgba(255, 253, 247, 0.68);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 8px;
  margin-bottom: 12px;
  padding: 12px;
}

.operation-box { border-left: 3px solid var(--accent); }

details summary { cursor: pointer; font-weight: 700; }

.action-wrap {
  margin-bottom: 12px;
}

.checkbox-line {
  align-items: center;
  display: flex;
  gap: 8px;
}

.checkbox-line input {
  width: auto;
}

h4 {
  margin: 0;
}

@media (max-width: 900px) {
  .filter-row,
  .aiops-grid {
    grid-template-columns: 1fr;
  }
}
</style>
