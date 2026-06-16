<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { aiApi } from '../../api/modules/ai'

const form = reactive({
  tool: 'prometheusQuery',
  mode: 'USER',
  input: '{"query":"up"}',
})

const loading = ref(false)
const result = ref<any>(null)
const error = ref('')

const metrics = computed(() => {
  const data = result.value || {}
  return [
    { label: '状态', value: data.status || '—' },
    { label: '耗时(ms)', value: data.elapsedMs ?? '—' },
    { label: '尝试次数', value: data.attempts ?? '—' },
    { label: '重试次数', value: data.retryCount ?? 0 },
    { label: '超时', value: data.timeout ? '是' : '否' },
    { label: 'Fallback', value: data.fallbackUsed ? '是' : '否' },
    { label: '快照恢复', value: data.snapshotRestored ? '是' : '否' },
  ]
})

const metadataItems = computed(() => {
  const meta = result.value?.metadata || {}
  return Object.entries(meta).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value),
  }))
})

function pretty(input: unknown) {
  if (input == null) return '暂无结果'
  return JSON.stringify(input, null, 2)
}

function parseInput(value: string) {
  if (!value.trim()) return {}
  return JSON.parse(value)
}

async function run() {
  loading.value = true
  error.value = ''
  try {
    result.value = await aiApi.harnessExecute({ tool: form.tool, mode: form.mode, input: parseInput(form.input) })
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
        <p class="eyebrow">Harness Debug</p>
        <h2>工具执行容器调试</h2>
        <p class="muted">用于检查工具执行的重试、超时、快照恢复与 fallback 行为，方便定位编排问题。</p>
      </div>
      <button class="primary-btn" :disabled="loading" @click="run">{{ loading ? '执行中...' : '执行' }}</button>
    </div>

    <div class="grid two-col">
      <section class="panel">
        <h3>执行参数</h3>
        <label>Tool<input v-model="form.tool" placeholder="prometheusQuery" /></label>
        <label>Mode<input v-model="form.mode" placeholder="USER / INTERNAL" /></label>
        <label>Input<textarea v-model="form.input" rows="10" /></label>
      </section>

      <section class="panel">
        <h3>执行摘要</h3>
        <div class="stat-grid">
          <div v-for="item in metrics" :key="item.label" class="stat-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
        <div class="chip-row">
          <span class="chip">{{ result?.tool || form.tool }}</span>
          <span class="chip">{{ result?.status || 'PENDING' }}</span>
          <span class="chip">{{ result?.observation ? 'HAS_OBSERVATION' : 'NO_OBSERVATION' }}</span>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
      </section>
    </div>

    <div class="grid three-col">
      <section class="panel">
        <h3>观察输出</h3>
        <pre>{{ result?.observation || '暂无观察输出' }}</pre>
      </section>
      <section class="panel">
        <h3>元数据</h3>
        <div v-if="metadataItems.length" class="timeline compact">
          <div v-for="item in metadataItems" :key="item.key" class="timeline-item">
            <strong>{{ item.key }}</strong>
            <p>{{ item.value }}</p>
          </div>
        </div>
        <p v-else class="muted">暂无元数据</p>
      </section>
      <section class="panel">
        <h3>执行结果</h3>
        <pre>{{ pretty(result) }}</pre>
      </section>
    </div>
  </div>
</template>
