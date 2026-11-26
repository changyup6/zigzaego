# 샘플 데이터 · 시드 · 시연 연결

> `ai/README.md` 에서 옮긴 절입니다. 명령은 `ai/` 폴더에서 실행합니다.

메뉴 8개 · 재료 12개(BOM 9 · 1:1 1 · 주기형 2) · 거래처 3곳 · 730일치(2년, `--days`로 바꿈) 배달 · 홀 판매 · 입고 영수증 · 정답 재고. 금 · 토 1.4배(배달은 일요일 1.2배 추가), 월별 계절 배수(우동 겨울↑ 여름↓, 12월↑) · 연 6% 성장, 2021 ~ 2026 공휴일 · 가상 지역 행사 배수, 비 오는 날 · 공휴일 배달 비중 상승, 정규 노이즈(CV 0.15), 월 2회 임의 휴무 + 설 · 추석 당일 휴무. 레시피는 돈까스집 기준 가상값, 단위는 G · ML · EA(스키마 V1과 같음).
휴무일은 `pos_sales.csv`에 행이 없고 `closed_days.csv`에 따로 있습니다. 안 팔린 메뉴도 행이 없습니다(포스 원본과 같게). `--seed`가 같으면 CSV 가 바이트 단위로 같습니다.
`events.csv` · `weather.csv` · `true_demand.csv` · `true_usage.csv`는 생성 정답입니다(공휴일 · 행사 배수, 비 여부, 노이즈 전 기대값, 재료 실제 소비). 서비스 입력이 아니고 `seed.sql`도 읽지 않습니다. 특히 비는 합성 데이터의 잡음일 뿐입니다 — 날씨 변수는 서비스 범위 밖입니다(DECISIONS #30).
열 정의는 [판매 데이터 사전 3절](../../docs/data/판매_데이터_사전.md), 만드는 방식은 [explainers/샘플-데이터.md](../explainers/샘플-데이터.md).

### 앱 화면용으로 넣기 (적재 절차)

화면(추천 · 소진 예상 · 최근 7일 소비 · 단가 추이)은 "오늘" 기준으로 계산합니다. 그래서 **`--end` 는 넣는 날의 전날**로 줍니다.

```bash
cd ai
uv run python scripts/make_sample_data.py --end 2026-11-02 --out data/screen/single            # 넣는 날이 11-03 이면
uv run python scripts/make_seed_sql.py --sample-dir data/screen/single                          # 빈 DB 용
uv run python scripts/make_sample_data.py --end 2026-11-02 --template all --stores 7 --out data/screen/multi
uv run python scripts/make_seed_sql.py --sample-dir data/screen/multi --append                  # seed_append.sql
psql -v ON_ERROR_STOP=1 -f data/screen/single/seed.sql <접속 옵션>
psql -v ON_ERROR_STOP=1 -f data/screen/multi/seed_append.sql <접속 옵션>
```

- 날이 지나면 마지막 판매일과 오늘 사이가 비어 화면이 다시 비어 갑니다. 다시 넣을 때는 둘 중 하나입니다
  - 가게를 새로 더하기: 새 `--end` 로 만든 뒤 다른 `--out` 폴더 · 다른 `--seed` 로 `--append`. 가게 이름 · 계정 이메일이 같으면 멈추므로, 여러 가게 출력은 `stores.csv` 의 `store_name` · `store_key` 를 바꾸고(예 끝에 `-b`) **가게 폴더 이름도 새 `store_key` 와 같게 바꾼 뒤**(예 `donkatsu-01` → `donkatsu-01-b`, `make_seed_sql.py` 는 `store_key` 이름의 폴더에서 CSV 를 읽는다) 다시 `make_seed_sql.py` 를 돌립니다
  - 그 가게를 지우고 다시 넣기: 가게에 딸린 표를 지운 뒤(아래) 같은 파일을 새 `--end` 로 만들어 넣습니다

```sql
-- 지울 가게 이름을 한 곳에 적는다. 한 트랜잭션 · 자식 표부터 (로컬 Postgres 16 · V1~V5 에서 시험한 순서)
BEGIN;
CREATE TEMP TABLE del_store ON COMMIT DROP AS SELECT id FROM store WHERE name = '샘플 치킨 5호점';
CREATE TEMP TABLE del_account ON COMMIT DROP AS
  SELECT account_id AS id FROM store_member WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM price_history WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM stock_count WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM purchase_doc_line WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM purchase_doc WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM purchase_order_item WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM purchase_order WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM recommendation WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM recommendation_run WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM forecast WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM job WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM pos_sale_line WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM pos_sale WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM store_closed_day WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM recipe WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM menu_alias WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM menu_component WHERE store_id IN (SELECT id FROM del_store);
UPDATE ingredient SET primary_supplier_product_id = NULL WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM supplier_product WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM supplier WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM ingredient WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM menu WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM store_member WHERE store_id IN (SELECT id FROM del_store);
DELETE FROM account WHERE id IN (SELECT id FROM del_account)
  AND NOT EXISTS (SELECT 1 FROM store_member m WHERE m.account_id = account.id);  -- 다른 가게에도 속한 계정은 남긴다
DELETE FROM store WHERE id IN (SELECT id FROM del_store);
COMMIT;
```

### 업종 · 규모가 다른 여러 가게 (S15P31A308-240)

위 기본값은 돈까스집 한 곳입니다. "여러 가게로 확장되는가" 를 시험하려고 가게 틀 7종을 둡니다(`src/weglee_ai/store_templates.py`, 값은 상식 기반 가상값).

| 틀 | 업종 | 배달 비중(대략) | 특징 |
|---|---|---|---|
| `donkatsu` | 돈까스 | 0.4 | 기본값. 판매 쪽 CSV 는 예전 출력과 바이트 단위로 같음 (주기형 재료 입고만 바뀜) |
| `bunsik` | 분식 | 0.45 | 어묵 · 라면 겨울↑, 쫄면 여름↑ |
| `chinese` | 중식 | 0.6 | 짬뽕 겨울↑ |
| `korean` | 한식 백반 | 0.25 | 평일 점심 상권 — 금 · 토 0.8배 |
| `chicken` | 치킨 | 0.8 | 금 · 토 1.6배, 일요일 배달 1.4배 |
| `cafe` | 카페 | 0.15 | 아이스티 여름↑ |
| `pasta` | 파스타 | 0.35 | 샐러드 여름↑, 크림 겨울↑ |

```bash
uv run python scripts/make_sample_data.py --list-templates                       # 틀 목록
uv run python scripts/make_sample_data.py --template all --stores 14 --out data/stores   # 틀마다 2곳
uv run python scripts/make_sample_data.py --template chicken,cafe --stores 4 --days 1826  # 5년치
uv run python scripts/make_seed_sql.py --sample-dir data/stores                  # 가게 14곳을 한 seed.sql 에
uv run python scripts/make_seed_sql.py --sample-dir data/stores --append         # data/stores/seed_append.sql
bash scripts/check_seed_multi.sh                                                 # Docker Postgres 에 넣어 보기 (먼저 WEGLEE_DEMO_PASSWORD_HASH 를 export)
```

- `--days` 는 1일 ~ 공휴일 표 범위(2021-01-01 ~ 2026-12-31) 안. 기본 끝날(2026-10-11)이면 최대 5년(1826일)
- 돈까스 한 곳(기본)만 `--out` 에 CSV 를 바로 씁니다. 그 밖(여러 곳, 다른 틀)은 `--out/가게키/`(예 `chicken-05/`) 에 CSV, `--out/stores.csv` 에 가게 목록(`store_key,template,store_name,scale,seed`)
- 가게 i 는 틀 목록을 차례로 받고 시드 `--seed`+i, 규모 배수(scale, 모든 메뉴 평일 평균에 곱함)는 `--seed` 로 0.6 ~ 1.8 에서 뽑습니다. 같은 명령이면 바이트 단위로 같음
- 모르는 틀 · `--stores` 0 이하 · 범위 밖 기간이면 한 줄 오류와 종료 코드 2, CSV 를 쓰지 않습니다
- 여러 가게 `seed.sql`: 가게마다 가게 · 데모 계정(`demo+가게키@wiggly.local`, 비밀번호는 위와 같음) · OWNER · 데이터. 이름 조회는 그 가게 안에서만 하고, 끝 검증은 가게마다 셉니다
- 화면 충족: seed.sql 은 끝(`COMMIT` 앞)에 메뉴 판매가 · 재료 대표 거래처 상품(발주 가능) · 단위 LOCKED · 거래처 마감 시각 · 배송 요일 · 매입 문서 줄마다 단가 이력(`price_history` DOC) · 마지막 날 재고 실사를 더 넣고 따로 검증합니다
- 발주 이력: 매입 문서 1장마다 보내고 입고 끝난 발주서(`purchase_order` SENT) 1장, 문서 줄마다 품목 1줄을 넣고 문서를 그 발주서에 잇습니다(`purchase_doc.purchase_order_id`). 주문 시각은 문서 날짜 − 거래처 리드타임 15시, 입고 시각은 문서 날짜 17시(가게 시간대)라 모두 마지막 실사 앞이고, 추정 재고 · 주기형 추정 · 단가 이력은 넣기 전과 같습니다. 입고 안 된 발주(추천의 입고 예정)는 만들지 않습니다. 생성기의 마지막 매입 문서는 `--end` 이틀쯤 전이라, 넣는 날이 달 초면 이번 달 발주 이력은 비어 있을 수 있습니다
- 주기형 재료(식용유 등)는 모든 틀에서 바닥나기 직전에만 발주 주기 12번치를 한 번에 채웁니다. 매 주기 조금씩 채우면 입고 직전 남은 양이 커서 앱의 주기형 추정 재고(마지막 입고량 − 그릇당 소비 × 그 뒤 판매)가 음수가 되고 실사 필요 표시가 뜹니다
- `--append`: "빈 DB" 대신 "넣으려는 가게 이름 · 계정 이메일이 이미 있으면 실패" 만 검사합니다. 기존 가게 데이터는 건드리지 않습니다(테스트 데이터가 있는 DB 용). 실제 스테이징 적용은 인프라 담당과 따로 정하고, 그 전까지 위 "공유 DB · 스테이징 적용 금지" 는 그대로입니다

### 이미 있는 가게에 이력만 더하기 (S15P31A308-243)

온보딩으로 메뉴 · 재료 · 레시피만 있는 가게(예: 팀 테스트 계정)를 2년 장사한 가게처럼 보이게 합니다. 가게 · 계정 · 메뉴 · 재료 · 레시피는 넣지 않고, 판매 · 휴무일 · 거래처(없는 것만) · 거래처 상품 · 매입 문서 · 발주 이력 · 단가 이력 · 첫날 · 마지막 날 실사 · 재료 대표 상품(비어 있을 때만, `unit_status` LOCKED)만 더합니다.

1) 그 가게를 **읽기만 해서** CSV 로 뜹니다 (가게 id 2 예시, psql):

