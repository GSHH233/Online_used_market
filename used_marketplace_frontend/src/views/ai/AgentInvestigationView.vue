<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  FileSearch,
  Play,
  RefreshCw,
  ShieldCheck,
  Wrench,
} from 'lucide-vue-next'
import {
  investigationApi,
  type InvestigationObservation,
  type InvestigationResult,
} from '../../api/modules/investigation'
import { knowledgeApi, type KnowledgeDocument } from '../../api/modules/knowledge'
import { operationsApi, type ControlledOperation } from '../../api/modules/operations'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const form = reactive({
  question: '检查接口 P95 上升是否与日志报错或数据库状态有关',
})
const loading = ref(false)
const operationLoading = ref(false)
const result = ref<InvestigationResult | null>(null)
const operations = ref<ControlledOperation[]>([])
const documents = ref<KnowledgeDocument[]>([])
const error = ref('')
const notice = ref('')
const actionForm = reactive({
  actionCode: 'MARK_EVENT_RESOLVED',
  docId: null as number | null,
})

const observations = computed(() => result.value?.observations || [])
const sources = computed(() => result.value?.sources || [])
const waitingOperations = computed(() =>
  operations.value.filter((item) => ['PENDING_APPROVAL', 'APPROVED', 'EXECUTING', 'FAILED'].includes(String(item.status))),
)

function setMessage(nextError = '', nextNotice = '') {
  error.value = nextError
  notice.value = nextNotice
}

function observationSummary(item: InvestigationObservation) {
  if (item.summary) return item.summary
  if (item.total != null) return `查询完成，共返回 ${item.total} 条结果`
  if (item.target) return `已检查 ${item.target}`
  return '工具已执行，未返回可展示摘要'
}

function statusTone(status?: string) {
  const value = String(status || '').toUpperCase()
  if (['SUCCESS', 'VERIFIED', 'RESOLVED', 'COMPLETED'].includes(value)) return 'success'
  if (['FAILED', 'REJECTED', 'ESCALATED'].includes(value)) return 'failed'
  return 'running'
}

async function loadWorkspace() {
  try {
    const [operationData, documentData] = await Promise.all([
      operationsApi.list({ limit: 30 }),
      knowledgeApi.documents({ limit: 60 }),
    ])
    operations.value = operationData
    documents.value = documentData
    if (!actionForm.docId && documentData.length) actionForm.docId = documentData[0].id
  } catch (cause: any) {
    setMessage(cause?.message || '加载处理计划失败')
  }
}

async function run() {
  if (!form.question.trim()) return
  loading.value = true
  result.value = null
  setMessage()
  try {
    result.value = await investigationApi.investigate({
      question: form.question,
      sessionId: `investigation-${Date.now()}`,
      maxSteps: 6,
    })
  } catch (cause: any) {
    setMessage(cause?.message || '智能排查执行失败，请检查内部服务和权限。')
  } finally {
    loading.value = false
  }
}

async function createPlan() {
  if (!result.value) return setMessage('请先完成一次排查')
  if (actionForm.actionCode === 'REPAIR_KNOWLEDGE_DOC' && !actionForm.docId) {
    return setMessage('请选择需要修复的知识文档')
  }
  operationLoading.value = true
  setMessage()
  try {
    const params = actionForm.actionCode === 'REPAIR_KNOWLEDGE_DOC'
      ? { docId: actionForm.docId, forceReindex: true }
      : { traceId: result.value.traceId }
    const created = await operationsApi.create({
      actionCode: actionForm.actionCode,
      params,
      reason: `根据排查结论创建：${form.question}`,
      traceId: result.value.traceId,
    })
    setMessage('', `处理计划 #${created.operationId} 已创建，尚未执行。`)
    await loadWorkspace()
  } catch (cause: any) {
    setMessage(cause?.message || '创建处理计划失败')
  } finally {
    operationLoading.value = false
  }
}

