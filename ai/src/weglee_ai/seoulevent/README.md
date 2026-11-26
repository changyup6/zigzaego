# 상권 유동인구 예측 (seoulevent) — 로컬 실행 코드

> **이 폴더는 로컬 PC 에서 실행되는 파일입니다.** 서버(CronJob · ai-batch 이미지)에서 돌지 않습니다.
> 로컬 PC 가 매일 예측표(CSV)를 만들어 DB 표 `sales_forecast_week` 에 넣어 두면, 백엔드 `GET /sales-forecast/this-week` 가 그 표를 읽어 홈 · 발주 탭의 "이번 주 매출 예측" 카드에 보여 줍니다. (S15P31A308-226 · 267, [ADR-0017](../../../../docs/adr/ADR-0017-district-flow-forecast.md))

```
 로컬 PC (매일 06:00, Windows 작업 스케줄러)
   수집 -> 데이터 만들기 -> 저장 모델로 예측 -> forecast.csv -> weglee_ai.sales_forecast -> DB sales_forecast_week
 서버
   BE GET /sales-forecast/this-week (읽기만) -> 화면
```

- 모델: **v3** (서울 상권 지하철 급증 7일 앞 예측, XGBoost 허들 + 보정). 원본 개발 · 실험 기록은 foodspring-crawler 저장소.
- 가게 -> 상권: 가게 좌표(`store.latitude` · `longitude`)가 있으면 반경 1km 상권, 없으면 `store.sigungu` 의 구. 서울 밖 가게는 줄을 만들지 않는다.
- 설명 문장: 모델 근거(SHAP) · 행사 · 숫자만 GMS 에 넘겨 만든다. 실패하면 `analysis` 는 비고 숫자는 그대로 저장된다.

## 준비 (최초 1회)

```bash
cd ai
uv sync --group event     # 수집 · 모델(xgboost · pyproj ...)
```

`ai/.env` (값은 커밋하지 않는다):

| 변수 | 용도 |
|---|---|
| `SEOULEVENT_DATA_DIR` | 데이터 폴더 (`raw/` 원천 · `out/` 예측표 · `models/` 저장 모델 · `logs/`). 약 1.2GB |
| `SEOUL_API_KEY` · `DATA_GO_KR_KEY` · `KOPIS_API_KEY` | 일정 · 승하차 수집 |
| `SALES_FORECAST_TARGETS` | 적재할 곳 이름 목록 (예: `STAGING,MAIN`) |
| `<이름>_SSH` · `<이름>_PEM` · `<이름>_NAMESPACE` | 곳마다 서버(`ubuntu@staging.example.com`) · ssh 키 파일 · k8s 네임스페이스(`staging`). 선택: `<이름>_PG_POD`(기본 `postgres-1`) · `<이름>_DB`(기본 `zigzaego`) |
| `GMS_KEY` · `GMS_BASE_URL` · `GMS_MODEL` | 설명 문장 (선택). `GMS_BASE_URL` 은 `.../v1` 까지만 — 끝에 `/responses` 를 붙이지 않는다 |
| `SEOULEVENT_MODEL` | 쓸 저장 모델 폴더 이름 (기본 `model-v3`) |

DB 에는 ssh → `kubectl exec <DB 파드> -- psql` 로 넣는다(DB 비밀번호 · 터널 없음, `scripts/demo_attach.py` 와 같은 경로). 넣을 DB 에는 마이그레이션 V20(`sales_forecast_week`) · V21(`store.latitude` · `longitude`)이 들어가 있어야 한다.

```
SALES_FORECAST_TARGETS=STAGING,MAIN
STAGING_SSH=ubuntu@staging.example.com
STAGING_PEM=~/keys/staging.pem
STAGING_NAMESPACE=staging
MAIN_SSH=...   MAIN_PEM=...   MAIN_NAMESPACE=...
```

## 자동 실행 (매일 06:00)

```powershell
powershell -ExecutionPolicy Bypass -File ai\scripts\register_daily_task.ps1   # 등록
Start-ScheduledTask -TaskName weglee-sales-forecast-daily                       # 지금 한 번 돌려 보기
Get-ScheduledTaskInfo -TaskName weglee-sales-forecast-daily                     # 마지막 실행 결과(LastTaskResult 0 = 성공)
```

- 로그: `<SEOULEVENT_DATA_DIR>/logs/daily_YYYYMMDD.log`
- PC 가 꺼져 놓친 실행은 켜지면 바로 1번 돈다. PC 가 계속 꺼져 있으면 카드는 마지막 적재 값을 보여 준다.

## 손으로 실행

```bash
uv run --group event python -m weglee_ai.seoulevent.daily      # 수집 -> 데이터 만들기 -> 예측(model-v3) -> DB 적재
```

단계별로 돌릴 때:

