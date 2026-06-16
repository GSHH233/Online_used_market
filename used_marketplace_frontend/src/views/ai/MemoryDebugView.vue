<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useMemoryStore } from '../../stores/memory'

const store = useMemoryStore()
const form = reactive({
  userId: 1,
  sessionId: 'debug-session',
  q: '偏好 品牌 预算 成色 交易 风险 文案',
  topK: 10,
  threshold: 0.05,
})
const graphForm = reactive({
  userId: 1,
  sessionId: 'debug-session',
  text: '我长期学习 Go，关注 AI Infra，喜欢结构化回答。',
})
const loading = ref('')
const error = ref('')

async function runL2Query() {
  loading.value = 'l2'
  error.value = ''
  try {
    await store.queryL2(form)
  } catch (e: any) {
    error.value = e?.message || '查询 L2 记忆失败'
  } finally {
    loading.value = ''
  }
}

async function runProfile() {
  loading.value = 'profile'
  error.value = ''
  try {
    await store.loadProfile({ userId: form.userId, sessionId: form.sessionId })
  } catch (e: any) {
    error.value = e?.message || '查询 Profile 失败'
  } finally {
    loading.value = ''
  }
}

async function runGraphExtract() {
  loading.value = 'graph'
  error.value = ''
  try {
    await store.extractGraph(graphForm)
  } catch (e: any) {
    error.value = e?.message || '抽取记忆图谱失败'
  } finally {
    loading.value = ''
  }
}
</script>

<template>
  <div class="page-shell">
    <section class="hero-card">
      <p class="eyebrow">Memory Debug</p>
      <h2>记忆系统调试</h2>
      <p class="muted">用于检查 L2 语义召回、结构化 Profile 和 Memory Graph 抽取结果。</p>
    </section>

    <div class="grid two-col">
      <section class="panel">
        <h3>L2 / Profile 参数</h3>
        <label>User ID<input v-model.number="form.userId" type="number" min="1" /></label>
        <label>Session ID<input v-model="form.sessionId" /></label>
        <label>Query<textarea v-model="form.q" rows="4" /></label>
        <div class="grid two-col">
          <label>TopK<input v-model.number="form.topK" type="number" min="1" max="50" /></label>
          <label>Threshold<input v-model.number="form.threshold" type="number" min="0" step="0.01" /></label>
        </div>
        <div class="inline-actions">
          <button class="primary-btn" :disabled="Boolean(loading)" @click="runL2Query">
            {{ loading === 'l2' ? '查询中...' : '查询 L2' }}
          </button>
          <button class="secondary-btn" :disabled="Boolean(loading)" @click="runProfile">
            {{ loading === 'profile' ? '查询中...' : '查询 Profile' }}
          </button>
        </div>
      </section>

      <section class="panel">
        <h3>Graph 抽取</h3>
        <label>User ID<input v-model.number="graphForm.userId" type="number" min="1" /></label>
        <label>Session ID<input v-model="graphForm.sessionId" /></label>
        <label>Text<textarea v-model="graphForm.text" rows="7" /></label>
        <button class="primary-btn" :disabled="Boolean(loading)" @click="runGraphExtract">
          {{ loading === 'graph' ? '抽取中...' : '抽取图谱' }}
        </button>
      </section>
    </div>

    <div class="grid three-col">
      <section class="panel">
        <h3>L2 Hits</h3>
        <pre>{{ store.l2Hits.length ? store.l2Hits : '暂无' }}</pre>
      </section>
      <section class="panel">
        <h3>Profile</h3>
        <pre>{{ store.profile || '暂无' }}</pre>
      </section>
      <section class="panel">
        <h3>Graph Result</h3>
        <pre>{{ store.memoryAssemblyDebug || '暂无' }}</pre>
      </section>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
  </div>
</template>
