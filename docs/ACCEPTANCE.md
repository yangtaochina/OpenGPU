# 验收测试对照（V1）

对应需求文档第 9 节 `AT-01 ~ AT-10`。自动化脚本：`scripts/acceptance.ps1`。

```powershell
# 前置：Docker 依赖已启动，platform-api 已在 8080 运行
# 重要：运行验收脚本前请先停止 Worker，否则 Worker 会抢占脚本要领取的任务
powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1            # 停止应用进程（含 Worker）
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -SkipWorker   # 只起依赖 + 后端
powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1

# 追加 AT-05（需要等待一个完整租约周期，建议先把 lease-seconds 调小）
powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1 -IncludeSlowTests
```

参数：`-BaseUrl` `-AdminUser` `-AdminPassword` `-WorkerToken`。

---

## 1. 用例对照

| 编号 | 场景 | 预期结果 | 自动化 | 实现位置 |
|---|---|---|---|---|
| AT-01 | 提交合法提示词 | 任务进入 `QUEUED` 并返回任务编号 | ✅ | `TaskService#create` → `POST /api/tasks` |
| AT-02 | 空闲 Worker 领取任务 | 进入 `ASSIGNED` / `RUNNING`，记录节点与租约 | ✅ | `ClaimService#claim` → `POST /api/worker/tasks/claim` |
| AT-03 | H3 正常生成并上传 | 进入 `SUCCEEDED`，网页可播放 | ⚠️ 需真实模型 | `worker/engines/h3_engine.py`（M0 后接入） |
| AT-04 | 两个 Worker 同时领取 | 同一任务只有一个有效租约 | ✅ | `SELECT ... FOR UPDATE SKIP LOCKED` |
| AT-05 | Worker 运行中断网 | 租约超时后任务重新排队或标记失败 | ⚠️ 慢测试 | `LeaseReaper#reapExpiredLeases` |
| AT-06 | Worker 重复提交完成 | 只保留一个有效结果，返回幂等响应 | ✅ | `task_result.task_id` 唯一索引 + `TaskExecutionService#complete` |
| AT-07 | 上传文件不存在或校验失败 | 任务不得进入 `SUCCEEDED` | ✅ | `TaskExecutionService#verifyResultFile` |
| AT-08 | 管理员重试失败任务 | 创建新的执行尝试并保留失败历史 | ✅ | `TaskService#retry` + `task_attempt` 追加表 |
| AT-09 | 节点被停用 | 心跳可被记录但不能领取新任务 | ✅ | `WorkerService#requireClaimableWorker` → `40300` |
| AT-10 | 非法或空提示词 | 拒绝创建并返回可理解错误 | ✅ | `TaskService#create` → `40001` |
| AT-11 | 视频要求与显卡能力匹配 | 只有显存/档位/时长满足要求的节点能领到该任务 | ✅ | `ClaimService#buildClaimQuery` + `NodeCapability#satisfies` |
| AT-12 | 无节点满足要求 | 任务保持 `QUEUED`，不报错，`capabilities` 标记 `serviceable=false` | ✅ | `CapabilityService#describe` |
| AT-13 | 开放用户注册 | 注册成功即登录；重复/非法用户名密码被拒；**注册无法提权** | ✅ | `UserAuthService#register` + `CredentialPolicy` |
| AT-14 | 管理端用户管理 | 建号/启停/改角色/重置密码生效；不能把自己或最后一个管理员锁死 | ✅ | `AdminUserService` + `AdminUserServiceTest` |

> AT-13 / AT-14 是本项目在需求文档 AT-01 ~ AT-10 之外自行扩展的用例。

脚本另外覆盖了非功能要求：未认证访问被拒（40100）、错误密码被拒、
Worker 凭证越权访问管理端被拒（40300）、非法枚举参数返回 40000、结果文件可下载与 `Content-Type` 正确。

---

## 2. 手动复核步骤

### AT-13 开放注册

```powershell
# 平台策略（免认证）：是否开放注册 + 账号密码长度限制
Invoke-RestMethod -Uri http://localhost:8080/api/auth/config | ConvertTo-Json -Depth 4

# 注册（成功即返回登录态）
$reg = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/register `
  -ContentType 'application/json' -Body '{"username":"alice","password":"secret123"}').data
$reg.role    # 期望 USER

# 重复注册（大小写不敏感）→ 40930
Invoke-WebRequest -Method Post -Uri http://localhost:8080/api/auth/register `
  -ContentType 'application/json' -Body '{"username":"ALICE","password":"secret123"}' -SkipHttpErrorCheck

