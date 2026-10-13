<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Ban, Pause, Play, RefreshCw, RotateCcw } from 'lucide-vue-next'
import { runtimeApi } from '../../api/modules/runtime'
import { useAuthStore } from '../../stores/auth'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore()
const run = ref<any>(null); const checkpoints = ref<any[]>([]); const loading = ref(false); const actionLoading = ref(''); const error = ref('')
const runId = computed(() => String(route.params.runId || ''))
const canPause = computed(() => auth.isAdmin && ['RUNNING','RESUMING'].includes(run.value?.status))
const canResume = computed(() => auth.isAdmin && ['FAILED','PAUSED'].includes(run.value?.status))
const canCancel = computed(() => auth.isAdmin && ['CREATED','PLANNING','RUNNING','RESUMING','PAUSED','FAILED'].includes(run.value?.status))
const canReplay = computed(() => auth.isAdmin && ['COMPLETED','FAILED','CANCELLED'].includes(run.value?.status))
function statusClass(status?: string){if(['COMPLETED','SUCCEEDED'].includes(String(status)))return'status-success';if(['FAILED','CANCELLED'].includes(String(status)))return'status-failed';if(['RUNNING','RESUMING','PLANNING'].includes(String(status)))return'status-running';return''}
function statusLabel(status?: string){const labels:Record<string,string>={CREATED:'已创建',PLANNING:'规划中',RUNNING:'执行中',RESUMING:'恢复中',PAUSED:'已暂停',COMPLETED:'已完成',SUCCEEDED:'已完成',FAILED:'执行失败',CANCELLED:'已取消',ESCALATED:'已转人工'};return labels[String(status||'').toUpperCase()]||'待确认'}
function observationSummary(value?: string){if(!value||value==='{}')return'本节点未产生业务结果';try{const parsed=JSON.parse(value);if(typeof parsed!=='object'||!parsed)return String(parsed);const preferred=parsed.summary||parsed.message||parsed.answer||parsed.status;if(preferred)return String(preferred);const fields=Object.entries(parsed).filter(([,item])=>['string','number','boolean'].includes(typeof item)).slice(0,4);return fields.length?fields.map(([key,item])=>`${key}: ${item}`).join(' · '):'节点结果已保存，可通过 Trace 进一步审计'}catch{return value}}
async function load(){loading.value=true;error.value='';try{const [detail,items]=await Promise.all([runtimeApi.get(runId.value),runtimeApi.checkpoints(runId.value)]);run.value=detail;checkpoints.value=Array.isArray(items)?items:[]}catch(e:any){error.value=e?.message||'加载运行详情失败'}finally{loading.value=false}}
async function act(name:string,call:()=>Promise<any>,confirmText:string){if(!window.confirm(confirmText))return;actionLoading.value=name;error.value='';try{const data:any=await call();if(data?.runId&&data.runId!==runId.value){await router.push(`/ai/runtime/${data.runId}`);return}await load()}catch(e:any){error.value=e?.message||`${name}失败`}finally{actionLoading.value=''}}
onMounted(load)
</script>