```sql
\copy (SELECT id, name, display_name, timezone FROM store WHERE id = 2) TO 'store.csv' CSV HEADER
\copy (SELECT id, name, kind, active, price, source FROM menu WHERE store_id = 2 ORDER BY id) TO 'menu.csv' CSV HEADER
\copy (SELECT id, name, unit, kind, storage_type, shelf_life_days, sourcing_type, price_sensitive, safety_days FROM ingredient WHERE store_id = 2 ORDER BY id) TO 'ingredient.csv' CSV HEADER
\copy (SELECT r.id, r.menu_id, m.name AS menu_name, r.ingredient_id, i.name AS ingredient_name, r.qty_per_serving FROM recipe r JOIN menu m ON m.id = r.menu_id JOIN ingredient i ON i.id = r.ingredient_id WHERE r.store_id = 2 ORDER BY r.id) TO 'recipe.csv' CSV HEADER
\copy (SELECT * FROM supplier WHERE store_id = 2 ORDER BY id) TO 'supplier.csv' CSV HEADER
\copy (SELECT id, doc_type, kind, doc_date, parse_status, supplier_id FROM purchase_doc WHERE store_id = 2 ORDER BY id) TO 'purchase_doc.csv' CSV HEADER
```

2) 그 CSV 로 틀을 만들어 생성하고, 그 가게에 넣는 SQL 을 만듭니다 (`--end` 는 넣는 날의 전날):

