<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertTriangle,
  CheckCircle2,
  Database,
  FileSearch,
  FileUp,
  LibraryBig,
  RefreshCw,
  Search,
  ShieldCheck,
  Wrench,
} from 'lucide-vue-next'
import {
  knowledgeApi,
  type KnowledgeDocument,
  type KnowledgeGap,
  type KnowledgeSearchResult,
  type KnowledgeTask,
  type StorageConsistency,
} from '../../api/modules/knowledge'
import { operationsApi, type ControlledOperation } from '../../api/modules/operations'
import { useAuthStore } from '../../stores/auth'

type TabKey = 'documents' | 'upload' | 'gaps' | 'retrieval' | 'storage'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const baseTabs = [
  { key: 'documents' as TabKey, label: '文档管理', icon: LibraryBig },
  { key: 'upload' as TabKey, label: '上传入库', icon: FileUp },
  { key: 'gaps' as TabKey, label: '知识缺口', icon: AlertTriangle },
  { key: 'storage' as TabKey, label: '存储健康', icon: Database },
]
const tabs = computed(() => auth.isAdmin
  ? [...baseTabs.slice(0, 3), { key: 'retrieval' as TabKey, label: '检索验证', icon: FileSearch }, baseTabs[3]]
  : baseTabs)

const activeTab = ref<TabKey>('documents')
const documents = ref<KnowledgeDocument[]>([])
const selectedDocId = ref<number | null>(null)
const tasks = ref<KnowledgeTask[]>([])
const gaps = ref<KnowledgeGap[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')

const docFilter = reactive({ keyword: '', status: '' })
const uploadForm = reactive({
  file: null as File | null,
  title: '',
  sourceType: 'GUIDE',
  tags: '',
  sourceTrustLevel: 'MEDIUM',
  docVersion: 'v1',
})
const uploadProgress = ref(0)
const uploadResult = ref<{ docId?: number; taskId?: number; warnings?: string[] } | null>(null)
const dragActive = ref(false)

const gapFilter = reactive({ status: '', priority: '' })
const selectedGapId = ref<number | null>(null)
const gapDocId = ref<number | null>(null)

const retrievalForm = reactive({
  question: '二手手机写全新未拆封但无发票，有哪些发布风险？',
  rewriteEnabled: true,
})
const retrievalResult = ref<KnowledgeSearchResult | null>(null)

const storageResult = ref<StorageConsistency | null>(null)
const operationResult = ref<ControlledOperation | null>(null)
let pollTimer: number | null = null
let pollAttempts = 0

const selectedDocument = computed(() =>
  documents.value.find((item) => item.id === selectedDocId.value) || null,
)
const selectedGap = computed(() =>
  gaps.value.find((item) => item.id === selectedGapId.value) || null,
)
const latestTasks = computed(() => tasks.value.slice(0, 12))

const taskCounts = computed(() => ({
  total: tasks.value.length,
  running: tasks.value.filter((item) => ['PENDING', 'QUEUED', 'RUNNING'].includes(String(item.status))).length,
  success: tasks.value.filter((item) => item.status === 'SUCCESS').length,
  failed: tasks.value.filter((item) => ['FAILED', 'DEAD_LETTERED'].includes(String(item.status))).length,
}))

const stageText: Record<string, string> = {
  CREATED: '等待开始处理',
  CONSISTENCY_CHECK: '正在检查现有版本',
  CLEANUP: '正在清理旧索引',
  SPLIT: '正在整理知识片段',
  DB_WRITE: '正在保存知识片段',
  EMBEDDING: '正在生成检索特征',
  VECTOR_WRITE: '正在写入向量索引',
  ES_WRITE: '正在写入关键词索引',
  VERIFY: '正在校验数据一致性',
  FINISHED: '已完成',
}

function setMessage(nextError = '', nextNotice = '') {
  error.value = nextError
  notice.value = nextNotice
}

function selectTab(tab: TabKey) {
  if (tab === 'retrieval' && !auth.isAdmin) return
  activeTab.value = tab
  router.replace({ query: { ...route.query, tab } })
}

function statusTone(status?: string) {
  const value = String(status || '').toUpperCase()
  if (['SUCCESS', 'ACTIVE', 'RESOLVED', 'CONSISTENT'].includes(value)) return 'success'
  if (['FAILED', 'DEAD_LETTERED', 'REJECTED'].includes(value)) return 'failed'
  if (['RUNNING', 'QUEUED', 'PENDING', 'TRIAGED', 'LINKED'].includes(value)) return 'running'
  return 'neutral'
}

function selectDocument(docId: number) {
  selectedDocId.value = docId
  gapDocId.value = docId
}

async function loadDocuments() {
  loading.value = true
  setMessage()
  try {
    documents.value = await knowledgeApi.documents({
      keyword: docFilter.keyword || undefined,
      status: docFilter.status || undefined,
      limit: 60,
    })
    if (!selectedDocId.value && documents.value.length) selectDocument(documents.value[0].id)
  } catch (cause: any) {
    setMessage(cause?.message || '加载知识文档失败')
  } finally {
    loading.value = false
  }
}

async function loadTasks() {
  try {
    tasks.value = await knowledgeApi.jobs({ limit: 40 })
  } catch (cause: any) {
    setMessage(cause?.message || '加载入库任务失败')
  }
}

async function loadGaps() {
  loading.value = true
  setMessage()
  try {
    gaps.value = await knowledgeApi.gaps({
      status: gapFilter.status || undefined,
      priority: gapFilter.priority || undefined,
      limit: 50,
    })
    if (!selectedGapId.value && gaps.value.length) selectedGapId.value = gaps.value[0].id
  } catch (cause: any) {
    setMessage(cause?.message || '加载知识缺口失败')
  } finally {
    loading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadDocuments(), loadTasks(), loadGaps()])
}

