# 智由 AI 视频任务平台 — API 契约（V1）

> 控制面基地址：`http://localhost:8080`
> 本文件是前端、Worker、后端三方共同遵守的唯一契约。任何一方修改接口，必须同步更新本文件。

## 1. 通用约定

### 1.1 响应信封

除文件下载与 `204 No Content` 外，所有接口返回统一信封：

```json
{ "code": 0, "message": "success", "data": {} }
```

- `code = 0` 表示成功；非 0 表示业务错误。
- `message` 为可直接展示给用户的中文/英文摘要。
- 错误时 `data` 为 `null`。

### 1.2 错误码

| code | 含义 | 建议 HTTP 状态 |
|---|---|---|
| 0 | 成功 | 200 / 201 |
| 40000 | 参数校验失败 | 400 |
| 40001 | 非法提示词（空或超长） | 400 |
| 40002 | 非法的视频要求参数 | 400 |
| 40003 | 用户名或密码格式不符合要求 | 400 |
| 40100 | 未认证 / 凭证无效 | 401 |
| 40300 | 无权限 | 403 |
| 40301 | 注册功能已关闭 | 403 |
| 40302 | 受保护的管理操作被拒绝（禁止操作自己 / 最后一个管理员） | 403 |
| 40400 | 资源不存在 | 404 |
| 40900 | 状态冲突（非法状态迁移） | 409 |
| 40910 | 租约已失效（过期或不匹配） | 409 |
| 40920 | 文件不存在或校验失败 | 409 |
| 40930 | 用户名已被占用 | 409 |
| 50000 | 服务内部错误 | 500 |
| 50001 | 存储服务错误 | 500 |

### 1.3 认证

| 调用方 | 方式 |
|---|---|
| 用户/管理员 | `POST /api/auth/login` 获取 JWT，请求头 `Authorization: Bearer <jwt>` |
| GPU Worker | 管理端创建节点时一次性下发 token，格式 `wkr_<workerId>_<secret>`，请求头 `Authorization: Bearer <token>` |

- Worker token 仅存储 SHA-256 哈希，明文只在创建时返回一次。
- 服务端日志不得记录完整 token。

### 1.4 分页

请求：`?page=0&size=20`（page 从 0 开始）。

响应 `data`：

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

### 1.5 枚举

| 枚举 | 取值 |
|---|---|
| `TaskStatus` | `QUEUED` `ASSIGNED` `RUNNING` `UPLOADING` `SUCCEEDED` `FAILED` `CANCELED` |
| `WorkerStatus` | `ONLINE` `OFFLINE` `DISABLED` |
| `WorkerRuntimeStatus` | `IDLE` `BUSY` |
| `AttemptStatus` | `ASSIGNED` `RUNNING` `UPLOADING` `SUCCEEDED` `FAILED` `LEASE_EXPIRED` |
| `role` | `USER` `ADMIN` |
| `resolution` | `480P` `720P` `1080P` `4K` |
| `durationSeconds` | `3` `5` `10` `15` |
| `fps` | `24` `30` `60` |
| `GpuTier` | `ENTRY` `STANDARD` `PRO` `ULTRA`（显卡算力档位，由显存推导，见 §7） |

### 1.6 任务状态机

```
QUEUED    → ASSIGNED | CANCELED
ASSIGNED  → RUNNING  | QUEUED | FAILED
RUNNING   → UPLOADING| FAILED | QUEUED
UPLOADING → SUCCEEDED| FAILED | QUEUED
FAILED    → QUEUED
SUCCEEDED / CANCELED → 终态
```

`→ QUEUED` 的回退由租约到期回收触发；`FAILED → QUEUED` 由管理员人工重试触发。

---

## 2. 认证接口

### POST /api/auth/login

请求：

```json
{ "username": "admin", "password": "admin123" }
```

响应 `data`：

```json
{ "token": "<jwt>", "username": "admin", "role": "ADMIN", "expiresInSeconds": 43200 }
```

内置账号（开发种子）：

| 用户名 | 密码 | 角色 |
|---|---|---|
| `admin` | `admin123` | ADMIN |
| `user` | `user123` | USER |

