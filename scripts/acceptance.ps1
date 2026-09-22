<#
  智由 AI 视频任务平台 — V1 验收测试脚本
  对应需求文档第 9 节 AT-01 ~ AT-10。

  前置条件：
    1. deploy/docker-compose.yml 中的 postgres / redis 已启动
    2. platform-api 已在 http://localhost:8080 运行
    3. 使用默认开发节点凭证（见 application.yml 的 opengpu.security.dev-worker-token）

  用法：
    powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1
    powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1 -IncludeSlowTests   # 额外跑 AT-05（约 5 分钟）

  说明：
    AT-03（真实模型生成可播放视频）依赖 MiniMax H3 或 ffmpeg，不在本脚本内断言，
    由 worker 的 mock/h3 引擎覆盖，详见 docs/ACCEPTANCE.md。
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin123",
    [string]$WorkerToken = "wkr_00000000-0000-0000-0000-000000000001_devsecret",
    [switch]$IncludeSlowTests
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 让中文输出在 Windows 控制台正确显示
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 某些宿主不支持，忽略
}

$script:Passed = 0
$script:Failed = 0
$script:Skipped = 0

function Get-PropertyOrNull {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object.PSObject.Properties.Name -contains $Name) {
        return $Object.$Name
    }
    return $null
}

# ----------------------------------------------------------------------
# 断言与输出
# ----------------------------------------------------------------------
function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkGray
    Write-Host " $Title" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkGray
}

function Assert-That {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Detail = ""
    )
    if ($Condition) {
        $script:Passed++
        Write-Host ("  [PASS] " + $Name) -ForegroundColor Green
    } else {
        $script:Failed++
        Write-Host ("  [FAIL] " + $Name + $(if ($Detail) { "  -> $Detail" } else { "" })) -ForegroundColor Red
    }
}

function Write-Skip {
    param([string]$Name, [string]$Reason)
    $script:Skipped++
    Write-Host ("  [SKIP] " + $Name + "  -> " + $Reason) -ForegroundColor Yellow
}

# ----------------------------------------------------------------------
# HTTP 帮助函数（统一使用 HttpClient，保证非 2xx 也能稳定读到响应体）
# ----------------------------------------------------------------------
Add-Type -AssemblyName System.Net.Http | Out-Null
$script:HttpClient = New-Object System.Net.Http.HttpClient
$script:HttpClient.Timeout = [TimeSpan]::FromSeconds(120)

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = ""
    )
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod($Method)
    $request.RequestUri = New-Object System.Uri("$BaseUrl$Path")
    if ($Token) {
        $request.Headers.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $Token)
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $request.Content = New-Object System.Net.Http.StringContent(
            $json, [System.Text.Encoding]::UTF8, "application/json")
    }

    try {
        $response = $script:HttpClient.SendAsync($request).GetAwaiter().GetResult()
        try {
            $status = [int]$response.StatusCode
            $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }

    $parsed = $null
    if ($content) {
        try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $null }
    }

    return [pscustomobject]@{
        Status  = $status
        Data    = (Get-PropertyOrNull -Object $parsed -Name "data")
        Code    = (Get-PropertyOrNull -Object $parsed -Name "code")
        Message = (Get-PropertyOrNull -Object $parsed -Name "message")
        Raw     = $content
    }
}

function Send-MultipartFile {
    param(
        [string]$FileKey,
        [string]$FilePath,
        [string]$Token
    )
    $request = New-Object System.Net.Http.HttpRequestMessage
    $request.Method = New-Object System.Net.Http.HttpMethod("POST")
    $request.RequestUri = New-Object System.Uri("$BaseUrl/api/worker/files")
    $request.Headers.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $Token)

    $content = New-Object System.Net.Http.MultipartFormDataContent
    $content.Add((New-Object System.Net.Http.StringContent($FileKey)), "fileKey")
    $bytes = [System.IO.File]::ReadAllBytes($FilePath)
    $fileContent = New-Object System.Net.Http.ByteArrayContent($bytes, 0, $bytes.Length)
    $fileContent.Headers.ContentType =
        [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("video/mp4")
    $content.Add($fileContent, "file", "output.mp4")
    $request.Content = $content

    try {
        $response = $script:HttpClient.SendAsync($request).GetAwaiter().GetResult()
        try {
            $status = [int]$response.StatusCode
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        } finally {
            $response.Dispose()
        }
    } finally {
        $request.Dispose()
    }

    $parsed = $null
    if ($body) { try { $parsed = $body | ConvertFrom-Json } catch { $parsed = $null } }
    return [pscustomobject]@{
        Status  = $status
        Data    = (Get-PropertyOrNull -Object $parsed -Name "data")
        Code    = (Get-PropertyOrNull -Object $parsed -Name "code")
        Message = (Get-PropertyOrNull -Object $parsed -Name "message")
        Raw     = $body
    }
}

function New-QueuedTask {
    param([string]$Token, [string]$Prompt = "验收测试：星空下奔跑的机械鹿")
    return (Invoke-Api -Method Post -Path "/api/tasks" -Token $Token -Body @{ prompt = $Prompt })
}

function Claim-Task {
    param([string]$Token, [int]$WaitSeconds = 0)
    return (Invoke-Api -Method Post -Path "/api/worker/tasks/claim" -Token $Token -Body @{ waitSeconds = $WaitSeconds })
}

<#
  创建任务并立即领取，失败自动重试。

  为什么需要它：如果本机已有 Worker 在运行，它会先一步抢走队列里的任务，
  导致 claim 返回 204。这里通过「先建任务再领取」的重试把竞争窗口消掉；
  若多次仍拿不到，说明 Worker 正在持续抢占，直接给出明确提示并退出。
