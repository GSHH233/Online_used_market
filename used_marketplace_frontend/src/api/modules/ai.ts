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
  publishAssistFeedback: (assistId: string, payload: any) => post(`/api/ai/products/publish-assist/${assistId}/feedback`, payload),
  publishAssistRecord: (assistId: string) => get(`/api/ai/products/publish-assist/${assistId}`),
  publishAssistRecords: (params?: Record<string, any>) => get(withQuery('/api/ai/products/publish-assist/admin/records', params)),
  publishAssistRecordAdmin: (assistId: string) => get(`/api/ai/products/publish-assist/admin/records/${assistId}`),
  publishAssistMetrics: (params?: Record<string, any>) => get(withQuery('/api/ai/products/publish-assist/metrics', params)),
  recordRiskOutcome: (productId: number | string, payload: any) => post(`/api/ai/products/${productId}/risk-outcomes`, payload),
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
  knowledgeGaps: (params?: Record<string, any>) => get(withQuery('/api/ai/knowledge/gaps', params)),
  knowledgeGap: (gapId: number | string) => get(`/api/ai/knowledge/gaps/${gapId}`),
  knowledgeGapMetrics: (params?: Record<string, any>) => get(withQuery('/api/ai/knowledge/gaps/metrics', params)),
  updateKnowledgeGap: (gapId: number | string, payload: any) => patch(`/api/ai/knowledge/gaps/${gapId}`, payload),
  linkKnowledgeGapDoc: (gapId: number | string, payload: any) => post(`/api/ai/knowledge/gaps/${gapId}/link-doc`, payload),
  regressKnowledgeGap: (gapId: number | string) => post(`/api/ai/knowledge/gaps/${gapId}/regress`),
  knowledgeGapRegressions: (gapId: number | string, params?: Record<string, any>) =>
    get(withQuery(`/api/ai/knowledge/gaps/${gapId}/regressions`, params)),
  unresolvedKnowledgeGapFeedback: (payload: any) => post('/api/ai/knowledge/gaps/feedback/unresolved', payload),

  l2Write: (params: Record<string, any> | URLSearchParams) => post(withQuery('/api/ai/l2-memory/debug/write', params)),
  l2Query: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/l2-memory/debug/query', params)),
  l2Profile: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/l2-memory/debug/profile', params)),
  graphExtract: (payload: any) => post('/api/ai/memory-graph/debug/extract', payload),
  graphNodes: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/memory-graph/debug/nodes', params)),
  graphEdges: (params: Record<string, any> | URLSearchParams) => get(withQuery('/api/ai/memory-graph/debug/edges', params)),

  reactAgent: (payload: any) => post('/api/ai/agent/react-chat', payload),
  graphAgent: (payload: any) => post('/api/ai/agent/graph-chat', payload),
  investigate: (payload: any) => post('/api/ai/agent/investigate', payload),
  runtimeStart: (payload: any) => post('/api/ai/agent/runs', payload),
  runtimeRuns: (params?: Record<string, any>) => get(withQuery('/api/ai/agent/runs', params)),
  runtimeRun: (runId: string) => get(`/api/ai/agent/runs/${runId}`),
  runtimeCheckpoints: (runId: string) => get(`/api/ai/agent/runs/${runId}/checkpoints`),
  runtimePause: (runId: string) => post(`/api/ai/agent/runs/${runId}/pause`),
  runtimeResume: (runId: string) => post(`/api/ai/agent/runs/${runId}/resume`),
  runtimeCancel: (runId: string) => post(`/api/ai/agent/runs/${runId}/cancel`),
  runtimeReplay: (runId: string, liveReadonly = false) => post(withQuery(`/api/ai/agent/runs/${runId}/replay`, { liveReadonly })),

  mysqlRead: (payload: any) => post('/api/ai/tools/mysql/read', payload),
  milvusRead: (payload: any) => post('/api/ai/tools/mysql/milvus/read', payload),
  storageConsistency: (params: Record<string, any>) => get(withQuery('/api/ai/storage/debug/consistency', params)),
  harnessExecute: (payload: any) => post('/api/ai/harness/debug/execute', payload),
  prometheusQuery: (payload: any) => post('/api/ai/tools/prometheus/query', payload),

  aiopsEvents: (params?: Record<string, any>) => get(withQuery('/api/ai/aiops/events', params)),
  aiopsEvent: (eventId: number | string) => get(`/api/ai/aiops/events/${eventId}`),
  createAiopsEvent: (payload: any) => post('/api/ai/aiops/events', payload),
  updateAiopsEvent: (eventId: number | string, payload: any) => patch(`/api/ai/aiops/events/${eventId}`, payload),
  approveAiopsEvent: (eventId: number | string, payload?: any) => post(`/api/ai/aiops/events/${eventId}/approve`, payload || {}),
  rejectAiopsEvent: (eventId: number | string, payload?: any) => post(`/api/ai/aiops/events/${eventId}/reject`, payload || {}),
  aiopsRemediation: (eventId: number | string) => get(`/api/ai/aiops/events/${eventId}/remediation`),
  aiopsMttrMetrics: (params?: Record<string, any>) => get(withQuery('/api/ai/aiops/events/metrics/mttr', params)),
  aiopsRunbooks: () => get('/api/ai/aiops/runbooks'),
  executeAiopsRunbook: (payload: any) => post('/api/ai/aiops/runbooks/execute', payload),
  createOperationPlan: (payload: any) => post('/api/ai/operations/plans', payload),
  controlledOperation: (operationId: number | string) => get(`/api/ai/operations/${operationId}`),
  approveOperation: (operationId: number | string, payload?: any) => post(`/api/ai/operations/${operationId}/approve`, payload || {}),
  rejectOperation: (operationId: number | string, payload?: any) => post(`/api/ai/operations/${operationId}/reject`, payload || {}),
  executeOperation: (operationId: number | string) => post(`/api/ai/operations/${operationId}/execute`),
}