```bash
uv run python scripts/make_sample_data.py --from-existing data/existing/dump --end 2026-11-02 --out data/existing/gen
uv run python scripts/make_seed_sql.py --sample-dir data/existing/gen --into-existing --store-name "지그재고 테스트"   # 또는 --store-id 2
psql -v ON_ERROR_STOP=1 -f data/existing/gen/seed_existing.sql <접속 옵션>
```

- 메뉴 · 재료 · 레시피는 떠 온 그대로입니다. 비활성 메뉴는 판매를 만들지 않고, 레시피가 없는 메뉴도 판매는 만듭니다
- 생성에만 쓰는 값(가상값): 메뉴 평일 평균 = min(20, 종류별 하루 매출 몫 ÷ 판매가) — MAIN 10만 원 · SET 6만 원 · OPTION 2.4만 원. 배달 비중 · 금토 배수는 돈까스 틀, 계절 배수 1
- 거래처 상품: 틀 7종에 같은 재료(이름 또는 `seed/ingredient_catalog.csv` 의 대표 이름 · 같은 단위)가 있으면 그 포장 · 단가, 없으면 (단위, 보관) 기본 포장(`store_templates.EXISTING_PACK_DEFAULTS`). 고기 · 신선 · 그 밖으로 나눠 가상 거래처 3곳(한빛축산 · 새벽청과 · 푸른식자재)에 둡니다
- 떠 온 거래처는 그대로 둡니다. 리드타임 · 발주 주기 · 채널이 다 있고 이름이 축산 · 청과류면 가상 거래처 대신 그 거래처에 상품을 겁니다. 빈 값이 있는 거래처에 걸면 추천이 SUPPLIER_UNSET 이 되어 걸지 않습니다
- 첫 문장이 확인합니다: 가게가 정확히 1곳, 그 가게에 판매 · 거래처 상품 · 실사가 없음(두 번 넣기 방지), 생성에 쓴 메뉴 · 재료 이름이 다 있음. 하나라도 어긋나면 아무것도 안 들어갑니다(한 트랜잭션, 끝에 건수 검증)
- 실제 스테이징에 넣기 전에 대상 가게 · 넣을 행 수를 사람이 확인합니다. 예외: 아래 `scripts/demo_attach.py`(S15P31A308-272)는 사람 확인 대신 "시연 계정 이메일 하나 · 판매 0 인 가게만 · staging 고정" 조건으로 자동 적용합니다

