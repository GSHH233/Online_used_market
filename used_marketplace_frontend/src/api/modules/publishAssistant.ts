import { get, post } from '../request'
import { withQuery, type QueryParams } from './query'

export interface PublishAssistRequest {
  title: string
  description: string
  brand?: string
  conditionText?: string
  categoryName?: string
  expectedPrice?: number
}

export interface PublishAssistResult {
  assistId: string
  traceId?: string
  optimizeResult?: {
    optimizedTitle?: string
    optimizedDescription?: string
    riskWarnings?: string[]
  }
  riskScore?: number
  riskWarnings?: string[]
  priceSuggestion?: string
  canPublish?: boolean
  riskEvidence?: Array<{
    title?: string
    content?: string
    source?: string
    docId?: number
    chunkId?: number
  }>
  sources?: Array<{
    docId?: number
    chunkId?: number
    title?: string
    content?: string
    score?: number
    source?: string
  }>
  generationMode?: string
  fallbackUsed?: boolean
  fallbackReason?: string
}

export interface PublishAssistFeedback {
  adoptionType: 'APPLIED' | 'PARTIAL' | 'REJECTED' | string
  adoptedFields?: string[]
  productId?: number
  reason?: string
}

export interface PublishAssistMetrics {
  totalCount?: number
  adoptionRate?: number
  adoptedCount?: number
  rejectedCount?: number
  riskOutcomeCount?: number
}

export const publishAssistantApi = {
  analyze: (payload: PublishAssistRequest) =>
    post<PublishAssistResult>('/api/ai/products/publish-assist', payload),
  feedback: (assistId: string, payload: PublishAssistFeedback) =>
    post(`/api/ai/products/publish-assist/${assistId}/feedback`, payload),
  record: (assistId: string) =>
    get<PublishAssistResult>(`/api/ai/products/publish-assist/${assistId}`),
  metrics: (params?: QueryParams) =>
    get<PublishAssistMetrics>(withQuery('/api/ai/products/publish-assist/metrics', params)),
}