function chooseFile(file?: File | null) {
  if (!file) return
  uploadForm.file = file
  if (!uploadForm.title) uploadForm.title = file.name.replace(/\.[^.]+$/, '')
}

function onFileInput(event: Event) {
  chooseFile((event.target as HTMLInputElement).files?.[0])
}

function onDrop(event: DragEvent) {
  dragActive.value = false
  chooseFile(event.dataTransfer?.files?.[0])
}

async function submitUpload() {
  if (!uploadForm.file) return setMessage('请选择需要入库的文档')
  loading.value = true
  uploadProgress.value = 0
  setMessage()
  try {
    const result = await knowledgeApi.upload(uploadForm.file, {
      title: uploadForm.title,
      sourceType: uploadForm.sourceType,
      tags: uploadForm.tags,
      sourceTrustLevel: uploadForm.sourceTrustLevel,
      docVersion: uploadForm.docVersion,
      async: true,
      onProgress: (percent) => { uploadProgress.value = percent },
    })
    const docId = result.document?.id
    const taskId = result.task?.id
    uploadResult.value = { docId, taskId, warnings: result.warnings }
    if (docId) selectDocument(docId)
    setMessage('', taskId ? '文档已接收，正在后台完成解析与入库。' : '文档已创建。')
    await Promise.all([loadDocuments(), loadTasks()])
    startTaskPolling()
  } catch (cause: any) {
    setMessage(cause?.message || '文档上传失败')
  } finally {
    loading.value = false
  }
}

function stopTaskPolling() {
  if (pollTimer !== null) window.clearTimeout(pollTimer)
  pollTimer = null
}

function startTaskPolling() {
  stopTaskPolling()
  pollAttempts = 0
  const poll = async () => {
    pollAttempts += 1
    await loadTasks()
    const task = tasks.value.find((item) => item.id === uploadResult.value?.taskId)
    if (!task || ['SUCCESS', 'FAILED', 'DEAD_LETTERED'].includes(String(task.status)) || pollAttempts >= 12) {
      stopTaskPolling()
      await loadDocuments()
      return
    }
    pollTimer = window.setTimeout(poll, Math.min(8000, 1500 + pollAttempts * 500))
  }
  pollTimer = window.setTimeout(poll, 1500)
}

async function updateGap(status: string) {
  if (!selectedGap.value) return
  loading.value = true
  setMessage()
  try {
    await knowledgeApi.updateGap(selectedGap.value.id, { status })
    setMessage('', `知识缺口已更新为 ${status}`)
    await loadGaps()
  } catch (cause: any) {
    setMessage(cause?.message || '更新知识缺口失败')
  } finally {
    loading.value = false
  }
}