### 시연 이력 사전 생성과 캐시 연결 (S15P31A308-343 · -344)

무거운 730일 생성은 시연 전에 한 번 수행합니다. 가입 때에는 같은 캐시의 SQL을 대상 가게에 재매핑합니다. 캐시가 없거나 맞지 않으면 생성기로 우회하지 않고 `FAILED`로 남깁니다. 실제 POS 매출을 복사하는 기능이 아니며 합성 이력입니다.

```bash
cd ai
# SOURCE_STORE_ID는 확정된 시연 메뉴·재료·레시피가 있는 가게입니다. DB는 조회만 합니다.
uv run python scripts/demo_attach.py --prepare-template SOURCE_STORE_ID --template-dir data/demo-template-v1 --today 2026-11-12
# 예측이 다른 준비 가게에 있으면 --forecast-store-id FORECAST_STORE_ID
# -349 시세 자산을 함께 연결하면 --market-sql seed/demo_market/pao-market-v1.sql (V23 필요)
# 모든 재료에 주문처 2~3곳(기존 거래처 + 합성 시세)을 채우려면 v1 대신 --market-sql seed/demo_market/pao-market-v2.sql
uv run python scripts/demo_attach.py --email <시연 이메일> --forever --template-dir data/demo-template-v1 --today 2026-11-12
uv run python scripts/demo_attach.py --email <시연 이메일> --dry-run --template-dir data/demo-template-v1 --today 2026-11-12
```

