<#
  智由 AI 视频任务平台 — 一键启动（开发环境，无 GPU 也能跑）

  启动顺序：
    1. Docker 依赖组件（postgres + redis）
    2. 后端 API（Spring Boot，8080）
    3. 前端（Vite，5173）
    4. GPU Worker（Mock 引擎，无需 GPU / 无需 MiniMax H3）

  用法：
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -SkipFrontend
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -Rebuild      # 强制重新打包后端

  说明：
    - 各组件后台运行，日志统一写到 logs\ 目录，PID 记录在 logs\pids.json
    - 停止请用 scripts\stop-all.ps1
    - Mock 引擎产出的视频是否可播放取决于本机是否有 ffmpeg；没有会打印 WARNING
#>
param(
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipWorker,
    [switch]$Rebuild,
    [string]$WorkerToken = "wkr_00000000-0000-0000-0000-000000000001_devsecret",
    [int]$DockerWaitSeconds = 180,
    [int]$ApiWaitSeconds = 120
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$pidFile = Join-Path $logDir "pids.json"

function Write-Step {
    param([string]$Text)
    Write-Host ""
    Write-Host ("=== " + $Text) -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Text)
    Write-Host ("    [OK] " + $Text) -ForegroundColor Green
}

function Write-Warn {
    param([string]$Text)
    Write-Host ("    [!!] " + $Text) -ForegroundColor Yellow
}

function Test-PortListening {
    param([int]$Port)
    # 不用 TcpClient 探测：PowerShell 5.1 的 TcpClient 默认是 IPv4 套接字，
    # 而 Vite 只绑定 ::1，会误判为未启动。直接查系统监听表更可靠。
    $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return ($null -ne $connections)
}

function Wait-PortListening {
    param([int]$Port, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -Port $Port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Wait-Http {
    param([string]$Url, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-Background {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory,
        [string]$LogFile
    )
    $errFile = $LogFile -replace '\.log$', '.err.log'
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $LogFile -RedirectStandardError $errFile
    Write-Ok ("{0} 已启动 (PID {1})，日志: {2}" -f $Name, $process.Id, $LogFile)
    return $process
}

$pids = [ordered]@{}

# ----------------------------------------------------------------------
Write-Step "1/4 启动 Docker 依赖组件 (postgres + redis)"

$dockerReady = $false
try {
    docker info --format '{{.ServerVersion}}' *> $null
    $dockerReady = ($LASTEXITCODE -eq 0)
} catch {
    $dockerReady = $false
}

if (-not $dockerReady) {
    $desktop = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $desktop) {
        Write-Warn "Docker 引擎未就绪，正在启动 Docker Desktop ..."
        Start-Process $desktop | Out-Null
        $deadline = (Get-Date).AddSeconds($DockerWaitSeconds)
        while ((Get-Date) -lt $deadline) {
            try {
                docker info --format '{{.ServerVersion}}' *> $null
                if ($LASTEXITCODE -eq 0) { $dockerReady = $true; break }
            } catch { }
            Start-Sleep -Seconds 5
        }
    }
}

if (-not $dockerReady) {
    Write-Host "    Docker 引擎不可用。请先启动 Docker Desktop 后重试。" -ForegroundColor Red
    exit 1
}
Write-Ok ("Docker 引擎就绪: " + (docker info --format '{{.ServerVersion}}'))

docker compose -f (Join-Path $root "deploy\docker-compose.yml") up -d postgres redis | Out-Null

# 等待 postgres 健康
$pgDeadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $pgDeadline) {
    $health = (docker inspect --format '{{.State.Health.Status}}' opengpu-postgres 2>$null)
    if ($health -eq "healthy") { break }
    Start-Sleep -Seconds 3
}
if ((docker inspect --format '{{.State.Health.Status}}' opengpu-postgres 2>$null) -ne "healthy") {
    Write-Host "    PostgreSQL 未在预期时间内变为 healthy。" -ForegroundColor Red
    exit 1
}
Write-Ok "PostgreSQL 已就绪 (localhost:5432, 库 opengpu)"
Write-Ok "Redis 已就绪 (localhost:6379)"

# ----------------------------------------------------------------------
Write-Step "2/4 启动后端 API (8080)"

$apiDir = Join-Path $root "platform-api"
$jar = Join-Path $apiDir "target\opengpu-platform-api.jar"

if ($Rebuild -or -not (Test-Path $jar)) {
    if ($SkipBuild) {
        Write-Host "    缺少 $jar，且指定了 -SkipBuild。" -ForegroundColor Red
        exit 1
    }
    Write-Warn "正在打包后端（首次或代码变更后需要，约 30 秒）..."
    Push-Location $apiDir
    try {
        mvn -B -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            Write-Host "    后端打包失败，请查看上面的 Maven 输出。" -ForegroundColor Red
            exit 1
        }
    } finally {
        Pop-Location
    }
}
Write-Ok "后端构建产物就绪"

# 若 8080 已被占用，说明后端已在运行
if (Test-PortListening -Port 8080) {
    Write-Warn "8080 端口已被占用，跳过后端启动（可能已在运行）"
} else {
    $apiProcess = Start-Background -Name "后端 API" -FilePath "java" `
        -Arguments @("-Dfile.encoding=UTF-8", "-jar", $jar) `
        -WorkingDirectory $apiDir -LogFile (Join-Path $logDir "api.log")
    $pids["api"] = $apiProcess.Id

    if (-not (Wait-Http -Url "http://localhost:8080/actuator/health" -TimeoutSeconds $ApiWaitSeconds)) {
        Write-Host "    后端在 $ApiWaitSeconds 秒内未就绪，请查看 logs\api.log" -ForegroundColor Red
        exit 1
    }
    Write-Ok "后端 API 已就绪: http://localhost:8080  (接口文档 /swagger-ui.html)"
}