async function linkGapDocument() {
  if (!selectedGap.value || !gapDocId.value) return setMessage('请先选择要关联的知识文档')
  loading.value = true
  setMessage()
  try {
    await knowledgeApi.linkGapDocument(selectedGap.value.id, gapDocId.value)
    setMessage('', '已关联文档，请执行回归验证后再关闭缺口。')
    await loadGaps()
  } catch (cause: any) {
    setMessage(cause?.message || '关联文档失败')
  } finally {
    loading.value = false
  }
}

async function regressGap() {
  if (!selectedGap.value) return
  loading.value = true
  setMessage()
  try {
    await knowledgeApi.regressGap(selectedGap.value.id)
    setMessage('', '回归验证已完成，结果已写入缺口记录。')
    await loadGaps()
  } catch (cause: any) {
    setMessage(cause?.message || '回归验证失败')
  } finally {
    loading.value = false
  }
}

async function runRetrieval() {
  if (!retrievalForm.question.trim()) return
  loading.value = true
  retrievalResult.value = null
  setMessage()
  try {
    retrievalResult.value = await knowledgeApi.search({
      question: retrievalForm.question,
      topK: 5,
      mode: 'HYBRID',
      rewriteEnabled: retrievalForm.rewriteEnabled,
    })
  } catch (cause: any) {
    setMessage(cause?.message || '检索验证失败')
  } finally {
    loading.value = false
  }
}

async function inspectStorage() {
  if (!selectedDocId.value) return setMessage('请先选择知识文档')
  loading.value = true
  storageResult.value = null
  operationResult.value = null
  setMessage()
  try {
    storageResult.value = await knowledgeApi.storageConsistency(selectedDocId.value)
  } catch (cause: any) {
    setMessage(cause?.message || '存储一致性检查失败')
  } finally {
    loading.value = false
  }
}

async function createRepairPlan() {
  if (!selectedDocId.value) return
  loading.value = true
  setMessage()
  try {
    operationResult.value = await operationsApi.create({
      actionCode: 'REPAIR_KNOWLEDGE_DOC',
      params: { docId: selectedDocId.value, forceReindex: true },
      reason: `修复知识文档“${selectedDocument.value?.title || selectedDocId.value}”的存储一致性`,
    })
    setMessage('', `处理计划 #${operationResult.value.operationId} 已创建，等待管理员审批。`)
  } catch (cause: any) {
    setMessage(cause?.message || '创建修复计划失败')
  } finally {
    loading.value = false
  }
}

watch(() => route.query.tab, (value) => {
  const target = String(value || 'documents') as TabKey
  if (tabs.value.some((item) => item.key === target)) activeTab.value = target
}, { immediate: true })

onMounted(() => Promise.all([loadDocuments(), loadTasks(), loadGaps()]))
onBeforeUnmount(stopTaskPolling)
</script>