async function approve(operationId: number) {
  operationLoading.value = true
  setMessage()
  try {
    await operationsApi.approve(operationId, '管理员确认排查证据后批准')
    setMessage('', `计划 #${operationId} 已批准，仍需单独确认执行。`)
    await loadWorkspace()
  } catch (cause: any) {
    setMessage(cause?.message || '审批失败')
  } finally {
    operationLoading.value = false
  }
}

async function reject(operationId: number) {
  operationLoading.value = true
  setMessage()
  try {
    await operationsApi.reject(operationId, '当前证据不足，转人工复核')
    setMessage('', `计划 #${operationId} 已拒绝并保留审计记录。`)
    await loadWorkspace()
  } catch (cause: any) {
    setMessage(cause?.message || '拒绝计划失败')
  } finally {
    operationLoading.value = false
  }
}

async function execute(operationId: number) {
  operationLoading.value = true
  setMessage()
  try {
    await operationsApi.execute(operationId)
    setMessage('', `计划 #${operationId} 已执行，验证结果已写回。`)
    await loadWorkspace()
  } catch (cause: any) {
    setMessage(cause?.message || '执行失败，系统已保留现场，可转人工处理。')
    await loadWorkspace()
  } finally {
    operationLoading.value = false
  }
}

onMounted(loadWorkspace)
</script>

