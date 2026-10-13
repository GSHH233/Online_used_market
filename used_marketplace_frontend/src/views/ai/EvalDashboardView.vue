<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Activity,
  BookOpenCheck,
  ChevronRight,
  ClipboardCheck,
  RefreshCw,
  Sparkles,
} from 'lucide-vue-next'
import { aiApi } from '../../api/modules/ai'
import { knowledgeApi, type KnowledgeDocument, type KnowledgeTask } from '../../api/modules/knowledge'
import { operationsApi, type ControlledOperation } from '../../api/modules/operations'
import { publishAssistantApi } from '../../api/modules/publishAssistant'

const router = useRouter()
const dashboard = ref<any>(null)
const gapMetrics = ref<Record<string, number>>({})
const publishMetrics = ref<any>({})
const aiopsMetrics = ref<any>({})
const documents = ref<KnowledgeDocument[]>([])
const tasks = ref<KnowledgeTask[]>([])
const operations = ref<ControlledOperation[]>([])
const loading = ref(false)
const error = ref('')
const moduleErrors = ref<string[]>([])

const reports = computed(() => dashboard.value?.recentReports || dashboard.value?.reports || [])
const rawMetrics = computed<any[]>(() => dashboard.value?.metrics || dashboard.value?.latestMetrics || [])

function findMetric(...keywords: string[]) {
  const item = rawMetrics.value.find((metric) => {
    const name = String(metric.name || metric.metricName || '').toLowerCase()
    return keywords.some((keyword) => name.includes(keyword.toLowerCase()))
  })
  return item?.value ?? item?.metricValue
}

function formatRate(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const number = Number(value)
  return `${(number <= 1 ? number * 100 : number).toFixed(number < 0.999 ? 1 : 0)}%`
}