# 试图提权：请求体里塞 role 也必须被忽略
(Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/register `
  -ContentType 'application/json' -Body '{"username":"bob","password":"secret123","role":"ADMIN"}').data.role
# 期望仍然是 USER
```

### AT-14 管理端用户管理

```powershell
$admin = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').data.token
$H = @{ Authorization = "Bearer $admin" }

# 列表 / 筛选
(Invoke-RestMethod -Uri "http://localhost:8080/api/admin/users?page=0&size=20" -Headers $H).data
(Invoke-RestMethod -Uri "http://localhost:8080/api/admin/users?role=ADMIN" -Headers $H).data
(Invoke-RestMethod -Uri "http://localhost:8080/api/admin/users?keyword=alice" -Headers $H).data

# 建号（可直接指定角色）
$new = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/admin/users -Headers $H `
  -ContentType 'application/json' -Body '{"username":"operator","password":"secret123","role":"ADMIN"}').data
$new.id

# 停用 -> 无法登录 -> 启用
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/users/$($new.id)/disable" -Headers $H | Out-Null
Invoke-WebRequest -Method Post -Uri http://localhost:8080/api/auth/login -ContentType 'application/json' `
  -Body '{"username":"operator","password":"secret123"}' -SkipHttpErrorCheck      # 期望 401
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/users/$($new.id)/enable" -Headers $H | Out-Null

# 重置密码
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/users/$($new.id)/reset-password" -Headers $H `
  -ContentType 'application/json' -Body '{"newPassword":"newsecret123"}' | Out-Null

# 改角色
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/admin/users/$($new.id)/role" -Headers $H `
  -ContentType 'application/json' -Body '{"role":"USER"}' | Out-Null

# 保护规则：不能把自己锁死（期望 40302）
$self = (Invoke-RestMethod -Uri http://localhost:8080/api/auth/me -Headers $H).data.id
Invoke-WebRequest -Method Post -Uri "http://localhost:8080/api/admin/users/$self/disable" -Headers $H -SkipHttpErrorCheck
Invoke-WebRequest -Method Post -Uri "http://localhost:8080/api/admin/users/$self/role" -Headers $H `
  -ContentType 'application/json' -Body '{"role":"USER"}' -SkipHttpErrorCheck
```

### AT-11 / AT-12 视频要求与显卡匹配

```powershell
$admin = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').data.token
$wkr = "wkr_00000000-0000-0000-0000-000000000001_devsecret"

# 1) 节点上报显卡能力（24GB / PRO / 最长 15 秒）
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/workers/register `
  -Headers @{Authorization="Bearer $wkr"} -ContentType 'application/json' `
  -Body '{"vramMb":24576,"gpuTier":"PRO","maxDurationSeconds":15}'

# 2) 查看能力矩阵：4K/15s/60fps 需要 59392MB，应标记为不可服务
$caps = (Invoke-RestMethod -Uri http://localhost:8080/api/capabilities `
  -Headers @{Authorization="Bearer $admin"}).data
$caps.fleet
$caps.matrix | Where-Object { $_.resolution -eq '4K' -and $_.durationSeconds -eq 15 -and $_.fps -eq 60 }

# 3) 创建一个超出全部节点能力的高要求任务
$heavy = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/tasks `
  -Headers @{Authorization="Bearer $admin"} -ContentType 'application/json' `
  -Body '{"prompt":"4K 高要求任务","requirement":{"resolution":"4K","durationSeconds":15,"fps":60}}').data
$heavy.requirement
# 期望：requiredVramMb=59392, gpuTier=ULTRA

# 4) 24GB 节点尝试领取（多试几次），永远拿不到该任务
1..5 | ForEach-Object {
  try {
    $c = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/worker/tasks/claim `
      -Headers @{Authorization="Bearer $wkr"} -ContentType 'application/json' `
      -Body '{"waitSeconds":0}').data
    "$($c.taskId)  <- 不等于 $($heavy.id) 即符合预期"
  } catch { "无任务可领 (204)" }
}

