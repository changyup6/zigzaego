# ai — 프롬프트 · 합성 데이터 · 예측 실험

서비스 코드가 아니라 서비스가 쓰는 재료를 만들고 재는 곳입니다.

- **LLM 프롬프트 개발 · 평가**: `prompts/`(온보딩 메뉴 구성 · 재료 분류, 영수증 정형화 · 매칭, 식자재몰 품목군 라벨)와 채점 코드 `src/weglee_ai/onboarding/` · `receipt/` · `ocr/` · `market/`. 백엔드는 이 중 메뉴 구성 · 영수증 정형화 · 품목군 라벨 프롬프트의 복사본을 씁니다(`backend/src/main/resources/`)
- **합성 데이터 · 시드**: 가상 가게의 판매 · 입고 · 레시피를 만드는 `sample_data.py` · `store_templates.py` 와 DB 에 넣는 `seed_sql.py` · `scripts/demo_attach.py`, 범용 재료표 `seed/`
- **예측 실험**: 요일 평균 기준식 `baseline.py` · 채점 `metrics.py` · TimesFM 검증 기록 `timesfm_eval/` · `synthetic_backtest.py` · `batch/`

서비스 예측에 TimesFM 은 쓰지 않습니다([DECISIONS #91](../docs/DECISIONS.md)). 발주 추천의 수요 예측은 최근 4주 같은 요일 평균(휴무일 제외)이고 백엔드가 계산합니다. 매출 예측 카드(#72)의 숫자는 상권 유동인구 예측 모델(`seoulevent` · `sales_forecast`, 로컬 실행)이 만듭니다([ADR-0017](../docs/adr/ADR-0017-district-flow-forecast.md)). 아래 TimesFM 야간 배치 · 채택 검증 절은 실험 기록이고, 스테이징에서 아직 도는 배치는 S15P31A308-312 에서 내립니다.

**시연 · 스테이징의 판매 이력은 합성 데이터입니다.** 백엔드에는 POS 판매를 올리는 API 가 없어, 판매(`pos_sale`)를 넣는 길은 이 폴더가 만든 가상 판매의 seed SQL 뿐입니다(seed 고정, 같은 seed 면 같은 데이터. 시연 자동 연결은 seed 15).

패키지 이름 `weglee_ai` 의 weglee 는 옛 서비스명(위글리)입니다.

## 최초 1회

[uv](https://docs.astral.sh/uv/)가 필요합니다.

```bash
cd ai
uv sync                                     # .venv 생성 + 의존성 설치
uv run python scripts/make_sample_data.py   # 샘플 데이터 → data/sample/*.csv
uv run python -m pytest                     # 테스트
```

- Python 3.11 고정 (`.python-version`)
- uv 없이 pip로: `pip install -r requirements.txt -r requirements-dev.txt && pip install -e .`

## 노트북 실행

위 "최초 1회"를 마친 뒤 둘 중 하나로 엽니다.

- **Jupyter Lab**: `cd ai && uv run jupyter lab` → 브라우저에서 `notebooks/01_baseline_wape.ipynb` → Run All
- **VS Code**: `ai/notebooks/01_baseline_wape.ipynb`를 열고 커널을 `ai/.venv`(Python 3.11)로 고른 뒤 Run All

마지막 셀까지 돌면 전체 WAPE · Bias와 메뉴별 · 요일별 표가 나옵니다. `pos_sales.csv 없음` 오류가 나면 샘플 데이터 생성을 먼저 실행합니다.

### 의존성 추가

`uv add <패키지>` (개발용은 `uv add --dev`) 뒤에 requirements를 다시 뽑아 같은 커밋에 넣습니다. `requirements*.txt`는 손으로 고치지 않습니다.

```bash
uv export --no-hashes --no-dev --no-emit-project -o requirements.txt
uv export --no-hashes --only-group dev --no-emit-project -o requirements-dev.txt
```

## 구조

### 폴더

| 폴더 | 하는 일 |
|---|---|
| `src/weglee_ai/` | 파이썬 패키지 (아래 표) |
| `scripts/` | 손으로 돌리는 도구: 샘플 데이터 · 시드 · 평가 · 시연 연결 · 상권 예측 자동 실행 등록 · 원본 옮기기 |
| `prompts/` | LLM 프롬프트 원본 (온보딩 · 영수증 · 식자재몰). 백엔드가 복사본을 쓰고 바이트 비교 테스트로 지킨다 |
| `seed/` | 시드용 참조표 (범용 재료표 · 품목군 · 보관 기본값) |
| `tests/` | pytest. `tests/seoulevent` 는 `event` 그룹이 있을 때만 돈다 |
| `docs/` | 운영 · 절차 문서 (샘플 데이터 · 시드) |
| `explainers/` | 이 폴더가 무엇이고 어떻게 돌아가는지 쉬운 말 설명 |
| `notebooks/` | 실험 노트북 |
| `data/` · `outputs/` | 커밋하지 않는다 (아래 data 규칙) |

### 패키지 (`src/weglee_ai/`)

| 패키지 · 모듈 | 하는 일 | 쓰임 |
|---|---|---|
| `onboarding/` | 온보딩: 범용 재료표 찾기, 레시피 확인 질문 규칙(백엔드 구현 기준) | 서비스 |
| `receipt/` | 영수증 한 장: 정형화 → 매칭, 폴더 실행 · 이어 하기 | 서비스 |
| `market/` | 식자재몰 상품명 → 품목군 라벨 (LLM 배치) | 서비스 |
| `llm.py` | LLM 호출 공통 (OpenAI 호환, 답에서 JSON 꺼내기) | 서비스 |
| `sales_forecast/` | 상권 예측표 → 가게별 이번 주 매출 예측 → GMS 설명 → DB `sales_forecast_week` 적재 | 서비스 (로컬 실행) |
| `seoulevent/` | 상권 유동인구 예측 v3 (수집 · 데이터 만들기 · 예측). foodspring-crawler 원본을 옮긴 것, 모듈 지도는 [README](src/weglee_ai/seoulevent/README.md) | 서비스 (로컬 실행) |
| `ocr/` | 영수증 OCR 방식 비교 | 실험 |
| `baseline.py` · `metrics.py` | 요일 평균 예측식 · WAPE · Bias | 기준 · 실험 |
| `sample_data.py` · `store_templates.py` · `seed_sql.py` | 합성 판매 데이터 · 가게 틀 7종 · 시드 SQL | 시연 · 개발 |
| `synthetic_backtest.py` | 합성 데이터 백테스트 | 실험 |
| `backtest.py` | 예측 백테스팅 — 감 · 요일 평균 · TimesFM 을 마지막 8주 rolling 으로 채점 · 채택 판정 (S15P31A308-57) | 실험 |
| `batch/` · `timesfm_eval/` | TimesFM 야간 배치 · 채택 검증 | 퇴역 (DECISIONS #91, S15P31A308-312 뒤 정리) |

## OCR 비교 실행

키 이름 · 명령 · 비용은 [src/weglee_ai/ocr/README.md](src/weglee_ai/ocr/README.md) (지라 S15P31A308-109).

## 규칙

- **`baseline.py`의 식은 백엔드 예측과 같아야 합니다.** 바꾸면 백엔드 · `docs/plans/3주_개발_계획.md` 4.3절과 같은 MR에서 맞춥니다
- 샘플 데이터의 이름 · 수치는 전부 가상입니다

### data 폴더

`data/` 전체가 `.gitignore`입니다. **POS 원본 매출 데이터는 어떤 경로로도 커밋하지 않습니다** (팀 Secret 규칙).

```
data/raw/         사장님에게 받은 원본 그대로 (POS 엑셀, 거래명세서). 고치지 않는다
data/processed/   raw를 정제한 결과. 만드는 코드는 scripts/ 또는 src/에 둔다
data/sample/      make_sample_data.py가 만드는 가상 데이터
```

- raw는 읽기만 합니다. 정제는 코드로 하고, 손으로 엑셀을 고치지 않습니다. 다시 돌리면 같은 processed가 나와야 합니다
- 원본 파일을 메신저 · Jira · 레포에 올리지 않습니다

### 노트북

- 이름: `NN_주제.ipynb` (예: `01_baseline_wape.ipynb`, `02_lightgbm.ipynb`). 번호는 만든 순서
- **출력을 지우고 커밋합니다** (VS Code: Clear All Outputs). 출력에 실데이터가 찍힐 수 있습니다
- 두 노트북 이상에서 쓰는 함수는 `src/weglee_ai/`로 옮기고 테스트를 붙입니다
- 결론은 노트북 맨 위 마크다운 셀에 한 줄로. 모델 선정 근거 문서는 `docs/plans/` 아래 md로 따로 씁니다

## 샘플 데이터 · 시드

가상 가게의 판매 · 입고 · 레시피 만들기, 업종별 가게 틀, 이미 있는 가게에 이력 더하기, 시연 가게 자동 연결, `seed.sql`, 합성 POS 엑셀은 [docs/sample-data.md](docs/sample-data.md).

## 야간 예측 배치

> **실험 기록 · 서비스 예측에는 쓰지 않음 (DECISIONS #91).** 스테이징 CronJob(`infra/k8s/overlays/staging/ai-batch.yaml`)은 S15P31A308-312 에서 내립니다. 그때까지 백엔드는 대상일 `timesfm` 행이 있으면 그 값을 먼저 읽습니다.

스테이징에서는 매일 03:00 CronJob(S15P31A308-91)이 실행합니다. 가게마다 판매를 읽어 오늘부터 7일을 TimesFM · 요일 평균 두 가지로 예측하고 `forecast`에 저장합니다. 개념 설명은 [explainers/야간-예측-배치.md](explainers/야간-예측-배치.md).

```bash
uv sync --group batch        # TimesFM · torch(CPU) · psycopg. 처음 실행 때 모델 가중치 약 900MB 를 받는다
DB_URL=jdbc:postgresql://localhost:5432/zigzaego DB_USERNAME=zigzaego DB_PASSWORD=...   uv run python scripts/run_forecast_batch.py [--today 2026-10-16] [--models timesfm,dow_avg]
```

- 접속 정보는 백엔드와 같은 환경변수(`DB_URL` JDBC 형식 · `DB_USERNAME` · `DB_PASSWORD`)
- 종료 코드: 모든 가게 성공 0, 실패한 가게가 있으면 1 (나머지 가게는 끝까지 돈다). 로그는 표준 출력
- 다시 돌려도 안전 — 같은 (가게, 메뉴, 날짜, 모델, 채널)은 덮어쓴다
- 실측(샘플 가게 1곳 · 메뉴 8 × 채널 2, CPU): 10.7초(가중치 캐시 후), 메모리 최대 약 1.9GB
- `tests/test_batch_db.py`는 `batch` 그룹과 Docker가 있을 때만 돈다(CI 는 건너뜀)
- 예측이 실제와 얼마나 맞았는지: `uv run python scripts/forecast_accuracy.py --store 1 --days 7` — 모델 × 채널 WAPE · Bias(평가 지표 정의 §4). 같은 규칙의 SQL 은 `weglee_ai.accuracy.ACCURACY_SQL`

## 이번 주 매출 예측 (상권 유동인구) — 로컬 실행

**로컬 PC 에서 실행되는 파일입니다** (서버 배치 아님). 로컬 PC 가 매일 06:00(Windows 작업 스케줄러) 예측표를 만들어 DB `sales_forecast_week` 에 넣으면 백엔드 `GET /sales-forecast/this-week` 가 읽습니다(S15P31A308-226 · 267, ADR-0017). 준비 · 실행 · 재학습은 [src/weglee_ai/seoulevent/README.md](src/weglee_ai/seoulevent/README.md).

```powershell
uv sync --group event
powershell -ExecutionPolicy Bypass -File scripts\register_daily_task.ps1    # 매일 06:00 자동 실행 등록
```

## 예측 백테스팅

감(지난주 같은 요일) · 요일 평균 · TimesFM 을 같은 기간 · 같은 행에서 채점하고 서비스에서 내린 TimesFM(DECISIONS #91)을 다시 쓸 근거가 있는지 한 줄로 냅니다 (평가 지표 정의 4절). 개념 설명은 [explainers/예측-백테스팅.md](explainers/예측-백테스팅.md).

```bash
uv run python scripts/backtest_forecast.py                          # 감 · 요일 평균 (몇 초)
uv run python scripts/backtest_forecast.py --timesfm                # TimesFM 까지 (batch 그룹, CPU 8주 약 8분)
uv run python scripts/backtest_forecast.py --data data/processed --timesfm   # 위글리 배달 이력
```

- 결과 md(결론 한 줄 · 발표용 표 · 전체 표)는 `outputs/backtest_forecast.md` — 커밋하지 않는다
- 노트북은 `notebooks/03_backtest_forecast.ipynb` (`USE_TIMESFM = True` 로 TimesFM)

## TimesFM

> 서비스 미사용 · 실험 기록입니다([DECISIONS #91](../docs/DECISIONS.md)).

`batch` 의존성 그룹에 있습니다(`timesfm[torch]`, torch 는 CPU 인덱스 고정). 기본 설치(노트북 · CI)에는 들어가지 않습니다.

| 버전 | 체크포인트 | 쓰임 |
|---|---|---|
| 2.5 (200M) | `google/timesfm-2.5-200m-pytorch` | 야간 예측 배치 · 채택 검증 실험 (zero-shot, CPU). 서비스 예측에는 쓰지 않음 (DECISIONS #91) |
| 3.0 | `google/timesfm-3.0-pytorch` | 다변량 · 공변량 실험용. **가중치가 비상업 라이선스**라 운영 판단과 분리한다 ([아키텍처 바이블 11절](../docs/spec/아키텍처_바이블.md)) |

### TimesFM 채택 검증 실험 (S15P31A308-54)

공개 데이터(Kaggle Recruit 음식점 · FreshRetailNet-50K 마트)로 이력 길이별 TimesFM 2.5 대 요일 평균을 잰 실험입니다. 서비스 미채택([DECISIONS #91](../docs/DECISIONS.md))입니다. 결과는 [docs/plans/2026-10-19_TimesFM_채택검증.md](../docs/plans/2026-10-19_TimesFM_채택검증.md).

```bash
uv sync --group batch
uv run python scripts/timesfm_eval/run_adoption.py --dataset recruit      # data/raw/recruit-restaurant-visitor-forecasting.zip
uv run --with pyarrow python scripts/timesfm_eval/run_adoption.py --dataset freshretail   # data/raw/freshretailnet/train.parquet
uv run python scripts/timesfm_eval/run_blend.py           # 저장된 예측값만 — TimesFM 재실행 없음
uv run python scripts/timesfm_eval/run_equivalence.py     # 저장된 예측값만 — TimesFM 재실행 없음
```

- 원자료는 Kaggle 에서 직접 받아 `data/raw/` 에 둡니다(재배포 금지라 커밋하지 않음). 결과 CSV · 리포트는 `outputs/timesfm_adoption/`
- `outputs/timesfm_adoption/` 예측 CSV 에는 Kaggle 실제값이 들어 있어 커밋 금지(재배포 금지)
- 그림은 matplotlib 이 있을 때만 그립니다(`uv run --with matplotlib ...`). pyarrow · matplotlib 은 팀 의존성에 넣지 않았습니다
- 시험(`tests/test_timesfm_eval_*.py`)은 가짜 예측기 · 합성 데이터만 써서 기본 설치로 돕니다
