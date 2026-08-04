<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { aiApi } from '../../api/modules/ai'

const form = reactive({
  docId: '',
  sampleDocs: 10,
  perDocLimit: 200,
})

const loading = ref(false)
const result = ref<any>(null)
const error = ref('')

const summary = computed(() => {
  const data = result.value?.summary ?? result.value
  if (!data) return []
  return [
    { label: '检查文档数', value: data.checkedDocs ?? data.sampleDocs ?? data.sampleCount ?? '—' },
    { label: '一致文档数', value: data.consistentDocs ?? data.consistentCount ?? '—' },
    { label: '不一致文档数', value: data.inconsistentDocs ?? data.inconsistentCount ?? '—' },
    { label: '通过率', value: data.passRate != null ? `${Math.round(Number(data.passRate) * 100)}%` : '—' },
    { label: '存储类型', value: (data.storage || []).join(' / ') || '—' },
  ]
})

const docs = computed(() => result.value?.docs || [])

function formatList(items: unknown) {
  if (!Array.isArray(items) || !items.length) return '—'
  return items.join(', ')
}

async function run() {
  loading.value = true
  error.value = ''
  try {
    result.value = await aiApi.storageConsistency({
      docId: form.docId || undefined,
      sampleDocs: form.sampleDocs,
      perDocLimit: form.perDocLimit,
    })
  } catch (e: any) {
    error.value = e?.message || '检查失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page-shell">
    <div class="hero-card">
      <div>
        <p class="eyebrow">Storage Consistency</p>
        <h2>三存储一致性检查与 Repair 视图</h2>
        <p class="muted">对 MySQL / Elasticsearch / Milvus 的文档、切片和索引状态进行统一核对。</p>
      </div>
      <button class="primary-btn" :disabled="loading" @click="run">{{ loading ? '检查中...' : '开始检查' }}</button>
    </div>

    <div class="grid two-col">
      <section class="panel">
        <h3>参数</h3>
        <label>Doc ID<input v-model="form.docId" placeholder="可选" /></label>
        <label>抽样文档数<input v-model.number="form.sampleDocs" type="number" min="1" /></label>
        <label>每文档切片上限<input v-model.number="form.perDocLimit" type="number" min="1" /></label>
      </section>

      <section class="panel">
        <h3>摘要</h3>
        <div class="stat-grid">
          <div v-for="item in summary" :key="item.label" class="stat-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
      </section>
    </div>

    <section class="panel">
      <h3>文档差异明细</h3>
      <div v-if="docs.length" class="timeline">
        <div v-for="doc in docs" :key="doc.docId" class="timeline-item">
          <strong>Doc {{ doc.docId }} - {{ doc.consistent ? '一致' : '不一致' }}</strong>
          <p>DB {{ doc.dbCount }} / ES {{ doc.esCount }} / Milvus {{ doc.milvusCount }}</p>
          <p>缺失 ES：{{ formatList(doc.missingInEs) }}</p>
          <p>缺失 Milvus：{{ formatList(doc.missingInMilvus) }}</p>
          <p>ES 额外：{{ formatList(doc.extraInEs) }}</p>
          <p>Milvus 额外：{{ formatList(doc.extraInMilvus) }}</p>
        </div>
      </div>
      <p v-else class="muted">暂无结果</p>
    </section>

    <details class="panel">
      <summary>原始结果</summary>
      <pre>{{ result || '暂无结果' }}</pre>
    </details>
  </div>
</template>

<style scoped>
details summary {
  cursor: pointer;
  font-weight: 800;
}
</style>
