import { post } from '../request'

export interface InvestigationRequest {
  question: string
  sessionId?: string
  traceId?: string
  maxSteps?: number
}

export interface InvestigationSource {
  docId?: number
  chunkId?: number
  title?: string
  content?: string
  score?: number
}

export interface InvestigationObservation {
  tool?: string
  target?: string
  summary?: string
  elapsedMs?: number
  total?: number
  columns?: string[]
  rows?: Array<Record<string, unknown>>
}

export interface InvestigationResult {
  traceId?: string
  strategy?: string
  routeReason?: string
  status?: string
  question?: string
  answer?: string
  success?: boolean
  approvalRequired?: boolean
  elapsedMs?: number
  agentTrace?: Array<Record<string, unknown>>
  observations?: InvestigationObservation[]
  sources?: InvestigationSource[]
  graph?: Array<Record<string, unknown>>
}

export const investigationApi = {
  investigate: (payload: InvestigationRequest) =>
    post<InvestigationResult>('/api/ai/agent/investigate', payload),
}