<template>
  <main class="page-shell runtime-detail">
    <section class="hero-card topbar">
      <div><button class="text-btn" type="button" @click="router.push('/ai/runtime')"><ArrowLeft :size="16" />返回运行记录</button><p class="eyebrow">运行详情</p><h2>{{ run ? statusLabel(run.status) : '加载中' }}</h2><p class="muted">{{ run?.question || '正在读取任务信息' }}</p></div>
      <button class="secondary-btn icon-action" :disabled="loading" type="button" @click="load"><RefreshCw :size="16" />刷新</button>
    </section>
    <template v-if="run">
      <section class="panel summary-grid"><div><span>当前状态</span><strong>{{ statusLabel(run.status) }}</strong></div><div><span>节点进度</span><strong>{{ run.completedNodeCount }}/{{ run.totalNodeCount }}</strong></div><div><span>总耗时</span><strong>{{ run.elapsedMs != null ? `${run.elapsedMs}ms` : '—' }}</strong></div></section>
      <section v-if="auth.isAdmin" class="panel action-bar">
        <button v-if="canPause" class="secondary-btn icon-action" :disabled="!!actionLoading" @click="act('暂停',()=>runtimeApi.pause(runId),'暂停将在下一个节点边界生效，确认继续吗？')"><Pause :size="16" />暂停</button>
        <button v-if="canResume" class="primary-btn icon-action" :disabled="!!actionLoading" @click="act('恢复',()=>runtimeApi.resume(runId),'恢复会复用成功节点快照，只执行剩余节点。确认继续吗？')"><Play :size="16" />恢复</button>
        <button v-if="canCancel" class="secondary-btn icon-action" :disabled="!!actionLoading" @click="act('取消',()=>runtimeApi.cancel(runId),'取消后当前任务不可继续，确认吗？')"><Ban :size="16" />取消</button>
        <button v-if="canReplay" class="secondary-btn icon-action" :disabled="!!actionLoading" @click="act('快照重放',()=>runtimeApi.replay(runId,false),'快照重放不会调用真实工具，确认创建新任务吗？')"><RotateCcw :size="16" />快照重放</button>
        <button v-if="canReplay" class="secondary-btn icon-action" :disabled="!!actionLoading" @click="act('只读重放',()=>runtimeApi.replay(runId,true),'将重新执行只读工具以比较环境变化，确认吗？')"><RotateCcw :size="16" />只读实时重放</button>
      </section>
      <section class="panel"><h3>任务问题</h3><p>{{ run.question }}</p><h3>最终结果</h3><p class="answer">{{ run.answer || '任务尚未生成最终结果。' }}</p><p v-if="run.errorMsg" class="error">{{ run.errorMsg }}</p></section>
      <details class="panel technical-details">
        <summary>查看节点、恢复点和技术信息</summary>
        <div class="technical-grid"><div><span>执行策略</span><strong>{{ run.strategy || 'DIRECT' }}</strong></div><div><span>运行编号</span><strong>{{ runId }}</strong></div><div><span>链路编号</span><strong class="trace">{{ run.traceId || '—' }}</strong></div></div>
        <div class="grid two-col runtime-grid">
          <section><h3>节点执行</h3><div v-if="run.nodes?.length" class="node-list"><details v-for="node in run.nodes" :key="node.nodeId" class="node"><summary><b :class="statusClass(node.status)">{{ statusLabel(node.status) }}</b><span>{{ node.nodeId }}</span><small>尝试 {{ node.attemptCount }} 次 · {{ node.elapsedMs ?? '—' }}ms</small></summary><p>工具：{{ node.toolName || '无' }} / 依赖：{{ node.dependencies?.join(', ') || '无' }}</p><p class="observation">{{ observationSummary(node.observationJson) }}</p><p v-if="node.errorMsg" class="error">{{ node.errorMsg }}</p></details></div><p v-else class="muted">暂无节点。</p></section>
          <section><h3>恢复点</h3><div v-if="checkpoints.length" class="timeline compact"><div v-for="item in checkpoints" :key="item.checkpointSeq" class="timeline-item"><strong>#{{ item.checkpointSeq }} {{ item.reason }}</strong><p>{{ item.createdTime ? new Date(item.createdTime).toLocaleString() : '' }}</p><small>状态指纹 {{ item.stateHash?.slice(0,16) }}</small></div></div><p v-else class="muted">暂无恢复点。</p></section>
        </div>
      </details>
    </template>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </main>
</template>

<style scoped>
.runtime-detail{display:grid;gap:16px}.text-btn,.icon-action{display:inline-flex;align-items:center;gap:7px}.text-btn{background:transparent;border:0;padding:0;margin-bottom:12px}.summary-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}.summary-grid div,.technical-grid div{display:grid;gap:5px}.summary-grid span,.technical-grid span{color:var(--ink-500);font-size:12px}.trace{font-size:13px;overflow-wrap:anywhere}.action-bar{display:flex;gap:8px;flex-wrap:wrap}.answer{white-space:pre-wrap;line-height:1.75}.technical-details>summary{cursor:pointer;font-weight:750}.technical-grid{display:grid;grid-template-columns:1fr 1fr 2fr;gap:18px;margin:16px 0}.runtime-grid{align-items:start}.node-list{display:grid;gap:8px}.node{border:1px solid var(--line);padding:10px}.node summary{display:grid;grid-template-columns:100px 1fr auto;gap:8px;cursor:pointer}.node small{color:var(--ink-500)}.observation{background:rgba(41,75,61,.05);border-left:2px solid var(--line-strong);line-height:1.6;padding:9px 10px}@media(max-width:760px){.summary-grid,.technical-grid{grid-template-columns:1fr}.node summary{grid-template-columns:1fr}.runtime-grid{grid-template-columns:1fr}}
</style>