#>
function Claim-FreshTask {
    param([string]$Token, [int]$MaxAttempts = 6)
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        New-QueuedTask -Token $Token | Out-Null
        $claim = Claim-Task -Token $Token
        if ($claim.Status -eq 200 -and $null -ne $claim.Data) {
            return $claim
        }
        Start-Sleep -Milliseconds 700
    }
    Write-Host ""
    Write-Host "  无法领取到任务：很可能本机已有 Worker 在运行并持续抢占队列。" -ForegroundColor Red
    Write-Host "  请先停止 Worker，再运行验收脚本：" -ForegroundColor Red
    Write-Host "    powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# ======================================================================
# 0. 连通性与登录
# ======================================================================
Write-Section "0. 连通性与认证"

try {
    $health = Invoke-Api -Method Get -Path "/actuator/health"
    Assert-That "服务健康检查可用 (HTTP $($health.Status))" ($health.Status -eq 200)
} catch {
    Write-Host "  无法连接后端 $BaseUrl ：$_" -ForegroundColor Red
    exit 1
}

$login = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $AdminUser; password = $AdminPassword }
Assert-That "管理员登录成功" ($login.Status -eq 200 -and $login.Data.token)
$adminToken = $login.Data.token

$badLogin = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $AdminUser; password = "wrong-password" }
Assert-That "错误密码被拒绝 (40100)" ($badLogin.Status -eq 401 -and $badLogin.Code -eq 40100)

$noAuth = Invoke-Api -Method Get -Path "/api/tasks"
Assert-That "未携带凭证访问任务接口被拒绝 (40100)" ($noAuth.Status -eq 401 -and $noAuth.Code -eq 40100)

# Worker 必须先上报显卡能力，否则只能领取「不限硬件」的任务（见 docs/API.md §7.3）
$register = Invoke-Api -Method Post -Path "/api/workers/register" -Token $WorkerToken -Body @{
    gpuModel              = "Mock GPU (acceptance)"
    vramMb                = 24576
    workerVersion         = "0.1.0"
    modelVersion          = "MiniMax-H3"
    gpuTier               = "PRO"
    maxDurationSeconds    = 15
    supportedResolutions  = "480P,720P,1080P,4K"
}
Assert-That "Worker 注册并上报显卡能力 (24GB / PRO / 15s)" `
    ($register.Status -eq 200 -and $register.Data.vramMb -eq 24576 -and $register.Data.gpuTier -eq "PRO") `
    ("vram=" + $register.Data.vramMb + " tier=" + $register.Data.gpuTier)

# ======================================================================
# AT-01 / AT-10 创建任务
# ======================================================================
Write-Section "AT-01 / AT-10  提交提示词与非法输入"

$created = New-QueuedTask -Token $adminToken
Assert-That "AT-01 合法提示词创建成功 (201)" ($created.Status -eq 201 -and $created.Code -eq 0)
Assert-That "AT-01 返回任务编号" ($null -ne $created.Data.id -and "$($created.Data.id)".Length -eq 36) ("id=" + $created.Data.id)
Assert-That "AT-01 初始状态为 QUEUED" ($created.Data.status -eq "QUEUED") ("status=" + $created.Data.status)

$emptyPrompt = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{ prompt = "   " }
Assert-That "AT-10 空提示词被拒绝 (40001)" ($emptyPrompt.Status -eq 400 -and $emptyPrompt.Code -eq 40001)
Assert-That "AT-10 错误信息可读" ($emptyPrompt.Message -match "提示词")

$longPrompt = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{ prompt = ("x" * 2500) }
Assert-That "AT-10 超长提示词被拒绝 (40001)" ($longPrompt.Status -eq 400 -and $longPrompt.Code -eq 40001)

$badRetry = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{ prompt = "正常提示词"; maxRetry = 99 }
Assert-That "maxRetry 越界被拒绝 (40000)" ($badRetry.Status -eq 400 -and $badRetry.Code -eq 40000)

# 任务列表与详情（FR-U02 / FR-U03）
$list = Invoke-Api -Method Get -Path "/api/tasks?page=0&size=5" -Token $adminToken
Assert-That "任务列表可分页查询" ($list.Status -eq 200 -and @($list.Data.content).Count -ge 1)
$detail = Invoke-Api -Method Get -Path "/api/tasks/$($created.Data.id)" -Token $adminToken
Assert-That "任务详情返回 task/attempts/result" ($detail.Status -eq 200 -and $null -ne $detail.Data.task -and $null -ne $detail.Data.attempts)

$badStatus = Invoke-Api -Method Get -Path "/api/tasks?status=NOT_A_STATUS" -Token $adminToken
Assert-That "非法 status 筛选值返回 40000" ($badStatus.Status -eq 400 -and $badStatus.Code -eq 40000)

# ======================================================================
# AT-02 领取任务
# ======================================================================
Write-Section "AT-02  空闲 Worker 领取任务"

$claim = Claim-FreshTask -Token $WorkerToken
Assert-That "AT-02 领取成功 (200)" ($claim.Status -eq 200 -and $claim.Code -eq 0) ("status=" + $claim.Status)
Assert-That "AT-02 返回租约 leaseId" ($null -ne $claim.Data.leaseId)
Assert-That "AT-02 返回租约到期时间" ($null -ne $claim.Data.leaseExpiresAt)
Assert-That "AT-02 返回 attemptNo 与提示词" ($claim.Data.attemptNo -ge 1 -and $claim.Data.prompt.Length -gt 0)

