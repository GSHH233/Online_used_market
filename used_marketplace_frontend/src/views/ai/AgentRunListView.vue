<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { RefreshCw } from 'lucide-vue-next'
import { runtimeApi, type RuntimeRun } from '../../api/modules/runtime'

const router = useRouter()
const runs = ref<RuntimeRun[]>([])
const loading = ref(false)
const error = ref('')
const counts = computed(() => ({
  total: runs.value.length,
  running: runs.value.filter((r) => ['RUNNING', 'RESUMING', 'PLANNING'].includes(String(r.status))).length,
  failed: runs.value.filter((r) => r.status === 'FAILED').length,
  completed: runs.value.filter((r) => r.status === 'COMPLETED').length,
}))

function statusClass(status?: string) {
  if (status === 'COMPLETED') return 'status-success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'status-failed'
  if (['RUNNING', 'RESUMING', 'PLANNING'].includes(String(status))) return 'status-running'
  return ''
}
function formatTime(value?: string) { return value ? new Date(value).toLocaleString() : '—' }
async function load() {
  loading.value = true; error.value = ''
  try { runs.value = await runtimeApi.list(30) }
  catch (e: any) { error.value = e?.message || '加载运行记录失败' }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <main class="page-shell runtime-page">
    <section class="hero-card topbar">
      <div><p class="eyebrow">运行记录</p><h2>智能排查审计记录</h2><p class="muted">查看历史问题、执行状态和失败恢复记录；新排查请从“智能排查”发起。</p></div>
      <button class="secondary-btn icon-action" :disabled="loading" type="button" @click="load"><RefreshCw :size="17" />{{ loading ? '刷新中' : '刷新' }}</button>
    </section>
    <section class="panel">
      <div class="stat-grid">
        <div class="stat-card"><span>最近任务</span><strong>{{ counts.total }}</strong></div>
        <div class="stat-card"><span>运行中</span><strong>{{ counts.running }}</strong></div>
        <div class="stat-card"><span>已完成</span><strong>{{ counts.completed }}</strong></div>
        <div class="stat-card"><span>失败</span><strong>{{ counts.failed }}</strong></div>
      </div>
    </section>
    <section class="panel">
      <h3>最近记录</h3>
      <div v-if="runs.length" class="run-list">
        <button v-for="run in runs" :key="run.runId" class="run-row" type="button" @click="router.push(`/ai/runtime/${run.runId}`)">
          <span><b :class="statusClass(run.status)">{{ run.status }}</b><small>{{ run.strategy || '自动路由' }}</small></span>
          <span class="question">{{ run.question }}</span>
          <span class="progress">{{ run.elapsedMs != null ? `${run.elapsedMs}ms` : '进行中' }}</span>
          <time>{{ formatTime(String(run.updatedAt || run.updatedTime || '')) }}</time>
        </button>
      </div>
      <p v-else-if="!loading" class="muted">暂无运行记录。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </section>
  </main>
</template>

<style scoped>
.runtime-page{display:grid;gap:16px}.icon-action{display:flex;align-items:center;gap:8px}.run-list{display:grid}.run-row{display:grid;grid-template-columns:150px minmax(240px,1fr) 100px 170px;gap:12px;align-items:center;background:transparent;border:0;border-bottom:1px solid var(--line);border-radius:0;padding:13px 6px;text-align:left}.run-row:hover{background:rgba(41,75,61,.05)}.run-row span:first-child{display:flex;gap:8px;align-items:center}.run-row small,.run-row time,.progress{color:var(--ink-500);font-size:12px}.question{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}@media(max-width:760px){.run-row{grid-template-columns:1fr auto}.run-row .question{grid-column:1/-1;white-space:normal}.run-row time{display:none}}
</style>