### GET /api/auth/me

响应 `data`：`{ "id": "uuid", "username": "admin", "role": "ADMIN" }`

### GET /api/auth/config

**免认证**。供登录/注册页读取平台策略，避免前端硬编码规则。

```json
{
  "registrationEnabled": true,
  "passwordMinLength": 6,
  "passwordMaxLength": 64,
  "usernameMinLength": 3,
  "usernameMaxLength": 32
}
```

### POST /api/auth/register

**免认证**，开放注册。

请求：

```json
{ "username": "alice", "password": "secret123" }
```

- `username`：3–32 位，仅允许字母、数字、下划线、连字符；**唯一性不区分大小写**
- `password`：6–64 位
- **注册出来的账号角色固定为 `USER`**，请求体中的 `role` 会被忽略，防止越权提权
- `registrationEnabled = false` 时返回 `40301`
- 用户名已被占用返回 `40930`
- 格式不合法返回 `40003`

响应：`201 Created`，`data` 与登录一致（**注册后直接自动登录**）：

```json
{ "token": "<jwt>", "username": "alice", "role": "USER", "expiresInSeconds": 43200 }
```

---

## 3. 任务接口（用户）

### POST /api/tasks

请求：

```json
{
  "prompt": "一只在星空下奔跑的机械鹿",
  "maxRetry": 2,
  "requirement": {
    "resolution": "1080P",
    "durationSeconds": 5,
    "fps": 30
  }
}
```

- `prompt`：必填，去空格后长度 1–2000。
- `maxRetry`：可选，0–5，默认取服务端配置。
- `requirement`：**可选**，视频生成要求。缺省表示不限定硬件，任何在线节点都可领取。
  - `resolution`：`480P` | `720P` | `1080P` | `4K`
  - `durationSeconds`：`3` | `5` | `10` | `15`
  - `fps`：`24` | `30` | `60`
  - 服务端据此推导所需显存与显卡档位（见 §7）。参数非法返回 `40002`。

响应：`201 Created`，`data` 为 `TaskView`。

### GET /api/capabilities

返回平台当前可服务的视频要求矩阵，供前端做选项联动与「当前无匹配节点」提示。

响应 `data`：

```json
{
  "fleet": {
    "onlineWorkers": 1,
    "maxVramMb": 24576,
    "maxGpuTier": "PRO",
    "maxDurationSeconds": 15
  },
  "options": {
    "resolutions": [
      { "value": "480P",  "label": "480P",  "width": 854,  "height": 480 },
      { "value": "720P",  "label": "720P",  "width": 1280, "height": 720 },
      { "value": "1080P", "label": "1080P", "width": 1920, "height": 1080 },
      { "value": "4K",    "label": "4K",    "width": 3840, "height": 2160 }
    ],
    "durations": [3, 5, 10, 15],
    "fps": [24, 30, 60]
  },
  "matrix": [
    {
      "resolution": "1080P",
      "durationSeconds": 5,
      "fps": 30,
      "requiredVramMb": 12288,
      "gpuTier": "STANDARD",
      "summary": "1080P · 5秒 · 30fps → 需要 ≥12GB 显存（STANDARD 级 GPU）",
      "serviceable": true
    }
  ]
}
```

- `matrix` 覆盖全部 48 种组合（4×4×3），前端按当前选择查表即可，**不要在前端重复实现推导公式**。
- `serviceable` 表示当前在线节点是否有人能满足该组合。
- 该接口需要登录（USER 或 ADMIN）。

### GET /api/tasks

查询参数：`status`（可选）、`page`、`size`。按 `createdAt` 倒序返回。

> V1 不做租户隔离（需求文档 2.2 明确排除），所有已登录用户可见全部任务。

响应 `data`：`PageResult<TaskView>`。

### GET /api/tasks/{id}

响应 `data` 为 `TaskDetailView`。

### POST /api/tasks/{id}/cancel

仅 `QUEUED` 可取消，成功返回 `TaskView`；否则 `40900`。

### POST /api/admin/tasks/{id}/retry

仅 `FAILED` 可重试。重试会：重置 `retryCount = 0`、清空错误信息、状态回 `QUEUED`，并保留原有 `TaskAttempt` 历史。