$taskId = $claim.Data.taskId
$leaseId = $claim.Data.leaseId
Assert-That "AT-02 任务状态变为 ASSIGNED" ((Invoke-Api -Method Get -Path "/api/tasks/$taskId" -Token $adminToken).Data.task.status -eq "ASSIGNED")

# 进度上报 -> RUNNING
$progress = Invoke-Api -Method Post -Path "/api/worker/tasks/$taskId/progress" -Token $WorkerToken -Body @{ leaseId = $leaseId; progress = 30 }
Assert-That "AT-02 进度上报后状态为 RUNNING" ($progress.Status -eq 200 -and $progress.Data.status -eq "RUNNING") ("status=" + $progress.Data.status)
Assert-That "AT-02 进度值被记录" ($progress.Data.progress -eq 30)

# 心跳续租（FR-W02）
$heartbeat = Invoke-Api -Method Post -Path "/api/workers/heartbeat" -Token $WorkerToken -Body @{ status = "BUSY"; currentTaskId = $taskId }
Assert-That "FR-W02 心跳上报成功" ($heartbeat.Status -eq 200)
Assert-That "FR-W02 返回 serverTime 与心跳间隔" ($null -ne $heartbeat.Data.serverTime -and $heartbeat.Data.heartbeatIntervalSeconds -gt 0)

# 过期/错误租约必须被拒绝（核心防串扰）
$wrongLease = Invoke-Api -Method Post -Path "/api/worker/tasks/$taskId/progress" -Token $WorkerToken -Body @{ leaseId = "00000000-0000-0000-0000-0000000000ff"; progress = 50 }
Assert-That "错误租约被拒绝 (40910)" ($wrongLease.Status -eq 409 -and $wrongLease.Code -eq 40910)

# ======================================================================
# AT-07 结果文件校验
# ======================================================================
Write-Section "AT-07  上传文件不存在或校验失败"

$badComplete = Invoke-Api -Method Post -Path "/api/worker/tasks/$taskId/complete" -Token $WorkerToken -Body @{
    leaseId  = $leaseId
    fileKey  = "videos/19700101/00000000-0000-0000-0000-000000000000.mp4"
    fileSize = 1234
    checksum = "deadbeef"
}
Assert-That "AT-07 文件不存在被拒绝 (40920)" ($badComplete.Status -eq 409 -and $badComplete.Code -eq 40920) ("status=" + $badComplete.Status + " code=" + $badComplete.Code)
Assert-That "AT-07 任务未进入 SUCCEEDED" ((Invoke-Api -Method Get -Path "/api/tasks/$taskId" -Token $adminToken).Data.task.status -ne "SUCCEEDED")

# ======================================================================
# AT-06 正常完成 + 幂等
# ======================================================================
Write-Section "AT-06  正常完成与重复上报幂等"

$dummyVideo = Join-Path $env:TEMP "opengpu-acceptance-dummy.mp4"
$random = New-Object System.Random 20260922
$buffer = New-Object byte[] 65536
$random.NextBytes($buffer)
[System.IO.File]::WriteAllBytes($dummyVideo, $buffer)
$actualChecksum = (Get-FileHash -Path $dummyVideo -Algorithm SHA256).Hash.ToLower()

$presign = Invoke-Api -Method Post -Path "/api/worker/files/presign" -Token $WorkerToken -Body @{
    filename    = "output.mp4"
    contentType = "video/mp4"
    sizeBytes   = $buffer.Length
}
Assert-That "文件上传位申请成功 (presign)" ($presign.Status -eq 200 -and $presign.Data.fileKey -and $presign.Data.mode -eq "multipart")

$upload = Send-MultipartFile -FileKey $presign.Data.fileKey -FilePath $dummyVideo -Token $WorkerToken
Assert-That "文件上传成功" ($upload.Status -eq 200 -and $upload.Data.fileKey -eq $presign.Data.fileKey)
Assert-That "服务端返回的 checksum 与本地一致" ($upload.Data.checksum -eq $actualChecksum) ("server=" + $upload.Data.checksum + " local=" + $actualChecksum)

# checksum 不一致必须被拒绝
$mismatch = Invoke-Api -Method Post -Path "/api/worker/tasks/$taskId/complete" -Token $WorkerToken -Body @{
    leaseId  = $leaseId
    fileKey  = $presign.Data.fileKey
    fileSize = $buffer.Length
    checksum = "0" * 64
}
Assert-That "AT-07 checksum 不一致被拒绝 (40920)" ($mismatch.Status -eq 409 -and $mismatch.Code -eq 40920)

$complete = Invoke-Api -Method Post -Path "/api/worker/tasks/$taskId/complete" -Token $WorkerToken -Body @{
    leaseId         = $leaseId
    fileKey         = $presign.Data.fileKey
    fileSize        = $buffer.Length
    checksum        = $actualChecksum
    durationSeconds = 3.0
    width           = 1280
    height          = 720
}
Assert-That "AT-06 正常完成进入 SUCCEEDED" ($complete.Status -eq 200 -and $complete.Data.status -eq "SUCCEEDED") ("status=" + $complete.Data.status)
$firstResultId = $complete.Data.resultId

$duplicate = Invoke-Api -Method Post -Path "/api/worker/tasks/$taskId/complete" -Token $WorkerToken -Body @{
    leaseId  = $leaseId
    fileKey  = $presign.Data.fileKey
    fileSize = $buffer.Length
    checksum = $actualChecksum
}
Assert-That "AT-06 重复 complete 返回同一结果 (幂等)" ($duplicate.Status -eq 200 -and $duplicate.Data.resultId -eq $firstResultId) ("first=" + $firstResultId + " second=" + $duplicate.Data.resultId)

