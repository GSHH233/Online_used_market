import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { aiApi } from '../api/modules/ai'
import { buildWebSocketUrl } from '../api/request'
import { useAuthStore } from './auth'

type ChatMessage = { role: 'user' | 'assistant' | 'system'; content: string }
type WsStatus = 'IDLE' | 'CONNECTING' | 'OPEN' | 'CLOSED'
const conversationStorageKey = 'ai-rag-conversation-id'

function currentConversationId(): string {
  let id = sessionStorage.getItem(conversationStorageKey)
  if (!id) {
    id = crypto.randomUUID()
    sessionStorage.setItem(conversationStorageKey, id)
  }
  return id
}

export const useAiChatStore = defineStore('aiChat', () => {
  const auth = useAuthStore()
  const messages = ref<ChatMessage[]>([])
  const sources = ref<any[]>([])
  const currentTraceId = ref('')
  const lowConfidence = ref(false)
  const debugPanelVisible = ref(false)
  const wsStatus = ref<WsStatus>('IDLE')
  const wsEvents = ref<any[]>([])
  const rawPayload = ref<any>(null)
  const isStreaming = ref(false)
  const responseMode = ref('')
  const fallbackUsed = ref(false)
  const fallbackReason = ref('')
  let socket: WebSocket | null = null
  let connecting: Promise<void> | null = null
  let connectionEpoch = 0

  async function runEval(payload: any) {
    const data = (await aiApi.ragEval(payload)) as any
    rawPayload.value = data
    messages.value = [
      { role: 'user', content: payload.question || '' },
      { role: 'assistant', content: data?.answer || '' },
    ]
    sources.value = data?.sources || []
    currentTraceId.value = payload.requestId
    lowConfidence.value = Boolean(data?.lowConfidence)
    return data
  }

  function appendAssistantDelta(delta: string) {
    const last = messages.value[messages.value.length - 1]
    if (!last || last.role !== 'assistant') {
      messages.value.push({ role: 'assistant', content: '' })
    }
    messages.value[messages.value.length - 1].content += delta
  }

  function connectWs(): Promise<void> {
    if (socket?.readyState === WebSocket.OPEN) return Promise.resolve()
    if (connecting) return connecting

    if (socket) socket.close()
    wsStatus.value = 'CONNECTING'

    connecting = new Promise((resolve, reject) => {
      const epoch = ++connectionEpoch
      socket = new WebSocket(buildWebSocketUrl('/ws/ai/rag-chat'))

      socket.onopen = () => {
        if (epoch !== connectionEpoch) {
          reject(new Error('登录状态已变化，请重新连接'))
          return
        }
        wsStatus.value = 'OPEN'
        connecting = null
        resolve()
      }
      socket.onclose = () => {
        if (epoch !== connectionEpoch) {
          reject(new Error('连接已关闭'))
          return
        }
        wsStatus.value = 'CLOSED'
        connecting = null
        reject(new Error('连接已关闭'))
      }
      socket.onerror = () => {
        if (epoch !== connectionEpoch) return
        wsStatus.value = 'CLOSED'
        connecting = null
        reject(new Error('WebSocket 连接失败'))
      }
      socket.onmessage = (e) => {
        if (epoch !== connectionEpoch) return
        const msg = JSON.parse(e.data)
        wsEvents.value.unshift(msg)
        if (msg.type === 'CHAT_ACK') currentTraceId.value = msg.requestId
        if (msg.type === 'CHAT_DELTA') appendAssistantDelta(msg.payload?.delta || '')
        if (msg.type === 'CHAT_SOURCE') sources.value.push(msg.payload)
        if (msg.type === 'CHAT_ERROR') {
          isStreaming.value = false
          messages.value.push({ role: 'system', content: msg.payload?.message || '回答生成失败' })
        }
        if (msg.type === 'CHAT_DONE') {
          rawPayload.value = msg.payload
          responseMode.value = msg.payload?.generationMode || ''
          fallbackUsed.value = Boolean(msg.payload?.fallbackUsed)
          fallbackReason.value = msg.payload?.fallbackReason || ''
          isStreaming.value = false
        }
      }
    })

    return connecting
  }

  function sendWs(payload: any) {
    socket?.send(JSON.stringify(payload))
  }

  async function startWsChat(payload: any) {
    const question = String(payload.question || '').trim()
    if (!question) return

    // Read the token at send time so an existing socket can be reused after
    // logout/login without carrying the previous user's identity.
    const token = String(auth.token || '').trim()
    if (!token) {
      throw new Error('请先登录后再使用聊天功能')
    }

    await connectWs()
    const requestId = payload.requestId || `ws-rag-${Date.now()}`
    currentTraceId.value = requestId
    sources.value = []
    rawPayload.value = null
    wsEvents.value = []
    responseMode.value = ''
    fallbackUsed.value = false
    fallbackReason.value = ''
    isStreaming.value = true
    messages.value.push({ role: 'user', content: question })
    messages.value.push({ role: 'assistant', content: '' })
    sendWs({
      type: 'CHAT_START',
      topK: 5,
      ...payload,
      question,
      requestId,
      token,
      conversationId: currentConversationId(),
    })
  }

  function cancelCurrent() {
    if (currentTraceId.value) {
      sendWs({ type: 'CHAT_CANCEL', requestId: currentTraceId.value })
    }
    isStreaming.value = false
  }

  function ping(requestId: string) {
    sendWs({ type: 'CHAT_PING', requestId })
  }

  function closeWs() {
    connectionEpoch++
    socket?.close()
    socket = null
    connecting = null
    wsStatus.value = 'CLOSED'
  }

  watch(() => auth.token, () => {
    closeWs()
    sessionStorage.removeItem(conversationStorageKey)
    messages.value = []
    sources.value = []
    wsEvents.value = []
    rawPayload.value = null
    currentTraceId.value = ''
    isStreaming.value = false
  })

  return {
    messages,
    sources,
    currentTraceId,
    lowConfidence,
    debugPanelVisible,
    wsStatus,
    wsEvents,
    rawPayload,
    isStreaming,
    responseMode,
    fallbackUsed,
    fallbackReason,
    runEval,
    connectWs,
    sendWs,
    startWsChat,
    cancelCurrent,
    ping,
    closeWs,
  }
})