- 접속 환경변수: 스테이징은 `WEGLEE_STAGING_SSH_HOST`, `WEGLEE_STAGING_PEM`을 쓰며 기본 환경(`--env staging`, 네임스페이스 `staging`)입니다. 작업 파일 위치는 `WEGLEE_DEMO_WORKDIR`입니다. `.env`를 자동으로 읽지 않습니다. 계정 이메일·접속 키·비밀번호를 manifest에 넣지 않습니다.

#### 운영 SSM 실행과 사전 점검 (S15P31A308-346)

운영(`--env prod`) 경로는 기본적으로 비활성화되어 있으며 SSH/pem을 쓰지 않고 기존 `infra/tools/prod-ssm/`과 같은 AWS SSM 경로(`AWS_PROFILE` 기본 `zigzaego`, `AWS_REGION` 기본 `ap-northeast-2`, `zigzaego-node-*`)로 접속합니다. 사전 조건으로 `aws login --profile zigzaego` 인증과 `app-secrets`의 `DEMO_ACCOUNT_EMAIL` 등록이 필요합니다.

```bash
cd ai
# 1. 사전 점검 (--dry-run: 운영 primary 조회 및 로컬 SQL 생성만 수행, DB 쓰기 0건)
WEGLEE_ALLOW_PROD=1 uv run python scripts/demo_attach.py --env prod --dry-run --email <시연 이메일> --template-dir data/demo-template-v1 --today 2026-11-12

# 2. 운영자 승인 실행 (--confirm-prod 필수: 검증된 템플릿 checksum/fingerprint 확인 후 원자적 연결)
WEGLEE_ALLOW_PROD=1 uv run python scripts/demo_attach.py --env prod --confirm-prod --watch --email <시연 이메일> --template-dir data/demo-template-v1 --today 2026-11-12
```

