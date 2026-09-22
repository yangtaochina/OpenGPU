@echo off
setlocal
cd /d "%~dp0"
title OpenGPU - Stop

powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\stop-all.ps1" %*

echo.
if not defined OPENGPU_NO_PAUSE pause
exit /b 0
