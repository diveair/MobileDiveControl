@echo off
setlocal
if exist "C:\Python314\pythonw.exe" (
    start "" "C:\Python314\pythonw.exe" "%~dp0tools\depth_lab\desktop.py"
) else (
    start "" pyw -3 "%~dp0tools\depth_lab\desktop.py"
)
