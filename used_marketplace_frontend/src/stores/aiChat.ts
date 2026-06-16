import { defineStore } from 'pinia'
import { ref } from 'vue'
import { aiApi } from '../api/modules/ai'
import { buildWebSocketUrl } from '../api/request'

type ChatMessage = { role: 'user' | 'assistant' | 'system'; content: string }
type WsStatus = 'IDLE' | 'CONNECTING' | 'OPEN' | 'CLOSED'

export const useAiChatStore = defineStore('aiChat', () => {
  const messages = ref<ChatMessage[]>([])
  const sources = ref<any[]>([])
  const currentTraceId = ref('')
  const lowConfidence = ref(false)
  const debugPanelVisible = ref(false)
  const wsStatus = ref<WsStatus>('IDLE')
  const wsEvents = ref<any[]>([])
  const rawPayload = ref<any>(null)
  let socket: WebSocket | null = null
  let connecting: Promise<void> | null = null

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
      socket = new WebSocket(buildWebSocketUrl('/ws/ai/rag-chat'))

      socket.onopen = () => {
        wsStatus.value = 'OPEN'
        connecting = null
        resolve()
      }
      socket.onclose = () => {
        wsStatus.value = 'CLOSED'
        connecting = null
      }
      socket.onerror = () => {
        wsStatus.value = 'CLOSED'
        connecting = null
        reject(new Error('WebSocket 连接失败'))
      }
      socket.onmessage = (e) => {
        const msg = JSON.parse(e.data)
        wsEvents.value.unshift(msg)
        if (msg.type === 'CHAT_ACK') currentTraceId.value = msg.requestId
        if (msg.type === 'CHAT_DELTA') appendAssistantDelta(msg.payload?.delta || '')
        if (msg.type === 'CHAT_SOURCE') sources.value.push(msg.payload)
        if (msg.type === 'CHAT_ERROR') messages.value.push({ role: 'system', content: msg.payload?.message || '回答生成失败' })
        if (msg.type === 'CHAT_DONE') rawPayload.value = msg.payload
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

    await connectWs()
    const requestId = payload.requestId || `ws-rag-${Date.now()}`
    currentTraceId.value = requestId
    sources.value = []
    rawPayload.value = null
    wsEvents.value = []
    messages.value.push({ role: 'user', content: question })
    messages.value.push({ role: 'assistant', content: '' })
    sendWs({
      type: 'CHAT_START',
      topK: 5,
      ...payload,
      question,
      requestId,
    })
  }

  function cancelCurrent() {
    if (currentTraceId.value) {
      sendWs({ type: 'CHAT_CANCEL', requestId: currentTraceId.value })
    }
  }

  function ping(requestId: string) {
    sendWs({ type: 'CHAT_PING', requestId })
  }

  function closeWs() {
    socket?.close()
    socket = null
    connecting = null
    wsStatus.value = 'CLOSED'
  }

  return {
    messages,
    sources,
    currentTraceId,
    lowConfidence,
    debugPanelVisible,
    wsStatus,
    wsEvents,
    rawPayload,
    runEval,
    connectWs,
    sendWs,
    startWsChat,
    cancelCurrent,
    ping,
    closeWs,
  }
})