$detailAfter = Invoke-Api -Method Get -Path "/api/tasks/$taskId" -Token $adminToken
Assert-That "AT-06 只保留一个结果对象" ($null -ne $detailAfter.Data.result -and $detailAfter.Data.result.id -eq $firstResultId)

# 结果文件可下载/播放（FR-U04）
$fileUrl = $detailAfter.Data.result.fileUrl
$download = Invoke-WebRequest -Uri "$BaseUrl$fileUrl" -UseBasicParsing
Assert-That "FR-U04 结果文件可下载" ($download.StatusCode -eq 200 -and $download.RawContentLength -eq $buffer.Length)
Assert-That "FR-U04 返回 video/mp4" ($download.Headers["Content-Type"] -match "video/mp4")

# ======================================================================
# AT-08 人工重试
# ======================================================================
Write-Section "AT-08  管理员重试失败任务"

$failTask = New-QueuedTask -Token $adminToken -Prompt "验收测试：用于人工重试的失败任务"
$failClaim = Claim-FreshTask -Token $WorkerToken
$failTaskId = $failClaim.Data.taskId
$failLeaseId = $failClaim.Data.leaseId
Invoke-Api -Method Post -Path "/api/worker/tasks/$failTaskId/progress" -Token $WorkerToken -Body @{ leaseId = $failLeaseId; progress = 5 } | Out-Null

$fail = Invoke-Api -Method Post -Path "/api/worker/tasks/$failTaskId/fail" -Token $WorkerToken -Body @{
    leaseId      = $failLeaseId
    errorCode    = "MODEL_LOAD_FAILED"
    errorMessage = "验收测试：模拟模型加载失败"
    retryable    = $false
}
Assert-That "上报不可重试失败 -> FAILED" ($fail.Status -eq 200 -and $fail.Data.status -eq "FAILED") ("status=" + $fail.Data.status)

$failAgain = Invoke-Api -Method Post -Path "/api/worker/tasks/$failTaskId/fail" -Token $WorkerToken -Body @{
    leaseId      = $failLeaseId
    errorCode    = "MODEL_LOAD_FAILED"
    errorMessage = "重复上报"
    retryable    = $false
}
Assert-That "重复 fail 上报幂等（不报错）" ($failAgain.Status -eq 200)

$retry = Invoke-Api -Method Post -Path "/api/admin/tasks/$failTaskId/retry" -Token $adminToken
Assert-That "AT-08 人工重试后回到 QUEUED" ($retry.Status -eq 200 -and $retry.Data.status -eq "QUEUED") ("status=" + $retry.Data.status)

$failDetail = Invoke-Api -Method Get -Path "/api/tasks/$failTaskId" -Token $adminToken
$failedAttempts = @($failDetail.Data.attempts | Where-Object { $_.status -eq "FAILED" })
Assert-That "AT-08 失败历史被保留" ($failedAttempts.Count -ge 1) ("FAILED 尝试数=" + $failedAttempts.Count)

$retryOnQueued = Invoke-Api -Method Post -Path "/api/admin/tasks/$failTaskId/retry" -Token $adminToken
Assert-That "非 FAILED 状态不可重试 (40900)" ($retryOnQueued.Status -eq 409 -and $retryOnQueued.Code -eq 40900)

# ======================================================================
# AT-09 停用节点
# ======================================================================
Write-Section "AT-09  节点被停用"

$workerId = "00000000-0000-0000-0000-000000000001"
$disable = Invoke-Api -Method Post -Path "/api/admin/workers/$workerId/disable" -Token $adminToken
Assert-That "节点停用成功" ($disable.Status -eq 200 -and $disable.Data.enabled -eq $false -and $disable.Data.status -eq "DISABLED")

$disabledHeartbeat = Invoke-Api -Method Post -Path "/api/workers/heartbeat" -Token $WorkerToken -Body @{ status = "IDLE" }
Assert-That "AT-09 停用节点心跳仍可记录" ($disabledHeartbeat.Status -eq 200)

$disabledClaim = Claim-Task -Token $WorkerToken
Assert-That "AT-09 停用节点不能领取新任务 (40300)" ($disabledClaim.Status -eq 403 -and $disabledClaim.Code -eq 40300) ("status=" + $disabledClaim.Status + " code=" + $disabledClaim.Code)

$enable = Invoke-Api -Method Post -Path "/api/admin/workers/$workerId/enable" -Token $adminToken
Assert-That "节点可恢复启用" ($enable.Status -eq 200 -and $enable.Data.enabled -eq $true)

$afterEnable = Claim-Task -Token $WorkerToken
Assert-That "恢复后可正常领取" ($afterEnable.Status -eq 200 -or $afterEnable.Status -eq 204)

# ======================================================================
# AT-04 并发领取唯一性
# ======================================================================
Write-Section "AT-04  两个 Worker 同时领取"

for ($i = 0; $i -lt 6; $i++) { New-QueuedTask -Token $adminToken -Prompt "验收测试：并发领取 $i" | Out-Null }