---

## 4. 管理端接口

### GET /api/admin/tasks

参数同 `GET /api/tasks`，可查看全部任务。

### GET /api/admin/workers

查询参数：`status`（可选）、`page`、`size`。响应 `PageResult<WorkerView>`。

### POST /api/admin/workers

请求：

```json
{
  "name": "gpu-node-01",
  "gpuModel": "RTX 4090",
  "vramMb": 24576,
  "workerVersion": "0.1.0",
  "modelVersion": "MiniMax-H3",
  "gpuTier": "PRO",
  "maxDurationSeconds": 15,
  "supportedResolutions": "480P,720P,1080P,4K"
}
```

- `vramMb`：**建议必填**。为空表示能力未知，该节点只能领取不限硬件的任务。
- `gpuTier`：可选，缺省时由 `vramMb` 推导（`ENTRY` < 12GB ≤ `STANDARD` < 20GB ≤ `PRO` < 40GB ≤ `ULTRA`）。
- `maxDurationSeconds`：可选，`0` 或省略表示不限制。
- `supportedResolutions`：可选，仅用于展示。

响应 `data`：

```json
{ "worker": { "...WorkerView" }, "token": "wkr_<uuid>_<secret>" }
```

`token` **仅此一次返回**，请立即保存到节点。

### POST /api/admin/workers/{id}/disable

停用节点：`enabled = false`、`status = DISABLED`。停用后节点心跳可被记录，但不能领取新任务。

### POST /api/admin/workers/{id}/enable

恢复节点：`enabled = true`、`status = ONLINE`。

### GET /api/admin/overview

响应 `data`：

```json
{
  "taskTotal": 10, "taskQueued": 2, "taskRunning": 1,
  "taskSucceeded": 6, "taskFailed": 1,
  "workerOnline": 1, "workerOffline": 0, "workerDisabled": 0
}
```

---

## 4.5 用户管理（管理员）

> 全部需要 `ADMIN` 角色。

### GET /api/admin/users

查询参数：`keyword`（用户名模糊匹配）、`role`、`enabled`、`page`、`size`。
按 `createdAt` 倒序。

响应 `data`：`PageResult<UserView>`。

### GET /api/admin/users/{id}

响应 `data`：`UserView`。

### POST /api/admin/users

管理员直接创建账号，**可以指定角色**（与开放注册的区别：注册固定为 `USER`）。

请求：

```json
{ "username": "operator", "password": "secret123", "role": "ADMIN" }
```

- `role`：可选，缺省 `USER`
- 用户名冲突返回 `40930`，格式非法返回 `40003`

响应：`201 Created`，`data` 为 `UserView`。

### POST /api/admin/users/{id}/enable

启用账号。响应 `data`：`UserView`。

### POST /api/admin/users/{id}/disable

停用账号。停用后该用户**仍可登录失败**（登录直接返回 `40100`），既有 JWT 在过期前仍有效（V1 不维护 token 黑名单）。

**受保护的操作**，命中任一条返回 `40302`：
- 不能停用自己
- 不能停用**最后一个处于启用状态的管理员**（避免把平台锁死）

### POST /api/admin/users/{id}/role

修改角色。

请求：`{ "role": "ADMIN" }`

**受保护的操作**，命中返回 `40302`：
- 不能修改自己的角色（避免自我锁死）
- 不能把**最后一个处于启用状态的管理员**降级为 `USER`

响应 `data`：`UserView`。

### POST /api/admin/users/{id}/reset-password

管理员重置他人密码。

请求：`{ "newPassword": "newsecret123" }`

- 长度校验同注册（`passwordMinLength` ~ `passwordMaxLength`）
- 不校验旧密码（这是管理端重置，不是用户自助改密）
- 响应 `data`：`UserView`

## 5. Worker 接口

> 全部需要 `Authorization: Bearer wkr_<workerId>_<secret>`。

### POST /api/workers/register

Worker 启动时上报基础信息与**显卡能力**。

请求：