- 환경·대상 가드: `--env prod`는 `WEGLEE_ALLOW_PROD=1`이 없으면 즉시 종료 코드 `2`로 거절되고, 쓰기 실행에는 `--confirm-prod`가 추가로 필요합니다. `--namespace`를 넘길 때 `--env`와 다르면 즉시 거부합니다.
- 일반 계정 차단: 운영 연결 시 클러스터 `Secret/app-secrets`의 `DEMO_ACCOUNT_EMAIL` SHA-256 해시와 `--email`이 일치하지 않거나 `archived-` 접두사·복수 가게·기존 판매 이력이 있으면 쓰기 0건으로 거부합니다. 이메일 원문과 시크릿 값은 표준 출력/오류 로그에 남기지 않으며, 대용량 SQL 전송 시 노드 임시 폴더는 `chmod 700` 생성 후 종료 `trap`으로 삭제합니다.
- 3노드 primary 추적과 재개: 매 실행마다 CNPG `role=primary` 파드와 `Cluster.status.currentPrimary`가 단일 파드(`postgres-1`~`3`)로 일치하는지 확인하고, SQL 트랜잭션 안에서 `pg_is_in_recovery()`까지 재검증합니다. 실패 시 `PREPARING` 동일 `attempt`만 `FAILED/ATTACH`로 남기므로 BE 재시도로 새 `attempt`를 연 뒤 위 명령을 다시 실행합니다.
- 준비 폴더는 새 경로여야 합니다. 임시 폴더에 생성하고 모두 검증한 뒤 rename하여 공개합니다. 기존 캐시는 덮어쓰지 않습니다. 메뉴·재료·레시피 스냅샷 전후가 다르거나 고정 주 예측이 없으면 폴더를 공개하지 않습니다. 원본 거래처 연락처·과거 거래는 수집하지 않고 가상 거래처를 생성합니다.
- `manifest.json`에는 버전, seed 15, 730일, `today`, 시작·마지막 날짜, 기대 행 수, 파일 SHA-256, 메뉴·재료·레시피 semantic fingerprint, 사용한 업무 표의 열·제약 schema fingerprint, 주간 예측과 생성 시간이 있습니다. 생성 CSV와 SQL은 `data/` 아래 별도 자산이며 커밋하지 않습니다. -322 준비·리허설 기록은 이 manifest를 증거로 사용합니다.
- 오늘 기본값은 `2026-11-12`이며 서버 `DEMO_ACCOUNT_TODAY`와 같아야 합니다. 마지막 판매일은 반드시 오늘 전날(`2026-11-11`)이고 시작일은 `2024-11-12`입니다. `--end`를 주면 `--today`와 같은 관계인지 검사합니다. 예측은 해당 주 월요일을 키로 가지며 오늘부터 일요일까지 날짜가 모두 있어야 합니다. 이미 지난 월~수 기록은 필수가 아닙니다.
- fingerprint는 DB ID와 숫자의 표시 자릿수를 제외하고 메뉴 이름·종류·활성·가격, 재료 이름·단위·종류·보관·유통기한·조달·가격민감·안전일수, 메뉴/재료 이름별 레시피 수량을 검사합니다. 정렬은 DB 지역 설정과 무관한 C 규칙이며 개수만 같은 변경도 실패합니다. schema 검사는 쓰는 업무 열의 타입·정밀도·기본값·identity와 기존 PK/UK/FK 보존을 검사합니다. 추가 UK와 V22/V23 표는 허용하되 기존 키 제거·업무 열 변경은 거부합니다. CHECK는 대상 DB가 INSERT 때 강제하며 위반하면 전체 트랜잭션이 취소됩니다. V22는 실행 SQL의 상태 계약으로 확인하고, 선택 시세 자산은 V23을 요구합니다.
- BE V22 `demo_preparation` 계약: 레시피 저장 commit 뒤 `PREPARING/ATTACH`와 attempt를 등록합니다. watcher는 현재 이메일 OWNER의 가게 하나만 선택합니다. 기본 1초 간격으로 온보딩을 기다리고 메뉴·재료·레시피 수가 한 번 더 같으면 연결합니다. `--forever`는 재가입한 새 가게도 같은 캐시로 처리합니다. FAILED는 BE 재시도로 attempt가 증가하여 PREPARING이 될 때까지 쓰지 않습니다.
- 연결 한 단위는 PostgreSQL 트랜잭션 하나입니다. 가게·준비 행·현재 OWNER·메뉴/재료/레시피를 잠그고 같은 attempt인지, 이메일 OWNER인지, schema와 semantics가 같은지 확인합니다. 대상 가게 범위에서 이름으로 모든 새 PK/FK를 재매핑하고 판매·발주·실사·가격·필수 주간 예측을 함께 넣습니다. 기대 건수·날짜와 모든 이력 FK의 가게 일치를 검증한 뒤 `DATA_READY/RECOMMENDATION`을 같은 commit에 기록합니다. 추천 계산과 최종 `READY`는 BE 작업자 책임입니다.
- 중복 실행은 DB 잠금으로 직렬화하며 DATA_READY/READY이면 SQL을 다시 적용하지 않습니다. 한 이메일의 같은 PC 실행은 기존 OS 파일 잠금도 유지합니다. 판매가 있다는 이유만으로 성공하지 않습니다. 캐시·fingerprint·예측·SQL 적용 실패는 전체 이력을 rollback한 뒤 같은 PREPARING attempt만 `FAILED/ATTACH`, 안전한 오류 코드, `retryable=true`로 바꿉니다. 연결이 실패해도 이미 더 최신 attempt나 READY인 상태를 덮지 않습니다. `--dry-run`은 SQL 파일만 만들고 상태도 쓰지 않습니다.
- SQL 자체에는 lock timeout 3초, statement timeout 30초가 있습니다. 프로세스가 끊기면 미완료 트랜잭션은 rollback됩니다. 상태가 PREPARING이면 같은 cache/attempt로 재실행할 수 있습니다. 부분 데이터가 이미 있는 가게는 삭제·보정하지 않고 실패하므로 운영자가 원인을 확인해야 합니다.
- 종료 코드: 0은 DATA_READY/READY 또는 dry-run, 1은 캐시·복사·예측·검증 실패, 2는 계정/가게·인자 오류, 3은 준비 등록 전, 4는 대기 timeout입니다. DATA_READY를 서비스 전체 READY로 해석하지 않습니다.

