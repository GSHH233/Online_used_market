<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { aiApi } from '../../api/modules/ai'

const form = reactive({
  question: '图执行一次完整发布分析',
})

const loading = ref(false)
const result = ref<any>(null)
const error = ref('')

const nodes = computed(() => result.value?.graph?.nodes || result.value?.nodes || [])
const edges = computed(() => result.value?.graph?.edges || result.value?.edges || [])
const trace = computed(() => result.value?.agentTrace || result.value?.trace || [])
const toolCalls = computed(() => result.value?.toolCalls || result.value?.tools || result.value?.actions || [])
const state = computed(() => result.value?.state || result.value?.graph || result.value?.context || result.value || {})

function snippet(value: unknown) {
  if (value == null) return '—'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

async function run() {
  loading.value = true
  error.value = ''
  try {
    result.value = await aiApi.graphAgent(form)
  } catch (e: any) {
    error.value = e?.message || '执行失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell">
    <div class="hero-card">
      <div>
        <p class="eyebrow">Graph Agent</p>
        <h2>Trace / Tool-call / State 三栏视图</h2>
        <p class="muted">围绕节点、边、工具调用和状态快照展示图执行过程，而不是直接堆 JSON。</p>
      </div>
      <button class="primary-btn" :disabled="loading" @click="run">{{ loading ? '运行中...' : '运行' }}</button>
    </div>

    <div class="grid two-col">
      <section class="panel">
        <h3>输入</h3>
        <label>Question<textarea v-model="form.question" rows="4" /></label>
      </section>

      <section class="panel">
        <h3>摘要</h3>
        <div class="stat-grid">
          <div class="stat-card"><span>节点</span><strong>{{ nodes.length }}</strong></div>
          <div class="stat-card"><span>边</span><strong>{{ edges.length }}</strong></div>
          <div class="stat-card"><span>Trace</span><strong>{{ trace.length }}</strong></div>
        </div>
      </section>
    </div>

    <div class="grid three-col">
      <section class="panel">
        <h3>Trace</h3>
        <div v-if="trace.length" class="timeline compact">
          <div v-for="(step, index) in trace" :key="index" class="timeline-item">
            <strong>{{ step.action || step.type || `Step ${Number(index) + 1}` }}</strong>
            <p>{{ snippet(step.thought || step.input || step.output || step.observation || step) }}</p>
          </div>
        </div>
        <p v-else class="muted">暂无 trace</p>
      </section>

      <section class="panel">
        <h3>Tool Call</h3>
        <div v-if="toolCalls.length" class="timeline compact">
          <div v-for="(call, index) in toolCalls" :key="index" class="timeline-item">
            <strong>{{ call.tool || call.name || call.action || `Tool ${Number(index) + 1}` }}</strong>
            <p>{{ snippet(call.args || call.input || call.parameters || call.result || call) }}</p>
          </div>
        </div>
        <p v-else class="muted">暂无工具调用</p>
      </section>

      <section class="panel">
        <h3>State</h3>
        <div class="timeline compact">
          <div class="timeline-item">
            <strong>图状态</strong>
            <p>{{ snippet(state) }}</p>
          </div>
          <div class="timeline-item">
            <strong>节点列表</strong>
            <p>{{ snippet(nodes) }}</p>
          </div>
          <div class="timeline-item">
            <strong>边列表</strong>
            <p>{{ snippet(edges) }}</p>
          </div>
        </div>
      </section>
    </div>

    <details class="panel">
      <summary>原始结果</summary>
      <pre>{{ result || '暂无结果' }}</pre>
      <p v-if="error" class="error">{{ error }}</p>
    </details>
  </div>
</template>

<style scoped>
details summary {
  cursor: pointer;
  font-weight: 800;
}
</style>
