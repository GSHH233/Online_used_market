import { get, post } from '../request'
import { withQuery } from './query'

export interface RuntimeRun {
  runId: string
  traceId?: string
  question?: string
  status?: string
  strategy?: string
  currentNodeId?: string
  elapsedMs?: number
  errorMessage?: string
  createdAt?: string
  updatedAt?: string
  [key: string]: unknown
}

export const runtimeApi = {
  list: (limit = 20) =>
    get<RuntimeRun[]>(withQuery('/api/ai/agent/runs', { limit })),
  get: (runId: string) =>
    get<RuntimeRun>(`/api/ai/agent/runs/${runId}`),
  checkpoints: (runId: string) =>
    get<Array<Record<string, unknown>>>(`/api/ai/agent/runs/${runId}/checkpoints`),
  pause: (runId: string) =>
    post<RuntimeRun>(`/api/ai/agent/runs/${runId}/pause`),
  resume: (runId: string) =>
    post<RuntimeRun>(`/api/ai/agent/runs/${runId}/resume`),
  cancel: (runId: string) =>
    post<RuntimeRun>(`/api/ai/agent/runs/${runId}/cancel`),
  replay: (runId: string, liveReadonly = false) =>
    post<RuntimeRun>(withQuery(`/api/ai/agent/runs/${runId}/replay`, { liveReadonly })),
}
