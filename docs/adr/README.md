# Architecture Decision Records

**되돌리기 비싼 결정**을 왜 그렇게 했는지 남기는 곳입니다. 한 결정 = 파일 하나.

## DECISIONS.md와 무엇이 다른가

| | [DECISIONS.md](../DECISIONS.md) | ADR (이 폴더) |
|---|---|---|
| 무엇 | **모든** 결정의 한 줄 색인 (제품 · 범위 · 운영 · 아키텍처) | 그중 **아키텍처 결정**의 긴 설명 |
| 담는 것 | 결정 · 근거 한 줄 · 반영 문서 · 상태 | 맥락 · 결정 · 버린 대안 · 감수하는 결과 |
| 언제 | 결정이 날 때마다 | 구조 · 기술 선택 · 데이터 모델 · 경계 · 보안처럼 나중에 바꾸면 전부 뜯어야 하는 것 |

ADR이 생기면 DECISIONS.md 행의 "반영 문서"에 링크하고, ADR 머리에 DECISIONS 번호를 적습니다. 둘 중 하나만 고치지 않습니다.

**ADR을 쓰지 않는 것**: 문구 · 색상 · 화면 배치 · 포인트 척도 · 일정. DECISIONS 한 줄이면 됩니다.

## 파일 규칙

```
docs/adr/ADR-0001-monorepo.md        번호 4자리 + 영문 kebab. 번호는 재사용하지 않는다
```

- 상태: `제안` → `채택` → (`폐기` | `대체됨 → ADR-NNNN`). **파일을 지우지 않는다.** 뒤집히면 상태만 바꾸고 새 ADR을 쓴다
- 영역: `repo` · `backend` · `frontend` · `ai` · `infra`
- 길이: 50줄 안팎. 길어지면 결정이 두 개 섞인 것

## 쓰는 절차

1. [template.md](template.md)를 복사해 다음 번호로 저장
2. `docs/DECISIONS.md`에 행 추가 (반영 문서에 ADR 링크)
3. 구현 코드와 **같은 MR**에 넣는다. ADR 없는 아키텍처 변경 MR은 승인하지 않는다
4. 제목에 `📝 docs:` 가 아니라 구현 타입(`✨ feat:` 등)을 쓴다. ADR은 구현의 일부다

## 목록