function formatMs(value: unknown) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const ms = Number(value)
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60_000).toFixed(1)}min`
}

const activeDocuments = computed(() =>
  documents.value.filter((item) => String(item.status).toUpperCase() === 'ACTIVE').length,
)
const failedTasks = computed(() =>
  tasks.value.filter((item) => ['FAILED', 'DEAD_LETTERED'].includes(String(item.status))).length,
)
const consistencyRate = computed(() => {
  const checked = tasks.value.filter((item) => item.consistent != null)
  if (!checked.length) return '—'
  return formatRate(checked.filter((item) => item.consistent).length / checked.length)
})
const pendingOperations = computed(() =>
  operations.value.filter((item) => item.status === 'PENDING_APPROVAL').length,
)

const metricGroups = computed(() => [
  {
    title: '发布助手',
    description: '建议是否真正帮助用户完成上架',
    icon: Sparkles,
    path: '/products/publish',
    actionLabel: '体验发布助手',
    metrics: [
      { label: '使用次数', value: publishMetrics.value?.totalCount ?? publishMetrics.value?.assistCount ?? '—' },
      { label: '建议采纳率', value: formatRate(publishMetrics.value?.adoptionRate) },
      { label: '风险反馈准确率', value: formatRate(publishMetrics.value?.riskPrecision) },
    ],
  },
  {
    title: '智能检索',
    description: '规则能否被找到，并在证据不足时正确拒答',
    icon: BookOpenCheck,
    path: '/ai/knowledge?tab=gaps',
    actionLabel: '处理知识缺口',
    metrics: [
      { label: 'Recall@5', value: formatRate(findMetric('recall@5', 'recall_at_5')) },
      { label: '拒答准确率', value: formatRate(findMetric('reject', '拒答')) },
      { label: '待处理缺口', value: gapMetrics.value?.openGaps ?? gapMetrics.value?.openCount ?? '—' },
    ],
  },
  {
    title: '知识治理',
    description: '文档从入库到三套检索存储的健康状态',
    icon: ClipboardCheck,
    path: '/ai/knowledge',
    actionLabel: '管理知识文档',
    metrics: [
      { label: '有效文档', value: activeDocuments.value },
      { label: '失败任务', value: failedTasks.value },
      { label: '一致性通过率', value: consistencyRate.value },
    ],
  },
  {
    title: '智能排查',
    description: '排查任务是否闭环，修复动作是否受控',
    icon: Activity,
    path: '/ai/investigate',
    actionLabel: '开始智能排查',
    metrics: [
      { label: '任务成功率', value: formatRate(findMetric('agent_success', 'success rate', '任务成功')) },
      { label: '待审批计划', value: pendingOperations.value },
      { label: 'P95 MTTR', value: formatMs(aiopsMetrics.value?.p95MttrMs) },
    ],
  },
])

async function loadDashboard() {
  loading.value = true
  error.value = ''
  moduleErrors.value = []
  const requests = await Promise.allSettled([
    aiApi.evalDashboard({ recentLimit: 20 }),
    knowledgeApi.gapMetrics(),
    publishAssistantApi.metrics(),
    aiApi.aiopsMttrMetrics(),
    knowledgeApi.documents({ limit: 100 }),
    knowledgeApi.jobs({ limit: 100 }),
    operationsApi.list({ limit: 100 }),
  ])
  const [dashboardResult, gapsResult, publishResult, aiopsResult, docsResult, tasksResult, operationsResult] = requests
  const labels = ['工程指标', '知识缺口', '发布反馈', '排查时效', '知识文档', '入库任务', '受控计划']
  moduleErrors.value = requests
    .map((result, index) => result.status === 'rejected' ? `${labels[index]}暂不可用` : '')
    .filter(Boolean)
  if (dashboardResult.status === 'fulfilled') dashboard.value = dashboardResult.value
  if (gapsResult.status === 'fulfilled') gapMetrics.value = gapsResult.value
  if (publishResult.status === 'fulfilled') publishMetrics.value = publishResult.value
  if (aiopsResult.status === 'fulfilled') aiopsMetrics.value = aiopsResult.value
  if (docsResult.status === 'fulfilled') documents.value = docsResult.value
  if (tasksResult.status === 'fulfilled') tasks.value = tasksResult.value
  if (operationsResult.status === 'fulfilled') operations.value = operationsResult.value
  if (dashboardResult.status === 'rejected') {
    error.value = dashboardResult.reason?.message || '加载运营总览失败'
  }
  loading.value = false
}

onMounted(loadDashboard)
</script>

<template>
  <div class="page-shell dashboard-page">
    <section class="hero-card page-heading">
      <div>
        <p class="eyebrow">运营总览</p>
        <h2>先看业务闭环，再定位工程细节</h2>
        <p class="muted">聚合发布、检索、知识治理和智能排查四条链路的当前状态。</p>
      </div>
      <button class="primary-btn" type="button" :disabled="loading" @click="loadDashboard">
        <RefreshCw :size="17" aria-hidden="true" />
        {{ loading ? '加载中...' : '刷新数据' }}
      </button>
    </section>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="moduleErrors.length" class="partial-warning">部分数据加载失败：{{ moduleErrors.join('、') }}。其余指标仍可查看。</p>

    <section class="metric-groups" aria-label="核心业务指标">
      <article v-for="group in metricGroups" :key="group.title" class="metric-group">
        <header>
          <span class="metric-icon"><component :is="group.icon" :size="20" aria-hidden="true" /></span>
          <div>
            <h3>{{ group.title }}</h3>
            <p>{{ group.description }}</p>
          </div>
        </header>
        <div class="metric-values">
          <div v-for="item in group.metrics" :key="item.label">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
        <button type="button" @click="router.push(group.path)">
          {{ group.actionLabel }}<ChevronRight :size="16" aria-hidden="true" />
        </button>
      </article>
    </section>

    <details class="panel engineering-section">
      <summary>工程指标与最近回归</summary>
      <div v-if="rawMetrics.length" class="engineering-metrics">
        <div v-for="(item, index) in rawMetrics.slice(0, 12)" :key="`${item.metricName || item.name}-${index}`">
          <span>{{ item.name || item.metricName || `指标 ${Number(index) + 1}` }}</span>
          <strong>{{ item.value ?? item.metricValue ?? '—' }}</strong>
        </div>
      </div>
      <div v-if="reports.length" class="report-list">
        <article v-for="(report, index) in reports.slice(0, 10)" :key="index">
          <strong>{{ report.name || report.reportName || report.evalType || `回归记录 ${Number(index) + 1}` }}</strong>
          <p>{{ report.summary || report.status || report.createdAt || '已记录' }}</p>
        </article>
      </div>
      <p v-if="!rawMetrics.length && !reports.length" class="muted">暂无工程回归数据。</p>
    </details>
  </div>
</template>

<style scoped>
.dashboard-page {
  gap: 18px;
}

.page-heading {
  align-items: center;
  display: flex;
  gap: 20px;
  justify-content: space-between;
}

.primary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
  white-space: nowrap;
}

.partial-warning {
  background: rgba(166, 104, 35, 0.08);
  border-left: 3px solid #a66823;
  color: #724819;
  margin: 0;
  padding: 10px 12px;
}

.metric-groups {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metric-group {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  display: grid;
  gap: 16px;
  padding: 18px;
}

.metric-group header {
  align-items: start;
  display: flex;
  gap: 11px;
}

.metric-icon {
  align-items: center;
  background: var(--ink-900);
  color: #fff;
  display: flex;
  height: 38px;
  justify-content: center;
  width: 38px;
}

.metric-group h3,
.metric-group p {
  margin: 0;
}

.metric-group p {
  color: var(--ink-500);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}

.metric-values {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.metric-values div {
  border-left: 2px solid var(--line-strong);
  display: grid;
  gap: 5px;
  padding-left: 10px;
}

.metric-values span {
  color: var(--ink-500);
  font-size: 11px;
}

.metric-values strong {
  font-size: 22px;
}

.metric-group > button {
  align-items: center;
  background: transparent;
  border: 0;
  color: var(--accent);
  display: inline-flex;
  gap: 4px;
  justify-self: start;
  min-height: 36px;
  padding: 4px 0;
}

.engineering-section summary {
  cursor: pointer;
  font-weight: 800;
}

.engineering-metrics {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 16px;
}

.engineering-metrics div {
  border-left: 2px solid var(--line-strong);
  display: grid;
  gap: 4px;
  padding-left: 10px;
}

.engineering-metrics span {
  color: var(--ink-500);
  font-size: 11px;
}

.report-list {
  display: grid;
  margin-top: 16px;
}

.report-list article {
  border-top: 1px solid var(--line);
  padding: 10px 0;
}

.report-list p {
  color: var(--ink-500);
  margin: 4px 0 0;
}

@media (max-width: 900px) {
  .metric-groups {
    grid-template-columns: 1fr;
  }

  .engineering-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .page-heading {
    align-items: stretch;
    display: grid;
  }

  .metric-values {
    grid-template-columns: 1fr;
  }
}
</style>