```bash
uv run --group event python -m weglee_ai.seoulevent.build                                            # raw -> out
uv run --group event python -m weglee_ai.seoulevent.model.forecast --model $SEOULEVENT_DATA_DIR/models/model-v3   # -> out/forecast/forecast.csv (오늘부터 7일)
uv run python -m weglee_ai.sales_forecast $SEOULEVENT_DATA_DIR/out/forecast/forecast.csv                    # -> DB
```

- 다시 돌려도 안전하다 — 같은 (가게, 주)는 덮어쓴다. 숫자가 그대로면 지난 설명 문장을 다시 쓴다(LLM 을 다시 부르지 않음).
- 실측(로컬 CPU): 수집 16분 · 데이터 만들기 8분 · 예측 6 ~ 18분, 메모리 최대 약 9GB(데이터 만들기). 서버 노드(4GB)에 맞지 않아 로컬에서 돌린다.

## 모듈 지도

이 폴더는 foodspring-crawler 원본을 그대로 옮긴 것이라 매일 실행에 쓰지 않는 모듈도 들어 있다. 고치지 않고 그대로 둔다(아래 "원본 옮기기").

| 모듈 | 하는 일 | 매일 실행 |
|---|---|---|
| `daily.py` | 수집 → 데이터 만들기 → 저장 모델로 예측 → DB 적재 (위글리 전용) | 진입점 |
| `collect_subway` · `collect_stadium` · `collect_hangang` · `collect_kopis` · `collect_kopis_rank` · `collect_kleague` · `collect_kbo` · `collect_events` · `collect_std_events` · `collect_kfa` · `collect_marathon` | 원천 수집: 지하철 승하차 · 경기장 · 한강 · 공연 · 예매 순위 · K리그 · KBO · 문화행사 · 축제 표준데이터 · 국가대표 · 마라톤 | 씀 |
| `build/` | 원천 → 상권 × 날짜 표(`district_day`) · 행사 표(`events`) · 급증 표(`spikes`) | 씀 |
| `model/forecast.py` · `model/features.py` · `model/experiment.py` · `model/history_effects.py` · `model/train.py` | 특징 만들기 · 저장 모델로 예측 · 모델 근거(SHAP) | 씀 |
| `config.py` · `ref/` · `_http.py` · `seoul_api.py` · `seoul_file.py` · `datagokr.py` · `holidays_kr.py` | 설정 · 참조표(역 좌표 · 도시 행사일 · 개화일) · API 호출 공통 | 씀 |
| `weekly.py` · `collect_news` · `collect_bus` | 주간 수집 + 스냅샷 (데이터 쌓기, 모델 입력 아님) | 주 1회 |
| `model/score.py` | 지난 예측 채점 (실제 승하차가 나온 뒤) | 가끔 |
| `model/publish.py` | 7일 · 3일 앞 예측을 합치고 일주일 설명(reason.json)을 만든다 (`weekly.py` 가 예측표를 만든 뒤 실행; 매일 `daily.py` 의 가게별 설명은 `sales_forecast` 담당) | 주 1회 |
| `collect_district` · `collect_holidays` | 상권 영역 · 공휴일 (처음 한 번, 해마다) | 1회성 |
| `collect_citydata` · `collect_rtd` · `collect_weather` · `collect_weather_fc` · `collect_subway_hourly` · `build/district_quarter` · `build/place_link` · `build/candidates` · `model/event_effects` · `model/quarter_link` · `check.py` · `places.py` | 실험 · 분석용 (원본 개발 기록) | 안 씀 |

## 원본 옮기기 (모델을 갱신할 때)

원본은 foodspring-crawler 저장소다. 이 폴더를 직접 고치지 말고, 원본을 고친 뒤 옮긴다.

```bash
uv run python scripts/sync_seoulevent.py <crawler 폴더>   # 복사 + import · ref 경로 바꾸기, daily.py · README.md 는 그대로
uv run --group event pytest tests/seoulevent tests/test_sales_forecast.py
```

## 모델 다시 학습 (월 1회 또는 모델 교체 때)

```bash
uv run --group event python -m weglee_ai.seoulevent.model.forecast --save model-v4   # -> models/model-v4/model.joblib + meta.json
```

그다음 `ai/.env` 의 `SEOULEVENT_MODEL=model-v4`. 예측표 열(날짜 · 상권 · 구 · 평소 인원 · 예상 인원 · 행사 · 모델 · 모델 근거 · 위도 · 경도 · 좌석)만 지키면 적재 · 백엔드 · 화면은 바꿀 필요가 없다. 저장 모델의 특징 목록이 코드와 다르면 예측이 거부된다.

## 테스트

```bash
uv run --group event pytest tests/seoulevent tests/test_sales_forecast.py
```

`tests/seoulevent` 는 `event` 그룹이 없으면 건너뛴다(CI 기본 설치).
