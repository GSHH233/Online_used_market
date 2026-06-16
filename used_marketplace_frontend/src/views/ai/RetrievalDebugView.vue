<script setup lang="ts">
import { reactive, ref } from 'vue'
import { aiApi } from '../../api/modules/ai'

const form = reactive({ question: 'RTX3060 温度偏高是不是矿卡', topK: 5, mode: 'HYBRID', sourceType: 'RULE', category: '显卡' })
const result = ref<any>(null)

async function run() {
  result.value = await aiApi.searchKnowledge({
    question: form.question,
    topK: form.topK,
    mode: form.mode,
    filter: { sourceType: form.sourceType, category: form.category, riskLevel: null },
  })
}
</script>
<template>
  <div>
    <h2>检索调试</h2>
    <label>问题<input v-model="form.question" /></label>
    <button @click="run">检索</button>
    <pre>{{ result || '暂无' }}</pre>
  </div>
</template>