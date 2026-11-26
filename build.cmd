@echo off
rem Build the TWPI add-on. Wrapper around build.ps1 for the case when PowerShell
rem script execution is blocked by ExecutionPolicy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