| 번호 | 제목 | 영역 | 상태 |
|---|---|---|---|
| [ADR-0001](ADR-0001-monorepo.md) | GitLab 모노레포 1개 | repo | 채택 |
| [ADR-0002](ADR-0002-multi-tenant-store-scope.md) | 첫날부터 멀티 테넌트, 모든 조회에 store 스코프 강제 | backend | 채택 |
| [ADR-0003](ADR-0003-code-first-swagger.md) | API 명세는 code-first Swagger | backend | 채택 |
| [ADR-0004](ADR-0004-backend-stack-baseline.md) | 백엔드 기술 스택 기준선 | backend | 일부 대체됨 → ADR-0014 (상품명 라벨 배치만 Spring Batch) |
| [ADR-0005](ADR-0005-jwt-auth-redis-refresh-token.md) | 인증은 JWT, refresh token은 Redis에 저장 | backend | 채택 |
| [ADR-0006](ADR-0006-nextjs-frontend.md) | 프론트엔드는 Next.js App Router, CSS 변수 토큰 + CSS Modules | frontend | 채택 |
| [ADR-0007](ADR-0007-db-schema-v1.md) | DB 스키마 V1 — 22개 표, 단일 키, DB 와 서버의 검사 경계 | backend | 채택 |
| [ADR-0008](ADR-0008-backend-observability.md) | 백엔드 관측은 Micrometer 메트릭 · Micrometer Tracing · 구조화 로그, actuator는 관리 포트로 분리 | backend | 채택 |
| [ADR-0009](ADR-0009-recommendation-run-record.md) | 추천 생성 실행 기록(`recommendation_run`)을 남겨 같은 날 재실행은 계산을 건너뛴다. 재계산은 `force=true` | backend | 채택 |
| [ADR-0010](ADR-0010-market-product-global-table.md) | 식자재몰 시세는 가게 스코프 없는 전역 표 `market_product` 에 스냅샷 전체 교체로 적재, 품목 판정은 크롤러 규칙을 옮긴 `ItemMatcher` | backend | 일부 대체됨 → ADR-0012 (수집일 단위 적재 · 라벨 판정) |
| [ADR-0011](ADR-0011-market-site-delivery-area.md) | 식자재몰 배달 가능 판정은 가게 시군구 한 칸(`store.sigungu`)과 전역 배송 지역 표 `market_site_delivery_area` 로. 모르는 몰은 '확인 필요' | backend | 채택 |
| [ADR-0012](ADR-0012-market-item-group-label.md) | 식자재몰 상품은 품목군 기준표 `market_item_group` + LLM 사전 분류 라벨 `market_product_label` 로 찾고, 시세는 수집일마다 쌓아 플랫폼별 최저가 · 일별 추이 | backend · ai | 채택 |
| [ADR-0013](ADR-0013-receipt-original-s3.md) | 영수증 원본 저장을 인터페이스 `ReceiptStorage` 로 나누고 운영만 S3(로컬 · 스테이징은 디스크) | backend · infra | 채택 |
| [ADR-0014](ADR-0014-market-label-spring-batch.md) | 식자재몰 상품명 라벨 배치는 Spring Batch Job(`marketProductLabelJob`), 시세 적재 이벤트가 기동. 메타 표는 Flyway V10 | backend | 채택 |
| [ADR-0015](ADR-0015-prod-aws-k3s-deploy-structure.md) | 운영은 AWS EC2 3대의 k3s(ALB · ECR · S3 · Terraform), 스테이징은 제공 인스턴스 1대의 k3s. DB 는 CloudNativePG, 운영 시크릿은 External Secrets(SSM), 관측은 스테이징 서버에 모은다 | infra | 채택 |
| [ADR-0016](ADR-0016-onboarding-replay-global-table.md) | 온보딩 저장본은 가게 스코프 없는 전역 표 `onboarding_replay`(V18)에 담고, 있으면 가게 찾기 · 메뉴 수집에서 Apify 를 부르지 않는다 | backend | 채택 |
| [ADR-0017](ADR-0017-district-flow-forecast.md) | 이번 주 매출 예측은 로컬 PC 가 지하철 이용객 예측 모델(v3)로 매일 만들어 ssh → DB 파드 psql 로 `sales_forecast_week` 에 넣고 BE 는 읽기만. 근거는 SHAP, 설명은 GMS | ai · backend | 채택 |
| [ADR-0018](ADR-0018-demo-account-resignup.md) | 시연 계정 이메일(`DEMO_ACCOUNT_EMAIL`)은 이미 가입돼 있어도 다시 가입된다. 옛 계정은 지우지 않고 이메일만 보관용으로 바꾼다 | backend | 채택 |
| [ADR-0019](ADR-0019-demo-account-fixed-today.md) | 시연 계정의 가게는 오늘이 고정일(`DEMO_ACCOUNT_TODAY`)이다. 가게별 시계 `StoreClock` 으로 읽고 `GET /stores/me` 의 `today` 로 FE 에 알린다 | backend · frontend | 채택 |
| [ADR-0020](ADR-0020-demo-preparation.md) | 시연 자료·고정 주 예측 커밋 후 추천을 계산하고 가게별 준비 완료 공개 | backend · ai | 채택 |
| [ADR-0021](ADR-0021-store-market-quotes.md) | 가게에 명시적으로 가져온 가격은 `store_market_quote`에 격리하고 동일 요청 수량의 환산 금액으로 비교한다 | backend · frontend · ai | 채택 |
| [ADR-0022](ADR-0022-menu-photo-url.md) | 메뉴 대표 사진 URL 저장과 표시 | backend · frontend | 채택 |
| [ADR-0023](ADR-0023-store-address.md) | 가게 전체 주소와 배송 시군구 분리 | backend · frontend | 채택 |