<template>
  <main class="page-shell investigation-page">
    <section class="hero-card investigation-hero">
      <div>
        <p class="eyebrow">智能排查</p>
        <h2>从异常描述到受控处理，证据和动作都可追踪</h2>
        <p class="muted">系统自动选择合适的排查策略；写操作始终需要管理员审批。</p>
      </div>
      <button class="primary-btn" type="button" :disabled="loading || !form.question.trim()" @click="run">
        <Play :size="17" aria-hidden="true" />
        {{ loading ? '正在查询与分析...' : '开始排查' }}
      </button>
    </section>

    <section class="panel query-panel">
      <label for="investigation-question">描述需要排查的问题</label>
      <textarea
        id="investigation-question"
        v-model="form.question"
        rows="3"
        placeholder="例如：接口响应明显变慢，检查日志、指标和数据库状态"
      />
      <div class="example-row">
        <span>快速输入</span>
        <button type="button" @click="form.question = '检查接口 P95 上升是否与日志报错或数据库状态有关'">接口变慢</button>
        <button type="button" @click="form.question = '知识检索无结果，检查关键词索引和向量索引状态'">检索无结果</button>
        <button type="button" @click="form.question = '检查最近失败的知识入库任务并给出处置建议'">入库失败</button>
      </div>
    </section>

    <p v-if="notice" class="success-msg">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <template v-if="result">
      <section class="panel conclusion-panel">
        <div class="conclusion-heading">
          <span class="result-icon" :data-tone="result.success ? 'success' : 'warning'">
            <component :is="result.success ? CheckCircle2 : AlertTriangle" :size="24" aria-hidden="true" />
          </span>
          <div>
            <p class="eyebrow">排查结论</p>
            <h3>{{ result.success ? '已完成证据分析' : '需要进一步人工确认' }}</h3>
          </div>
          <span class="status-label" :data-tone="statusTone(result.status)">{{ result.status || 'COMPLETED' }}</span>
        </div>
        <p class="answer">{{ result.answer || '系统未生成结论。' }}</p>
        <div class="meta-row">
          <span>证据 {{ observations.length + sources.length }} 条</span>
          <span>耗时 {{ result.elapsedMs ?? '—' }} ms</span>
          <span>链路 {{ result.traceId || '—' }}</span>
        </div>
      </section>

      <div class="content-split">
        <section class="panel">
          <div class="panel-heading"><FileSearch :size="19" aria-hidden="true" /><h3>关键证据</h3></div>
          <div v-if="observations.length || sources.length" class="evidence-list">
            <article v-for="(item, index) in observations" :key="`${item.tool}-${index}`">
              <strong>{{ item.tool || '内部查询' }}</strong>
              <p>{{ observationSummary(item) }}</p>
              <small v-if="item.elapsedMs != null">耗时 {{ item.elapsedMs }}ms</small>
            </article>
            <article v-for="(source, index) in sources" :key="`${source.chunkId}-${index}`">
              <strong>{{ source.title || '知识库依据' }}</strong>
              <p>{{ source.content || '命中相关知识规则' }}</p>
            </article>
          </div>
          <p v-else class="muted">本次未返回可展示证据。</p>
        </section>

        <section class="panel action-panel">
          <div class="panel-heading"><ShieldCheck :size="19" aria-hidden="true" /><h3>建议动作</h3></div>
          <p class="muted">先创建计划，不会立即改动生产数据。</p>
          <label>
            处理动作
            <select v-model="actionForm.actionCode">
              <option value="MARK_EVENT_RESOLVED">确认问题已解决</option>
              <option value="REPAIR_KNOWLEDGE_DOC">修复知识文档索引</option>
            </select>
          </label>
          <label v-if="actionForm.actionCode === 'REPAIR_KNOWLEDGE_DOC'">
            目标文档
            <select v-model="actionForm.docId">
              <option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.title }}</option>
            </select>
          </label>
          <button class="primary-btn" type="button" :disabled="operationLoading" @click="createPlan">
            <Wrench :size="16" aria-hidden="true" />创建处理计划
          </button>
        </section>
      </div>

      <details class="panel technical-details">
        <summary><ChevronDown :size="16" />查看执行过程</summary>
        <div class="technical-grid">
          <div><span>执行策略</span><strong>{{ result.strategy || 'DIRECT' }}</strong></div>
          <div><span>路由原因</span><strong>{{ result.routeReason || '根据问题复杂度自动选择' }}</strong></div>
          <div><span>步骤数量</span><strong>{{ result.agentTrace?.length || 0 }}</strong></div>
          <div><span>并行节点</span><strong>{{ result.graph?.length || 0 }}</strong></div>
        </div>
      </details>
    </template>

    <section class="panel operation-section">
      <div class="section-title">
        <div>
          <p class="eyebrow">受控操作</p>
          <h3>待处理计划</h3>
        </div>
        <button class="secondary-btn" type="button" :disabled="operationLoading" @click="loadWorkspace">
          <RefreshCw :size="16" />刷新
        </button>
      </div>
      <div v-if="waitingOperations.length" class="operation-list">
        <article v-for="operation in waitingOperations" :key="operation.operationId">
          <div class="operation-main">
            <span class="operation-mark">#{{ operation.operationId }}</span>
            <div>
              <strong>{{ operation.actionCode === 'REPAIR_KNOWLEDGE_DOC' ? '修复知识文档索引' : '确认事件已解决' }}</strong>
              <p>{{ operation.reason || '未填写处理原因' }}</p>
            </div>
          </div>
          <span class="status-label" :data-tone="statusTone(operation.status)">{{ operation.status }}</span>
          <div v-if="auth.isAdmin" class="operation-actions">
            <button v-if="operation.status === 'PENDING_APPROVAL'" class="secondary-btn" type="button" @click="reject(operation.operationId)">拒绝</button>
            <button v-if="operation.status === 'PENDING_APPROVAL'" class="primary-btn" type="button" @click="approve(operation.operationId)">批准</button>
            <button v-if="operation.status === 'APPROVED'" class="primary-btn" type="button" @click="execute(operation.operationId)">确认执行</button>
          </div>
          <p v-else class="permission-note">仅管理员可审批和执行；内部用户保持只读。</p>
        </article>
      </div>
      <p v-else class="muted">当前没有待审批或待执行计划。</p>
    </section>
  </main>
</template>

<style scoped>
.investigation-page {
  display: grid;
  gap: 16px;
}

.investigation-hero,
.conclusion-heading,
.panel-heading,
.meta-row,
.section-title,
.operation-main,
.operation-actions {
  align-items: center;
  display: flex;
}

