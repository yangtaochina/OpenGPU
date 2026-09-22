@echo off
rem ---------------------------------------------------------------------------
rem OpenGPU GPU Worker launcher (Windows).
rem
rem Usage:
rem   run.bat                 -> start the worker with the current environment
rem   run.bat --help          -> extra args are forwarded to worker.py
rem
rem If a virtual environment exists at worker\.venv it is used automatically.
rem ---------------------------------------------------------------------------
setlocal
cd /d "%~dp0"

set "PY=python"
if exist ".venv\Scripts\python.exe" set "PY=.venv\Scripts\python.exe"

"%PY%" worker.py %*
exit /b %ERRORLEVEL%
