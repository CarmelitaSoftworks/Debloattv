@echo off
rem Atajo para ejecutar debloat.ps1 sin cambiar la política de ejecución de PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0debloat.ps1" %*