```json
{
  "name": "gpu-node-01",
  "gpuModel": "RTX 4090",
  "vramMb": 24576,
  "workerVersion": "0.1.0",
  "modelVersion": "MiniMax-H3",
  "gpuTier": "PRO",
  "maxDurationSeconds": 15,
  "supportedResolutions": "480P,720P,1080P,4K"
}
```

- `vramMb`：显存（MB）。**这是任务匹配的硬约束**。
- `gpuTier`：可选，显卡档位；缺省时由 `vramMb` 推导。
- `maxDurationSeconds`：可选，该节点能承受的最长时长；缺省视为不限制。
- `supportedResolutions`：可选，仅用于展示。
- **未上报 `vramMb` 的节点只能领取「无硬件要求」的任务。**

响应 `data`：`WorkerView`。

### POST /api/workers/heartbeat

请求：

```json
{ "status": "IDLE", "currentTaskId": null }
```

- `status`：`IDLE` | `BUSY`。
- `currentTaskId`：`BUSY` 时必填，服务端据此续租。

响应 `data`：

```json
{ "serverTime": "2026-09-22T10:00:00Z", "heartbeatIntervalSeconds": 20, "currentTaskId": null }
```

### POST /api/worker/tasks/claim

长轮询领取一个任务。

请求：`{ "waitSeconds": 20 }`（0–30）

响应：
- `200` + `data = TaskAssignment`
- `204 No Content`：等待超时且无任务

`TaskAssignment`：

```json
{
  "taskId": "uuid",
  "attemptId": "uuid",
  "attemptNo": 1,
  "prompt": "一只在星空下奔跑的机械鹿",
  "leaseId": "uuid",
  "leaseExpiresAt": "2026-09-22T10:05:00Z"
}
```

### POST /api/worker/tasks/{taskId}/progress

上报进度并续租。

请求：`{ "leaseId": "uuid", "progress": 45 }`

- `progress`：0–100。达到 100 时任务状态由 `RUNNING` 变为 `UPLOADING`。

响应 `data`：`{ "taskId": "...", "status": "RUNNING", "progress": 45, "leaseExpiresAt": "..." }`

### POST /api/worker/tasks/{taskId}/complete

提交结果元数据。**幂等**：重复提交同一租约返回同一结果。

请求：

```json
{
  "leaseId": "uuid",
  "fileKey": "videos/20260922/xxxx.mp4",
  "fileSize": 1048576,
  "checksum": "sha256-hex",
  "durationSeconds": 3.2,
  "width": 1280,
  "height": 720
}
```

服务端会校验文件存在性（必要时校验 checksum），校验失败返回 `40920`，任务不得进入 `SUCCEEDED`。

响应 `data`：`{ "taskId": "...", "status": "SUCCEEDED", "resultId": "uuid", "fileUrl": "/api/files/..." }`

### POST /api/worker/tasks/{taskId}/fail

上报执行失败。

请求：

```json
{ "leaseId": "uuid", "errorCode": "CUDA_OOM", "errorMessage": "显存不足", "retryable": true }
```

处理规则：
- `retryable = true` 且 `retryCount + 1 <= maxRetry` → `retryCount++`，状态回 `QUEUED`。
- 否则 → `FAILED`。

响应 `data`：`{ "taskId": "...", "status": "QUEUED", "retryCount": 1, "errorCode": "CUDA_OOM" }`

### POST /api/worker/files/presign

为本次结果申请一个存储 key。

请求：`{ "filename": "output.mp4", "contentType": "video/mp4", "sizeBytes": 1048576 }`

响应 `data`：

```json
{
  "fileKey": "videos/20260922/xxxx.mp4",
  "uploadUrl": "/api/worker/files",
  "method": "POST",
  "mode": "multipart",
  "maxSizeBytes": 2147483648,
  "expiresAt": "2026-09-22T10:30:00Z"
}
```

> V1 统一走平台中转上传（`mode = multipart`）。MinIO 直传（`mode = presigned-put`）为后续优化项。

### POST /api/worker/files

`multipart/form-data` 上传，字段：`fileKey`（文本）、`file`（二进制）。

响应 `data`：

