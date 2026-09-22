<#
  智由 AI 视频任务平台 — 停止开发环境

  用法：
    powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1                 # 只停应用进程
    powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -WithContainers # 同时停掉 Docker 依赖
    powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -WithContainers -CleanData  # 连数据卷一起删除

  说明：
    - 优先按 logs\pids.json 记录的 PID 停止；记录缺失时按命令行特征兜底匹配
    - 只匹配本项目的进程，不会影响其它 java / node / python 程序
#>
param(
    [switch]$WithContainers,
    [switch]$CleanData
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root "logs"
$pidFile = Join-Path $logDir "pids.json"

function Stop-ByPattern {
    param([string]$Title, [string]$ProcessName, [string]$Pattern)
    $targets = Get-CimInstance Win32_Process -Filter "Name='$ProcessName'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like $Pattern }
    if (-not $targets) {
        Write-Host ("    " + $Title + ": 未在运行") -ForegroundColor DarkGray
        return
    }
    foreach ($target in $targets) {
        Stop-Process -Id $target.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host ("    " + $Title + ": 已停止 PID " + $target.ProcessId) -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "=== 停止应用进程" -ForegroundColor Cyan

# 1) 按 pids.json
if (Test-Path $pidFile) {
    try {
        $pids = Get-Content $pidFile -Raw | ConvertFrom-Json
        foreach ($property in $pids.PSObject.Properties) {
            $process = Get-Process -Id $property.Value -ErrorAction SilentlyContinue
            if ($process) {
                Stop-Process -Id $property.Value -Force -ErrorAction SilentlyContinue
                Write-Host ("    " + $property.Name + ": 已停止 PID " + $property.Value) -ForegroundColor Green
            }
        }
    } catch {
        Write-Host "    pids.json 解析失败，改用命令行匹配" -ForegroundColor Yellow
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

# 2) 兜底：按命令行特征匹配（覆盖 pids.json 缺失或 npm 包装进程的情况）
Stop-ByPattern -Title "后端 API"   -ProcessName "java.exe"   -Pattern "*opengpu-platform-api*"
Stop-ByPattern -Title "前端 Vite"  -ProcessName "node.exe"   -Pattern "*OpenGPU*web*"
Stop-ByPattern -Title "GPU Worker" -ProcessName "python.exe" -Pattern "*worker.py*"

if ($WithContainers) {
    Write-Host ""
    Write-Host "=== 停止 Docker 依赖组件" -ForegroundColor Cyan
    $compose = Join-Path $root "deploy\docker-compose.yml"
    if ($CleanData) {
        docker compose -f $compose down -v
        Write-Host "    已停止并删除数据卷（下次启动会重建空库）" -ForegroundColor Green
    } else {
        docker compose -f $compose down
        Write-Host "    已停止容器（数据卷保留）" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "完成。" -ForegroundColor Green
Write-Host ""
