# ADR-0014: 식자재몰 상품명 라벨 배치는 Spring Batch Job 으로, 시세 적재 이벤트가 기동한다

- 상태: 채택
- 영역: backend
- 결정 로그: [DECISIONS #76](../DECISIONS.md)
- 관련 이슈: S15P31A308-229 · S15P31A308-232
- 바꾸는 것: [ADR-0004](ADR-0004-backend-stack-baseline.md) 의 "Spring Batch 는 쓰지 않는다" (이 배치 한 곳에만 쓴다. 추천 생성 등 기존 `@Scheduled` 는 그대로) · [ADR-0012](ADR-0012-market-item-group-label.md) 의 "라벨은 `ai/scripts/market/classify_products.py` 가 만들어 CSV 로 적재"

## 맥락

- 상품명 라벨(`market_product_label`, ADR-0012)은 고유 상품명 약 39만 개 중 품목군 별칭이 든 후보 약 10만 개를 LLM 에 40개씩 보내 붙인다. 요청 2,600번 · 입력 1,900만 토큰 · 몇 시간짜리 작업이라 중간에 끊기면 **그때까지 결과를 지키고 남은 것만 이어서** 돌아야 한다. 또 새 수집분이 올 때마다 **새 상품명만** 분류해야 한다(지라 229 완료 조건: 증분 실행 · 1회 비용 · 시간 기록).
- 지금까지는 ai 저장소의 Python 스크립트가 Parquet 를 읽어 `labels.csv` 를 만들고, 운영자가 그 파일을 `import-market-products` 프로필로 다시 넣었다. 데이터셋 → 라벨 → 적재가 세 번의 손 작업이고, 어느 상품명이 라벨이 없는지는 DB 가 아는데 Parquet 와 CSV 를 따로 맞춰야 했다.
- 크롤링은 서비스 밖(크롤러 저장소)에서 돌고 서비스는 사이트에 요청하지 않는다(ADR-0010). 그러므로 라벨 배치의 "언제" 는 시각이 아니라 **시세가 DB 에 들어온 순간**이다.

## 결정

- **Spring Batch 한 Job** `marketProductLabelJob` · 단계 `classifyProductNames` (`market/label/MarketProductLabelJobConfig`). chunk 크기 = `app.market-label.batch-size`(LLM 한 요청의 상품명 수, 기본 40) × `threads`(나란히 보내는 요청 수, 기본 8) = 320.
  - reader `MarketLabelCandidateReader`: `market_product` 의 고유 상품명 중 현재 `label_version` 라벨이 없는 것(증분) 가운데 품목군 별칭이 낱말로 든 후보만. 후보 판정 `MarketItemGroupCatalog.Snapshot.isCandidate` 는 크롤러 `taxonomy.word_re` 와 같은 규칙.
  - writer `MarketProductLabelWriter`: chunk 하나를 `batch-size`개씩 잘라 `GmsClient.complete` 를 `threads`개 **나란히** 보내고(고정 스레드 풀, 단계마다 만들고 닫는다), 전부 받으면 `market_product_label` 에 upsert. 한 요청이 전송 실패면 그 chunk 는 커밋되지 않고 다음 실행이 다시 받는다. 한 요청에 약 30초라 순차(1)로는 후보 10만 개가 하루를 넘겨 나란히 보낸다 — GMS 분당 한도(30,000 요청)에는 한참 못 미친다. 프롬프트 · 스키마는 `src/main/resources/market/item_group.*`(ai `prompts/market/` 복사본), 응답 검증 · 깨진 묶음 반으로 나누기는 `MarketProductLabelClassifier`(ai `classify.py` 를 그대로 옮김).
  - chunk 마다 커밋되므로 실패해도 그때까지 라벨은 남고, 다시 돌리면 남은 이름만 간다. 단계가 끝나면 요청 수 · 다시 보낸 수 · 입력 · 출력 토큰 · 형태별 건수를 `BATCH_STEP_EXECUTION.EXIT_MESSAGE` 와 실행 컨텍스트에 남긴다.
- **기동은 이벤트만.** `MarketProductImportRunner` 가 시세 CSV 적재 트랜잭션을 끝내면 `MarketProductsImportedEvent` 를 발행하고 `MarketProductLabelJobTrigger` 가 받아 같은 스레드에서 Job 을 끝까지 돌린다. `@Scheduled` · 기동 시 자동 실행(`spring.batch.job.enabled=false`) 은 없다. GMS 설정이 비어 있으면 WARN 만 남기고 건너뛴다(시세는 라벨 없이도 규칙 폴백으로 쓸 수 있다).
- **라벨 CSV 백업**(`MarketLabelBackup` · `MarketLabelBackupListener`): chunk 트랜잭션이 커밋된 뒤(`afterCommit` 동기화) 라벨 표 전체를 `MARKET_LABEL_BACKUP_DIR/labels_latest.csv`(기본 `./data/market-labels`) 로 바꿔치기하고, 단계 끝에 시각 붙은 사본을 남긴다. 열은 `MARKET_LABELS_FILE` 적재 형식과 같아 DB 를 새로 만들어도 LLM 을 다시 부르지 않고 되살린다. 백업 실패는 WARN 만(라벨은 DB 에 있다).
- **메타 표는 Flyway** V10 (`BATCH_JOB_INSTANCE` · `BATCH_JOB_EXECUTION` · `_PARAMS` · `BATCH_STEP_EXECUTION` · `_CONTEXT` 둘 · 시퀀스 셋). `spring.batch.jdbc.initialize-schema=never`. 스키마는 Flyway 만 바꾼다는 ADR-0004 그대로.
- 모델은 `GMS_MODEL`(팀 결정 2026-11-06: `gpt-6-luna`). `label_version`(`MARKET_LABEL_VERSION`, 기본 `v2`)은 프롬프트 · 품목군 표 · 모델이 바뀔 때 올린다 — 이전 버전 라벨이 붙은 상품명은 다시 분류한다.
- JPA `job` 표 저장소의 빈 이름을 `jobEntityRepository` 로 준다 — 기본 이름 `jobRepository` 가 Spring Batch 의 `JobRepository` 빈과 겹친다.
- ai 의 `classify_products.py` 와 `MARKET_LABELS_FILE` 적재는 지우지 않는다 — 시험지 라벨링 · 프롬프트 실험(228)과 운영 DB 없이 돌리는 경우에 남겨 둔다. 운영 라벨의 정본은 이 Job 이다.

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| Python 스크립트 그대로 + CSV 적재 | 손 작업 세 번. "라벨 없는 상품명" 의 정본이 DB 인데 Parquet · CSV 를 따로 맞춰야 한다. 중간 실패 시 이어 돌리기가 파일 상태에 기댄다 |
| 지금 job 실행기(`JobService` · `@Async`)로 직접 반복 | 실행 · 커밋 · 재시작 단위를 손으로 만들어야 하고, 비용 · 건수 기록도 직접 표를 둬야 한다. Spring Batch 는 그 메타 표(`BATCH_*`)와 chunk 커밋이 기본이다 |
| `@Scheduled` cron | 크롤링이 서비스 밖에서 불규칙하게 끝난다. 시각을 정하면 새 수집분이 없는데도 돌거나(비용 0 이지만 기록이 쌓임) 수집 뒤 하루를 기다린다 |
| Job 을 비동기(`@Async`)로 기동 | `import-market-products` 프로필은 러너가 끝나면 프로세스가 끝나 Job 이 중간에 죽는다. 운영 서버에서 돌릴 일이 생기면 그때 실행기를 붙인다 |
| `spring.batch.jdbc.initialize-schema=always` | 스키마 이력이 Flyway 밖에 생긴다. 테스트 · 스테이징 · 운영의 DDL 경로가 둘이 된다 |

## 결과

- **좋아지는 것**: 시세 CSV 한 번 넣으면 라벨까지 끝난다. 실행마다 BATCH 표에 언제 · 몇 개 · 요청 몇 번 · 토큰 얼마가 남는다. 끊겨도 이어서 돈다
- **감수하는 것**: 의존성 둘(`spring-boot-starter-batch` · `spring-boot-batch-jdbc`)과 표 6개 · 시퀀스 3개. Job 은 적재 러너 스레드에서 동기로 돌아 적재 프로세스가 후보 수에 비례해 오래 걸린다(10만 개 · 요청 2,600번이 8개 병렬로 약 3시간 반). 상한 `MARKET_LABEL_LIMIT` 으로 나눠 돌릴 수 있고, 429 가 잦으면 `MARKET_LABEL_THREADS` 를 줄인다
- **다시 볼 시점**: 라벨 배치를 운영 서버 안에서(파일 업로드 · API 로) 돌리게 되면 비동기 기동 · 중복 실행 방지(`JobExecutionAlreadyRunningException`)를 둔다. 배치가 둘 이상 되면 공통 설정(실행기 · 알림)을 뺀다
