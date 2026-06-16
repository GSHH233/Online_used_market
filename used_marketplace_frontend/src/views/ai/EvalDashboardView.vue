<script setup lang="ts">
import { computed, ref } from 'vue'
import { aiApi } from '../../api/modules/ai'

const result = ref<any>(null)
const loading = ref(false)
const error = ref('')

const reports = computed(() => result.value?.recentReports || result.value?.reports || [])
const metrics = computed(() => result.value?.metrics || result.value?.latestMetrics || [])

async function run() {
  loading.value = true
  error.value = ''
  try {
    result.value = await aiApi.evalDashboard()
  } catch (e: any) {
    error.value = e?.message || '加载评测看板失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell">
    <section class="hero-card topbar">
      <div>
        <p class="eyebrow">Eval Dashboard</p>
        <h2>评测看板</h2>
        <p class="muted">查看最近的 RAG、Agent、Memory、Harness 和 AIOps 评测结果。</p>
      </div>
      <button class="primary-btn" :disabled="loading" @click="run">{{ loading ? '加载中...' : '刷新' }}</button>
    </section>

    <section v-if="metrics.length" class="panel">
      <h3>核心指标</h3>
      <div class="stat-grid">
        <div v-for="(item, index) in metrics" :key="index" class="stat-card">
          <span>{{ item.name || item.metricName || `Metric ${Number(index) + 1}` }}</span>
          <strong>{{ item.value ?? item.metricValue ?? '-' }}</strong>
        </div>
      </div>
    </section>

    <section class="panel">
      <h3>最近报告</h3>
      <div v-if="reports.length" class="timeline">
        <div v-for="(report, index) in reports" :key="index" class="timeline-item">
          <strong>{{ report.name || report.reportName || report.evalType || `Report ${Number(index) + 1}` }}</strong>
          <p>{{ report.status || report.summary || report.path || report.createdAt || '暂无摘要' }}</p>
        </div>
      </div>
      <p v-else class="muted">暂无报告，点击刷新后查看。</p>
      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <section class="panel">
      <h3>原始数据</h3>
      <pre>{{ result || '暂无数据' }}</pre>
    </section>
  </div>
</template>
