# API 공통 계약

> 엔드포인트 상세(요청 · 응답 · 오류 · 예시)의 정본은 **서버 Swagger**다 (결정 #19).
> 새 엔드포인트는 springdoc 어노테이션(`@Tag` `@Operation` `@ApiResponse` `@Schema`)으로 코드에 문서화한다. 별도 명세 문서를 만들지 않는다.
> 이 파일은 모든 엔드포인트가 공유하는 계약만 담는다.

## 1. 스코프

- 모든 엔드포인트는 **로그인한 계정의 가게로 스코프**된다. URL · 쿼리 · 바디로 `store_id`를 받지 않는다 (결정 #11)
- 다른 가게의 리소스 id를 넣으면 **404**. 403으로 존재를 알려주지 않는다
- 통합 테스트에 가게 2개를 넣어 위를 확인한다 (3주 계획 8절)

## 2. 엔드포인트 목록

최소 목록은 [plans/3주_개발_계획.md 7절](plans/3주_개발_계획.md). 구현되면 Swagger가 정본이고 7절은 갱신하지 않는다.

## 3. 상태 전이

- `recommendation`: `PENDING → APPROVED | REJECTED` 한 번만. 재변경 시도는 **409**. 다시 바꾸려면 새 추천 (3주 계획 8절)
- `(store_id, ingredient_id, run_date)` unique. 같은 날 같은 재료 추천 재생성 요청은 기존 건을 돌려준다

## 4. 아직 안 정한 것

BE 첫 엔드포인트 MR에서 `DECISIONS.md`에 번호를 달아 확정한다.

| 항목 | 후보 |
|---|---|
| ~~응답 래퍼~~ | **확정 (결정 #22)**: `{ "success": true, "data": {...}, "error": null }`. 코드는 `global.response.ApiResponse` |
| ~~오류 형식~~ | **확정 (결정 #22)**: `{ "success": false, "data": null, "error": { "code", "message", "fieldErrors"? } }` + HTTP 상태. code는 `<도메인>_<사유>` UPPER_SNAKE_CASE. 서비스는 `BusinessException(ErrorCode)`를 던지고 `GlobalExceptionHandler`가 변환한다 |
| ~~금액 타입~~ | **확정 (결정 #55)**: 대한민국 원(KRW) 정수, JSON 정수(예 `12000`). 판매가 · 단가 등 모든 금액. 소수 · 음수 요청은 400. 단, 단위 단가(원 / 기준 단위)와 가격 지수는 소수 넷째 자리 숫자 (결정 #57) |
| ~~수량 타입~~ | **확정 (결정 #42)**: 재료 기준 단위(`EA` · `G` · `ML`) 그대로, 소수 셋째 자리까지의 JSON 숫자(예 `6000.000`). 응답 직전 셋째 자리 반올림(5 이상 올림), 0 이 된 줄은 뺀다. 포장 단위 환산 없음 |
| 날짜 | `YYYY-MM-DD`, 시각은 ISO-8601 `+09:00` |
| ~~인증~~ | **확정 (결정 #21)**: JWT, access token은 `Authorization: Bearer` 헤더. refresh token은 응답 바디로 주고받는다 (결정 #43) |

## 5. 인증 · 공개 경로 · CORS

구현: `backend/.../global/security/SecurityConfig.java` · `backend/.../auth/` (결정 #21 · #43)

- 공개 경로 밖의 모든 요청은 `Authorization: Bearer <access token>` 헤더가 필요하다. 서버 세션 · 쿠키 인증은 없다
- 401 코드 (모두 4절의 공통 응답 형식)

  | code | 언제 | FE가 할 일 |
  |---|---|---|
  | `COMMON_UNAUTHORIZED` | 토큰 없음 | 로그인 화면 |
  | `AUTH_TOKEN_EXPIRED` | access token 만료 | `POST /auth/refresh` 후 원래 요청 재시도 |
  | `AUTH_INVALID_TOKEN` | 서명 · 형식이 틀린 토큰 | 로그인 화면 |
  | `AUTH_INVALID_REFRESH_TOKEN` | refresh token이 없거나 이미 씀 · 만료 · 로그아웃됨 | 로그인 화면 |
  | `AUTH_INVALID_CREDENTIALS` | 로그인 이메일 · 비밀번호 틀림 (둘을 구분하지 않는다) | 오류 문구 |

- 권한이 없으면 **403** `COMMON_FORBIDDEN`, 로그인한 계정에 연결된 가게가 없으면 **403** `AUTH_NO_STORE`
- 회원가입: `POST /auth/signup {email, password, storeName?, agreements: ["TERMS","PRIVACY","COLLECTION"]}` → **201** 로그인과 같은 `{accessToken, expiresIn, refreshToken}`. 계정 · 가게(시간대 Asia/Seoul) · OWNER 연결을 한 번에 만든다. storeName이 없거나 공백뿐이면 이메일 @ 앞부분이 가게 이름이다. 약관 셋 모두 필요하고 동의 기록은 저장하지 않는다. 400 `COMMON_INVALID_INPUT`(이메일 형식 · 255자 초과, 비밀번호 8자 미만 · UTF-8 72바이트 초과, storeName 100자 초과, 약관 누락 · 모르는 값), 409 `AUTH_EMAIL_TAKEN`(이미 가입된 이메일) (S15P31A308-204, 결정 #67). 시연 계정 이메일(`DEMO_ACCOUNT_EMAIL`)은 이미 가입돼 있어도 201 — 옛 계정은 이메일만 보관용으로 바뀌고 새 계정 · 새 가게가 생긴다 (결정 #98, ADR-0018)
- 가게의 오늘: `GET /stores/me` 의 `today`(가게 시간대의 오늘 `YYYY-MM-DD`). FE 는 기기 날짜 대신 이 값을 오늘로 쓴다. 시연 계정(`DEMO_ACCOUNT_EMAIL`)의 가게는 고정일(`DEMO_ACCOUNT_TODAY`, 기본 2026-11-12)이고 그 밖의 가게는 실제 날짜다 (ADR-0019)
- 가게 전체 주소: `GET /stores/me`의 nullable `address`는 선택한 네이버 플레이스의 서버 수집 주소다. `POST /onboarding/jobs`에서 서버가 보관한 검색 후보(30분/최대 500개) 또는 SEARCH replay의 일치하는 placeId로 저장하며 본문의 주소는 사용하지 않는다. 확인 가능한 주소가 없으면 null이다. 배송 판정용 `sigungu`와 분리하며 `PATCH /stores/me`로 전체 주소를 수정하지 않는다. 더보기 위치는 `address` → `sigungu` → `–` 순서다([ADR-0023](adr/ADR-0023-store-address.md)).
- 토큰 흐름: `POST /auth/login {email, password}` → `{accessToken, expiresIn, refreshToken}`. refresh token은 **한 번만** 쓸 수 있어 `POST /auth/refresh {refreshToken}` 응답의 새 값으로 바꿔 저장한다. `POST /auth/logout {refreshToken}`은 그 refresh token만 지운다(access token은 만료까지 유효하니 FE가 버린다)
- 인증 없이 열린 경로: Swagger(`/swagger-ui/index.html` · `/v3/api-docs`), `/actuator/health`, `/auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout`(access token이 만료돼도 로그아웃할 수 있게)
  - 배포 환경의 상태 확인: `/livez` · `/readyz`. actuator(`/actuator/health/**` · `/actuator/prometheus`)는 배포 환경에서 외부에 열리지 않는 관리 포트에 있다(ADR-0008)
- CORS 허용 오리진: 로컬 기본값 `http://localhost:3000`. 스테이징 · 운영은 환경 변수 `APP_CORS_ALLOWED_ORIGINS`(쉼표 구분)로 지정한다. 허용 헤더는 `Authorization` · `Content-Type`

## 6. 온보딩 · 레시피 (결정 #56)

모양은 FE 목(`frontend/src/lib/mock/onboarding.ts` · `recipes.ts`, `lib/types.ts`) 그대로다. 요청 · 응답 상세는 Swagger.

| 주소 | 하는 일 | 오류 |
|---|---|---|
| `POST /onboarding/jobs` `{placeId?, naverMapUrl?, includeYogiyo, placeName?}` | 가게 분석 job 을 만들고 `Job`(QUEUED)을 바로 준다. 진행은 `GET /jobs/{id}`. `placeName` 이 오면 가게 이름 · 화면 이름을 그 이름으로 바꾼다(가입 때는 이메일 앞부분) | 400 `ONBOARDING_PLACE_REQUIRED` · `ONBOARDING_PLACE_URL_INVALID` |
| `POST /onboarding/jobs/menu-photo` (multipart `file`, 이미지 10MB 이하) | 메뉴판 사진으로 새 분석 job. 사진은 저장하지 않는다 | 400 `ONBOARDING_PHOTO_REQUIRED` · `_NOT_IMAGE` · `_TOO_LARGE` |
| `GET /onboarding/recipe-questions` | 가장 최근 DONE 분석의 질문 `RecipeQuestionResponse[]` | 404 `ONBOARDING_JOB_NOT_FOUND` |
| `POST /onboarding/recipes/confirm` `RecipeAnswerRequest[]` | 답을 적용해 메뉴 · 재료 · 레시피 저장 → `RecipeBookResponse` | 400 `ONBOARDING_QUESTION_NOT_FOUND` · `ONBOARDING_ANSWER_INVALID`, 404 `ONBOARDING_JOB_NOT_FOUND` |
| `POST /onboarding/recipes/skip` | 추정값 그대로 저장 → `RecipeBookResponse` | 404 `ONBOARDING_JOB_NOT_FOUND` |
| `GET /onboarding/recipes` | 저장된 `RecipeBookResponse` | — |
| `PATCH /ingredients/{id}/serving` `{perServing}` | 재료 1인분 고치기. 그 재료의 레시피 줄을 모두 같은 비율로 고친다 → `RecipeBookResponse`. FE 목의 `PATCH /ingredients/{id}` 는 재료 일반 수정(이름 · 단위 등)이라 주소가 다르다 | 400 `COMMON_INVALID_INPUT`, 404 `COMMON_NOT_FOUND` |

- 분석 job 의 `result`: 수집이 끝나면(progress 0.3) `{"menus":[{id,name}]}`, DONE 이면 `{"menus":[{id,name,price,ingredientCount}],"needsMenuPhoto","draft","questions","extraction","classify"}`. 메뉴 0개면 `{"menus":[],"needsMenuPhoto":true}`. `extraction` · `classify` 는 채점용 LLM 원본 출력(ai `scripts/onboarding/score.py`)
- 분석 job 의 `error`: `APIFY_UNAVAILABLE` · `APIFY_NOT_CONFIGURED`(수집) · `LLM_UNAVAILABLE` · `LLM_FAILED` · `LLM_BAD_OUTPUT`(GMS). 다시 시도는 새 job
- 레시피 단위 표기는 `g | ml | 개` — DB 기준 단위 `G · ML · EA` 와 1:1, 수량 자릿수는 #42
- `RecipeBookResponse` 메뉴 `price` 는 원 정수(예 `9900`, #55). 모르면 null
- `RecipeBookResponse` 재료 `perServing` = 그 재료 레시피 줄 중 가장 작은 양, 메뉴 줄 `mult` = 양 / perServing(소수 셋째 자리). 더블 메뉴에만 쓰인 재료는 perServing 이 2배로 보인다
- 메뉴 CRUD 응답과 `RecipeBookResponse.menus[]`의 `photoUrl`은 대표 메뉴 사진 원본 HTTP(S) URL이며 없으면 `null`이다(#45 · #114). 사진 필드 없는 이전 분석 초안도 확정할 수 있고, 사진 없는 재확정은 저장된 사진을 지우지 않는다. 조회는 기존 메뉴와 동일하게 로그인한 가게 범위다.
- `estimated` = 사장님이 확인하지 않은 재료(`unit_status='ESTIMATED'`, 레시피 줄 `source='AI'`)

## 7. 재료 단가 이력 · 가격 지수

구현: `backend/.../price/` (S15P31A308-64). 계산 정의는 [plans/2026-10-15_가격지수_정의.md](plans/2026-10-15_가격지수_정의.md), 숫자 자릿수는 결정 #57

- `GET /ingredients/{id}/price-history` — 재료 하나에 연결된 이 가게 모든 거래처 상품의 단가 이력을 단위 단가(원 / 재료 기준 단위)로 합쳐 주별 중앙값 · 가격 지수와 함께 준다. 출처 `MARKET` 과 `base_qty_per_pack` 을 모르는 상품은 빠진다. 남의 가게 · 없는 · 숫자 아닌 id 는 404 `COMMON_NOT_FOUND`
- `weekly` 는 7일 칸 8개(마지막 칸 = 오늘 − 6일 ~ 오늘, 오늘 = 서울 날짜), 관측 없는 주는 `null`. `observations` 는 최근 56일, 날짜 내림차순, 최대 200건. 이력이 없으면 `weekly` 8칸 `null` · `observations` `[]` · `index.reason` `NO_RECENT`

```json
{"success": true, "error": null, "data": {
  "ingredientId": "12", "unit": "G",
  "weekly": [4.2, 4.2, 4.5, null, 4.4, 4.4, 4.6, 4.8],
  "observations": [{"observedOn": "2026-10-14", "unitPrice": 4.8, "source": "MANUAL", "supplierProductId": "3", "productName": "대파 1kg"}],
  "index": {"level": "HIGH", "index": 1.1429, "basePrice": 4.2, "currentPrice": 4.8, "sampleCount": 5, "reason": "OK"}
}}
```

실제 응답의 단가 · 지수는 소수 넷째 자리까지 채워 나온다(`4.8000`).

### 7.1 재료별 식자재몰 최저가 · 구매 링크 (S15P31A308-231 · 232 · 229)

구현: `backend/.../market/`. 시세는 크롤러 통합 데이터셋을 전역 표 `market_product` 에 **수집일마다 쌓은 것**(ADR-0010 · ADR-0012, 적재는 `backend/README.md` "식자재몰 시세 적재"). 정확한 품목 판정은 **재료 이름이 품목군(`market_item_group`) 별칭과 정확히 같으면 LLM 사전 분류 라벨(`source: "LABEL"`)**, 아니면 크롤러 `match.py` 와 같은 규칙(`source: "RULE"`) — 기준은 [품목군 분류 기준](plans/2026-11-03_식자재몰_품목군_분류_기준.md).

- 응답에 `itemGroup {code, name, baseUnit}`(매핑 없으면 null) · `source` · `ingredientUnit`(재료 기준 단위) · **`toIngredientUnitFactor`**(원/재료 단위 1 = `unitPrice × 계수`. 같은 단위면 1, 품목군 환산값으로 바꿀 수 없으면 null. 계란 EA → G 는 1/60) 이 있다. 발주 화면의 '기존 거래처 단가' 와 나란히 놓을 때 이 계수를 곱한다. 사이트마다 `collectedOn`(그 사이트 1위 상품의 수집일)이 있어 오늘 값이 아니면 화면이 표시할 수 있다
- LABEL 경로의 단가는 품목군 기준 단위로 환산한 값이다(30구 · 1.8kg(60g×30) 모두 원/개, 간장 1.9kg 은 밀도 1.2 로 원/ml). 총량은 크롤러 규격 해석 → 라벨이 상품명에서 읽은 수량 순. 환산값이 없는 단위(두부 2모)는 `otherUnits`
- 후보는 **사이트마다 가장 최근 수집일**의 줄만 쓴다(시세가 날마다 쌓여도 "지금 최저가" 는 최신 것)

- `GET /ingredients/{id}/offers` — 로그인한 가게의 재료 하나를 **이름**으로 시세에서 찾아 **정확히 같은 품목만**(계란 검색에 계란찜 · 깐 계란 · 계란 찜기 없음) 단가 순으로 준다. 남의 가게 · 없는 · 숫자 아닌 id 는 404 `COMMON_NOT_FOUND`
- `unit` = 품목군 기준 단위(없으면 정확한 상품이 가장 많은 단위 하나, `G` · `ML` · `EA`). `matchesIngredientUnit` 이 false 면(계란 재료는 `G`, 몰은 개 단위) `toIngredientUnitFactor` 로 바꿔 비교한다. `sites` = 그 단위의 사이트별 상위 3개(같은 상품번호는 가장 싼 점포 하나), 사이트는 1위 단가 순. 같은 단위 중앙값의 5배 밖은 이상치로 빼고 `excludedOutliers` 에 센다. `otherUnits` = 비교 단위에는 없고 다른 단위로만 파는 사이트의 1위
- 단가는 원 / 단위 1(`G` · `ML` 은 원/g · 원/ml, `EA` 는 원/개) 소수 넷째 자리(결정 #57). 판매가는 원 정수(#55). 정확한 상품이 없으면 `unit` null · `sites` `[]` · `otherUnits` `[]`
- **배달 가능 판매처**(S15P31A308-230 · 결정 #70 · ADR-0011): 가게 시군구(`storeSigungu`, `PATCH /stores/me { "sigungu": "서울 중구" }` 로 받는다. `"<시도> <시군구>"` 한글, 빈 글자면 지움, 형식 오류 400 `COMMON_INVALID_INPUT`)에 배달하지 않는 사이트 · 점포는 빼고 이름만 `notDelivering` 에 적는다. 배송 지역 정보가 없는 사이트(농협몰 · 오아시스마켓 · 그리팅몰 · 아워홈몰)는 그대로 나오되 `deliveryUnknown` 에 적는다(**'확인 필요'** 표시). 가게 시군구가 없으면 모든 사이트가 `deliveryUnknown`. 사이트마다 `delivery`(`AVAILABLE` · `UNKNOWN`) · `dawnDelivery`(새벽배송, 모르면 null)
- **가격 비교 켬/끔**(S15P31A308-205 · 결정 #48): `GET /stores/me` 의 `priceCompare`(기본 `true`), `PATCH /stores/me { "priceCompare": false }` 로 바꾼다(빠지거나 null 이면 그대로, true/false 가 아니면 400 `COMMON_MALFORMED_REQUEST`). 저장 · 조회만 하고 이 API 응답은 바꾸지 않는다 — FE 가 이 값을 보고 최저가를 숨긴다
- 배송 지역 시드는 데이터셋에서 아는 범위만: 식봄 = 서울 중구(새벽배송), 식자재왕몰 = 점포 소재 시군구(상일점 · 천호점 = 서울 강동구, 마포점 = 서울 마포구, 신월점 = 서울 양천구, 성남점 · 양주점 · 의정부점 · 평택신장점 · 호매실점(수원시) = 경기, 충주점 = 충북 충주시), 마켓컬리 = 전국. 식봄 · 쿠팡만 보여 주는 화면(결정 #48)은 `site` 로 거른다. 쿠팡은 수집하지 않는다(S15P31A308-122)

```json
{"success": true, "error": null, "data": {
  "ingredientId": "12", "query": "계란", "itemGroup": {"code": "EGG", "name": "계란", "baseUnit": "EA"}, "source": "LABEL",
  "unit": "EA", "matchesIngredientUnit": false, "ingredientUnit": "G", "toIngredientUnitFactor": 0.0166667,
  "collectedOn": "2026-10-27", "excludedOutliers": 1,
  "storeSigungu": "서울 중구", "deliveryUnknown": ["그리팅몰"], "notDelivering": ["식자재왕몰"],
  "sites": [{"site": "식봄", "bestUnitPrice": 189.7, "delivery": "AVAILABLE", "dawnDelivery": true, "collectedOn": "2026-10-26", "offers": [
    {"site": "식봄", "region": "서울 중구", "productName": "계란(중란/30알/국내산)판", "spec": "1판/30구 / 판", "price": 5690, "unit": "EA", "unitPrice": 189.7, "url": "https://www.foodspring.co.kr/goods/detail/1", "form": "RAW"}]}],
  "otherUnits": [{"site": "그리팅몰", "region": null, "productName": "[제주애월아빠들] 제주품은 동물복지 유정란 (15구) 750g", "spec": null, "price": 10500, "unit": "G", "unitPrice": 14.0, "url": "https://www.greating.co.kr/market/marketDetail?itemId=1"}]
}}
```

### 7.2 재료별 식자재몰 일별 최저가 추이 (S15P31A308-229)

- `GET /ingredients/{id}/market-price-history?days=30` — 최근 `days`일(오늘 포함, 기본 30 · 1~365 밖은 400 `COMMON_INVALID_INPUT`) 동안 **품목군 × 사이트 × 수집일**의 최저 단가를 사이트별 선으로 준다. 대상 · 단위 · 이상치 · 배달 규칙은 7.1 과 같고 날짜마다 적용한다. 수집이 없는 날은 점이 없다(0 으로 채우지 않는다). 사이트는 마지막 점 단가 순. 남의 가게 · 없는 · 숫자 아닌 id 는 404
- 발주 화면의 가격 블록은 7.1 의 `sites[].bestUnitPrice × toIngredientUnitFactor` 를 '기존 거래처' 와 나란히 놓고, 이 응답으로 플랫폼별 추이 선을 그린다

```json
{"success": true, "error": null, "data": {
  "ingredientId": "12", "query": "계란", "itemGroup": {"code": "EGG", "name": "계란", "baseUnit": "EA"}, "source": "LABEL",
  "unit": "EA", "ingredientUnit": "G", "toIngredientUnitFactor": 0.0166667, "from": "2026-10-02", "to": "2026-10-31",
  "storeSigungu": "서울 중구", "deliveryUnknown": ["그리팅몰"], "notDelivering": ["식자재왕몰"],
  "sites": [{"site": "식봄", "delivery": "AVAILABLE", "dawnDelivery": true, "points": [
    {"date": "2026-10-26", "unitPrice": 199.6667, "price": 5990, "productName": "계란(대란/30알/국내산)판"},
    {"date": "2026-10-27", "unitPrice": 189.6667, "price": 5690, "productName": "계란(중란/30알/국내산)판"}]}]
}}
```

## 8. 영수증 (S15P31A308-111 · 112 · 113)

모양은 업로드 API 제안(`docs/plans/2026-10-19_영수증_업로드_API_제안.md`) · 확인 목록 API 제안(`2026-10-19_영수증_확인목록_API_제안.md`)에서 시작했고, 제안에서 달라진 점은 두 제안 문서 머리에 있다. 화면 쪽 타입은 `frontend/src/lib/types.ts` 의 `ReceiptBoard`. 바꾸는 주소는 모두 바뀐 뒤의 board(= `GET /receipts` 의 `data`)를 준다. 요청 · 응답 상세는 Swagger.

| 주소 | 하는 일 | 오류 |
|---|---|---|
| `GET /receipts` | board — 올린 장 · 확인 묶음 · 재료 선택지 · 확정만 남은 영수증. job 이 도는 동안 2초마다 부른다 | — |
| `POST /receipts` (multipart `files` 여러 개) | 장마다 문서를 만들고 job 하나로 읽는다. **202** + board(새 장 `st: wait`) | 400 `RECEIPT_FILE_REQUIRED` · `_TOO_MANY_FILES`(51장 이상) · `_FILE_TOO_LARGE`(장당 10MB) · `_FILE_NOT_SUPPORTED`(JPG · PNG · PDF 밖) · `COMMON_INVALID_INPUT`(요청 20MB 초과), 503 `RECEIPT_OCR_NOT_CONFIGURED` |
| `GET /receipts/{docId}` | 한 장 = `files[]` 칸 + `supplierName` + `lines[]`(`id` · `lineNo` · `rawName` · `qty` · `unit` · `unitPrice` · `amount` · `ingredientId` · `confidence` · `status`) | 404 `COMMON_NOT_FOUND` |
| `POST /receipts/{docId}/retry` | `st: fail` 인 장을 새 job 으로 다시 읽는다(줄 지우고 같은 원본) → board | 404 `COMMON_NOT_FOUND`, 409 `RECEIPT_NOT_FAILED`, 503 `RECEIPT_OCR_NOT_CONFIGURED` |
| `DELETE /receipts/{docId}` | 확인 전 장의 문서 · 줄 행을 지운다 → board | 404 `COMMON_NOT_FOUND`, 409 `RECEIPT_ALREADY_CONFIRMED`(사장님이 정한 줄 있음 · 확정됨) · `RECEIPT_IN_PROGRESS`(읽는 중) |
| `POST /receipts/matches/{matchId}/confirm` `{choice, basePerPack?}` | 같은 상품명 확인 전 줄 전부 확정(`choice` = 재료 id · `cycle` · `ignore`) → board + `gain` + `skippedLineIds` | 400 `RECEIPT_INGREDIENT_REQUIRED` · `_UNIT_REQUIRED` · `_UNIT_INVALID`, 404 `RECEIPT_MATCH_NOT_FOUND` · `COMMON_NOT_FOUND`(재료), 409 `RECEIPT_DATE_UNKNOWN` |
| `POST /receipts/lines/confirm` `{lines: LineDecisionRequest[]}` | 줄 단위 확정(`action` = `INGREDIENT` · `CYCLE` · `IGNORE`) → board + `confirm` | 400 `RECEIPT_LINE_REQUIRED` · `_LINE_DUPLICATE`, 404 `COMMON_NOT_FOUND`, 409 `RECEIPT_LINE_ALREADY_DECIDED` |
| `POST /receipts/docs/confirm` `{docIds}` | 줄이 모두 자동 확정된 영수증(`readyDocs`)을 확정 — 이때 재고에 들어간다(#41) → board + `confirm` | 400 `RECEIPT_DOC_REQUIRED`, 404 `COMMON_NOT_FOUND`, 409 `RECEIPT_DOC_NOT_PARSED` · `RECEIPT_DATE_UNKNOWN` · `RECEIPT_DOC_HAS_PENDING` |

- 영수증 확정(RECEIVE 문서)이 발주서에 잇는 줄: 아직 입고 안 된 줄 먼저, 없으면 자동 입고된 줄(`auto_received`) 중 예정일이 문서 날짜 ±3일 안인 것(예정일이 가까운 순). 자동 입고 줄에 이어지면 받은 양 · 단가 · 시각을 영수증 값으로 덮고 `auto_received=false` 로 바꾼다 — 같은 배송이 재고에 두 번 들어가지 않는다(결정 #84). 문서 줄 전부가 한 발주서에 맞지 않으면 맞는 줄만 줄 단위로 잇는다 — 이어진 줄은 발주 줄로, 나머지는 영수증으로 한 번씩만 재고에 들어간다(결정 #86). 응답 필드 변경 없음
- board: `{jobId, files, matches, options, readyDocs}`. `jobId` = 가장 최근 영수증 job(없으면 null)
- `files[]`: `{id, file, at, st, kind, docDate, lineCount, error}`. CONFIRMED 가 아닌 장 전부 + CONFIRMED 는 올린 지 7일 안, id 오름차순. `file` 은 올린 파일 이름(모르면 null)
- `st`: `wait` 대기 · `read` 읽는 중 · `done` 다 읽음 · `fail` 실패. job 이 끝났는데 읽기 전에 멈춘 장(서버 재시작 등)도 `fail`, `error` 는 job 사유 또는 `RECEIPT_INTERRUPTED`
- `matches[]`: 확인 목록 제안 2절 모양. **`id` 는 상품명 UTF-8 의 base64url(패딩 없음)** — 상품명에 `/` 가 있어도 주소에 넣을 수 있게. 화면 글자는 `name`
- `readyDocs[]`: `{docId, kind, date, lines}`. `gain` 은 고른 재료 기준 단위(#42)
- CLOVA 키가 없을 때(가짜 글자 인식) 올리기 · 다시 읽기는 `RECEIPT_ALLOW_FAKE_OCR=true` 일 때만 열린다. 기본은 503 `RECEIPT_OCR_NOT_CONFIGURED`
- 요청 한 번은 20MB 까지(서버 업로드 상한). 더 많으면 화면이 나눠 여러 번 보낸다(요청마다 job 하나)
- 지워도 원본 파일은 서버에 남는다(#34 — 탈퇴 · 서비스 종료 때만 삭제)

## 9. 입고 탭 · 입고 확인

구현: `backend/.../receiving/` (S15P31A308-71). 근거 [plans/2026-10-19_입고_확인_API_제안.md](plans/2026-10-19_입고_확인_API_제안.md). 4절 질문 4개 중 1번 단위는 제안(포장 단위)을 버리고 결정 #42 대로 **기준 단위**, 2~4번은 제안값(주소 그대로 · 최근 14일 50줄 · 이미 입고 = 409)으로 확정했다

- **수량은 재료 기준 단위(개 · g · ml), 단가는 단위 단가**(#42 · #57): 수량은 소수 셋째 자리, 단가는 원 / 기준 단위 1 소수 넷째 자리 JSON 숫자. 포장 개수 · 포장 1개 값으로 바꿔 주지 않는다. DB 칸(`received_qty` = 포장 개수, `received_unit_price` · `price_history.price` = 포장 1개 원 정수 #55)은 서버가 `supplier_product.base_qty_per_pack`(포장 크기)으로 바꿔 쓴다
- 포장 크기를 모르는 상품(`basePerPack: null`)은 기준 단위 양을 낼 수 없어 `qty` · `unitPrice` · `finalQty` · `receivedQty` 가 null 이다(포장 개수를 기준 단위 양처럼 내지 않는다). 입고 확인 때 `basePerPack` 을 같이 보내면 상품에 채워진다

### GET /receiving

입고 예정 = 취소 안 된(`cancelled_at` 없음) · 보낸(`SENT`) 발주서의 · 아직 입고 확인 안 된 줄(보내지 않은 `DRAFT` 발주서는 빠진다, 결정 #63)(도착 예정일 순, 없으면 끝). 최근 발주 = 발주서를 만든 날이 최근 14일(오늘 포함, 가게 시간대)인 줄, 최대 50줄, 새 발주부터. `history.status`: `DRAFT` · `SENT` · `RECEIVED` · `CANCELLED`(줄 안 옴 또는 발주서 취소). `firstReceiptDone` = 입고 확인된 발주 줄이나 확정된 입고 영수증이 하나라도 있음

```json
{
  "incoming": [
    { "orderId": "31", "itemId": "77", "ingredientId": "12", "name": "토마토 캔",
      "supplier": "OO상회", "packLabel": "3000 g 캔", "unit": "g", "basePerPack": 3000.000,
      "qty": 12000.000, "unitPrice": 3.9000, "expectedDate": "2026-10-20", "today": false }
  ],
  "history": [
    { "orderId": "30", "itemId": "70", "date": "2026-10-16", "name": "생크림 1L", "unit": "ml",
      "recommendedQty": 6000.000, "finalQty": 5000.000, "supplier": "OO상회",
      "status": "RECEIVED", "receivedQty": 5000.000, "receivedAt": "2026-10-18T09:10:00+09:00" }
  ],
  "firstReceiptDone": true
}
```

- 식봄 · 쿠팡 발주서(거래처 없음)의 줄도 `SENT` 가 되면 들어오고 `supplier` 는 "식봄" · "쿠팡"(#90)
- `unit` 은 재료 기준 단위 표기 `g` · `ml` · `개`(6절과 같음), `packLabel` 은 `"3000 g 캔"` 꼴(포장 이름이 없으면 null), `basePerPack` 은 포장 1개 = 기준 단위 몇
- `incoming.qty` = 주문 포장 수 × `basePerPack`, `incoming.unitPrice` = 거래처 상품 포장 1개 값 ÷ `basePerPack`(소수 넷째 자리)
- `history.recommendedQty` = 그 줄을 만든 추천의 추천량(기준 단위 그대로, 수동 주문은 null), `finalQty` · `receivedQty` = 포장 수 × `basePerPack`(입고 전이면 `receivedQty` null)

### POST /purchase-orders/from-recommendations

승인한 추천을 발주서(`DRAFT`)로 만든다. 주문처 선택(#64)을 받는 부분이 S15P31A308-201(#90)이다. 요청 본문은 생략 가능

```json
{ "items": [ { "recommendationId": "12", "source": "SIKBOM" }, { "recommendationId": "13" } ] }
```

- `items` = 추천 하나와 그 줄의 주문처 `source`(`SUPPLIER` · `SIKBOM` · `COUPANG`, 생략하면 `SUPPLIER`). 주면 그 추천만 발주서로 만든다. `recommendationIds` · `date` 는 그대로고, `items` 와 `recommendationIds` 를 같이 주면 400 `COMMON_INVALID_INPUT`. `items` 안 같은 `recommendationId` 두 번 → 400 `PURCHASE_ORDER_RECOMMENDATION_DUPLICATE`, 알 수 없는 `source` → 400. `items` 를 빈 목록(`[]`)으로 보내면 400 `COMMON_INVALID_INPUT`. `items` 없이 `recommendationIds` 만 · 본문 없음은 전부 `SUPPLIER` 로 예전과 같다
- 발주서 장수: `SUPPLIER` 줄은 거래처별 한 장, `SIKBOM` 줄은 한 장, `COUPANG` 줄은 한 장(응답 순서 = 거래처 id 순, 식봄, 쿠팡). 검사에 하나라도 걸리면 한 장도 안 만든다
- 응답 발주서에 `source` 가 더해지고, 식봄 · 쿠팡 발주서는 `supplierId` · `supplierName` 이 null(이름은 FE 가 `source` 로 표시). 품목 줄에 `unitPrice`(포장 1개 상당 원 정수, nullable) · `productUrl`(식봄 · 쿠팡 줄 링크, nullable) 이 더해진다
- `unitPrice`: `SUPPLIER` = 요청 순간의 상품 `price`(없으면 null). `SIKBOM` = 서버가 식봄 1위 단가(`/ingredients/{id}/offers` 의 식봄 `bestUnitPrice`) × `toIngredientUnitFactor` × 대표 상품 `base_qty_per_pack`(없으면 1) 을 원 정수로 반올림(HALF_UP). 클라이언트가 가격을 보내지 않는다. `COUPANG` = null(쿠팡 가격은 수집하지 않는다, #68)
- 메시지: `SUPPLIER` 는 거래처 템플릿 그대로. `SIKBOM` · `COUPANG` 은 줄마다 `- 재료이름 포장수단위 — 링크`(식봄 = 1위 상품 url, 쿠팡 = 재료 이름 쿠팡 검색 주소). 입고 예정일은 오늘 + 1일
- 수량은 예전처럼 승인 수량을 대표 상품 포장 단위로 올림한 포장 수. 가격 비교를 끈 가게가 `SIKBOM` 을 보내도 BE 는 막지 않는다(FE 책임, #48)
- 오류: 식봄 상품이 없거나 재료 단위로 바꿀 수 없는 재료를 `SIKBOM` 으로 → 409 `PURCHASE_ORDER_SOURCE_PRICE_UNSET`(같은 요청의 다른 줄도 발주서가 안 생긴다). 그 밖은 404 `COMMON_NOT_FOUND`(없는 · 남의 가게 추천) · 409 `PURCHASE_ORDER_NOT_APPROVED` · `PURCHASE_ORDER_ALREADY_ORDERED` · `PURCHASE_ORDER_PRODUCT_UNSET` 그대로
- 식봄 · 쿠팡 발주서에 나중에 영수증을 올리는 연결은 하지 않는다(거래처 id 가 없어 이어지지 않는다 — 후속)

### POST /purchase-orders/{id}/send

주문 메시지에서 복사 · 보냄 · 주문 완료를 누른 발주서를 보낸 것으로 기록한다(결정 #63, S15P31A308-200). 본문 없음. `DRAFT` → `SENT` 와 `sentAt` 기록, 응답 200 = 그 발주서. 이미 `SENT` 면 바꾸지 않고 200(`sentAt` 은 처음 보낸 시각). `CANCELLED` 는 409 `PURCHASE_ORDER_CANCELLED`, 없는 id · 다른 가게 발주서는 404. 보낸 뒤부터 입고 예정 · 추천의 입고 예정량(onOrder)에 들어간다

### GET /purchase-orders/history?month=YYYY-MM

발주 이력 탭(S15P31A308-202, #62)의 한 달치. 구현: `backend/.../purchaseorder/`. `month` 생략 = 가게 시간대 이번 달. 달 경계는 가게 시간대 1일 00:00 이상 · 다음 달 1일 00:00 미만. 발주일 = 발주서 `sent_at`, 없으면 `created_at`. 보내지 않은 DRAFT 발주서는 넣지 않는다(#63). 발주일 내림차순, 같은 날이면 줄 id 내림차순, 페이지 없음

```json
{
  "month": "2026-10",
  "lines": [
    { "date": "2026-10-14", "orderId": "31", "itemId": "77", "ingredientName": "토마토",
      "supplierName": "한빛농산", "qty": 12000.000, "unit": "g", "amount": 46800, "status": "SENT" }
  ],
  "totalAmount": 46800,
  "earliestMonth": "2026-09"
}
```

- `qty` = 기준 단위(#42). 입고 확인한 줄은 받은 포장 수 × `base_qty_per_pack`, 아니면 주문 포장 수 × `base_qty_per_pack`. 포장 크기를 모르면 `qty` 는 null(포장 개수를 기준 단위 양처럼 내지 않는다), `unit` 은 그래도 재료 기준 단위. `amount` 는 포장 수 × 포장 가격이라 포장 크기와 무관하게 계산한다
- `amount` = 원 정수(#55, 반올림). 입고 확인한 줄은 받은 포장 수 × `received_unit_price`, 아니면 주문 포장 수 × 발주서를 만들 때 저장한 줄 단가 `unit_price`(#90, 가격이 바뀌어도 그대로. 거래처 옛 줄은 null 이면 거래처 상품의 지금 `price`). 쿠팡 줄 · 가격을 모르면 null. 식봄 · 쿠팡 발주서 줄의 `supplierName` 은 "식봄" · "쿠팡"
- `status`: 줄 취소 · 발주서 취소 → `CANCELLED`, 입고 확인 → `RECEIVED`, 그 밖은 `SENT`
- `totalAmount` = `CANCELLED` 를 뺀 `amount` 합(null 은 0), `earliestMonth` = DRAFT 를 뺀 가장 이른 발주의 달(없으면 null)
- `month` 형식 틀림 · 없는 달(`2026-13` · `2026-1` · `abc`) → 400 `COMMON_INVALID_INPUT`, 토큰 없음 → 401

### PATCH /purchase-orders/{orderId}/items/{itemId}/receive

```json
{ "receivedQty": 12000, "unitPrice": 3.9, "basePerPack": 3000 }
```

- `receivedQty`: 받은 양, 기준 단위, 0 보다 크고 소수 셋째 자리까지(#42)
- `unitPrice`: 단위 단가(원 / 기준 단위 1), 0 이상 소수 넷째 자리까지(#57). 0 · 생략 = 거래처 상품 단가 그대로
- `basePerPack`: 포장 1개 = 기준 단위 몇. 거래처 상품의 포장 크기가 비어 있을 때만 필요하고, 받으면 그 상품에 채운다(같은 트랜잭션). 포장 크기가 이미 있으면 쓰지 않는다
- 서버 환산: 포장 개수 = `receivedQty` ÷ 포장 크기, 소수 셋째 자리 반올림(5 이상 올림). 포장 1개 값 = `unitPrice` × 포장 크기, 원 정수 반올림. 추정 재고는 포장 개수 × 포장 크기를 더하므로 나누어떨어지지 않으면 최대 포장 크기 × 0.0005 만큼 어긋난다(3000 g 캔에 1000 g → 0.333캔 → 999 g)

| 경우 | 응답 |
|---|---|
| 정상 | 200 + `GET /receiving` 과 같은 모양(바뀐 피드 전체) |
| `receivedQty` 없음 · ≤ 0 · 소수 넷째 자리 이상 | 400 `COMMON_INVALID_INPUT` |
| `unitPrice` 음수 · 소수 다섯째 자리 이상, `basePerPack` ≤ 0 · 소수 넷째 자리 이상 | 400 `COMMON_INVALID_INPUT` |
| 환산 결과 포장 개수가 0 으로 반올림됨 · 포장 1개 값이 0원 또는 칸(정수 10자리)을 넘음 | 400 `COMMON_INVALID_INPUT` (`fieldErrors` 없음) |
| 포장 크기를 모르는 상품인데 `basePerPack` 없음 | 400 `RECEIVING_UNIT_REQUIRED` (아무것도 안 씀) |
| 끝자리 0 | 무시한다 — `12000.000` = `12000`, `3.90` = `3.9`, `1e3` = `1000`, `0E+10` = `0` (숫자 값이 같으면 판정도 같다. 진짜 소수 넷째 자리 `1.2345` 수량 · 다섯째 자리 `3.90001` 단가는 400) |
| 숫자 길이 | 1000자(자리)까지. 넘으면 400 `COMMON_MALFORMED_REQUEST`(본문 읽기 단계) |
| 이미 입고 확인된 줄 | 409 `RECEIVING_ALREADY_RECEIVED` (덮어쓰기 없음) |
| 취소된 줄 · 취소된 발주서 | 409 `RECEIVING_CANCELLED` |
| 남의 가게 · 없는 · 숫자 아닌 id, 줄이 그 발주서 것이 아님 | 404 `COMMON_NOT_FOUND` |

한 트랜잭션: `received_qty` · `received_unit_price` · `received_at` 기록 → 재고는 따로 쓰지 않음(추정 재고가 이미 더함, #41 ①) → `price_history` `source=RECEIVE` 1행(포장 1개 단가, 단위 = 포장 이름 · 모르면 `PACK`, 단가를 안 바꿔도 1행. 단가도 거래처 단가도 없으면 안 씀) → 그 재료의 첫 입고면 `unit_status = LOCKED`. 영수증 줄 확정(112)은 주소 없이 같은 서비스 메서드(`ReceivingService.recordReceipt`)를 부른다 — 이 메서드는 DB 칸 단위(포장 개수 · 포장 1개 원 정수)를 그대로 받고, 값이 틀리면 `COMMON_INVALID_INPUT`(숫자 1000자리 넘음 포함)

## 10. 재고 실사 (S15P31A308-47)

구현: `backend/.../inventory/` (`StockCountController` · `StockCountService`). 근거 [3주 계획 4.2절](plans/3주_개발_계획.md) · 결정 #7(도입 당일 실사 1회가 추정 재고의 시작값) · #9(음수 보정 + 실사 필요 플래그).

### POST /stock-counts

```json
{ "countedAt": "2026-10-14T09:30:00+09:00",
  "counts": [ { "ingredientId": "12", "qty": 4250.5 },
              { "ingredientId": "13", "qty": 300, "estimatedQty": 500, "method": "CORRECTED" } ] }
```

- `counts[].qty`: 지금 실제로 남은 양, 재료 기준 단위(개 · g · ml), 0 이상 소수 셋째 자리까지(#42). 0 = 다 썼다. 포장 단위로 받지 않는다
- `counts[].estimatedQty` · `method`: 실사 때 화면에 보여 준 추정치와 사장님 답(`CONFIRMED` 맞아요 · `CORRECTED` 아니요 실제는 N · `FULL_COUNT` 직접 다 셈, 기본값). `CONFIRMED` · `CORRECTED` 는 `estimatedQty` 필수 — 추정치 대 답이 쌓여야 레시피 역산 보정(설계 2절)을 할 수 있다. `FULL_COUNT` 로 `estimatedQty` 를 비우면 서버가 직전 추정치로 채운다(#93) — 실사 차이가 반복되는 재료는 발주 추천 안전재고가 오른다
- `countedAt`: 보통 생략한다(지금). 미래(시계 오차 2분 허용)거나 그 재료의 기존 최신 실사보다 이르면 400 — 이른 실사는 넣어도 기준점이 되지 않아 아무 일도 안 일어난 것처럼 보이기 때문. 화면을 연 시각 같은 오래된 값을 보내지 않는다(그 뒤 입고 확인된 줄이 다시 더해진다). 한 번에 최대 200줄
- **실사는 영업 시작 전 값이다.** 판매는 날짜 단위라 추정 재고는 실사한 날의 판매량부터 뺀다(입고 확인은 실사 시각 뒤 것만). 마감 뒤에 셌으면 다음 날 영업 전에 입력한다. 마감 확인(12절)으로 넣은 실사는 마감 뒤 값으로 다음 날 판매부터 뺀다(#92)
- 주기형(`CYCLE`) 재료는 받지 않는다(400) — 추정 재고가 입고 기록으로만 계산해 실사값을 쓰지 않는다(설계 5.1절)
- 행은 덮어쓰지 않고 쌓인다. **가장 최근 실사가 기준점**이다 — 그 재료의 추정 재고는 이 값에서 다시 시작하고 `needsCount` 가 꺼진다(음수로 내려가 0 보정되면 다시 켜짐), `/stores/me` 체크리스트의 `STOCK_COUNT` 가 끝남, 영수증 날짜가 이 시각 이전이면 PAST(재고 미반영) · 이후면 RECEIVE (설계 4.2절)

| 경우 | 응답 |
|---|---|
| 정상 | **201** + `GET /inventory/estimate` 와 같은 모양(바뀐 추정 재고 전체, 재료 id 오름차순) |
| 빈 목록 · 줄이 null · 201줄 이상 · 같은 재료 두 번 · `qty` 없음 · 음수 · 소수 넷째 자리 이상 · `countedAt` 미래 · 기존 최신 실사보다 이름 · 주기형 재료 · `CONFIRMED`/`CORRECTED` 인데 `estimatedQty` 없음 | 400 `COMMON_INVALID_INPUT` |
| `method` 가 모르는 값 · `countedAt` 에 시간대 없음 | 400 `COMMON_MALFORMED_REQUEST` (본문 읽기 단계) |
| 끝자리 0 | 무시한다 — `1.2000` = `1.2` (입고 확인 9절과 같은 규칙) |
| 다른 가게 · 없는 · 숫자 아닌 재료 id 가 하나라도 있음 | 404 `COMMON_NOT_FOUND` (아무것도 기록하지 않음) |

한 트랜잭션: 재료가 전부 내 가게 것인지 확인 → 주기형 · 기존 최신 실사보다 이른 시각 거름 → `stock_count` 줄마다 INSERT → 바뀐 추정 재고를 읽어 돌려준다.

알아둘 것: 영수증 PAST · RECEIVE 경계는 가게 전체의 **가장 늦은 실사일** 하나다(`ReceiptAnalyzer`). 재료 일부만 실사해도 그 날짜가 경계가 되므로, 실사하지 않은 재료의 그 이전 날짜 영수증은 PAST(재고 미반영)로 분류된다. 도입 당일 핵심 재료를 한 번에 실사하는 흐름(#7)을 전제로 한다.

## 11. 재료 카테고리 (S15P31A308-273 · 274 · 275)

구현: `backend/.../ingredient/` (`IngredientCategoryController` · `IngredientCategoryService` · `IngredientCategoryClassifier`). 기준 [분류 기준 문서](plans/2026-11-06_재료_카테고리_분류_기준.md), 결정 #77 · #78 · #82. 요청 · 응답 상세는 Swagger(`IngredientCategory` 태그)가 정본.

- 카테고리는 **가게별 표**다. 기본 11종(`code` MEAT · SEAFOOD · VEGETABLE · FRUIT · EGG_DAIRY · GRAIN_NOODLE · TOFU_BEAN · SAUCE · PROCESSED · BEVERAGE · OTHER)은 가게가 생길 때 들어가고, 사장님이 만든 것은 `code` 가 `null`. 이름은 바꿔도 `code` 는 그대로라 '기타' 판별 · 아이콘은 `code` 로
- 재료 응답(`GET /ingredients` · `/ingredients/{id}`)의 `category {id, name, code}`. 재료가 생기면 이름으로 자동 분류(재료표 · 품목군 표 → LLM → 기타, 274)하고, 사장님이 고른 카테고리(`category_source = OWNER`)는 자동 분류가 덮어쓰지 않는다

| 요청 | 하는 일 | 응답 · 오류 |
|---|---|---|
| `GET /ingredient-categories` | 로그인한 가게의 카테고리 전부(`sortOrder` → id 순) + 각각의 `ingredientCount` | 200 `[{id, name, code, sortOrder, ingredientCount}]` |
| `POST /ingredient-categories` `{name}` | 새 카테고리. 순서(`sortOrder`)는 기타를 뺀 마지막 다음(최대 98) — 기타(99)는 언제나 맨 뒤. 이름은 앞뒤 공백을 자르고 가게 안에서 공백 · 대소문자를 뺀 채 견준다 | 201 · 빈 이름 400 `COMMON_INVALID_INPUT` · 같은 이름 409 `INGREDIENT_CATEGORY_DUPLICATE_NAME` · 가게당 30개(기본 포함) 넘침 400 `INGREDIENT_CATEGORY_LIMIT_EXCEEDED` |
| `PATCH /ingredient-categories/{id}` `{name?, sortOrder?}` | 보낸 칸만. `sortOrder` 는 0~98. 기본 카테고리 이름도 바꿀 수 있다(코드 유지) | 200 · 기타 이름 변경 400 `INGREDIENT_CATEGORY_OTHER_IMMUTABLE`(기타의 `sortOrder` 는 99 고정, 보내도 무시) · 같은 이름 409 |
| `DELETE /ingredient-categories/{id}` | 그 카테고리의 재료를 전부 기타로 옮기고(`category_source = AUTO` — 자동 분류가 다시 볼 수 있게) 지운다. 한 트랜잭션 | 200 `data: null` · 기타 삭제 400 `INGREDIENT_CATEGORY_OTHER_IMMUTABLE` |
| `POST /ingredient-categories/auto-assign` | 자동 분류로 기타인 재료만 다시 분류 (274) | 200 `{assignedCount, otherCount}` |
| `PATCH /ingredients/{id}` `{categoryId}` | 재료의 카테고리를 고른다 → `category_source = OWNER` | 200 재료 · 다른 가게 · 없는 · 숫자 아닌 카테고리 id 404 `COMMON_NOT_FOUND` (재료는 그대로) |
| `POST /ingredients` `{…, categoryId?}` | 보내면 그 카테고리(OWNER)로 만들고 자동 분류를 건너뛴다 | 201 · 카테고리 id 404 면 재료를 만들지 않는다 |

다른 가게 · 없는 · 숫자 아닌 카테고리 id 는 전부 404 `COMMON_NOT_FOUND` (재료 API 와 같은 규칙). 인증 없으면 401. 만들기 · 이름 바꾸기는 가게 행을 잠그고 하므로 같은 가게의 동시 요청이 30개 상한이나 공백만 다른 이름을 비켜 가지 않는다.

## 12. 마감 재고 확인 (S15P31A308-244)

결정 #88(마감 웹 푸시 · 재고 확인 · 손실 기록) · #92(스키마 V17, 마감 확인 실사). 요청 · 응답 상세는 Swagger(`ClosingCheck` 태그)가 정본.
화면은 `/closing-check` (S15P31A308-255).

### GET /closing-check/items (S15P31A308-249)

오늘 마감 때 확인할 재료 0~5개(#94). 응답 줄 `{ingredientId, name, estimatedQty, baseUnit, packUnit, baseQtyPerPack, estimatedPacks, step, reason}`.

- 대상: 주기형 · 오늘 이미 실사한 재료는 빼고, `reason` ① `NEEDS_COUNT` 실사 필요 ② `REPEATED_LOSS` 실사 차이 반복(#93) ③ `RUNNING_OUT` 소진 예상 3일 이내 ④ `STALE_COUNT` 마지막 실사 7일 넘음 — 이 순서. 같은 순위 안에서는 포장 단위 없는 재료가 뒤로
- 포장: 가장 최근 입고 상품(없으면 대표 상품)의 `pack_unit` · `base_qty_per_pack` 이 둘 다 있으면 `estimatedPacks` = 추정 ÷ 포장 크기를 0.5 단위 반올림(1450 g · 1망=1000 g → 1.5), `step` 0.5. 아니면 셋 다 null 이고 `step` 1(기준 단위)
- 화면은 포장 수 × `baseQtyPerPack` 로 기준 단위를 만들어 `POST /closing-check` 에 보낸다

### 마감 시각 · 웹 푸시 구독 (S15P31A308-248)

#95. 요청 · 응답 상세는 Swagger(`Store` · `Push` 태그).

| 요청 | 하는 일 | 응답 · 오류 |
|---|---|---|
| `GET /stores/me` | 기존 응답에 `closingTimes: [{dayOfWeek, closeTime}]`(요일 순, 빠진 요일 = 휴무 · 미설정) | 200 |
| `PUT /stores/me/closing-times` `{closingTimes: [{dayOfWeek: 1~7(월=1), closeTime: "HH:mm"}]}` | 전체를 바꾼다. 빠진 요일은 지운다. 자정을 넘는 마감은 영업을 시작한 요일에(05:00 전 시각 = 다음 날 새벽) | 200 `GET /stores/me` 모양 · 같은 요일 두 번 · 범위 밖 · 형식 오류 400 `COMMON_INVALID_INPUT` |
| `POST /push-subscriptions` `{endpoint, keys: {p256dh, auth}}` | 브라우저 구독 등록. 같은 endpoint 면 가게 · 계정 · 키 갱신 | 200 `data: null` · https 푸시 서비스(FCM · Mozilla · Apple · Windows) 아님 · 키 길이(65 · 16바이트) 오류 400 |
| `DELETE /push-subscriptions` `{endpoint}` | 내 가게의 그 구독 삭제. 없으면 그대로 200 | 200 `data: null` |

알림: 5분마다, 그날 마감 시각이 지나고 3시간 안 · 휴무일 아님 · 그날 아직 안 보냄 · 확인 대상 재료(`GET /closing-check/items`)가 1개 이상인 가게에 구독마다 1건. 본문 JSON `{title: "오늘 재고 확인", body: "확인할 재료 N개", url: "/closing-check"}` — 서비스워커(S15P31A308-254)가 띄우고 클릭하면 `url` 을 연다. VAPID 키(`VAPID_PUBLIC_KEY` · `VAPID_PRIVATE_KEY` · `VAPID_SUBJECT`)가 없으면 보내지 않는다.

### POST /closing-check (S15P31A308-252)

구현: `backend/.../closingcheck/` (`ClosingCheckController` · `ClosingCheckService`).

```json
{ "counts": [ { "ingredientId": "12", "qty": 1500, "estimatedQty": 1450, "method": "CORRECTED" } ],
  "adjustments": [ { "ingredientId": "13", "qty": 1000, "reason": "SPOILED", "memo": "냉장고 고장" } ],
  "sourceText": "우유 한 팩 상했어" }
```

- `counts`: 남은 양(실사). 10절 `POST /stock-counts` 의 줄과 같은 모양 · 같은 검사. `estimatedQty` 를 생략하면 서버가 **빼기까지 반영한 지금 추정 재고**를 넣는다(말한 손실이 실사 차이로 또 잡히지 않게)
- `adjustments`: 버린 양(빼기 기록, `stock_adjustment`). `qty` 는 0 보다 큰 기준 단위 양, `reason` 은 `DISCARD` 폐기 · `SPOILED` 상함 · `DAMAGED` 파손 · `SPILLED` 쏟음 · `OTHER` 기타. 같은 재료 여러 줄 가능. 추정 재고보다 많이 버려도 받고 남은 양은 0
- `sourceText`: 자유 입력 원문. 빼기 줄마다 `source_text` 로 남는다(2000자까지)
- 둘 중 하나는 있어야 한다

한 트랜잭션: 재료가 전부 내 가게의 주기형 아닌 재료인지 확인 → 빼기를 **지금 − 1초**로 넣고(`before_qty` = 지금 추정 재고, `after_qty` = max(0, before − qty)) → 실사를 **지금**, `counted_after_close = true` 로 넣는다 → 바뀐 추정 재고 전체를 돌려준다.

- 같은 재료에 빼기와 실사가 함께 오면 **실사가 최종값**이다 (실사가 1초 늦어 빼기는 기준점에 흡수된다)
- 추정 재고 = … − Σ 빼기(마지막 실사 이후). 실사가 들어오면 그 앞 빼기는 기준점에 흡수된다
- 여기서 넣은 실사는 **마감 뒤 값**이라 추정 재고가 다음 날 판매부터 뺀다(#92). 10절의 실사는 지금처럼 영업 전 값

| 경우 | 응답 |
|---|---|
| 정상 | **201** + `GET /inventory/estimate` 와 같은 모양 |
| 둘 다 빔 · 같은 재료 실사 두 번 · 빼기 `qty` 0 이하 · 실사 `qty` 음수 · 소수 넷째 자리 이상 · 주기형 재료 · 메모 500자 · 원문 2000자 초과 | 400 `COMMON_INVALID_INPUT` |
| `reason` · `method` 가 모르는 값 | 400 `COMMON_MALFORMED_REQUEST` |
| 다른 가게 · 없는 · 숫자 아닌 재료 id 가 하나라도 있음 | 404 `COMMON_NOT_FOUND` (아무것도 기록하지 않음) |

### 가게별 가격 가져오기와 다중 주문처 (S15P31A308-349 · 350)

V23 이후 가게별 `store_market_quote` 행이 있으면 기존 offers/history 응답에 합친다(HTTP 적재 API 없음). 응답 단위와 `toIngredientUnitFactor`는 모든 사이트에 동일하게 적용된다. 가게별 가격이 포함되면 재료 기준 단위/계수 1로 정규화하며 실제 `offers[].price/spec/productName` 포장은 보존한다. `sites[].synthetic`은 합성 시연 가격 여부다. 가게별 행이 없으면 기존 응답 계약을 유지한다. 사이트·수집일별 가게 가격 우선, 미래 가격 제외, 사이트별 최신 날짜만 현재 후보로 사용한다. `delivery=UNKNOWN`은 비교 추천에 사용하지 않는다.

`POST /purchase-orders/from-recommendations`의 source는 `SUPPLIER`, `SIKBOM`, `COUPANG`, `MARKETBOM`, `ORDERLINK`다. 몰 가격이 있는 요청은 당일 수집 값과 실제 상품 URL을 저장한다. 오늘이 아닌 값은 `PURCHASE_ORDER_SOURCE_PRICE_UNSET`(409)로 거부한다. 쿠팡에 가격 자체가 없을 때의 기존 검색 링크 저장은 호환된다. 몰 단가는 기존 상품 포장에 해당하는 환산 금액으로 저장된다. 비교/절감액은 동일 요청 수량 기준이며 배송비와 실제 다른 몰 포장 올림 결제 금액을 포함하지 않는다([ADR-0021](adr/ADR-0021-store-market-quotes.md)).

거래처 `price-history.weekly`는 StoreClock의 오늘을 끝으로 한 8개의 7일 구간이다. 마지막 구간은 오늘−6일~오늘, 앞 칸은 7일씩 이전이며 관측 없음은 null이다. FE는 같은 추천 실행의 `runDate`를 구간 기준으로 사용한다. 식자재몰 history의 `points[].date`도 이 구간에 넣으며 미래 관측·누락은 값을 만들지 않는다.

## 시연 자료 준비 (S15P31A308-344)

[ADR-0020](adr/ADR-0020-demo-preparation.md). 인증된 현재 가게만 다루며 URL/본문의 store id는 받지 않는다.
`GET /stores/me/demo-preparation`은 읽기 전용이고, `POST /stores/me/demo-preparation/retry`는 본문이 없다.
레시피 confirm/skip에서 기본 메뉴·재료·레시피를 저장한 트랜잭션이 PREPARING을 등록한다.
GET 호출·화면 진입만으로 자료 복사나 추천을 시작하지 않는다.

아래 객체는 기존 `{success,data,error}` 응답의 `data` 예시다.

```json
{"eligible":true,"status":"PREPARING","phase":"ATTACH","attempt":1,"errorCode":null,"retryable":false,"updatedAt":"2026-11-12T01:20:00Z"}
```

| status | phase | 의미 |
|---|---|---|
| NOT_STARTED | null | 아직 시작하지 않음. attempt=0, updatedAt=null. GET은 행을 만들지 않음 |
| PREPARING | ATTACH | 기본 레시피 저장 후 시연 이력·재고·주문·예측 복사 대기/진행 |
| DATA_READY | RECOMMENDATION | 자료와 고정 주 예측 커밋 완료, 추천 계산 대기/진행 |
| READY | RECOMMENDATION | 추천과 실행 기록까지 성공적으로 커밋 완료 |
| FAILED | ATTACH 또는 RECOMMENDATION | 해당 단계 실패. errorCode와 retryable 확인 |

```json
{"eligible":true,"status":"FAILED","phase":"RECOMMENDATION","attempt":1,"errorCode":"DEMO_RECOMMENDATION_FAILED","retryable":true,"updatedAt":"2026-11-12T01:20:01Z"}
```

- 현재 설정된 시연 이메일의 가게만 `eligible=true`. 일반 가게·보관된 옛 시연 계정은
  `{eligible:false,status:"NOT_STARTED",phase:null,attempt:0,errorCode:null,retryable:false,updatedAt:null}`.
  일반 가게는 이 상태를 준비 중으로 해석하지 않고 기존 정상 빈 결과 계약을 사용한다.
- POST는 시작 전이면 기본 레시피가 있을 때 PREPARING을 등록한다. 없으면 409 `DEMO_ONBOARDING_REQUIRED`.
  일반 가게는 409 `DEMO_NOT_ELIGIBLE`. 인증이 없으면 기존 401이다.
- FAILED의 `retryable=true`만 POST로 다시 시작한다. ATTACH 실패는 PREPARING으로, RECOMMENDATION 실패는
  DATA_READY로 보내며 attempt를 1 올리고 errorCode를 지운다. 진행 중/완료 POST는 멱등이며 attempt도 그대로다.
  같은 가게 동시 재시도는 하나만 실행되고 타 가게 상태에는 영향이 없다.
- Python 복사기의 `DEMO_ATTACH_FAILED` 및 검증 오류, BE의 `DEMO_FORECAST_MISSING`(현재 가게 시계의 주 예측
   오늘~일요일 날짜 누락), `DEMO_CORE_DATA_MISSING`, `DEMO_RECOMMENDATION_FAILED`는 준비 상태의 오류 코드다.
  외부 예외/SQL/개인정보는 반환하지 않는다. ATTACH 오류의 구체적인 코드는 복사기 문서를 따른다.
- 시연 가게의 `/recommendations/run`은 NOT_STARTED/PREPARING에서 409 `DEMO_DATA_PREPARING`, FAILED에서
  409 `DEMO_DATA_FAILED`. `force=true`도 동일하며 추천/당일 완료 캐시를 쓰지 않는다.
  DATA_READY부터 계산할 수 있고, 서버의 준비 worker가 자료 커밋 후 추천 성공과 READY를 함께 커밋한다.
- READY가 될 때까지 상태 조회로 대기할 수 있다. 일반 빈 상태와 실패를 READY로 취급하지 않는다.
  시연 자료 준비가 온보딩 저장 성공이나 선택 질문 응답 여부를 대신하지 않는다.

### 시연 OCR 프론트 미리보기 (#116)

기존 `GET /stores/me/demo-preparation`의 `eligible=true`만 시연 여부로 사용한다. 추가 서버 필드나 OCR 정책 변경은 없다.
시연 프론트는 5초 뒤 로컬 영수증 미리보기를 표시하며 업로드·OCR 재시도·문서 확정 API를 쓰지 않는다. 실제 저장된 영수증은
로컬 확정 대상에 섞지 않는다. 일반 계정 또는 자격 조회 실패에서는 기존 API 처리와 오류 표시를 유지한다.
