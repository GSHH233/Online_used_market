export type SourceItem = {
  title?: string
  content?: string
  sourceType?: string
  score?: number
}

export type PublishAssistRequest = {
  title: string
  description: string
  brand: string
  conditionText: string
  categoryName: string
  expectedPrice: number
}

export type RagEvalRequest = {
  type: 'CHAT_START'
  requestId: string
  question: string
  topK: number
  filter?: { sourceType?: string; category?: string; riskLevel?: string | null }
}

export type RagEvalResult = {
  answer?: string
  sources?: SourceItem[]
  confidenceLevel?: string
  retrievalDebug?: unknown
  promptDebug?: unknown
}

export type AgentChatRequest = {
  sessionId: string
  traceId: string
  question: string
  maxSteps: number
  mode: 'USER' | 'INTERNAL'
}
