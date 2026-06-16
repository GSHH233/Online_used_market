import { get, patch, post } from '../request'

const toQuery = (params?: Record<string, any> | URLSearchParams) => {
  if (!params) return ''
  if (params instanceof URLSearchParams) return params.toString()
  return new URLSearchParams(Object.entries(params).reduce<Record<string, string>>((acc, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') acc[key] = String(value)
    return acc
  }, {})).toString()
}

const withQuery = (path: string, params?: Record<string, any> | URLSearchParams) => {
  const query = toQuery(params)
  return query ? `${path}?${query}` : path
}

export const aiApi = {
  publishAssist: (payload: any) => post('/api/ai/products/publish-assist', payload),
  optimize: (payload: any) => post('/api/ai/products/optimize', payload),
  ragEval: (payload: any) => post('/api/ai/rag-chat/eval', payload),

  evalDashboard: (params?: { recentLimit?: number }) => get(withQuery('/api/ai/agent/eval-dashboard', params)),
  agentTraces: (params?: Record<string, any>) => get(withQuery('/api/ai/agent/traces', params)),
  agentTrace: (traceId: string) => get(`/api/ai/agent/traces/${traceId}`),

  createKnowledge: (payload: any) => post('/api/ai/knowledge/docs', payload),
  updateKnowledgeGovernance: (docId: number | string, payload: any) => patch(`/api/ai/knowledge/docs/${docId}/governance`, payload),
  reindexKnowledge: (docId: number | string) => post(`/api/ai/knowledge/reindex/${docId}`),
  ingestKnowledge: (docId: number | string, params?: { async?: boolean }) => post(withQuery(`/api/ai/knowledge/jobs/ingest/${docId}`, params)),
  repairKnowledge: (docId: number | string, params?: { forceReindex?: boolean; async?: boolean }) =>
    post(withQuery(`/api/ai/knowledge/repair/${docId}`, params)),
  repairBatch: (payload?: any) => post('/api/ai/knowledge/repair/batch', payload || {}),
  knowledgeJob: (taskId: number | string) => get(`/api/ai/knowledge/jobs/${taskId}`),
  knowledgeJobs: (params?: Record<string, any>) => get(withQuery('/api/ai/knowledge/jobs', params)),
  searchKnowledge: (payload: any) => post('/api/ai/knowledge/search', payload),
  debugMilvusByDoc: (docId: number | string) => get(`/api/ai/knowledge/debug/milvus/by-doc/${docId}`),

  l2Write: (params: Record<string, any> | URLSearchParams) => post(withQuery('/api/ai/l2-memory/debug/write', params)),
  l2Query: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/l2-memory/debug/query', params)),
  l2Profile: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/l2-memory/debug/profile', params)),
  graphExtract: (payload: any) => post('/api/ai/memory-graph/debug/extract', payload),
  graphNodes: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/memory-graph/debug/nodes', params)),
  graphEdges: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/memory-graph/debug/edges', params)),

  reactAgent: (payload: any) => post('/api/ai/agent/react-chat', payload),
  graphAgent: (payload: any) => post('/api/ai/agent/graph-chat', payload),

  mysqlRead: (payload: any) => post('/api/ai/tools/mysql/read', payload),
  milvusRead: (payload: any) => post('/api/ai/tools/mysql/milvus/read', payload),
  storageConsistency: (params: Record<string, any>) => get(withQuery('/api/ai/storage/debug/consistency', params)),
  harnessExecute: (payload: any) => post('/api/ai/harness/debug/execute', payload),
  prometheusQuery: (payload: any) => post('/api/ai/tools/prometheus/query', payload),

  aiopsEvents: (params?: Record<string, any>) => get(withQuery('/api/ai/aiops/events', params)),
  aiopsEvent: (eventId: number | string) => get(`/api/ai/aiops/events/${eventId}`),
  createAiopsEvent: (payload: any) => post('/api/ai/aiops/events', payload),
  updateAiopsEvent: (eventId: number | string, payload: any) => patch(`/api/ai/aiops/events/${eventId}`, payload),
}