<template>
  <div class="page-shell knowledge-page">
    <section class="hero-card page-heading">
      <div>
        <p class="eyebrow">知识治理</p>
        <h2>让规则文档从上传到可检索，全程可追踪</h2>
        <p class="muted">统一管理文档入库、知识缺口、检索验证和三存储健康状态。</p>
      </div>
      <button class="secondary-btn" type="button" :disabled="loading" @click="refreshAll">
        <RefreshCw :size="17" aria-hidden="true" />刷新
      </button>
    </section>

    <nav class="section-tabs" aria-label="知识治理页面">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :class="{ active: activeTab === tab.key }"
        type="button"
        @click="selectTab(tab.key)"
      >
        <component :is="tab.icon" :size="17" aria-hidden="true" />{{ tab.label }}
      </button>
    </nav>

    <p v-if="notice" class="success-msg">{{ notice }}</p>
    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="activeTab === 'documents'">
      <section class="panel">
        <div class="toolbar">
          <label>
            搜索文档
            <input v-model="docFilter.keyword" placeholder="标题、标签或来源" @keyup.enter="loadDocuments" />
          </label>
          <label>
            状态
            <select v-model="docFilter.status">
              <option value="">全部</option>
              <option value="ACTIVE">已生效</option>
              <option value="DRAFT">草稿</option>
              <option value="DISABLED">已停用</option>
            </select>
          </label>
          <button class="primary-btn" type="button" @click="loadDocuments"><Search :size="16" />查询</button>
        </div>
      </section>

      <section class="panel">
        <div class="section-title">
          <div>
            <h3>文档列表</h3>
            <p class="muted">共 {{ documents.length }} 份，点击一行可作为后续治理对象。</p>
          </div>
          <button class="secondary-btn" type="button" @click="selectTab('upload')"><FileUp :size="16" />上传文档</button>
        </div>
        <div v-if="documents.length" class="document-list">
          <button
            v-for="doc in documents"
            :key="doc.id"
            :class="{ selected: selectedDocId === doc.id }"
            type="button"
            @click="selectDocument(doc.id)"
          >
            <span class="file-mark">{{ (doc.fileName || doc.title).slice(0, 1) }}</span>
            <span class="document-main">
              <strong>{{ doc.title }}</strong>
              <small>{{ doc.fileName || doc.sourceUri || '手工录入' }} · {{ doc.docVersion || 'v1' }}</small>
            </span>
            <span class="status-label" :data-tone="statusTone(doc.status)">{{ doc.status || 'DRAFT' }}</span>
          </button>
        </div>
        <p v-else class="empty-copy">没有找到符合条件的知识文档。</p>
      </section>
    </template>

    <template v-else-if="activeTab === 'upload'">
      <div class="content-split">
        <section class="panel upload-panel">
          <div
            class="drop-zone"
            :class="{ active: dragActive }"
            @dragenter.prevent="dragActive = true"
            @dragover.prevent
            @dragleave.prevent="dragActive = false"
            @drop.prevent="onDrop"
          >
            <FileUp :size="30" aria-hidden="true" />
            <strong>{{ uploadForm.file?.name || '拖入规则文档，或点击选择文件' }}</strong>
            <span>支持 MD、PDF、DOC、DOCX</span>
            <input type="file" accept=".md,.pdf,.doc,.docx" aria-label="选择知识文档" @change="onFileInput" />
          </div>
          <div class="grid two-col">
            <label>文档标题<input v-model="uploadForm.title" placeholder="默认使用文件名" /></label>
            <label>
              来源类型
              <select v-model="uploadForm.sourceType">
                <option value="GUIDE">操作指南</option>
                <option value="RULE">平台规则</option>
                <option value="FAQ">常见问题</option>
                <option value="MARKET">市场信息</option>
              </select>
            </label>
            <label>标签<input v-model="uploadForm.tags" placeholder="手机, 凭证, 发布风险" /></label>
            <label>文档版本<input v-model="uploadForm.docVersion" /></label>
          </div>
          <button class="primary-btn" type="button" :disabled="loading || !uploadForm.file" @click="submitUpload">
            {{ loading ? '正在提交...' : '开始入库' }}
          </button>
          <div v-if="uploadProgress > 0" class="progress-block">
            <div><span>上传进度</span><strong>{{ uploadProgress }}%</strong></div>
            <progress :value="uploadProgress" max="100" />
          </div>
          <div v-if="uploadResult" class="result-note">
            <CheckCircle2 :size="20" aria-hidden="true" />
            <div>
              <strong>文档已进入处理队列</strong>
              <p>系统会继续完成解析、切片、向量化、索引和一致性校验。</p>
            </div>
          </div>
        </section>

        <section class="panel">
          <h3>入库任务</h3>
          <div class="stat-line">
            <span>运行中 <b>{{ taskCounts.running }}</b></span>
            <span>成功 <b>{{ taskCounts.success }}</b></span>
            <span>失败 <b>{{ taskCounts.failed }}</b></span>
          </div>
          <div v-if="latestTasks.length" class="task-list">
            <article v-for="task in latestTasks" :key="task.id">
              <div>
                <strong>{{ documents.find((doc) => doc.id === task.docId)?.title || `文档 ${task.docId}` }}</strong>
                <span class="status-label" :data-tone="statusTone(task.status)">{{ task.status }}</span>
              </div>
              <p>{{ stageText[String(task.stage)] || task.stage || '等待处理' }} · {{ task.elapsedMs ?? 0 }}ms</p>
              <p v-if="task.status === 'SUCCESS'">片段 {{ task.chunkCount ?? 0 }} · 三存储校验{{ task.consistent ? '通过' : '待确认' }}</p>
              <p v-if="task.errorMsg" class="error">{{ task.errorMsg }}</p>
            </article>
          </div>
          <p v-else class="empty-copy">暂无入库任务。</p>
        </section>
      </div>
    </template>

    <template v-else-if="activeTab === 'gaps'">
      <section class="panel">
        <div class="toolbar">
          <label>
            处理状态
            <select v-model="gapFilter.status">
              <option value="">全部</option>
              <option value="OPEN">待处理</option>
              <option value="TRIAGED">已分诊</option>
              <option value="LINKED">已关联文档</option>
              <option value="RESOLVED">已解决</option>
            </select>
          </label>
          <label>
            优先级
            <select v-model="gapFilter.priority">
              <option value="">全部</option>
              <option value="HIGH">高</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
            </select>
          </label>
          <button class="primary-btn" type="button" @click="loadGaps"><Search :size="16" />筛选</button>
        </div>
      </section>

      <div class="content-split">
        <section class="panel gap-list">
          <h3>待治理问题</h3>
          <button
            v-for="gap in gaps"
            :key="gap.id"
            :class="{ selected: selectedGapId === gap.id }"
            type="button"
            @click="selectedGapId = gap.id"
          >
            <span>
              <strong>{{ gap.query }}</strong>
              <small>出现 {{ gap.occurrenceCount || 1 }} 次 · 最近 {{ gap.lastSeenAt || '刚刚' }}</small>
            </span>
            <span class="status-label" :data-tone="statusTone(gap.status)">{{ gap.status || 'OPEN' }}</span>
          </button>
          <p v-if="!gaps.length" class="empty-copy">当前没有符合条件的知识缺口。</p>
        </section>

        <section class="panel gap-detail">
          <template v-if="selectedGap">
            <p class="eyebrow">问题详情</p>
            <h3>{{ selectedGap.query }}</h3>
            <dl>
              <div><dt>识别原因</dt><dd>{{ selectedGap.reasonCode || '证据不足' }}</dd></div>
              <div><dt>优先级</dt><dd>{{ selectedGap.priority || 'MEDIUM' }}</dd></div>
              <div><dt>重复出现</dt><dd>{{ selectedGap.occurrenceCount || 1 }} 次</dd></div>
            </dl>
            <label>
              关联知识文档
              <select v-model="gapDocId">
                <option :value="null">请选择文档</option>
                <option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.title }}</option>
              </select>
            </label>
            <div class="inline-actions">
              <button class="secondary-btn" type="button" @click="linkGapDocument">关联文档</button>
              <button class="secondary-btn" type="button" @click="updateGap('TRIAGED')">标记已分诊</button>
              <button class="primary-btn" type="button" @click="regressGap">执行回归验证</button>
            </div>
            <p class="hint">只有回归命中后，缺口才应进入已解决状态。</p>
          </template>
          <p v-else class="empty-copy">选择一条问题查看治理动作。</p>
        </section>
      </div>
    </template>

    <template v-else-if="activeTab === 'retrieval'">
      <section class="panel retrieval-form">
        <label>验证问题<textarea v-model="retrievalForm.question" rows="3" /></label>
        <label class="toggle-line">
          <input v-model="retrievalForm.rewriteEnabled" type="checkbox" />
          启用多路问题改写
        </label>
        <button class="primary-btn" type="button" :disabled="loading" @click="runRetrieval">
          <Search :size="16" />验证 Top 5 结果
        </button>
      </section>
      <section v-if="retrievalResult" class="panel">
        <div class="section-title">
          <div>
            <h3>检索结果</h3>
            <p class="muted">命中 {{ retrievalResult.hits?.length || 0 }} 条 · {{ retrievalResult.elapsedMs ?? '—' }}ms</p>
          </div>
        </div>
        <div class="search-results">
          <article v-for="(hit, index) in retrievalResult.hits || []" :key="`${hit.chunkId}-${index}`">
            <span>{{ index + 1 }}</span>
            <div>
              <strong>{{ hit.title || `知识片段 ${hit.chunkId}` }}</strong>
              <p>{{ hit.content }}</p>
            </div>
            <b>{{ hit.score != null ? Number(hit.score).toFixed(3) : '—' }}</b>
          </article>
        </div>
        <details v-if="retrievalResult.rewritePlan">
          <summary>查看问题改写过程</summary>
          <dl class="rewrite-detail">
            <div><dt>规范问题</dt><dd>{{ retrievalResult.rewritePlan.mainQuery || '—' }}</dd></div>
            <div><dt>子问题</dt><dd>{{ retrievalResult.rewritePlan.subQueries?.join('；') || '—' }}</dd></div>
            <div><dt>近义表达</dt><dd>{{ retrievalResult.rewritePlan.synonymQueries?.join('；') || '—' }}</dd></div>
          </dl>
        </details>
      </section>
    </template>

    <template v-else-if="activeTab === 'storage'">
      <section class="panel storage-toolbar">
        <label>
          检查文档
          <select v-model="selectedDocId">
            <option :value="null">请选择文档</option>
            <option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.title }}</option>
          </select>
        </label>
        <button class="primary-btn" type="button" :disabled="loading || !selectedDocId" @click="inspectStorage">
          <ShieldCheck :size="17" />检查存储健康
        </button>
      </section>

      <section v-if="storageResult" class="panel storage-result">
        <div class="health-heading">
          <component :is="storageResult.consistent ? CheckCircle2 : AlertTriangle" :size="28" aria-hidden="true" />
          <div>
            <h3>{{ storageResult.consistent ? '三套存储数据一致' : '发现存储差异' }}</h3>
            <p>{{ selectedDocument?.title }}</p>
          </div>
        </div>
        <div class="storage-counts">
          <div><span>主数据片段</span><strong>{{ storageResult.dbCount ?? 0 }}</strong></div>
          <div><span>关键词索引</span><strong>{{ storageResult.esCount ?? 0 }}</strong></div>
          <div><span>向量索引</span><strong>{{ storageResult.milvusCount ?? 0 }}</strong></div>
        </div>
        <details v-if="!storageResult.consistent">
          <summary>查看差异明细</summary>
          <p>关键词索引缺失：{{ storageResult.missingInEs?.join(', ') || '无' }}</p>
          <p>向量索引缺失：{{ storageResult.missingInMilvus?.join(', ') || '无' }}</p>
        </details>
        <button
          v-if="!storageResult.consistent"
          class="primary-btn"
          type="button"
          :disabled="loading"
          @click="createRepairPlan"
        >
          <Wrench :size="16" />创建处理计划
        </button>
        <div v-if="operationResult" class="result-note">
          <CheckCircle2 :size="20" />
          <div>
            <strong>计划 #{{ operationResult.operationId }} 已创建</strong>
            <p>当前状态：{{ operationResult.status }}。审批与执行将在智能排查页完成。</p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.knowledge-page {
  gap: 16px;
}

