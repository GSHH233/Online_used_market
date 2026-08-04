import { get, post } from '../request'
import { withQuery, type QueryParams } from './query'

export interface ControlledOperation {
  operationId: number
  traceId?: string
  actionCode?: string
  riskLevel?: string
  status?: string
  reason?: string
  idempotencyKey?: string
  params?: Record<string, unknown>
  approvedBy?: number
  approvedAt?: string
  manualRequired?: boolean
  escalationReason?: string
  execution?: {
    success?: boolean
    verified?: boolean
    summary?: string
    errorMessage?: string
  }
}

export interface OperationPlanRequest {
  actionCode: string
  params?: Record<string, unknown>
  reason?: string
  traceId?: string
}

export const operationsApi = {
  list: (params?: QueryParams) =>
    get<ControlledOperation[]>(withQuery('/api/ai/operations', params)),
  create: (payload: OperationPlanRequest) =>
    post<ControlledOperation>('/api/ai/operations/plans', payload),
  get: (operationId: number) =>
    get<ControlledOperation>(`/api/ai/operations/${operationId}`),
  approve: (operationId: number, comment?: string) =>
    post<ControlledOperation>(`/api/ai/operations/${operationId}/approve`, comment ? { reason: comment } : {}),
  reject: (operationId: number, comment?: string) =>
    post<ControlledOperation>(`/api/ai/operations/${operationId}/reject`, comment ? { reason: comment } : {}),
  execute: (operationId: number) =>
    post<ControlledOperation>(`/api/ai/operations/${operationId}/execute`),
}
