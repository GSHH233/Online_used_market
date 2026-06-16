<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useAiChatStore } from '../../stores/aiChat'
import { useAuthStore } from '../../stores/auth'

const store = useAiChatStore()
const auth = useAuthStore()
const form = reactive({
  question: '二手手机标题写“全新未拆封但无发票”有风险吗？',
  topK: 5,
})
const loading = ref(false)
const error = ref('')

const quickQuestions = [
  '二手手机写全新未拆封但无发票，有风险吗？',
  '显卡有跑分截图但温度偏高，标题怎么写更稳？',
  '相机快门次数比较高，发布时需要说明什么？',
  '无人机缺电池还能发布吗？',
]

const hasAnswer = computed(() => store.messages.some((item) => item.role === 'assistant' && item.content.trim()))
const isStreaming = computed(() => loading.value || store.wsStatus === 'CONNECTING')

function sourceTitle(source: any, index: number) {
  return source?.title || source?.docTitle || source?.sourceTitle || `引用依据 ${index + 1}`
}

function sourceContent(source: any) {
  return source?.snippet || source?.content || source?.text || source?.chunk || JSON.stringify(source)
}

async function send() {
  if (!form.question.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try {
    await store.startWsChat({
      question: form.question,
      topK: form.topK,
      filter: { sourceType: 'RULE' },
    })
  } catch (e: any) {
    error.value = e?.message || '发送失败'
  } finally {
    loading.value = false
  }
}

function useQuestion(question: string) {
  form.question = question
}

function stop() {
  store.cancelCurrent()
}
</script>

<template>
  <div class="page-shell rag-chat-page">
    <section class="hero-card chat-hero">
      <div>
        <p class="eyebrow">规则问答</p>
        <h2>不确定能不能写，先问一下</h2>
        <p class="muted">把标题、描述或交易情况发过来，系统会结合平台规则给出解释和建议。</p>
      </div>
      <span class="status-pill" :class="{ online: store.wsStatus === 'OPEN' }">
        {{ store.wsStatus === 'OPEN' ? '实时回答中' : '准备就绪' }}
      </span>
    </section>

    <section class="panel chat-panel">
      <div class="question-pills" aria-label="常见问题">
        <button
          v-for="question in quickQuestions"
          :key="question"
          class="question-pill"
          type="button"
          @click="useQuestion(question)"
        >
          {{ question }}
        </button>
      </div>

      <div class="message-list">
        <div v-if="!store.messages.length" class="empty-state">
          <strong>可以直接问发布风险</strong>
          <p>建议把商品、成色、凭证、缺失配件一起写清楚，回答会更准确。</p>
        </div>
        <article v-for="(message, index) in store.messages" :key="index" class="message-row" :class="message.role">
          <div class="message-bubble">
            <strong>{{ message.role === 'user' ? '你' : message.role === 'assistant' ? 'AI 发布助手' : '系统' }}</strong>
            <p>{{ message.content || (message.role === 'assistant' ? '正在生成...' : '') }}</p>
          </div>
        </article>
      </div>

      <div v-if="store.sources.length" class="source-list">
        <h3>参考规则</h3>
        <details v-for="(source, index) in store.sources" :key="index" class="source-item">
          <summary>{{ sourceTitle(source, index) }}</summary>
          <p>{{ sourceContent(source) }}</p>
        </details>
      </div>

      <form class="chat-input" @submit.prevent="send">
        <textarea
          v-model="form.question"
          rows="3"
          placeholder="输入你想确认的发布风险或规则问题"
          @keydown.ctrl.enter.prevent="send"
        />
        <div class="chat-actions">
          <button type="button" class="secondary-btn" :disabled="!hasAnswer && !isStreaming" @click="stop">停止</button>
          <label v-if="auth.isInternal" class="topk-field">
            TopK
            <input v-model.number="form.topK" type="number" min="1" max="10" />
          </label>
          <button class="primary-btn" :disabled="loading || !form.question.trim()">
            {{ loading ? '发送中...' : '发送' }}
          </button>
        </div>
      </form>

      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <details v-if="auth.isInternal" class="panel debug-details">
      <summary>调试信息</summary>
      <div class="grid two-col">
        <div>
          <h3>Trace</h3>
          <p>{{ store.currentTraceId || '暂无' }}</p>
          <p>连接状态：{{ store.wsStatus }}</p>
        </div>
        <div>
          <h3>Raw Payload</h3>
          <pre>{{ store.rawPayload || '暂无' }}</pre>
        </div>
      </div>
      <h3>WebSocket Event</h3>
      <pre>{{ store.wsEvents.slice(0, 8) }}</pre>
    </details>
  </div>
</template>

<style scoped>
.rag-chat-page {
  gap: 16px;
}

.chat-hero {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.status-pill {
  border: 1px solid rgba(23, 19, 17, .22);
  border-radius: 999px;
  padding: 8px 12px;
  color: var(--ink-500);
  background: rgba(255, 255, 255, .65);
  white-space: nowrap;
}

.status-pill.online {
  color: var(--success);
  border-color: rgba(31, 122, 69, .35);
}

.chat-panel {
  display: grid;
  gap: 16px;
}

.question-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.question-pill {
  background: #fffdf8;
  border: 1px solid rgba(23, 19, 17, .14);
  border-radius: 999px;
  color: var(--ink-700);
  font-size: 13px;
  padding: 8px 12px;
  text-align: left;
}

.question-pill:hover {
  background: rgba(143, 47, 28, .92);
  border-color: var(--accent);
  color: #fff;
}

.message-list {
  min-height: 360px;
  display: grid;
  align-content: start;
  gap: 12px;
}

.empty-state {
  padding: 28px;
  border: 1px dashed rgba(23, 19, 17, .22);
  border-radius: 16px;
  background: rgba(255, 255, 255, .58);
}

.message-row {
  display: flex;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: min(720px, 88%);
  border: 1px solid rgba(23, 19, 17, .15);
  border-radius: 16px;
  padding: 12px 14px;
  background: rgba(255, 255, 255, .72);
}

.message-row.user .message-bubble {
  background: rgba(143, 47, 28, .92);
  color: #fff;
}

.message-bubble p {
  margin: 8px 0 0;
  white-space: pre-wrap;
  line-height: 1.7;
}

.source-list {
  display: grid;
  gap: 8px;
}

.source-item {
  border: 1px solid rgba(23, 19, 17, .14);
  border-radius: 12px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, .62);
}

.source-item summary {
  cursor: pointer;
  font-weight: 700;
}

.chat-input {
  display: grid;
  gap: 10px;
}

.chat-actions {
  display: flex;
  justify-content: flex-end;
  align-items: end;
  gap: 10px;
  flex-wrap: wrap;
}

.topk-field {
  width: 96px;
  margin: 0;
}

.debug-details summary {
  cursor: pointer;
  font-weight: 700;
}

@media (max-width: 760px) {
  .chat-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .message-bubble {
    max-width: 100%;
  }
}
</style>
