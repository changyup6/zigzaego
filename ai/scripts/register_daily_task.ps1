# 이번 주 매출 예측(상권 유동인구, S15P31A308-226)을 로컬 PC 에서 매일 06:00 자동 실행하도록 Windows 작업 스케줄러에 등록한다.
# 하는 일: 수집 -> 데이터 만들기 -> 저장 모델로 예측 -> DB sales_forecast_week 적재 (python -m weglee_ai.seoulevent.daily).
# 설정(적재할 곳 SALES_FORECAST_TARGETS · 수집 키 · GMS · SEOULEVENT_DATA_DIR)은 ai/.env 에서 읽는다. 로그는 <SEOULEVENT_DATA_DIR>/logs/daily_YYYYMMDD.log
# 등록:  powershell -ExecutionPolicy Bypass -File ai\scripts\register_daily_task.ps1
# 확인:  Get-ScheduledTaskInfo -TaskName weglee-sales-forecast-daily
# 지금 한 번:  Start-ScheduledTask -TaskName weglee-sales-forecast-daily
# 해제:  Unregister-ScheduledTask -TaskName weglee-sales-forecast-daily -Confirm:$false

$ai = Split-Path -Parent $PSScriptRoot
$uv = (Get-Command uv -ErrorAction Stop).Source
$envFile = Join-Path $ai ".env"
$dataDir = (Get-Content $envFile -ErrorAction Stop | Where-Object { $_ -match '^\s*SEOULEVENT_DATA_DIR\s*=' } |
    ForEach-Object { ($_ -split '=', 2)[1].Trim().Trim('"') } | Select-Object -First 1)
if (-not $dataDir) { throw "ai/.env 에 SEOULEVENT_DATA_DIR 이 없다" }
$logs = Join-Path $dataDir "logs"
New-Item -ItemType Directory -Force -Path $logs | Out-Null

# cmd /c 로 감싸 표준 출력 · 오류를 날짜별 로그에 남긴다 (작업 스케줄러는 출력을 버린다)
$run = "`"$uv`" run --directory `"$ai`" --group event python -m weglee_ai.seoulevent.daily"
$log = "`"$logs\daily_%date:~0,4%%date:~5,2%%date:~8,2%.log`""
$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument "/c set PYTHONIOENCODING=utf-8&& $run >> $log 2>&1" -WorkingDirectory $ai
$trigger = New-ScheduledTaskTrigger -Daily -At 6am
# StartWhenAvailable: PC 가 꺼져 놓친 실행은 켜지면 바로 1회 실행
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 90)

Register-ScheduledTask -TaskName "weglee-sales-forecast-daily" -Action $action -Trigger $trigger -Settings $settings `
    -Description "위글리 이번 주 매출 예측 - 로컬 수집 · 예측 · DB 적재 (S15P31A308-226)" -Force | Out-Null

Write-Host "등록됨: weglee-sales-forecast-daily (매일 06:00), 로그: $logs"
