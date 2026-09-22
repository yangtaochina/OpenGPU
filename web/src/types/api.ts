// 契约：docs/API.md §1.5 枚举、§1.4 分页、§6 视图对象

export type TaskStatus =
  | 'QUEUED'
  | 'ASSIGNED'
  | 'RUNNING'
  | 'UPLOADING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED'

export type WorkerStatus = 'ONLINE' | 'OFFLINE' | 'DISABLED'

export type WorkerRuntimeStatus = 'IDLE' | 'BUSY'

export type AttemptStatus =
  | 'ASSIGNED'
  | 'RUNNING'
  | 'UPLOADING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'LEASE_EXPIRED'

export type Role = 'USER' | 'ADMIN'

/** §1.5 用户角色（与 Role 同义，提供语义化别名） */
export type UserRole = Role

/** §1.5 显卡算力档位（由显存推导，见 §7） */
export type GpuTier = 'ENTRY' | 'STANDARD' | 'PRO' | 'ULTRA'

/** §1.5 视频分辨率 */
export type VideoResolution = '480P' | '720P' | '1080P' | '4K'

/** 统一响应信封：`{ code, message, data }` */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

/** §1.4 分页响应体 */
export interface PageResult<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface PageQuery {
  page?: number
  size?: number
}

// ---------- §2 认证 ----------

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  username: string
  role: Role
  expiresInSeconds: number
}

export interface MeView {
  id: string
  username: string
  role: Role
}

/** §2 GET /api/auth/config —— 平台注册/密码策略（免认证） */
export interface AuthConfigView {
  registrationEnabled: boolean
  passwordMinLength: number
  passwordMaxLength: number
  usernameMinLength: number
  usernameMaxLength: number
}

/** §2 POST /api/auth/register —— 注册固定为 USER，注册后自动登录 */
export interface RegisterRequest {
  username: string
  password: string
}

// ---------- §3 任务 ----------

/** §3 用户提交的视频要求（可选） */
export interface VideoRequirementInput {
  resolution: VideoResolution
  durationSeconds: number
  fps: number
}

/** §6 TaskView.requirement：服务端推导出的硬件约束 */
export interface TaskRequirementView extends VideoRequirementInput {
  requiredVramMb: number
  gpuTier: GpuTier
  summary: string
}

export interface CreateTaskRequest {
  prompt: string
  /** 0–5，缺省时取服务端配置 */
  maxRetry?: number
  /** 可选；缺省表示不限定硬件，任何在线节点都可领取 */
  requirement?: VideoRequirementInput
}

// ---------- §3 GET /api/capabilities ----------

export interface CapabilityResolutionOption {
  value: VideoResolution
  label: string
  width: number
  height: number
}

export interface CapabilityFleetView {
  onlineWorkers: number
  maxVramMb: number | null
  maxGpuTier: GpuTier | null
  maxDurationSeconds: number | null
}

export interface CapabilityOptionsView {
  resolutions: CapabilityResolutionOption[]
  durations: number[]
  fps: number[]
}

export interface CapabilityMatrixEntry {
  resolution: VideoResolution
  durationSeconds: number
  fps: number
  requiredVramMb: number
  gpuTier: GpuTier
  summary: string
  /** 当前在线节点是否有人能满足该组合 */
  serviceable: boolean
}

export interface CapabilityView {
  fleet: CapabilityFleetView
  options: CapabilityOptionsView
  matrix: CapabilityMatrixEntry[]
}

export interface TaskResultView {
  id: string
  taskId: string
  fileKey: string
  /** 相对地址，如 /api/files/videos/20260922/xxxx.mp4 */
  fileUrl: string
  fileSize: number
  checksum: string
  durationSeconds: number
  width: number
  height: number
  createdAt: string
}

export interface TaskView {
  id: string
  prompt: string
  status: TaskStatus
  progress: number
  retryCount: number
  maxRetry: number
  /** null 表示该任务不限定硬件 */
  requirement: TaskRequirementView | null
  workerId: string | null
  workerName: string | null
  leaseExpiresAt: string | null
  errorCode: string | null
  errorMessage: string | null
  result: TaskResultView | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  updatedAt: string
}

export interface TaskAttemptView {
  id: string
  taskId: string
  workerId: string | null
  workerName: string | null
  leaseId: string
  attemptNo: number
  status: AttemptStatus
  errorCode: string | null
  errorMessage: string | null
  startedAt: string | null
  endedAt: string | null
}

export interface TaskDetailView {
  task: TaskView
  attempts: TaskAttemptView[]
  result: TaskResultView | null
}

export interface TaskListQuery extends PageQuery {
  status?: TaskStatus
}

// ---------- §4 管理端 ----------

export interface CreateWorkerRequest {
  name: string
  gpuModel?: string
  vramMb?: number
  workerVersion?: string
  modelVersion?: string
  /** 可选；缺省时服务端按 vramMb 推导 */
  gpuTier?: GpuTier
  /** 最长可承受时长（秒）；0 或省略表示不限制 */
  maxDurationSeconds?: number
  supportedResolutions?: string
}

export interface WorkerView {
  id: string
  name: string
  status: WorkerStatus
  runtimeStatus: WorkerRuntimeStatus
  gpuModel: string | null
  vramMb: number | null
  gpuTier: GpuTier | null
  maxDurationSeconds: number | null
  supportedResolutions: string | null
  workerVersion: string | null
  modelVersion: string | null
  currentTaskId: string | null
  lastHeartbeatAt: string | null
  enabled: boolean
  createdAt: string
}

export interface CreateWorkerResponse {
  worker: WorkerView
  /** 仅创建时返回一次，请立即保存到节点 */
  token: string
}

export interface WorkerListQuery extends PageQuery {
  status?: WorkerStatus
}

export interface AdminOverviewView {
  taskTotal: number
  taskQueued: number
  taskRunning: number
  taskSucceeded: number
  taskFailed: number
  workerOnline: number
  workerOffline: number
  workerDisabled: number
}

// ---------- §4.5 用户管理（管理员） ----------

/** §6 UserView */
export interface UserView {
  id: string
  username: string
  role: UserRole
  enabled: boolean
  lastLoginAt: string | null
  createdAt: string
}

export interface CreateUserRequest {
  username: string
  password: string
  /** 可选，缺省 USER */
  role?: UserRole
}

export interface ResetPasswordRequest {
  newPassword: string
}

export interface UpdateUserRoleRequest {
  role: UserRole
}

export interface UserListQuery extends PageQuery {
  /** 用户名模糊匹配 */
  keyword?: string
  role?: UserRole
  enabled?: boolean
}