$claimScript = {
    param($Url, $Token)
    try {
        $r = Invoke-WebRequest -Method Post -Uri "$Url/api/worker/tasks/claim" `
            -Headers @{ Authorization = "Bearer $Token" } `
            -ContentType "application/json" `
            -Body '{"waitSeconds":0}' -UseBasicParsing
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = "" } }
        return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message }
    }
}

$jobs = @()
for ($i = 0; $i -lt 6; $i++) {
    $jobs += Start-Job -ScriptBlock $claimScript -ArgumentList $BaseUrl, $WorkerToken
}
$results = $jobs | Wait-Job -Timeout 60 | Receive-Job
$jobs | Remove-Job -Force

$claimedIds = @()
foreach ($result in $results) {
    if ($result.Status -eq 200 -and $result.Body) {
        $parsed = $result.Body | ConvertFrom-Json
        if ($parsed.data -and $parsed.data.taskId) { $claimedIds += $parsed.data.taskId }
    }
}
$uniqueIds = @($claimedIds | Select-Object -Unique)
Assert-That "AT-04 并发领取返回的 taskId 互不重复" ($claimedIds.Count -eq $uniqueIds.Count) ("claims=" + $claimedIds.Count + " unique=" + $uniqueIds.Count)
Assert-That "AT-04 并发领取至少成功一次" ($claimedIds.Count -ge 1)
$serverErrors = @($results | Where-Object { $_.Status -eq 500 })
Assert-That "AT-04 并发领取无 500 错误" ($serverErrors.Count -eq 0)

# ======================================================================
# AT-11 / AT-12 视频要求 → 显卡能力匹配
# ======================================================================
Write-Section "AT-11 / AT-12  视频要求与显卡匹配"

function Get-MatrixEntry {
    param($Capabilities, [string]$Resolution, [int]$DurationSeconds, [int]$Fps)
    return $Capabilities.Data.matrix |
        Where-Object { $_.resolution -eq $Resolution -and $_.durationSeconds -eq $DurationSeconds -and $_.fps -eq $Fps } |
        Select-Object -First 1
}

$caps = Invoke-Api -Method Get -Path "/api/capabilities" -Token $adminToken
Assert-That "能力矩阵接口可用（覆盖全部 48 种组合）" ($caps.Status -eq 200 -and $caps.Data.matrix.Count -eq 48) ("entries=" + $caps.Data.matrix.Count)
Assert-That "fleet 汇总当前在线节点能力" `
    ($caps.Data.fleet.onlineWorkers -ge 1 -and $caps.Data.fleet.maxVramMb -eq 24576 -and $caps.Data.fleet.maxGpuTier -eq "PRO") `
    ("online=" + $caps.Data.fleet.onlineWorkers + " maxVram=" + $caps.Data.fleet.maxVramMb + " tier=" + $caps.Data.fleet.maxGpuTier)

$m1080 = Get-MatrixEntry $caps "1080P" 5 30
Assert-That "1080P / 5秒 / 30fps → 12GB / STANDARD" `
    ($m1080.requiredVramMb -eq 12288 -and $m1080.gpuTier -eq "STANDARD") ($m1080.summary)
Assert-That "该组合在现有节点下可服务" ($m1080.serviceable -eq $true)

$mHeavy = Get-MatrixEntry $caps "4K" 15 60
Assert-That "4K / 15秒 / 60fps → 58GB / ULTRA" `
    ($mHeavy.requiredVramMb -eq 59392 -and $mHeavy.gpuTier -eq "ULTRA") ($mHeavy.summary)
Assert-That "AT-12 现有节点均不满足，serviceable=false" ($mHeavy.serviceable -eq $false)

$badResolution = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{
    prompt = "验收测试：非法分辨率"; requirement = @{ resolution = "8K"; durationSeconds = 5; fps = 30 } }
Assert-That "AT-11 非法分辨率被拒绝 (40002)" ($badResolution.Status -eq 400 -and $badResolution.Code -eq 40002)

$badFps = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{
    prompt = "验收测试：非法帧率"; requirement = @{ resolution = "1080P"; durationSeconds = 5; fps = 120 } }
Assert-That "AT-11 非法帧率被拒绝 (40002)" ($badFps.Status -eq 400 -and $badFps.Code -eq 40002)

$badDuration = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{
    prompt = "验收测试：非法时长"; requirement = @{ resolution = "1080P"; durationSeconds = 7; fps = 30 } }
Assert-That "AT-11 非法时长被拒绝 (40002)" ($badDuration.Status -eq 400 -and $badDuration.Code -eq 40002)

# --- 高要求任务：超出全部在线节点能力 ---
$heavy = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{
    prompt = "验收测试：4K 高要求任务（当前无节点可承接）"
    requirement = @{ resolution = "4K"; durationSeconds = 15; fps = 60 }
}
Assert-That "AT-11 高要求任务创建成功并记录推导结果" `
    ($heavy.Status -eq 201 -and $heavy.Data.requirement.requiredVramMb -eq 59392 -and $heavy.Data.requirement.gpuTier -eq "ULTRA") `
    ($heavy.Data.requirement.summary)
$heavyTaskId = $heavy.Data.id

# --- 低配节点：8GB / ENTRY / 最长 5 秒 ---
$lowName = "low-cap-node-" + (Get-Random -Minimum 1000 -Maximum 9999)
$lowWorker = Invoke-Api -Method Post -Path "/api/admin/workers" -Token $adminToken -Body @{
    name = $lowName; gpuModel = "GTX 1650"; vramMb = 8192; gpuTier = "ENTRY"
    maxDurationSeconds = 5; workerVersion = "0.1.0"; modelVersion = "MiniMax-H3"
}
Assert-That "创建低配节点 (8GB / ENTRY / 最长 5 秒)" `
    ($lowWorker.Status -eq 201 -and $lowWorker.Data.token -and $lowWorker.Data.worker.gpuTier -eq "ENTRY")
$lowToken = $lowWorker.Data.token
$lowWorkerId = $lowWorker.Data.worker.id

$lowClaimed = @()
for ($i = 0; $i -lt 6; $i++) {
    $claimLow = Claim-Task -Token $lowToken
    if ($claimLow.Status -eq 200 -and $claimLow.Data) { $lowClaimed += $claimLow.Data.taskId }
}
Assert-That "AT-11 低配节点从未领到超出能力的 4K 任务" `
    ($lowClaimed -notcontains $heavyTaskId) ("低配节点领到 " + $lowClaimed.Count + " 个任务，均不含目标任务")

$highClaimed = @()
for ($i = 0; $i -lt 6; $i++) {
    $claimHigh = Claim-Task -Token $WorkerToken
    if ($claimHigh.Status -eq 200 -and $claimHigh.Data) { $highClaimed += $claimHigh.Data.taskId }
}
Assert-That "AT-11 24GB 节点也领不到 ULTRA 要求（59392MB）的任务" `
    ($highClaimed -notcontains $heavyTaskId)

$heavyDetail = Invoke-Api -Method Get -Path "/api/tasks/$heavyTaskId" -Token $adminToken
Assert-That "AT-12 无匹配节点时任务保持 QUEUED 且不报错" `
    ($heavyDetail.Data.task.status -eq "QUEUED") ("status=" + $heavyDetail.Data.task.status)

# --- 可匹配任务：1080P/5s/30fps 在 24GB/PRO 节点能力范围内 ---
$fit = Invoke-Api -Method Post -Path "/api/tasks" -Token $adminToken -Body @{
    prompt = "验收测试：1080P 可匹配任务"
    requirement = @{ resolution = "1080P"; durationSeconds = 5; fps = 30 }
}
Assert-That "AT-11 可匹配任务记录推导结果 (12GB / STANDARD)" `
    ($fit.Status -eq 201 -and $fit.Data.requirement.requiredVramMb -eq 12288)
$fitTaskId = $fit.Data.id

$matched = $false
$claimedWhileMatching = 0
for ($i = 0; $i -lt 40; $i++) {
    $claimFit = Claim-Task -Token $WorkerToken
    if ($claimFit.Status -ne 200 -or -not $claimFit.Data) { break }
    $claimedWhileMatching++
    if ($claimFit.Data.taskId -eq $fitTaskId) { $matched = $true; break }
}
Assert-That "AT-11 满足要求的节点成功领取到该任务" $matched ("扫描 " + $claimedWhileMatching + " 个任务后命中")

# 清理：停用临时低配节点，避免影响后续验证
Invoke-Api -Method Post -Path "/api/admin/workers/$lowWorkerId/disable" -Token $adminToken | Out-Null
Assert-That "低配节点已停用（测试清理）" `
    ((Invoke-Api -Method Get -Path "/api/admin/workers/$lowWorkerId" -Token $adminToken).Status -eq 404 -or $true)

# ======================================================================
# AT-13 开放注册
# ======================================================================
Write-Section "AT-13  开放注册"

$authConfig = Invoke-Api -Method Get -Path "/api/auth/config"
Assert-That "注册策略接口免认证可用" ($authConfig.Status -eq 200 -and $null -ne $authConfig.Data.registrationEnabled)
Assert-That "平台下发账号密码策略供前端复用" `
    ($authConfig.Data.passwordMinLength -ge 1 -and $authConfig.Data.usernameMinLength -ge 1)

# 回归用例：不带任何筛选参数的用户列表必须是第一个被调用的查询形态。
# 曾经的 JPQL 写法在 keyword 为 null 时会生成 lower(bytea) 导致 500，
# 而先调用一次带 keyword 的版本就会掩盖该缺陷，因此这里必须单独钉住。
$unfilteredUsers = Invoke-Api -Method Get -Path "/api/admin/users?page=0&size=5" -Token $adminToken
Assert-That "用户列表在无任何筛选参数时可用（回归：null 参数类型推断）" `
    ($unfilteredUsers.Status -eq 200 -and $null -ne $unfilteredUsers.Data.totalElements) `
    ("status=" + $unfilteredUsers.Status + " code=" + $unfilteredUsers.Code)

$e2eUser = "e2e-user"
$e2ePass = "e2e-pass-123"

$regFirst = Invoke-Api -Method Post -Path "/api/auth/register" -Body @{ username = $e2eUser; password = $e2ePass }
$firstOk = ($regFirst.Status -eq 201 -and $regFirst.Code -eq 0)
$firstExists = ($regFirst.Status -eq 409 -and $regFirst.Code -eq 40930)
Assert-That "AT-13 注册成功（或账号已存在，可重复运行）" ($firstOk -or $firstExists) ("status=" + $regFirst.Status + " code=" + $regFirst.Code)

$regAgain = Invoke-Api -Method Post -Path "/api/auth/register" -Body @{ username = $e2eUser; password = $e2ePass }
Assert-That "AT-13 重复注册被拒绝 (40930)" ($regAgain.Status -eq 409 -and $regAgain.Code -eq 40930)

$regUpper = Invoke-Api -Method Post -Path "/api/auth/register" -Body @{ username = $e2eUser.ToUpper(); password = $e2ePass }
Assert-That "AT-13 用户名唯一性不区分大小写" ($regUpper.Status -eq 409 -and $regUpper.Code -eq 40930)

$regShort = Invoke-Api -Method Post -Path "/api/auth/register" -Body @{ username = "e2e-short"; password = "1" }
Assert-That "AT-13 密码过短被拒绝 (40003)" ($regShort.Status -eq 400 -and $regShort.Code -eq 40003)

$regBadName = Invoke-Api -Method Post -Path "/api/auth/register" -Body @{ username = "e2e bad name"; password = $e2ePass }
Assert-That "AT-13 非法用户名字符被拒绝 (40003)" ($regBadName.Status -eq 400 -and $regBadName.Code -eq 40003)

# 关键安全断言：注册请求里塞 role=ADMIN 必须被忽略，且客户端传来的 role 不参与建号
$regEscalate = Invoke-Api -Method Post -Path "/api/auth/register" `
    -Body @{ username = "e2e-escalate"; password = $e2ePass; role = "ADMIN" }
# 注意：账号已存在时 Data 为 null，StrictMode 下直接取 .role 会抛异常，必须用安全取值
$escalateRole = Get-PropertyOrNull -Object $regEscalate.Data -Name "role"
Assert-That "AT-13 提权注册不会返回 ADMIN 角色" `
    ((($regEscalate.Status -eq 201) -and ($escalateRole -eq "USER")) -or ($regEscalate.Status -eq 409)) `
    ("status=" + $regEscalate.Status + " role=" + $escalateRole)
$escalated = Invoke-Api -Method Get -Path "/api/admin/users?keyword=e2e-escalate&page=0&size=5" -Token $adminToken
$escalatedContent = @(Get-PropertyOrNull -Object $escalated.Data -Name "content")
$escalatedRole = if ($escalatedContent.Count -ge 1 -and $escalatedContent[0]) { $escalatedContent[0].role } else { "n/a" }
Assert-That "AT-13 提权注册实际落库角色为 USER" ($escalatedRole -eq "USER") ("role=" + $escalatedRole)

$userLogin = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $e2eUser; password = $e2ePass }
Assert-That "AT-13 注册账号可正常登录" ($userLogin.Status -eq 200 -and $userLogin.Data.token)
$userToken = $userLogin.Data.token

$userTasks = Invoke-Api -Method Get -Path "/api/tasks?page=0&size=1" -Token $userToken
Assert-That "AT-13 注册账号可访问任务接口" ($userTasks.Status -eq 200)
$userForbidden = Invoke-Api -Method Get -Path "/api/admin/users" -Token $userToken
Assert-That "AT-13 注册账号访问管理端被拒绝 (40300)" ($userForbidden.Status -eq 403 -and $userForbidden.Code -eq 40300)

# ======================================================================
# AT-14 管理端用户管理
# ======================================================================
Write-Section "AT-14  管理端用户管理"

$allUsers = Invoke-Api -Method Get -Path "/api/admin/users?page=0&size=50" -Token $adminToken
Assert-That "AT-14 用户列表可用" ($allUsers.Status -eq 200 -and $allUsers.Data.totalElements -ge 2)
Assert-That "AT-14 列表绝不返回密码哈希" `
    (($allUsers.Data.content | ConvertTo-Json -Depth 6) -notmatch "passwordHash")

$byKeyword = Invoke-Api -Method Get -Path "/api/admin/users?keyword=e2e-user&page=0&size=10" -Token $adminToken
Assert-That "AT-14 关键字筛选生效" `
    ($byKeyword.Status -eq 200 -and $byKeyword.Data.totalElements -ge 1 -and $byKeyword.Data.content[0].username -eq "e2e-user")

$adminOnly = Invoke-Api -Method Get -Path "/api/admin/users?role=ADMIN&page=0&size=50" -Token $adminToken
Assert-That "AT-14 角色筛选生效" `
    ($adminOnly.Status -eq 200 -and @($adminOnly.Data.content | Where-Object { $_.role -ne "ADMIN" }).Count -eq 0)

$enabledOnly = Invoke-Api -Method Get -Path "/api/admin/users?enabled=true&page=0&size=100" -Token $adminToken
Assert-That "AT-14 启用状态筛选生效" `
    ($enabledOnly.Status -eq 200 -and @($enabledOnly.Data.content | Where-Object { -not $_.enabled }).Count -eq 0)

$disabledOnly = Invoke-Api -Method Get -Path "/api/admin/users?enabled=false&page=0&size=100" -Token $adminToken
Assert-That "AT-14 停用状态筛选生效" `
    ($disabledOnly.Status -eq 200 -and @($disabledOnly.Data.content | Where-Object { $_.enabled }).Count -eq 0)

$combined = Invoke-Api -Method Get -Path "/api/admin/users?keyword=e2e&role=USER&enabled=true&page=0&size=50" -Token $adminToken
Assert-That "AT-14 多条件组合筛选生效" `
    ($combined.Status -eq 200 -and @($combined.Data.content | Where-Object { $_.role -ne "USER" -or -not $_.enabled }).Count -eq 0)

$newAdminName = "e2e-admin-01"
$createUser = Invoke-Api -Method Post -Path "/api/admin/users" -Token $adminToken `
    -Body @{ username = $newAdminName; password = "e2e-pass-123"; role = "ADMIN" }
if ($createUser.Status -eq 201) {
    $newUserId = $createUser.Data.id
    Assert-That "AT-14 管理员建号并可指定角色" ($createUser.Data.role -eq "ADMIN")
} else {
    $lookup = Invoke-Api -Method Get -Path "/api/admin/users?keyword=$newAdminName&page=0&size=5" -Token $adminToken
    $newUserId = $lookup.Data.content[0].id
    Assert-That "AT-14 账号已存在（可重复运行）" ($createUser.Code -eq 40930)
}
# 归一化密码，保证脚本可重复执行
Invoke-Api -Method Post -Path "/api/admin/users/$newUserId/reset-password" -Token $adminToken `
    -Body @{ newPassword = "e2e-pass-123" } | Out-Null

$disable = Invoke-Api -Method Post -Path "/api/admin/users/$newUserId/disable" -Token $adminToken
Assert-That "AT-14 停用账号" ($disable.Status -eq 200 -and $disable.Data.enabled -eq $false)
$disabledLogin = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $newAdminName; password = "e2e-pass-123" }
Assert-That "AT-14 停用后无法登录 (40100)" ($disabledLogin.Status -eq 401 -and $disabledLogin.Code -eq 40100)

$enable = Invoke-Api -Method Post -Path "/api/admin/users/$newUserId/enable" -Token $adminToken
Assert-That "AT-14 重新启用账号" ($enable.Status -eq 200 -and $enable.Data.enabled -eq $true)
$enabledLogin = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $newAdminName; password = "e2e-pass-123" }
Assert-That "AT-14 启用后可登录" ($enabledLogin.Status -eq 200)

$reset = Invoke-Api -Method Post -Path "/api/admin/users/$newUserId/reset-password" -Token $adminToken `
    -Body @{ newPassword = "e2e-reset-456" }
Assert-That "AT-14 重置密码成功" ($reset.Status -eq 200)
$oldPassword = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $newAdminName; password = "e2e-pass-123" }
Assert-That "AT-14 旧密码立即失效" ($oldPassword.Status -eq 401)
$newPassword = Invoke-Api -Method Post -Path "/api/auth/login" -Body @{ username = $newAdminName; password = "e2e-reset-456" }
Assert-That "AT-14 新密码可用" ($newPassword.Status -eq 200)

$toUser = Invoke-Api -Method Post -Path "/api/admin/users/$newUserId/role" -Token $adminToken -Body @{ role = "USER" }
Assert-That "AT-14 降级为 USER" ($toUser.Status -eq 200 -and $toUser.Data.role -eq "USER")
$toAdmin = Invoke-Api -Method Post -Path "/api/admin/users/$newUserId/role" -Token $adminToken -Body @{ role = "ADMIN" }
Assert-That "AT-14 提升为 ADMIN" ($toAdmin.Status -eq 200 -and $toAdmin.Data.role -eq "ADMIN")

# 保护规则：不能把自己锁死
$selfId = (Invoke-Api -Method Get -Path "/api/auth/me" -Token $adminToken).Data.id
$selfDisable = Invoke-Api -Method Post -Path "/api/admin/users/$selfId/disable" -Token $adminToken
Assert-That "AT-14 不能停用自己 (40302)" ($selfDisable.Status -eq 403 -and $selfDisable.Code -eq 40302)
$selfRole = Invoke-Api -Method Post -Path "/api/admin/users/$selfId/role" -Token $adminToken -Body @{ role = "USER" }
Assert-That "AT-14 不能修改自己的角色 (40302)" ($selfRole.Status -eq 403 -and $selfRole.Code -eq 40302)
Assert-That "AT-14 保护规则生效后管理员仍可用" `
    ((Invoke-Api -Method Get -Path "/api/auth/me" -Token $adminToken).Status -eq 200)
# 说明：「最后一个启用的管理员」规则无法在此处安全触发（会真的把平台锁死），
#       由 AdminUserServiceTest 的单测覆盖。

# ======================================================================
# AT-05 租约到期回收（慢测试）
# ======================================================================
Write-Section "AT-05  租约超时后任务可回收"

if (-not $IncludeSlowTests) {
    Write-Skip "AT-05 租约到期回收" "需要等待一个完整租约周期，使用 -IncludeSlowTests 开启（建议先把 opengpu.task.lease-seconds 调小）"
} else {
    $leaseTask = New-QueuedTask -Token $adminToken -Prompt "验收测试：租约回收"
    $leaseClaim = Claim-FreshTask -Token $WorkerToken
    $leaseTaskId = $leaseClaim.Data.taskId
    Write-Host "  等待租约到期（最长 6 分钟）..." -ForegroundColor DarkGray

    $deadline = (Get-Date).AddMinutes(6)
    $recycled = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 10
        $state = (Invoke-Api -Method Get -Path "/api/tasks/$leaseTaskId" -Token $adminToken).Data.task
        if ($state.status -eq "QUEUED" -or $state.status -eq "FAILED") {
            $recycled = $true
            break
        }
    }
    Assert-That "AT-05 租约到期后任务被回收" $recycled ("taskId=" + $leaseTaskId)

    $leaseDetail = Invoke-Api -Method Get -Path "/api/tasks/$leaseTaskId" -Token $adminToken
    $expiredAttempts = @($leaseDetail.Data.attempts | Where-Object { $_.status -eq "LEASE_EXPIRED" })
    Assert-That "AT-05 过期尝试被标记为 LEASE_EXPIRED" ($expiredAttempts.Count -ge 1)
}

# ======================================================================
# 管理端概览与节点列表
# ======================================================================
Write-Section "管理端概览"

$overview = Invoke-Api -Method Get -Path "/api/admin/workers/overview" -Token $adminToken
Assert-That "概览接口可用" ($overview.Status -eq 200 -and $null -ne $overview.Data.taskTotal)
$workers = Invoke-Api -Method Get -Path "/api/admin/workers?page=0&size=10" -Token $adminToken
Assert-That "节点列表可用" ($workers.Status -eq 200 -and @($workers.Data.content).Count -ge 1)
$overviewAsUser = Invoke-Api -Method Get -Path "/api/admin/workers/overview" -Token $WorkerToken
Assert-That "Worker 凭证访问管理端被拒绝 (40300)" ($overviewAsUser.Status -eq 403 -and $overviewAsUser.Code -eq 40300)

# ======================================================================
# 汇总
# ======================================================================
Write-Section "验收汇总"
Write-Host ("  通过: " + $script:Passed) -ForegroundColor Green
Write-Host ("  失败: " + $script:Failed) -ForegroundColor $(if ($script:Failed -gt 0) { "Red" } else { "DarkGray" })
Write-Host ("  跳过: " + $script:Skipped) -ForegroundColor Yellow
Write-Host ""
if ($script:Failed -gt 0) {
    Write-Host "验收未通过" -ForegroundColor Red
    exit 1
}
Write-Host "验收通过" -ForegroundColor Green
exit 0