검증은 live DB 쓰기 없이 로컬 PostgreSQL에서 수행합니다:

```bash
DEMO_TEST_POSTGRES=1 uv run python -m pytest tests/test_demo_template_postgres.py -q -s
# 준비된 예시식당 캐시로도 신규 가게 세 곳을 검증할 때
DEMO_TEST_POSTGRES=1 DEMO_PAO_CACHE=/path/to/prepared-cache uv run python -m pytest tests/test_demo_template_postgres.py -q -s
```

출력의 `generation_seconds`와 `copy_seconds`를 별도로 기록합니다. 로컬 연결 시간은 스테이징의 5초 목표 달성을 증명하지 않으므로 -322의 같은 환경 신규 가입 3회 리허설에서 각각 측정해야 합니다. 전체 150초 시연 목표도 이 작업만으로 검증되지 않습니다.

## 샘플을 DB 에 넣기 (seed.sql)

샘플 CSV 를 INSERT 문 한 파일(`seed.sql`)로 바꿉니다. 스크립트는 DB 에 붙지 않고, 적용은 psql 로 합니다.

```bash
cd ai
uv run python scripts/make_sample_data.py              # data/sample/*.csv
uv run python scripts/make_seed_sql.py                 # data/sample/seed.sql (--sample-dir · --out 로 바꿀 수 있다)
psql -v ON_ERROR_STOP=1 -f data/sample/seed.sql <접속 옵션>
```

- **공유 DB · 스테이징 적용 금지.** 데모 계정 비밀번호 해시는 코드에 없고 환경변수 `WEGLEE_DEMO_PASSWORD_HASH` 로 받습니다. 로컬 · 일회용 DB 에만 넣습니다
- Windows psql 은 한글이 깨지므로 먼저 `PGCLIENTENCODING=UTF8` 을 설정합니다 (PowerShell: `$env:PGCLIENTENCODING="UTF8"`)
- **빈 DB(Flyway 마이그레이션만 적용, V12 까지) 전용입니다.** 가게가 이미 있으면 첫 문장에서 멈추고, 전체가 한 트랜잭션(`BEGIN … COMMIT`)이라 아무것도 들어가지 않습니다
- 가게 1개(`샘플 돈까스`) + 데모 계정 1개(OWNER): `demo@wiggly.local`. 비밀번호 해시(BCrypt `$2a$`, 60자)는 환경변수 `WEGLEE_DEMO_PASSWORD_HASH` 로 받고, 없거나 모양이 다르면 SQL 생성이 멈춥니다. 비밀번호는 `WEGLEE_DEMO_PASSWORD` 로 공유합니다
- id 는 DB 가 매깁니다(CSV id 를 넣지 않음). 다른 표는 이름으로 찾아 연결하므로 시드 뒤 앱이 INSERT 해도 id 가 겹치지 않습니다
- 정답 재고(`true_stock.csv`)는 첫 날 하루치만 재고 실사(`stock_count`)로 넣습니다. 시각은 그날 23:59:59 — 그날 소비 · 입고를 다 반영한 마감 재고입니다
- 매입 문서 `kind` 는 CSV 값을 쓰지 않고 실사일로 다시 정합니다: 실사일 뒤 문서는 `RECEIVE`(확인된 줄이 추정 재고에 더해짐), 실사일 당일 · 이전은 `PAST`
- 끝(`COMMIT` 직전)의 검증 블록이 표별 행 수와 매입 줄의 빈 연결 수를 생성 때 센 값과 견주고, 다르면 멈춥니다(아무것도 안 들어감)
- CSV 에 없는 필수 칸의 기본값은 `src/weglee_ai/seed_sql.py` 맨 위 상수에 있습니다
- 재료 카테고리(S15P31A308-273 · 274): 가게마다 기본 11종을 넣고, 재료는 샘플 폴더(여러 가게면 `stores.csv` 옆)에 `ingredient_categories.csv` 가 있으면 그 값, 없으면 기타. 열은 `store_name,ingredient_name,category_code,category_name,category_source` — 백엔드 자동 분류가 `INGREDIENT_CATEGORY_BACKUP_DIR`(기본 `backend/data/ingredient-categories`)에 쓰는 `ingredient_categories_latest.csv` 를 그 이름 그대로(또는 `ingredient_categories.csv` 로) 복사해 두면 DB 를 새로 만들어도 LLM 을 다시 부르지 않습니다. 보관 위치(`storage_location`)는 백엔드 기본값 규칙(주기형 · 보관 방식 없음 → 창고, 냉동 → 냉동고, 냉장 · 신선 → 냉장고, 상온 → 선반)으로 채웁니다

