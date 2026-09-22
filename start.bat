@echo off
setlocal
cd /d "%~dp0"
title OpenGPU - Start

powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\start-all.ps1" %*
if errorlevel 1 goto failed

if not defined OPENGPU_NO_BROWSER start "" "http://localhost:5173"

echo.
echo All components started. See the summary above for URLs.
echo.
if not defined OPENGPU_NO_PAUSE pause
exit /b 0

:failed
echo.
echo [ERROR] Startup failed. Read the output above for details.
echo         Logs: logs\api.log  logs\web.log  logs\worker.log
echo.
if not defined OPENGPU_NO_PAUSE pause
exit /b 1
