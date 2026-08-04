import { get, patch, post, upload } from '../request'
import { withQuery, type QueryParams } from './query'

export interface KnowledgeDocument {
  id: number
  title: string
  sourceType?: string
  sourceUri?: string
  tags?: string
  sourceTrustLevel?: string
  docVersion?: string
  status?: string
  fileName?: string
  mimeType?: string
  parserType?: string
  activeIndexVersion?: string
  createdTime?: string
}

export interface KnowledgeTask {
  id: number
  docId: number
  taskType?: string
  status?: string
  stage?: string
  asyncTask?: boolean
  chunkCount?: number
  dbCount?: number
  esCount?: number
  milvusCount?: number
  consistent?: boolean
  elapsedMs?: number
  errorMsg?: string
  createdTime?: string
  updatedTime?: string
}

export interface KnowledgeUploadResult {
  document: KnowledgeDocument
  task?: KnowledgeTask
  fileName?: string
  mimeType?: string
  fileHash?: string
  parserType?: string
  sectionCount?: number
  warnings?: string[]
}

export interface KnowledgeGap {
  id: number
  query: string
  normalizedQuery?: string
  reasonCode?: string
  status?: string
  priority?: string
  occurrenceCount?: number
  recurrenceCount?: number
  linkedDocId?: number
  linkedTaskId?: number
  latestTraceId?: string
  firstSeenAt?: string
  lastSeenAt?: string
  resolvedAt?: string
}

export interface KnowledgeSearchHit {
  docId?: number
  chunkId?: number
  title?: string
  content?: string
  score?: number
  sourceType?: string
}

export interface KnowledgeSearchResult {
  query?: string
  retrievalMode?: string
  total?: number
  hits?: KnowledgeSearchHit[]
  rewritePlan?: {
    originalQuery?: string
    mainQuery?: string
    subQueries?: string[]
    synonymQueries?: string[]
  }
  elapsedMs?: number
}

export interface StorageConsistency {
  docId: number
  dbCount?: number
  esCount?: number
  milvusCount?: number
  consistent?: boolean
  missingInEs?: number[]
  missingInMilvus?: number[]
  extraInEs?: number[]
  extraInMilvus?: number[]
}

export interface UploadKnowledgeOptions {
  title?: string
  sourceType?: string
  tags?: string
  sourceTrustLevel?: string
  docVersion?: string
  async?: boolean
  signal?: AbortSignal
  onProgress?: (percent: number) => void
}

export const knowledgeApi = {
  documents: (params?: QueryParams) =>
    get<KnowledgeDocument[]>(withQuery('/api/ai/knowledge/docs', params)),
  document: (docId: number) =>
    get<KnowledgeDocument>(`/api/ai/knowledge/docs/${docId}`),
  create: (payload: Record<string, unknown>) =>
    post<KnowledgeDocument>('/api/ai/knowledge/docs', payload),
  upload: (file: File, options: UploadKnowledgeOptions = {}) => {
    const form = new FormData()
    form.append('file', file)
    if (options.title) form.append('title', options.title)
    form.append('sourceType', options.sourceType || 'GUIDE')
    if (options.tags) form.append('tags', options.tags)
    form.append('sourceTrustLevel', options.sourceTrustLevel || 'MEDIUM')
    form.append('docVersion', options.docVersion || 'v1')
    form.append('async', String(options.async ?? true))
    return upload<KnowledgeUploadResult>('/api/ai/knowledge/docs/upload', form, {
      signal: options.signal,
      onProgress: options.onProgress ? (percent) => options.onProgress?.(percent) : undefined,
    })
  },
  updateGovernance: (docId: number, payload: Record<string, unknown>) =>
    patch<KnowledgeDocument>(`/api/ai/knowledge/docs/${docId}/governance`, payload),
  ingest: (docId: number, asyncTask = true) =>
    post<KnowledgeTask>(withQuery(`/api/ai/knowledge/jobs/ingest/${docId}`, { async: asyncTask })),
  jobs: (params?: QueryParams) =>
    get<KnowledgeTask[]>(withQuery('/api/ai/knowledge/jobs', params)),
  job: (taskId: number) =>
    get<KnowledgeTask>(`/api/ai/knowledge/jobs/${taskId}`),
  search: (payload: Record<string, unknown>) =>
    post<KnowledgeSearchResult>('/api/ai/knowledge/search', payload),
  gaps: (params?: QueryParams) =>
    get<KnowledgeGap[]>(withQuery('/api/ai/knowledge/gaps', params)),
  updateGap: (gapId: number, payload: Record<string, unknown>) =>
    patch<KnowledgeGap>(`/api/ai/knowledge/gaps/${gapId}`, payload),
  linkGapDocument: (gapId: number, docId: number) =>
    post<KnowledgeGap>(`/api/ai/knowledge/gaps/${gapId}/link-doc`, { docId }),
  regressGap: (gapId: number) =>
    post(`/api/ai/knowledge/gaps/${gapId}/regress`),
  gapMetrics: (params?: QueryParams) =>
    get<Record<string, number>>(withQuery('/api/ai/knowledge/gaps/metrics', params)),
  storageConsistency: (docId: number, limit = 200) =>
    get<StorageConsistency>(withQuery('/api/ai/knowledge/storage/consistency', { docId, limit })),
}