```json
{ "fileKey": "videos/20260922/xxxx.mp4", "fileUrl": "/api/files/videos/20260922/xxxx.mp4", "size": 1048576, "checksum": "sha256-hex", "contentType": "video/mp4" }
```

### GET /api/files/{*key}

下载/播放结果文件。**免认证**（`<video>` 标签无法携带 Authorization 头）。key 为随机 UUID 文件名，V1 接受该风险。

返回原始字节流，带 `Content-Type` 与 `Content-Length`。

---

## 6. 视图对象

### TaskView

```json
{
  "id": "uuid",
  "prompt": "一只在星空下奔跑的机械鹿",
  "status": "RUNNING",
  "progress": 45,
  "retryCount": 0,
  "maxRetry": 2,
  "requirement": {
    "resolution": "1080P",
    "durationSeconds": 5,
    "fps": 30,
    "requiredVramMb": 12288,
    "gpuTier": "STANDARD",
    "summary": "1080P · 5秒 · 30fps → 需要 ≥12GB 显存（STANDARD 级 GPU）"
  },
  "workerId": "uuid | null",
  "workerName": "gpu-node-01 | null",
  "leaseExpiresAt": "2026-09-22T10:05:00Z | null",
  "errorCode": null,
  "errorMessage": null,
  "result": { "...TaskResultView | null" },
  "createdAt": "...",
  "startedAt": "...",
  "finishedAt": null,
  "updatedAt": "..."
}
```

`requirement` 为 `null` 表示该任务不限定硬件。

### TaskDetailView

```json
{ "task": { "...TaskView" }, "attempts": [ { "...TaskAttemptView" } ], "result": { "...TaskResultView | null" } }
```

### TaskResultView

```json
{
  "id": "uuid", "taskId": "uuid", "fileKey": "videos/.../a.mp4",
  "fileUrl": "/api/files/videos/.../a.mp4",
  "fileSize": 1048576, "checksum": "hex",
  "durationSeconds": 3.2, "width": 1280, "height": 720,
  "createdAt": "..."
}
```

### TaskAttemptView

```json
{
  "id": "uuid", "taskId": "uuid", "workerId": "uuid | null", "workerName": "gpu-node-01 | null",
  "leaseId": "uuid", "attemptNo": 1, "status": "SUCCEEDED",
  "errorCode": null, "errorMessage": null,
  "startedAt": "...", "endedAt": "..."
}
```

### WorkerView

```json
{
  "id": "uuid", "name": "gpu-node-01", "status": "ONLINE", "runtimeStatus": "IDLE",
  "gpuModel": "RTX 4090", "vramMb": 24576,
  "gpuTier": "PRO", "maxDurationSeconds": 15, "supportedResolutions": "480P,720P,1080P,4K",
  "workerVersion": "0.1.0", "modelVersion": "MiniMax-H3",
  "currentTaskId": null, "lastHeartbeatAt": "...",
  "enabled": true, "createdAt": "..."
}
```

### UserView

```json
{
  "id": "uuid",
  "username": "alice",
  "role": "USER",
  "enabled": true,
  "lastLoginAt": "2026-09-22T10:00:00Z | null",
  "createdAt": "..."
}
```

> `passwordHash` 永远不会出现在任何响应中。

---

## 7. 视频要求与显卡匹配

用户提交任务时可选择视频要求，平台据此推导所需的**显存下限**与**显卡档位**，
并在 Worker 领取任务时做能力过滤：**只有能力满足要求的节点才能领到该任务**。

### 7.1 用户可选参数

| 参数 | 可选值 | 说明 |
|---|---|---|
| `resolution` | `480P` `720P` `1080P` `4K` | 决定基础显存需求 |
| `durationSeconds` | `3` `5` `10` `15` | 越长显存/显存驻留越高 |
| `fps` | `24` `30` `60` | 越高单帧计算与显存压力越大 |

### 7.2 推导规则（占位系数）

> ⚠️ 下列系数是**工程占位值**，仅用于把「用户要求」映射成「硬件约束」的框架。
> **必须等 M0（MiniMax H3 单机部署验证）完成后，用实测的峰值显存数据校准**，
> 在此之前不得作为对外承诺。

