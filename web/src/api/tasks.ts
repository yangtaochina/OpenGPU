import { http } from '@/api/client'
import type {
  CapabilityView,
  CreateTaskRequest,
  PageResult,
  TaskDetailView,
  TaskListQuery,
  TaskView,
} from '@/types/api'

/** POST /api/tasks —— 创建任务，返回 TaskView（201） */
export function createTask(payload: CreateTaskRequest): Promise<TaskView> {
  return http.post<TaskView>('/api/tasks', payload)
}

/** GET /api/tasks —— 当前用户可见任务（V1 无租户隔离，返回全部） */
export function listTasks(params: TaskListQuery = {}): Promise<PageResult<TaskView>> {
  return http.get<PageResult<TaskView>>('/api/tasks', { params })
}

/** GET /api/tasks/{id} */
export function getTask(id: string): Promise<TaskDetailView> {
  return http.get<TaskDetailView>(`/api/tasks/${id}`)
}

/** POST /api/tasks/{id}/cancel —— 仅 QUEUED 可取消 */
export function cancelTask(id: string): Promise<TaskView> {
  return http.post<TaskView>(`/api/tasks/${id}/cancel`)
}

/** GET /api/capabilities —— 视频要求可服务矩阵（需登录） */
export function getCapabilities(): Promise<CapabilityView> {
  return http.get<CapabilityView>('/api/capabilities')
}

/** GET /api/admin/tasks —— 管理端任务列表 */
export function listAdminTasks(params: TaskListQuery = {}): Promise<PageResult<TaskView>> {
  return http.get<PageResult<TaskView>>('/api/admin/tasks', { params })
}

/** POST /api/admin/tasks/{id}/retry —— 仅 FAILED 可重试 */
export function retryTask(id: string): Promise<TaskView> {
  return http.post<TaskView>(`/api/admin/tasks/${id}/retry`)
}
