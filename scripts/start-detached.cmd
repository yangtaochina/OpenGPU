@echo off
REM ===========================================================================
REM  脱离当前终端启动开发环境（detached start）
REM
REM  适用场景：从远程 shell / CI / 自动化脚本里启动，
REM            需要各组件在调用方退出、终端关闭之后继续存活。
REM
REM  普通本地开发请直接双击项目根目录的 start.bat，不需要这个文件。
REM
REM  原理：通过 Windows 任务计划程序启动本脚本，进程归属于
REM        Task Scheduler 服务，不属于调用方的进程树/作业对象。
REM ===========================================================================
setlocal
cd /d "%~dp0.."
if not exist "logs" mkdir "logs"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\start-all.ps1" >> "logs\start-detached.log" 2>&1
endlocal