```
基础显存 baseVramMb：
  480P → 4096     720P → 8192     1080P → 12288     4K → 24576

时长系数 durationFactor：  3~5s → 1.0     10s → 1.25     15s → 1.5
帧率系数 fpsFactor：       24 → 0.9       30 → 1.0       60 → 1.6

requiredVramMb = 向上取整到 1GB 倍数( baseVramMb × durationFactor × fpsFactor )
```

显卡档位由 `requiredVramMb` 推导，同时决定节点能否承接：

| GpuTier | 显存区间 | 典型显卡 |
|---|---|---|
| `ENTRY` | < 12 GB | GTX 1660 / RTX 3050 |
| `STANDARD` | 12 – 19 GB | RTX 3060 12G / RTX 4070 |
| `PRO` | 20 – 39 GB | RTX 3090 / RTX 4090 |
| `ULTRA` | ≥ 40 GB | A100 / H100 |

### 7.3 领取时的匹配条件

Worker 调用 `POST /api/worker/tasks/claim` 时，服务端只返回同时满足下列条件的任务：

```
task.required_vram_mb  IS NULL  OR  task.required_vram_mb  <= worker.vram_mb
task.min_gpu_tier      IS NULL  OR  task.min_gpu_tier      <= worker.gpu_tier
task.duration_seconds  IS NULL  OR  worker.max_duration_seconds 为空/<=0   -- 视为不限制
                                    OR  task.duration_seconds <= worker.max_duration_seconds
```

- 三个条件任一不满足，该任务对该节点**不可见**，节点会继续领取下一个候选任务。
- 节点未上报 `vramMb` 时视为「无能力信息」，只能领取 `required_vram_mb IS NULL` 的任务。
- 因此当在线节点都不满足某个要求时，任务会一直停留在 `QUEUED`；
  前端通过 `GET /api/capabilities` 的 `serviceable` 字段提示用户，而不是让任务静默卡住。

### 7.4 设计边界

- 这是**基于能力的规则匹配**，不是性能预测或机器学习调度。
- 不区分同显存的不同显卡算力（例如 24GB 的 3090 与 4090 视为同级），
  也不做价格竞价、地域调度与资源预留（与需求文档 2.2 一致）。

## 8. 关键行为约束（验收相关）

| 编号 | 约束 |
|---|---|
| AT-01 | 合法提示词 → 任务 `QUEUED` 并返回任务编号 |
| AT-02 | 空闲 Worker 领取 → `ASSIGNED` → `RUNNING`，记录节点与租约 |
| AT-04 | 两个 Worker 同时领取，同一任务只有一个有效租约（`FOR UPDATE SKIP LOCKED`） |
| AT-05 | Worker 断网超过租约 → 租约回收，任务回 `QUEUED` 或 `FAILED` |
| AT-06 | 重复 `complete` 只产生一个结果，返回幂等响应 |
| AT-07 | 文件不存在或 checksum 不符 → 任务不得进入 `SUCCEEDED` |
| AT-08 | 管理员重试 → 新增 `TaskAttempt` 并保留失败历史 |
| AT-09 | 节点停用 → 心跳可记录，但不能领取新任务（`403` 或 claim 返回空） |
| AT-10 | 空/非法提示词 → 拒绝创建并返回可理解错误 |
| AT-11 | 带视频要求的任务，只有显存/档位/时长满足的节点能领取 |
| AT-12 | 无任何在线节点满足要求时，任务保持 `QUEUED` 且不报错 |

## 9. 超时与心跳参数（服务端配置）

| 参数 | 默认 | 说明 |
|---|---|---|
| `opengpu.task.lease-seconds` | 300 | 租约时长，`progress`/`heartbeat` 会续租 |
| `opengpu.task.max-retry` | 2 | 自动重试上限，超过则 `FAILED` |
| `opengpu.task.heartbeat-timeout-seconds` | 90 | 超过该时长无心跳 → `OFFLINE` |
| `opengpu.task.lease-reaper-interval-seconds` | 15 | 租约回收扫描周期 |
| `opengpu.task.max-file-size-bytes` | 2147483648 | 单个结果文件上限（2 GiB） |
