import { http } from '@/api/client'
import type {
  AdminOverviewView,
  CreateWorkerRequest,
  CreateWorkerResponse,
  PageResult,
  WorkerListQuery,
  WorkerView,
} from '@/types/api'

/** GET /api/admin/workers */
export function listWorkers(params: WorkerListQuery = {}): Promise<PageResult<WorkerView>> {
  return http.get<PageResult<WorkerView>>('/api/admin/workers', { params })
}

/** POST /api/admin/workers —— token 仅此一次返回 */
export function createWorker(payload: CreateWorkerRequest): Promise<CreateWorkerResponse> {
  return http.post<CreateWorkerResponse>('/api/admin/workers', payload)
}

/** POST /api/admin/workers/{id}/disable */
export function disableWorker(id: string): Promise<WorkerView> {
  return http.post<WorkerView>(`/api/admin/workers/${id}/disable`)
}

/** POST /api/admin/workers/{id}/enable */
export function enableWorker(id: string): Promise<WorkerView> {
  return http.post<WorkerView>(`/api/admin/workers/${id}/enable`)
}

/** GET /api/admin/overview */
export function fetchOverview(): Promise<AdminOverviewView> {
  return http.get<AdminOverviewView>('/api/admin/overview')
}