.investigation-hero,
.section-title {
  gap: 20px;
  justify-content: space-between;
}

.primary-btn,
.secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  justify-content: center;
}

.query-panel,
.action-panel {
  display: grid;
  gap: 12px;
}

.example-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.example-row > span {
  color: var(--ink-500);
  font-size: 12px;
}

.example-row button {
  background: transparent;
  border: 1px solid var(--line);
  color: var(--ink-700);
  font-size: 12px;
  min-height: 36px;
  padding: 6px 9px;
}

.conclusion-panel {
  display: grid;
  gap: 14px;
}

.conclusion-heading {
  gap: 10px;
}

.conclusion-heading h3,
.conclusion-heading p,
.section-title h3,
.section-title p {
  margin: 0;
}

.conclusion-heading .status-label {
  margin-left: auto;
}

.result-icon {
  align-items: center;
  background: rgba(31, 122, 69, 0.08);
  color: var(--success);
  display: flex;
  height: 42px;
  justify-content: center;
  width: 42px;
}

.result-icon[data-tone='warning'] {
  background: rgba(157, 116, 32, 0.09);
  color: #805e18;
}

.answer {
  line-height: 1.75;
  margin: 0;
  white-space: pre-wrap;
}

.meta-row {
  color: var(--ink-500);
  flex-wrap: wrap;
  font-size: 12px;
  gap: 14px;
}

.content-split {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 1.2fr) minmax(300px, 0.8fr);
}

.panel-heading {
  gap: 8px;
  margin-bottom: 10px;
}

.panel-heading h3 {
  margin: 0;
}

.evidence-list {
  display: grid;
}

.evidence-list article {
  border-bottom: 1px solid var(--line);
  padding: 10px 0;
}

.evidence-list p {
  color: var(--ink-700);
  line-height: 1.6;
  margin: 5px 0;
}

.evidence-list small {
  color: var(--ink-500);
}

.technical-details summary {
  align-items: center;
  cursor: pointer;
  display: flex;
  font-weight: 750;
  gap: 6px;
}

.technical-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 14px;
}

.technical-grid div {
  border-left: 2px solid var(--line-strong);
  display: grid;
  gap: 4px;
  padding-left: 10px;
}

.technical-grid span {
  color: var(--ink-500);
  font-size: 12px;
}

.operation-list {
  display: grid;
  margin-top: 12px;
}

.operation-list article {
  align-items: center;
  border-top: 1px solid var(--line);
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) auto auto;
  padding: 13px 0;
}

.operation-main {
  gap: 11px;
  min-width: 0;
}

.operation-main p {
  color: var(--ink-500);
  margin: 4px 0 0;
}

.operation-mark {
  align-items: center;
  background: var(--ink-900);
  color: #fff;
  display: flex;
  font-size: 11px;
  height: 34px;
  justify-content: center;
  min-width: 42px;
}

.operation-actions {
  gap: 7px;
}

.status-label {
  border: 1px solid var(--line);
  color: var(--ink-700);
  font-size: 11px;
  font-weight: 750;
  padding: 4px 7px;
}

.status-label[data-tone='success'] {
  color: var(--success);
}

.status-label[data-tone='failed'] {
  color: var(--failed);
}

.permission-note {
  color: var(--ink-500);
  font-size: 12px;
  margin: 0;
}

@media (max-width: 900px) {
  .content-split,
  .technical-grid {
    grid-template-columns: 1fr 1fr;
  }

  .operation-list article {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .operation-actions,
  .permission-note {
    grid-column: 1 / -1;
  }
}

@media (max-width: 680px) {
  .investigation-hero {
    align-items: stretch;
    display: grid;
  }

  .content-split,
  .technical-grid {
    grid-template-columns: 1fr;
  }

  .operation-list article {
    grid-template-columns: 1fr;
  }

  .status-label {
    justify-self: start;
  }
}
</style>