# 5) 任务应保持 QUEUED
(Invoke-RestMethod -Uri "http://localhost:8080/api/tasks/$($heavy.id)" `
  -Headers @{Authorization="Bearer $admin"}).data.task.status
```

**创建低配节点验证反向约束**（8GB / ENTRY / 最长 5 秒）：

```powershell
$low = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/admin/workers `
  -Headers @{Authorization="Bearer $admin"} -ContentType 'application/json' `
  -Body '{"name":"low-cap-node","gpuModel":"GTX 1650","vramMb":8192,"gpuTier":"ENTRY","maxDurationSeconds":5}').data
$low.worker.gpuTier   # 期望 ENTRY
$low.token            # 该节点的凭证，仅此一次返回
```

用 `$low.token` 反复调用 claim，应始终领不到 4K 任务（也领不到「要求时长 > 5 秒」的任务）。

### AT-01 / AT-10

```powershell
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').data.token

Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/tasks `
  -Headers @{Authorization="Bearer $token"} -ContentType 'application/json' `
  -Body '{"prompt":"一只在星空下奔跑的机械鹿"}'
# 期望：code=0，data.status=QUEUED，data.id 为 36 位 UUID

# 非法输入
Invoke-WebRequest -Method Post -Uri http://localhost:8080/api/tasks `
  -Headers @{Authorization="Bearer $token"} -ContentType 'application/json' `
  -Body '{"prompt":"   "}' -SkipHttpErrorCheck
# 期望：HTTP 400，code=40001
```

### AT-05 租约回收（需要缩短租约）

用较短租约重启后端，便于观察：

```powershell
java -jar target/opengpu-platform-api.jar `
  --opengpu.task.lease-seconds=30 `
  --opengpu.task.lease-reaper-interval-seconds=10
```

1. 创建任务并领取（拿到 `taskId` / `leaseId`），**不要**再上报进度或心跳。
2. 等待约 40 秒后查询 `GET /api/tasks/{taskId}`。
3. 期望：`status` 回到 `QUEUED`（重试预算未用尽），且 `attempts` 中出现 `LEASE_EXPIRED` 的记录。
4. 若重试预算已用尽，则期望 `status = FAILED`。

### AT-03 真实生成

需先完成 M0。在 `worker/` 下：

```powershell
$env:OPENGPU_ENGINE="h3"
$env:OPENGPU_H3_COMMAND="python C:\h3\run.py --prompt {prompt} --out {output}"
python worker.py
```

或在有 `ffmpeg` 的机器上用 Mock 引擎验证可播放性：

```powershell
$env:OPENGPU_ENGINE="mock"   # ffmpeg 在 PATH 时生成真实可播放 mp4
python worker.py
```

验证方式：网页任务详情中 `<video>` 能播放，或直接下载结果文件用播放器打开。

---

## 3. 手工异常场景检查表

| 场景 | 操作 | 期望 |
|---|---|---|
| 租约不匹配 | 用旧 `leaseId` 调 `progress` | `40910` |
| 过期 Worker 覆盖结果 | 任务被回收后旧 Worker 再调 `complete` | `40910`，结果不被覆盖 |
| 重复 complete | 同一 `leaseId` 连续调两次 | 两次返回同一 `resultId` |
| checksum 不符 | `complete` 传错误 checksum | `40920`，状态不进入 `SUCCEEDED` |
| 非 `QUEUED` 取消 | 对 `RUNNING` 任务调 `cancel` | `40900` |
| 非 `FAILED` 重试 | 对 `QUEUED` 任务调 `retry` | `40900` |
| 停用节点 | 停用后调 `claim` | `40300` |
| 停用节点心跳 | 停用后调 `heartbeat` | `200`，心跳被记录 |
| Worker 越权 | 用 Worker token 调 `/api/admin/**` | `40300` |
| 用户越权 | 用 `user` 账号调 `/api/admin/**` | `40300` |
| 非法视频要求 | `requirement.resolution = "8K"` | `40002` |
| 无能力节点领取 | 8GB 节点领取 4K 任务 | 返回 204，任务保持 `QUEUED` |
| 能力未知节点 | 不传 `vramMb` 的节点领取带要求任务 | 返回 204（只能领不限硬件的任务） |
| 时长超限 | 声明 `maxDurationSeconds=5` 的节点领取 15 秒任务 | 返回 204 |

---

## 4. 常见问题

| 现象 | 原因与处理 |
|---|---|
| `claim` 一直返回 204 | 队列为空；确认任务已成功创建且状态为 `QUEUED` |
| `claim` 返回 40100 | Worker token 与数据库中节点不匹配；核对 `dev-worker-01` 的 token |
| `claim` 返回 40300 | 节点被停用；管理端启用，或检查 `enabled` 字段 |
| `complete` 返回 40920 | 未先上传文件，或 checksum/大小与落库值不一致 |
| `complete` 返回 40910 | 租约已过期或被回收；任务可能已重新排队 |
| 节点列表显示 `OFFLINE` | 超过 `heartbeat-timeout-seconds` 无心跳；确认 Worker 进程仍在运行 |
| Mock 任务产出不可播放 | 本机无 `ffmpeg` 且未配置 `OPENGPU_MOCK_SAMPLE_VIDEO` |
| 启动报 jwt-secret 长度不足 | `opengpu.security.jwt-secret` 需 ≥ 32 字节 |
| Redis 连不上但功能正常 | 设计如此，队列唤醒退化为轮询 |
| 带视频要求的任务一直 `QUEUED` | 当前在线节点不满足要求；查 `GET /api/capabilities` 的 `serviceable`，或看节点是否上报了 `vramMb` |
| 所有带要求的任务都领不到 | 节点未上报显存（`OPENGPU_VRAM_MB=0`），此时只能领取不限硬件的任务 |
| 注册返回 `40301` | 平台关闭了注册；`opengpu.security.registration-enabled=false` 或环境变量 `OPENGPU_REGISTRATION_ENABLED=false` |
| 注册返回 `40930` | 用户名已被占用（比较时忽略大小写） |
| 停用账号后仍能访问 | 停用只阻止**再次登录**，已签发的 JWT 在过期前仍然有效（V1 不维护 token 黑名单） |
| 管理端操作返回 `40302` | 触发了保护规则：不能停用自己 / 不能改自己角色 / 不能停用或降级最后一个启用的管理员 |
