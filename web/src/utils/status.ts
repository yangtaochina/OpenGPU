// 状态 -> 中文标签 + Element Plus Tag 类型的统一映射
import type {
  AttemptStatus,
  GpuTier,
  Role,
  TaskStatus,
  WorkerRuntimeStatus,
  WorkerStatus,
} from '@/types/api'

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface StatusMeta {
  label: string
  tagType: TagType
}

const UNKNOWN_META: StatusMeta = { label: '未知', tagType: 'info' }

export const TASK_STATUS_META: Record<TaskStatus, StatusMeta> = {
  QUEUED: { label: '排队中', tagType: 'info' },
  ASSIGNED: { label: '已分配', tagType: 'warning' },
  RUNNING: { label: '运行中', tagType: 'primary' },
  UPLOADING: { label: '上传中', tagType: 'warning' },
  SUCCEEDED: { label: '已成功', tagType: 'success' },
  FAILED: { label: '失败', tagType: 'danger' },
  CANCELED: { label: '已取消', tagType: 'info' },
}

export const WORKER_STATUS_META: Record<WorkerStatus, StatusMeta> = {
  ONLINE: { label: '在线', tagType: 'success' },
  OFFLINE: { label: '离线', tagType: 'info' },
  DISABLED: { label: '已停用', tagType: 'danger' },
}

export const WORKER_RUNTIME_STATUS_META: Record<WorkerRuntimeStatus, StatusMeta> = {
  IDLE: { label: '空闲', tagType: 'info' },
  BUSY: { label: '忙碌', tagType: 'warning' },
}

export const ATTEMPT_STATUS_META: Record<AttemptStatus, StatusMeta> = {
  ASSIGNED: { label: '已分配', tagType: 'warning' },
  RUNNING: { label: '运行中', tagType: 'primary' },
  UPLOADING: { label: '上传中', tagType: 'warning' },
  SUCCEEDED: { label: '成功', tagType: 'success' },
  FAILED: { label: '失败', tagType: 'danger' },
  LEASE_EXPIRED: { label: '租约过期', tagType: 'info' },
}

/** §7 显卡档位 -> 中文标签 */
export const GPU_TIER_META: Record<GpuTier, StatusMeta> = {
  ENTRY: { label: '入门', tagType: 'info' },
  STANDARD: { label: '标准', tagType: 'primary' },
  PRO: { label: '专业', tagType: 'warning' },
  ULTRA: { label: '旗舰', tagType: 'danger' },
}

/** §1.5 用户角色 -> 中文标签 */
export const USER_ROLE_META: Record<Role, StatusMeta> = {
  USER: { label: '用户', tagType: 'info' },
  ADMIN: { label: '管理员', tagType: 'danger' },
}

/** §7 显卡档位下拉选项（含显存区间，供管理端新建节点时选择） */
export const GPU_TIER_OPTIONS: Array<{ value: GpuTier; label: string; vramRange: string }> = [
  { value: 'ENTRY', label: '入门', vramRange: '< 12 GB' },
  { value: 'STANDARD', label: '标准', vramRange: '12 - 19 GB' },
  { value: 'PRO', label: '专业', vramRange: '20 - 39 GB' },
  { value: 'ULTRA', label: '旗舰', vramRange: '>= 40 GB' },
]

/** 任务终态：不再轮询 */
export const TERMINAL_TASK_STATUSES: TaskStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELED']
export function isTerminalTaskStatus(status: TaskStatus | string | null | undefined): boolean {
  if (!status) return true
  return TERMINAL_TASK_STATUSES.includes(status as TaskStatus)
}

/** 存在任意非终态任务时继续轮询 */
export function hasActiveTask(tasks: Array<{ status: TaskStatus }>): boolean {
  return tasks.some((task) => !isTerminalTaskStatus(task.status))
}

function resolve(map: Record<string, StatusMeta>, status: string | null | undefined): StatusMeta {
  if (!status) return UNKNOWN_META
  return map[status] ?? { label: status, tagType: 'info' }
}

export function taskStatusMeta(status: TaskStatus | string | null | undefined): StatusMeta {
  return resolve(TASK_STATUS_META, status)
}

export function workerStatusMeta(status: WorkerStatus | string | null | undefined): StatusMeta {
  return resolve(WORKER_STATUS_META, status)
}

export function workerRuntimeStatusMeta(
  status: WorkerRuntimeStatus | string | null | undefined,
): StatusMeta {
  return resolve(WORKER_RUNTIME_STATUS_META, status)
}

export function attemptStatusMeta(status: AttemptStatus | string | null | undefined): StatusMeta {
  return resolve(ATTEMPT_STATUS_META, status)
}

export function gpuTierMeta(tier: GpuTier | string | null | undefined): StatusMeta {
  return resolve(GPU_TIER_META, tier)
}

export function userRoleMeta(role: Role | string | null | undefined): StatusMeta {
  return resolve(USER_ROLE_META, role)
}

export const TASK_STATUS_OPTIONS = (Object.keys(TASK_STATUS_META) as TaskStatus[]).map((value) => ({
  value,
  label: TASK_STATUS_META[value].label,
}))

export const WORKER_STATUS_OPTIONS = (Object.keys(WORKER_STATUS_META) as WorkerStatus[]).map(
  (value) => ({ value, label: WORKER_STATUS_META[value].label }),
)

export const USER_ROLE_OPTIONS = (Object.keys(USER_ROLE_META) as Role[]).map((value) => ({
  value,
  label: USER_ROLE_META[value].label,
}))