.page-heading,
.section-title,
.health-heading {
  align-items: center;
  display: flex;
  gap: 16px;
  justify-content: space-between;
}

.section-title h3,
.section-title p,
.health-heading h3,
.health-heading p {
  margin: 0;
}

.section-tabs {
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 4px;
  overflow-x: auto;
}

.section-tabs button {
  align-items: center;
  background: transparent;
  border: 0;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--ink-500);
  display: inline-flex;
  flex: 0 0 auto;
  gap: 7px;
  min-height: 44px;
}

.section-tabs button.active {
  border-bottom-color: var(--accent);
  color: var(--ink-900);
}

.toolbar,
.storage-toolbar {
  align-items: end;
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(220px, 1fr) 180px auto;
}

.toolbar label,
.storage-toolbar label {
  margin: 0;
}

.document-list,
.task-list,
.gap-list,
.search-results {
  display: grid;
}

.document-list > button,
.gap-list > button {
  align-items: center;
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  color: inherit;
  display: grid;
  gap: 12px;
  grid-template-columns: auto minmax(0, 1fr) auto;
  min-height: 62px;
  padding: 10px 4px;
  text-align: left;
  width: 100%;
}

.document-list > button:hover,
.document-list > button.selected,
.gap-list > button:hover,
.gap-list > button.selected {
  background: rgba(41, 75, 61, 0.06);
}

