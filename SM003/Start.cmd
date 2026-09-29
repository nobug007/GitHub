@echo off
cd /d "%~dp0"
if not exist "bin\Release\net8.0-windows\ShineMung.SM003.exe" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
    if errorlevel 1 exit /b 1
)
start "" "%~dp0bin\Release\net8.0-windows\ShineMung.SM003.exe"