## 합성 POS 엑셀 (파서 시험용 · 업로드 API 없음)

실물 POS 파일 대신 샘플 CSV 로 POS export 모양의 `.xlsx` 를 만듭니다(S15P31A308-163). **백엔드에는 POS 엑셀 업로드 API 가 아직 없습니다**(2026-11-10 `backend/` 확인 — 파일을 받는 엔드포인트는 메뉴판 사진 · 영수증뿐). 그래서 이 파일을 앱에 올려 시험할 수는 없고, 파서가 생기면 쓸 입력 샘플입니다. 결과는 `data/pos_excel/` 이고 커밋하지 않습니다.

```bash
cd ai
uv run python scripts/make_sample_data.py     # data/sample/*.csv
uv run python scripts/make_pos_excel.py       # 샘플 · 토스 × 한 달 · 1년 · 2년 × 정상 · 깨진 = 12개
uv run python scripts/make_pos_excel.py --vendor toss --start 2026-09-01 --end 2026-09-30 --store-name "가상 가게" --seed 15
```

- `sample_*.xlsx`: 업로드 파서(S15P31A308-38)의 샘플 형식(`sold_date,menu_name,qty,channel` + `amount`). 백엔드에 POS 업로드 API 가 없어 앱에 올려 시험할 수는 없습니다. 앱 화면의 판매 이력은 위 seed SQL 로 넣습니다
- `toss_*.xlsx`: 토스플레이스 매출 리포트 모양. "상품 주문" 시트(두 번째) · 상품 1개당 1행 · 주문채널 홀 / 배민 / 쿠팡이츠 / 요기요. [조사 문서](../../docs/data/POS_export_형식_조사.md)에 없는 칸(수량 · 금액 · 주문번호 · 주문상태 이름, 합계 · 취소 표기)은 **가정**이고 시트 제목 줄에도 적혀 있습니다
- `*_broken.xlsx`: 머리글이 몇 줄 아래 · 빈 줄 · 합계 행 · 취소 건(샘플 = +1 / −1 쌍, 토스 = 주문상태 "취소")이 들어 있습니다. 취소를 빼면 합계는 정상 파일과 같습니다
- 미매핑 시험: `--seed` 로 고른 메뉴 몇 개의 이름을 안쪽 공백 · `(L)` 로 바꿉니다. 바꾼 목록은 `data/pos_excel/unmapped_menus.csv`
- 기간 밖 · `--start` 가 `--end` 보다 늦음 · 입력 CSV 없음 · 가격 없는 메뉴면 파일을 쓰지 않고 이유 한 줄과 종료 코드 2 로 끝납니다
