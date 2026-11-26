# backend

Spring Boot API 서버. 스택과 이유는 [ADR-0004](../docs/adr/ADR-0004-backend-stack-baseline.md), 인증은 [ADR-0005](../docs/adr/ADR-0005-jwt-auth-redis-refresh-token.md).

## 로컬 실행

Docker가 켜져 있어야 합니다. 작업 디렉터리는 `backend/`입니다.

```bash
cp .env.example .env   # 값 채우기. 변수 설명은 .env.example 주석. 커밋 금지
./gradlew bootRun      # compose.yaml의 Postgres · Redis를 자동으로 띄우고 연결
```

| 주소 | |
|---|---|
| http://localhost:8080/swagger-ui/index.html | API 명세 (정본, ADR-0003). 우측 Authorize에 access token 입력 |
| http://localhost:8080/actuator/health | 헬스 체크 |

IntelliJ로 실행할 때도 Working directory를 `backend`로 둡니다. 그래야 `backend/.env`를 읽습니다.

## 계정 만들기

공개 가입(DECISIONS #46) API가 나오기 전까지 쓰는 **임시 운영 도구**입니다 (DECISIONS #54). 위글리 계정 · 스테이징 테스트 계정만 만들고, 공개 가입이 배포되면 지웁니다. 운영자가 계정 · 가게 · 가게 연결(OWNER)을 만듭니다. 웹 서버 없이 한 번 돌고 끝납니다.

1. `backend/.env`에 `CREATE_ACCOUNT_*` 값을 채웁니다 (설명은 `.env.example` 맨 아래)
2. 실행합니다

```bash
./gradlew bootRun --args='--spring.profiles.active=create-account'
```

로그에 `계정 생성 완료 — email=…, accountId=…, storeId=…`가 찍히면 그 이메일 · 비밀번호로 바로 로그인됩니다.
같은 이메일이 이미 있으면 아무것도 만들지 않고 실패합니다. 끝나면 `.env`에서 `CREATE_ACCOUNT_PASSWORD` 줄을 지우세요.

스테이징 · 운영은 빌드된 jar에 같은 환경 변수를 주고 `java -jar <jar> --spring.profiles.active=create-account`로 실행합니다.

## 온보딩 저장본

시연에서 가게 찾기(`GET /onboarding/places`)와 분석 job 의 메뉴 수집이 Apify 실시간 호출로 느린 것을, 미리 받아 둔 결과(`onboarding_replay` 표, ADR-0016)로 건너뜁니다 (S15P31A308-321). 저장본이 있는 검색어 · placeId 는 Apify 를 부르지 않고, 없으면 지금처럼 Apify 를 부릅니다. 일반 요청은 저장본을 만들지 않고, 저장본은 지울 때까지 남습니다. 웹 서버 없이 한 번 돌고 끝납니다.

1. `backend/.env`에 `ONBOARDING_REPLAY_QUERY=<시연에서 칠 검색어>` 와 `APIFY_TOKEN` 을 채웁니다 (앞뒤 공백 · 안쪽 연속 공백은 한 칸으로 맞춰 저장하고 읽습니다)
2. 저장합니다 — Apify 로 검색을 실제로 부르고 후보 전부(최대 5곳)의 메뉴를 실제로 수집해 저장한 뒤, 그 메뉴로 분석(메뉴 구성 추출 · 재료 분류, GMS LLM)도 실제로 돌려 분석 결과(ANALYSIS, S15P31A308-333)까지 저장합니다. **GMS 값 세 개(`GMS_KEY` · `GMS_BASE_URL` · `GMS_MODEL`)도 필요합니다 — 하나라도 비면 모든 후보의 분석이 실패해 종료 코드 1 로 끝납니다** — 후보 하나당 LLM 호출이 여러 번이라 시간이 걸립니다. 저장본이 있는 placeId 는 분석 job 이 Apify · GMS 를 부르지 않습니다. 저장본으로 답할 때는 실제 호출처럼 보이게 가게 찾기 4초 · 분석 15초를 기다립니다(`ONBOARDING_REPLAY_SEARCH_DELAY` · `ONBOARDING_REPLAY_ANALYSIS_DELAY`, `0s` 면 바로). 같은 검색어를 다시 돌리면 덮어씁니다

```bash
./gradlew bootRun --args='--spring.profiles.active=onboarding-replay-save'
```

로그에 `온보딩 저장본 저장 끝 — … 후보 N곳 모두 저장` 이 찍히면 끝입니다. 후보가 0곳이면 아무것도 저장하지 않고 종료 코드 1, 메뉴 수집 · 분석에 실패한 후보가 있으면 성공분은 저장하고 실패한 placeId 를 로그에 남기며 종료 코드 1 입니다(같은 명령을 다시 돌리면 됩니다). 검색어가 비면 `IllegalArgumentException` 으로 멈춥니다.

3. 지울 때는 같은 환경 변수로 지우기 프로필을 돌립니다 — 그 검색어의 검색 결과와 그 후보들의 메뉴 · 분석 결과 행을 지웁니다. 지울 것이 없으면 `지울 저장본 없음` 로그만 남기고 종료 코드 0 입니다

```bash
./gradlew bootRun --args='--spring.profiles.active=onboarding-replay-delete'
```

스테이징 · 운영은 빌드된 jar에 같은 환경 변수를 주고 `java -jar <jar> --spring.profiles.active=onboarding-replay-save` 로 실행합니다.

들어갔는지는 DB 에서 봅니다.

```sql
SELECT kind, replay_key, jsonb_array_length(payload) AS n, captured_at FROM onboarding_replay ORDER BY kind, captured_at;
```

## 식자재몰 시세 적재

재료별 최저가(`GET /ingredients/{id}/offers`) · 일별 추이(`/market-price-history`)가 읽는 `market_product` 표를 크롤러 통합 데이터셋으로 채우고, 시세가 들어가면 **같은 실행에서 상품명 라벨 배치**(Spring Batch `marketProductLabelJob`, ADR-0014)가 돌아 `market_product_label` 을 채웁니다 (ADR-0010 · ADR-0012, S15P31A308-232 · 229). 시세는 **파일에 든 수집일의 줄만** 지우고 다시 넣어 날짜별로 쌓이고, 라벨은 라벨 없는 고유 상품명 중 품목군 별칭이 든 후보만 40개씩 GMS 에 보내 상품명 기준으로 upsert 합니다(증분 — 이미 현재 `MARKET_LABEL_VERSION` 라벨이 있는 이름은 보내지 않습니다). 서비스는 사이트에 요청을 보내지 않습니다 — 수집은 크롤러 저장소(`foodspring-crawler`)가 합니다.

1. 크롤러 저장소에서 Parquet 를 CSV 로 내보냅니다 (약 48만 줄 · 100MB)

```bash
python scripts/export_market_csv.py data/dataset/products_20261031.parquet
```

2. `backend/.env`에 `MARKET_PRODUCTS_FILE=<그 CSV 경로>` 와 GMS 값(`GMS_KEY` · `GMS_BASE_URL` · `GMS_MODEL`)을 적고 실행합니다. GMS 값이 비어 있으면 시세만 넣고 라벨 배치는 건너뜁니다. 처음에는 `MARKET_LABEL_LIMIT=40`(요청 1번)으로 비용을 확인한 뒤 상한을 지우고 다시 돌리면 남은 상품명만 이어서 분류합니다. 실험용 라벨 CSV 를 그대로 넣으려면 `MARKET_LABELS_FILE=<ai/data/market/labels.csv 경로>` 를 같이(또는 따로) 적습니다. 시세 · 라벨 CSV 둘 중 하나는 있어야 합니다. 품목군 기준표(`market_item_group`)는 마이그레이션 V8 시드로 들어가 있고, `ai/seed/market_item_group.csv` 가 바뀌면 `uv run python scripts/market/make_item_group_sql.py` 출력으로 새 마이그레이션을 만듭니다

```bash
./gradlew bootRun --args='--spring.profiles.active=import-market-products'
```

로그에 `식자재몰 시세 적재 끝 — N줄` 뒤 `상품명 라벨 배치 끝 — COMPLETED (job execution N, 라벨 … · 요청 … · 입력 토큰 …)` 이 찍히면 끝입니다. 열이 빠졌거나 값이 형식에 안 맞으면 줄 번호와 함께 실패하고 이전 스냅샷이 그대로 남습니다. 라벨 배치가 중간에 실패하면(GMS 오류 등) 그때까지 커밋된 라벨은 남고, 같은 명령을 다시 돌리면 남은 상품명만 갑니다.

들어갔는지는 DB 에서 봅니다 (`docker compose exec postgres psql -U zigzaego` 또는 아무 SQL 클라이언트).

```sql
-- 시세: 수집일 · 사이트별 줄 수
SELECT collected_on, site, count(*) FROM market_product GROUP BY 1, 2 ORDER BY 1, 2;
-- 라벨: 버전 · 형태별 건수
SELECT label_version, form, count(*) FROM market_product_label GROUP BY 1, 2 ORDER BY 1, 2;
-- 배치 실행 기록 (Spring Batch 메타 표, V10): 언제 · 상태 · 읽은 · 쓴 수 · 비용 요약
SELECT e.job_execution_id, e.start_time, e.end_time, e.status, s.read_count, s.write_count, s.exit_message
FROM batch_job_execution e JOIN batch_step_execution s ON s.job_execution_id = e.job_execution_id
ORDER BY e.job_execution_id DESC;
-- 아직 라벨 없는 후보가 몇 개 남았나 (다음 실행이 보낼 수의 상한)
SELECT count(DISTINCT p.product_name) FROM market_product p
LEFT JOIN market_product_label l ON l.product_name = p.product_name AND l.label_version = 'v2' WHERE l.product_name IS NULL;
```

### 라벨 백업 · 되살리기

라벨은 LLM 비용이 든 결과라 배치가 chunk 를 커밋할 때마다 라벨 표 **전체**를 `backend/data/market-labels/labels_latest.csv` 로 내보내고(임시 파일 → 바꿔치기), 단계가 끝나면 `labels_<yyyyMMdd_HHmm>.csv` 사본을 남깁니다 (`MARKET_LABEL_BACKUP_DIR`, 비우면 끔). Ctrl-C 로 멈춰도 마지막 커밋까지의 라벨이 파일에 있습니다. 열은 `MARKET_LABELS_FILE` 적재 형식과 같습니다.

DB 를 새로 만들었거나 라벨 표가 비었을 때는 그 파일을 그대로 넣습니다 — LLM 을 다시 부르지 않습니다(시세 CSV 를 같이 주지 않으면 라벨 배치도 돌지 않습니다). 운영자 프로필은 러너가 끝나면 프로세스가 스스로 내려갑니다.

```bash
MARKET_LABELS_FILE=./data/market-labels/labels_latest.csv ./gradlew bootRun --args='--spring.profiles.active=import-market-products'
```

지금 당장 손으로 내보내려면 (psql):

```bash
docker exec backend-postgres-1 psql -U zigzaego -d zigzaego -c "\copy (SELECT product_name, item_group_code, form, qty, qty_unit, confidence, reason, label_version FROM market_product_label ORDER BY 1) TO STDOUT WITH (FORMAT csv, HEADER)" > data/market-labels/labels_manual.csv
```
스테이징 · 운영은 빌드된 jar 에 같은 환경 변수를 주고 `java -jar <jar> --spring.profiles.active=import-market-products`로 실행합니다. 데이터는 팀 내부 분석용이라 밖으로 내보내지 않습니다.

## 테스트

```bash
./gradlew test         # Testcontainers가 Postgres · Redis를 띄운다. Docker 필요. .env 불필요
```

## 설정이 어디서 오나

| 환경 | DB · Redis 접속 정보 | 그 밖의 값 (JWT · CORS) |
|---|---|---|
| 로컬 `bootRun` | `compose.yaml`이 띄운 컨테이너 (자동 연결) | `backend/.env` |
| 테스트 | Testcontainers (자동 연결) | `application.yaml` 기본값 |
| 스테이징 · 운영 | 환경 변수 (`.env.example`의 스테이징 항목) | 환경 변수 |

비밀값(DB 비밀번호 · JWT 키 등)은 코드 · 문서 · 커밋에 넣지 않습니다. 실제 값은 팀 채널에서 받습니다.

### 운영자 실행과 웹 작업 격리 (S15P31A308-347)

실제 러너 프로필 `import-market-products`, `create-account`, `onboarding-replay-save`,
`onboarding-replay-delete`에서는 `JobRecovery`와 `@Scheduled` 인프라를 등록하지 않는다.
따라서 같은 DB의 QUEUED/RUNNING 사용자 작업을 SERVER_RESTARTED로 바꾸거나,
추천·자동입고·마감 푸시 배치를 실행하지 않는다. 기존 러너의 적재·삭제·계정 생성과
종료 코드는 그대로이며, 비데몬 스케줄러 스레드 없이 종료한다.
일반 웹 서버는 기존처럼 기동 때 미완료 작업을 복구하고 예약 배치를 실행한다.

### 시연 자료 준비 (S15P31A308-344)

V22 `demo_preparation`과 Python 사전 생성물 복사기(S15P31A308-343)를 함께 사용한다.
`DEMO_ACCOUNT_EMAIL`의 현재 가게만 레시피 저장 후 PREPARING이 되고, 복사기의 기존 `--forever` watcher가
자료+고정 주 예측을 커밋한 DATA_READY부터 웹 서버가 추천을 계산한다. 성공한 뒤에만 READY다.
GET `/stores/me/demo-preparation`은 읽기 전용이며, 실패 시 POST `.../retry`가 해당 실패 단계만 다시 시작한다.
일반 가게와 보관된 옛 계정은 준비 대상이 아니다. API 예시와 오류는 [API 계약](../docs/API.md#시연-자료-준비-s15p31a308-344),
트랜잭션과 DB 브리지는 [ADR-0020](../docs/adr/ADR-0020-demo-preparation.md)을 따른다.

웹 worker의 조회 간격은 `app.demo-preparation.poll-delay`와 `initial-delay`(밀리초, 각각 기본 1000)로 조정한다.
복사 프로세스가 꺼지거나 실패한 경우 조회가 READY를 가장하지 않는다. PREPARING이면 watcher 재기동으로 계속하고,
FAILED이면 오류 원인을 수정한 뒤 명시적으로 재시도한다. 추천 실패 재시도는 복사한 자료를 다시 넣지 않는다.