# ----------------------------------------------------------------------
Write-Step "3/4 启动前端 (5173)"

if ($SkipFrontend) {
    Write-Warn "已跳过前端"
} elseif (Test-PortListening -Port 5173) {
    Write-Warn "5173 端口已被占用，跳过前端启动（可能已在运行）"
} else {
    $webDir = Join-Path $root "web"
    if (-not (Test-Path (Join-Path $webDir "node_modules"))) {
        Write-Warn "未找到 node_modules，正在执行 npm install（首次约 1-3 分钟）..."
        Push-Location $webDir
        try {
            npm install
            if ($LASTEXITCODE -ne 0) {
                Write-Host "    npm install 失败。" -ForegroundColor Red
                exit 1
            }
        } finally {
            Pop-Location
        }
    }
    $npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
    if (-not $npm) { $npm = "npm.cmd" }
    $webProcess = Start-Background -Name "前端 Vite" -FilePath $npm `
        -Arguments @("run", "dev") `
        -WorkingDirectory $webDir -LogFile (Join-Path $logDir "web.log")
    $pids["web"] = $webProcess.Id

    if (Wait-PortListening -Port 5173 -TimeoutSeconds 90) {
        Write-Ok "前端已就绪: http://localhost:5173  (admin / admin123)"
    } else {
        Write-Warn "前端 90 秒内未监听 5173，请查看 logs\web.log"
    }
}

# ----------------------------------------------------------------------
Write-Step "4/4 启动 GPU Worker (Mock 引擎，无需 GPU)"

if ($SkipWorker) {
    Write-Warn "已跳过 Worker"
} else {
    $workerDir = Join-Path $root "worker"

    # Worker 没有端口可判断是否已在运行，用进程名匹配避免重复启动
    $existingWorkers = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*worker.py*' })

    if ($existingWorkers.Count -gt 0) {
        Write-Warn ("已检测到 Worker 正在运行 (PID " + (($existingWorkers | ForEach-Object { $_.ProcessId }) -join ", ") + ")，跳过启动")
        $pids["worker"] = $existingWorkers[0].ProcessId
    } else {
        # 子进程继承当前进程环境变量
        $env:OPENGPU_API_BASE = "http://localhost:8080"
        $env:OPENGPU_WORKER_TOKEN = $WorkerToken
        $env:OPENGPU_ENGINE = "mock"
        $env:OPENGPU_MOCK_DURATION_SECONDS = "6"
        # 本机无 GPU，显式声明一组「模拟算力」，让带视频要求的任务也能被领取。
        # 接入真实节点时必须改成 nvidia-smi 实测值（见 worker/.env.example）。
        $env:OPENGPU_VRAM_MB = "24576"
        $env:OPENGPU_GPU_TIER = "PRO"
        $env:OPENGPU_MAX_DURATION_SECONDS = "15"
        $env:OPENGPU_SUPPORTED_RESOLUTIONS = "480P,720P,1080P,4K"
        $env:PYTHONIOENCODING = "utf-8"

        $python = (Get-Command python -ErrorAction SilentlyContinue).Source
        if (-not $python) {
            Write-Host "    未找到 python，跳过 Worker。" -ForegroundColor Red
        } else {
            $workerProcess = Start-Background -Name "GPU Worker (mock)" -FilePath $python `
                -Arguments @("worker.py") `
                -WorkingDirectory $workerDir -LogFile (Join-Path $logDir "worker.log")
            $pids["worker"] = $workerProcess.Id
            Start-Sleep -Seconds 4
            if ($workerProcess.HasExited) {
                Write-Warn "Worker 已退出，请查看 logs\worker.log（常见原因：token 不正确）"
            } else {
                Write-Ok "Worker 已启动，将自动领取任务"
            }
        }
    }
}

# ----------------------------------------------------------------------
# 合并历史 PID：本次因「端口已被占用」而跳过的组件，保留其原有进程记录，
# 否则重复启动会覆盖 pids.json，导致 stop-all 找不到已在运行的进程。
if (Test-Path $pidFile) {
    try {
        $previous = Get-Content $pidFile -Raw | ConvertFrom-Json
        foreach ($property in $previous.PSObject.Properties) {
            if (-not $pids.Contains($property.Name)) {
                if (Get-Process -Id $property.Value -ErrorAction SilentlyContinue) {
                    $pids[$property.Name] = $property.Value
                    Write-Ok ("沿用已运行组件记录: {0} (PID {1})" -f $property.Name, $property.Value)
                }
            }
        }
    } catch {
        Write-Warn "旧的 pids.json 无法解析，已忽略"
    }
}

$pids | ConvertTo-Json | Set-Content -Path $pidFile -Encoding UTF8

Write-Step "启动完成"
Write-Host "    前端页面   http://localhost:5173        (admin / admin123 或 user / user123)"
Write-Host "    接口文档   http://localhost:8080/swagger-ui.html"
Write-Host "    健康检查   http://localhost:8080/actuator/health"
Write-Host "    日志目录   $logDir"
Write-Host "    停止服务   powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1"
Write-Host ""
Write-Host "    实时查看后端日志:  Get-Content logs\api.log -Wait" -ForegroundColor DarkGray
Write-Host ""
