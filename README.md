# 지그재고 (Zigzaego)

> 소형 식당 사장님을 위한 **재고 · 발주 추천 서비스**. 가게 메뉴 정보로 레시피를 자동 세팅하고, 매일 발주를 추천하며, 식자재몰 최저가와 함께 승인 → 거래처별 주문 메시지 → 발주 이력까지 한 사이클을 돌립니다.

SSAFY 15기 2학기 자율 프로젝트이며, 6명이 한 팀(PM·FE 1, AI·DB 2, Backend 2, Infra 1)으로 만들었습니다. 이 저장소는 팀 저장소에서 **공개용으로 정리한 사본**입니다. 서버 주소 · 배포 설정 · CI · 팀 협업 도구 설정 · 비밀값은 제외했고, 문서 속 이름은 역할로 바꿨습니다.

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-8-DC382D?logo=redis&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-migration-CC0200?logo=flyway&logoColor=white)
![Next.js](https://img.shields.io/badge/Next.js-16-000000?logo=nextdotjs&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)
![Python](https://img.shields.io/badge/Python-3.11-3776AB?logo=python&logoColor=white)
![LightGBM](https://img.shields.io/badge/LightGBM-forecast-9ACD32)
![Swagger](https://img.shields.io/badge/Swagger-springdoc-85EA2D?logo=swagger&logoColor=black)
![Testcontainers](https://img.shields.io/badge/Testcontainers-tests-2496ED?logo=docker&logoColor=white)

## 목차

1. [서비스 소개](#서비스-소개)
2. [내 역할](#내-역할)
3. [시스템 구성](#시스템-구성)
4. [주요 화면](#주요-화면)
5. [API](#api)
6. [실행 방법](#실행-방법)
7. [저장소 구조와 문서](#저장소-구조와-문서)
8. [공개본에서 뺀 것](#공개본에서-뺀-것)

## 서비스 소개

- 사장님이 네이버 플레이스에서 가게를 찾으면 메뉴를 수집하고, LLM 이 메뉴별 재료 구성(레시피 초안)을 추출합니다. 사장님은 질문 몇 개에 답해 레시피를 확정합니다.
- 판매 · 재고 · 레시피를 바탕으로 재료별 발주 수량을 **서버 코드로 계산해 추천**합니다(LLM 이 수량을 정하지 않음). 사장님이 승인 · 수정하면 거래처별 주문 메시지와 발주서가 만들어집니다.
- 거래처 영수증 · 거래명세서를 사진으로 올리면 OCR + LLM 으로 품목을 정형화해 재료와 매칭하고, 입고 · 재고에 반영합니다.
- 식자재몰(식봄 · 쿠팡 등) 가격을 모아 재료별 최저가와 가격 추이를 보여 줍니다.
- 상권 유동인구(지하철 승하차 급증 예측 모델) 기반의 이번 주 매출 예보 카드를 제공합니다.
- 모든 API 는 로그인한 계정의 가게로 스코프되고, 다른 가게의 id 는 404 입니다([ADR-0002](docs/adr/ADR-0002-multi-tenant-store-scope.md)).

기획은 [docs/spec/PRODUCT.md](docs/spec/PRODUCT.md), 결정 로그는 [docs/DECISIONS.md](docs/DECISIONS.md) 에 있습니다.

## 내 역할

팀에서 **AI · 데이터 담당**으로 시작해, 백엔드 일감(온보딩 · 영수증 · 발주 · 입고)도 맡았습니다. 아래는 팀 저장소 커밋 이력(약 140개)에서 확인되는 영역입니다.

**백엔드 (Spring Boot)**
- **온보딩 파이프라인** (`onboarding/`, `job/`, `llm/`): 가게 찾기 → 메뉴 수집 → LLM 메뉴 구성 추출 → 레시피 질문 · 확정 흐름. 오래 걸리는 작업을 `@Async` job 으로 돌리고 상태를 조회하는 job 실행기. 메뉴 사진 묶음당 10MiB 상한 처리.
- **온보딩 저장본 재생**([ADR-0016](docs/adr/ADR-0016-onboarding-replay-global-table.md)): 시연 중 외부 수집 · LLM 호출 없이 미리 받아 둔 결과로 1초 안에 응답하는 경로.
- **영수증 · 매입 문서** (`purchasedoc/`, 변경 파일 수 기준 최대 영역): 업로드 → OCR → 정형화 → 재료 매칭 → 확인 목록 → 확정 API.
- **발주서 · 입고** (`purchaseorder/`, `receiving/`): 발주서 주문처(거래처 · 식봄 · 쿠팡) 지정과 줄 단가 저장, 발주서 보냄 기록, 도착 예정일이 지난 줄을 자동 입고하는 배치, 자동 입고 줄에 영수증을 확정해도 재고가 한 번만 늘도록 한 수정.
- 일 소비량(`consumption/`), 가격 이력(`price/`), 가격 비교 켬/끔 설정, 공개 가입 일부, Flyway 마이그레이션 · 통합 테스트.

**AI · 데이터**
- LLM 프롬프트와 평가: 메뉴 구성 추출, 영수증 정형화, OCR 평가 코드와 테스트(`ai/prompts`, `ai/src/weglee_ai/{ocr,receipt,onboarding}`).
- **합성 데이터 생성기**: 가게 틀 7종 · 1~5년치 판매 · 입고 · 발주 이력, 여러 가게 seed, 이미 있는 가게에 이력을 덧붙이는 `--into-existing` 모드, 시연 가게 자동 연결(`demo_attach`).
- **TimesFM 채택 검증**(`ai/src/weglee_ai/timesfm_eval/`): 동등성 · 섞기 · 채택 검증 실험. 결과적으로 서비스 예측에는 쓰지 않기로 결정([DECISIONS](docs/DECISIONS.md) #91).
- ruff 도입, LLM 호출 · JSON 추출 모듈 정리 등 ai 코드 정리.

**문서 · 품질**: 스키마 변경마다 ERD 문서([docs/design/erd.md](docs/design/erd.md)) 갱신, 스테이징 접속 정보 · 데모 비밀번호를 환경변수로 옮겨 비밀값을 코드에서 제거, 컨트롤러 DTO 이름 규칙 정리.

상권 유동인구 기반 급증 예측 모델은 팀원 담당입니다. 저는 그 모델을 포함해 서비스 예측에 무엇을 쓸지 판단하는 검증과 시연용 합성 이력을 맡았습니다.

## 시스템 구성

```mermaid
flowchart LR
    U[사장님 · 모바일 웹] --> FE[Next.js 16 / React 19<br/>frontend]
    FE -->|REST + JWT| BE[Spring Boot API<br/>backend]
    BE --> PG[(PostgreSQL 18<br/>Flyway 마이그레이션)]
    BE --> RD[(Redis 8<br/>refresh token)]
    BE --> JOB[@Async job 실행기]
    JOB --> LLM[LLM API<br/>메뉴 구성 · 영수증 정형화]
    JOB --> OCR[CLOVA OCR<br/>영수증 글자 인식]
    JOB --> APIFY[Apify<br/>네이버 플레이스 수집]
    BE --> MKT[식자재몰 시세 적재 · 라벨 배치<br/>Spring Batch]
    AI[ai / Python<br/>프롬프트 · 합성 데이터 · 예측 실험] -->|seed SQL · 야간 예측 배치| PG
    AI --> FC[유동인구 급증 모델<br/>LightGBM]
    FC -->|이번 주 매출 예보| BE
```

| 영역 | 폴더 | 스택 |
|---|---|---|
| 프론트엔드 | `frontend/` | Next.js 16 (App Router), React 19, TypeScript, CSS Modules, Playwright |
| 백엔드 | `backend/` | Java 21, Spring Boot 4.1, Spring Security + JWT, Spring Batch, JPA, Flyway, springdoc, Testcontainers |
| AI · 데이터 | `ai/` | Python 3.11, pandas, LightGBM, DuckDB, TimesFM(실험), 노트북 · 합성 데이터 · 프롬프트 |
| 배포 구성 | (공개본 제외) | 팀 저장소는 k3s + kustomize, FE 는 Vercel |

## 주요 화면

화면은 `frontend/src/app`(라우트)과 `frontend/src/components`(공용 UI)에 있습니다. 디자인 정본은 [docs/design/DESIGN.md](docs/design/DESIGN.md), HTML 프로토타입은 `docs/design/prototype/` 입니다.

| 화면 | 라우트 | 주 컴포넌트 |
|---|---|---|
| 로그인 · 가입 | `/login`, `/signup` | `AuthForm` |
| 온보딩 (가게 찾기 → 분석 → 레시피 질문 → 마감 시각) | `/onboarding` | `PlaceFinder`, `AnalysisProgress`, `RecipeQuestions`, `ClosingTimeStep` |
| 홈 (오늘의 현황 · 매출 예보) | `/` | `HomeTiles` |
| 발주 (추천 · 승인 · 주문 메시지 · 이력) | `/order` | `OrderScreen`, `OrderMessageSheet`, `HistoryScreen` |
| 입고 (영수증 · 입고 확인) | `/receiving` | `ReceiptBlock` |
| 레시피 | `/recipes` | `RecipeBook`, `RecipeAddSheet` |
| 거래처 및 재료 (가격 비교 · 추이) | `/suppliers`, `/ingredients` | `IngredientsTab`, `IngredientSheet`, `PriceTrend` |
| 마감 확인 | `/closing-check` | `ClosingCheck` |
| 더보기 (POS 업로드 · 가게 설정 · 가격 비교 켬/끔) | `/more` | `MoreScreen`, `PosUpload`, `PriceCompareSwitch` |

## API

- API 는 **code-first** 입니다. 컨트롤러의 springdoc 어노테이션이 정본이고, 로컬 기동 후 `http://localhost:8080/swagger-ui/index.html` 에서 요청 · 응답 · 오류를 볼 수 있습니다([ADR-0003](docs/adr/ADR-0003-code-first-swagger.md)).
- 공통 계약(응답 래퍼 `{success, data, error}`, 오류 코드, 인증, 스코프)은 [docs/API.md](docs/API.md) 에 있습니다. 컨트롤러는 26개이며, 아래는 주요 엔드포인트만 추렸습니다.
- 표시: ● 제가 주로 만든 것, ○ 일부 참여.

| 메서드 | 경로 | 하는 일 | 내 몫 |
|---|---|---|---|
| POST | `/auth/signup` · `/auth/login` · `/auth/refresh` · `/auth/logout` | 가입 · 로그인 · 토큰 갱신(refresh 1회용) · 로그아웃 | ○ |
| GET / PATCH | `/stores/me` | 내 가게 정보 · 수정 | ○ |
| PUT | `/stores/me/closing-times` | 요일별 마감 시각 저장 | |
| GET | `/onboarding/places` | 네이버 플레이스 가게 검색 | ○ |
| POST | `/onboarding/jobs` | 온보딩 분석 job 시작(메뉴 수집 + LLM 구성 추출) | ● |
| POST | `/onboarding/jobs/menu-photo` | 메뉴판 사진으로 분석 | ● |
| GET | `/jobs/{id}` | job 진행 상태 조회 | ● |
| GET | `/onboarding/recipe-questions` | 레시피 확정용 질문 | ● |
| POST | `/onboarding/recipes/confirm` · `/onboarding/recipes/skip` | 레시피 확정 · 건너뛰기 | ● |
| GET | `/onboarding/recipes` | 추출된 레시피 조회 | ● |
| GET / POST | `/receipts` | 영수증 · 명세서 목록 · 업로드 | ● |
| GET / DELETE | `/receipts/{docId}` | 문서 조회 · 삭제 | ● |
| POST | `/receipts/{docId}/retry` | 인식 재시도 | ● |
| POST | `/receipts/matches/{matchId}/confirm` · `/receipts/lines/confirm` · `/receipts/docs/confirm` | 재료 매칭 · 줄 · 문서 단위 확정 | ● |
| POST | `/recommendations/run` | 오늘의 발주 추천 실행 | |
| GET | `/recommendations` | 추천 목록 | |
| PATCH | `/recommendations/{id}` | 추천 승인 · 수정 · 거절(PENDING → APPROVED/REJECTED, 재변경 409) | |
| POST | `/purchase-orders/from-recommendations` | 추천 결정을 거래처별 발주서로 저장 | ○ |
| POST | `/purchase-orders/{id}/send` | 발주서 보냄 기록 | ● |
| GET | `/purchase-orders/history` | 발주 이력 | ○ |
| GET | `/receiving` | 입고 예정 · 입고 목록 | ● |
| PATCH | `/purchase-orders/{orderId}/items/{itemId}/receive` | 발주 줄 입고 확인 | ● |
| GET | `/consumption/daily` | 일 소비량 | ● |
| GET | `/ingredients/{id}/price-history` | 재료 가격 이력 | ● |
| GET | `/ingredients/{id}/offers` · `/ingredients/{id}/market-price-history` | 식자재몰 상품 비교 · 시세 추이 | |
| GET / POST / PATCH / DELETE | `/ingredients`, `/menus`, `/recipes`, `/suppliers`, `/supplier-products` | 재료 · 메뉴 · 레시피 · 거래처 · 거래처 상품 CRUD | |
| GET | `/forecast` · `/sales-forecast/this-week` | 수요 · 이번 주 매출 예보 | |
| GET / POST | `/closing-check/items` · `/closing-check` | 마감 재고 확인 | |
| POST / DELETE | `/push-subscriptions` | 웹 푸시 구독 | |

전체 목록은 Swagger 와 [docs/API.md](docs/API.md) 를 보세요.

## 실행 방법

사전 준비: JDK 21, Docker, Node.js, Python 3.11(ai 만 실행할 때).

```bash
# 1) 백엔드 — Docker 가 켜져 있어야 하며 compose.yaml 의 Postgres · Redis 를 자동으로 띄웁니다
cd backend
cp .env.example .env     # DB_PASSWORD, JWT_SECRET(32바이트 이상) 등을 채웁니다
./gradlew bootRun        # http://localhost:8080/swagger-ui/index.html

# 2) 프론트엔드
cd frontend
cp .env.example .env     # API 주소 등
npm install
npm run dev              # http://localhost:3000

# 3) ai (선택) — 합성 데이터 · 실험 노트북
cd ai
pip install -r requirements.txt -r requirements-dev.txt && pip install -e .
```

LLM · OCR · Apify 키가 없어도 앱은 뜨며, 해당 기능(온보딩 분석, 영수증 인식, 가게 검색)만 실패 응답을 줍니다. 변수 설명은 `backend/.env.example` 주석을 보세요. 세부는 [backend/README.md](backend/README.md) · [frontend/README.md](frontend/README.md) · [ai/README.md](ai/README.md).

> 공개본은 정리 뒤 빌드 · 테스트를 다시 돌려 보지 않았습니다.

## 저장소 구조와 문서

```
backend/    Spring Boot API (도메인 패키지별: onboarding, purchasedoc, purchaseorder, receiving ...)
frontend/   Next.js 앱 + Playwright e2e
ai/         프롬프트 · 합성 데이터 · 예측 실험 · 노트북 · 테스트
docs/       기획 · 설계 · ADR 23건 · 결정 로그 · ERD
```

- 문서 목록: [docs/INDEX.md](docs/INDEX.md)
- 기술 결정 기록: [docs/adr/](docs/adr/) · 결정 로그: [docs/DECISIONS.md](docs/DECISIONS.md)
- ERD: [docs/design/erd.md](docs/design/erd.md)

## 공개본에서 뺀 것

환경 변수 파일 · 자격 증명 · 서버 주소와 배포 · 인프라 설정(k8s, Terraform, 서버 스크립트) · CI 설정 · AI 도구 설정 · 운영 DB 덤프 · 팀 협업 도구 연동 스크립트. 문서 안에서 이들을 가리키는 링크는 끊어져 있을 수 있습니다.