.file-mark {
  align-items: center;
  background: var(--ink-900);
  color: #fff;
  display: inline-flex;
  font-weight: 800;
  height: 34px;
  justify-content: center;
  width: 34px;
}

.document-main,
.gap-list button > span:first-child {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.document-main small,
.gap-list small {
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-label {
  border: 1px solid var(--line);
  color: var(--ink-700);
  font-size: 11px;
  font-weight: 750;
  padding: 4px 7px;
}

.status-label[data-tone='success'] {
  border-color: rgba(31, 122, 69, 0.28);
  color: var(--success);
}

.status-label[data-tone='failed'] {
  border-color: rgba(180, 35, 24, 0.25);
  color: var(--failed);
}

.status-label[data-tone='running'] {
  border-color: rgba(157, 116, 32, 0.3);
  color: #805e18;
}

.content-split {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 1.2fr) minmax(320px, 0.8fr);
}

.drop-zone {
  align-items: center;
  border: 1px dashed var(--line-strong);
  display: grid;
  gap: 7px;
  justify-items: center;
  min-height: 160px;
  padding: 24px;
  position: relative;
  text-align: center;
}

.drop-zone.active {
  background: rgba(41, 75, 61, 0.07);
  border-color: var(--accent);
}

.drop-zone span {
  color: var(--ink-500);
  font-size: 12px;
}

.drop-zone input {
  cursor: pointer;
  inset: 0;
  opacity: 0;
  position: absolute;
}

.upload-panel {
  display: grid;
  gap: 14px;
}

.progress-block,
.result-note {
  display: grid;
  gap: 8px;
}

.progress-block > div {
  display: flex;
  justify-content: space-between;
}

.progress-block progress {
  accent-color: var(--accent);
  width: 100%;
}

.result-note {
  align-items: start;
  background: rgba(31, 122, 69, 0.06);
  border-left: 3px solid var(--success);
  grid-template-columns: auto 1fr;
  padding: 11px 12px;
}

.result-note p {
  margin: 4px 0 0;
}

.stat-line {
  border-bottom: 1px solid var(--line);
  display: flex;
  gap: 16px;
  padding-bottom: 12px;
}

.task-list article {
  border-bottom: 1px solid var(--line);
  padding: 12px 0;
}

.task-list article > div {
  align-items: center;
  display: flex;
  gap: 10px;
  justify-content: space-between;
}

.task-list p {
  color: var(--ink-500);
  margin: 5px 0 0;
}

.gap-list {
  align-content: start;
}

.gap-detail dl,
.rewrite-detail {
  display: grid;
  gap: 9px;
}

.gap-detail dl div,
.rewrite-detail div {
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 10px;
  grid-template-columns: 90px 1fr;
  padding-bottom: 8px;
}

dt {
  color: var(--ink-500);
}

dd {
  margin: 0;
}

.toggle-line {
  align-items: center;
  display: flex;
  gap: 8px;
}

.toggle-line input {
  width: auto;
}

.retrieval-form {
  display: grid;
  gap: 12px;
}

.retrieval-form .primary-btn {
  justify-self: start;
}

.search-results article {
  align-items: start;
  border-bottom: 1px solid var(--line);
  display: grid;
  gap: 12px;
  grid-template-columns: 26px 1fr auto;
  padding: 13px 0;
}

.search-results article > span {
  align-items: center;
  background: var(--ink-900);
  color: #fff;
  display: flex;
  font-size: 12px;
  height: 24px;
  justify-content: center;
  width: 24px;
}

.search-results p {
  color: var(--ink-700);
  line-height: 1.6;
  margin: 5px 0 0;
}

details summary {
  cursor: pointer;
  font-weight: 750;
}

.storage-result {
  display: grid;
  gap: 16px;
}

.health-heading {
  justify-content: flex-start;
}

.storage-counts {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(3, 1fr);
}

.storage-counts div {
  border-left: 2px solid var(--line-strong);
  display: grid;
  gap: 4px;
  padding-left: 12px;
}

.storage-counts span {
  color: var(--ink-500);
  font-size: 12px;
}

.storage-counts strong {
  font-size: 24px;
}

.empty-copy {
  color: var(--ink-500);
  margin: 18px 0 4px;
}

.primary-btn,
.secondary-btn {
  align-items: center;
  display: inline-flex;
  gap: 7px;
}

@media (max-width: 900px) {
  .content-split {
    grid-template-columns: 1fr;
  }

  .toolbar,
  .storage-toolbar {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .page-heading,
  .section-title {
    align-items: stretch;
    display: grid;
  }

  .document-list > button,
  .gap-list > button {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .document-list .status-label,
  .gap-list .status-label {
    grid-column: 2;
    justify-self: start;
  }

  .storage-counts {
    grid-template-columns: 1fr;
  }
}
</style>
