@echo off
powershell -NoProfile -ExecutionPolicy Bypass -Command "$pids = Get-NetTCPConnection -LocalPort 5000 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; foreach ($processId in $pids) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }"
python app.py
pause
