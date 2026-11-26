# ADR-0016: 온보딩 저장본은 가게 스코프 없는 전역 표 `onboarding_replay` 에 담고, 있으면 Apify 를 부르지 않는다

- 상태: 채택
- 영역: backend
- 결정 로그: [DECISIONS #96](../DECISIONS.md)
- 관련 이슈: S15P31A308-321, S15P31A308-333(ANALYSIS 종류 추가), S15P31A308-337(재생 지연)

## 맥락

- 온보딩의 가게 찾기(`GET /onboarding/places`)는 Apify 실시간 호출로 10초 남짓, 분석 job 의 메뉴 수집은 수 분 걸린다. 시연에서는 정해진 검색어를 쓰므로 결과를 미리 받아 둘 수 있다.
- 검색 결과와 메뉴 목록은 네이버 플레이스의 공개 정보라 어느 가게의 것도 아니다. 그런데 ADR-0002 는 모든 표에 `store_id` 를 두라고 한다. 시세 표 `market_product`(ADR-0010)와 같은 처지다.
- 응답 모양 · 주소 · 오류 코드는 FE 가 목 기준으로 이미 맞춰 쓰고 있어(DECISIONS #56) 바꿀 수 없다.

## 결정

- Flyway `V18__onboarding_replay.sql` 에 **가게 스코프 없는** 전역 표 `onboarding_replay` 를 둔다. 열: `id`(IDENTITY) · `kind`(`SEARCH` | `MENUS` | `ANALYSIS`, ANALYSIS 는 `V19__onboarding_replay_analysis.sql` 로 CHECK 제약을 넓혀 추가) · `replay_key`(SEARCH = 정규화한 검색어, MENUS · ANALYSIS = placeId) · `payload`(jsonb, 변환 뒤 레코드 `PlaceCandidateResponse[]` / `CollectedMenu[]`, ANALYSIS 는 분석 job 결과 `OnboardingResult` — job `result_json` 과 같은 모양) · `captured_at`. `(kind, replay_key)` unique. ADR-0002 의 예외이며, 이 표를 직접 노출하는 API 는 두지 않는다.
- JPA 엔티티를 두지 않고 `OnboardingReplayRepository`(JdbcTemplate)로만 읽고 쓴다.
- 앞단 서비스 `OnboardingPlaceSource` 가 저장본을 먼저 찾고, 있으면 돌려주며 `ApifyNaverPlaceClient` 를 부르지 않는다. 없으면 클라이언트를 그대로 부른다. `OnboardingController` · `OnboardingAnalyzer` 는 이 서비스를 부른다.
- 검색어 정규화는 앞뒤 공백 제거 + 안쪽 연속 공백 한 칸, 대소문자 유지. 읽기 · 쓰기가 같은 함수(`OnboardingReplayRepository.normalizeQuery`)를 쓴다.
- 쓰기는 운영자 실행만 한다: 프로필 `onboarding-replay-save` · `onboarding-replay-delete`, 환경 변수 `ONBOARDING_REPLAY_QUERY`. 일반 요청은 저장본을 만들지 않는다. 저장본은 지울 때까지 남는다(유효기간 없음).
- **ANALYSIS (S15P31A308-333)**: 저장 실행은 후보마다 메뉴를 저장한 뒤 그 메뉴로 분석(LLM, GMS 키 필요)을 실제로 돌려 결과도 저장한다(`OnboardingAnalyzer.analyzePlaceFresh`). `analyzePlace` 는 맨 앞에서 ANALYSIS 저장본을 찾고, 있으면 Apify · GMS 를 부르지 않고 메뉴 이름 중간 결과와 진행률 1.0 을 남긴 뒤 저장된 결과를 그대로 돌려준다. 분석이 실패한 후보는 실패 목록에 넣고 성공분은 남기며 종료 코드 1. 지우기는 ANALYSIS 행도 지운다.
- **재생 지연 (S15P31A308-337)**: 저장본으로 답할 때는 실제 호출처럼 보이게 기다린다. 가게 찾기는 `app.onboarding-replay.search-delay`(기본 4초) 뒤에 답하고, 분석은 `app.onboarding-replay.analysis-delay`(기본 15초) 동안 진행률을 30번에 나눠 올리며 30% 를 넘을 때 메뉴 이름 중간 결과를 남긴다. 저장본이 없는 요청은 기다리지 않는다. 환경 변수 `ONBOARDING_REPLAY_SEARCH_DELAY` · `ONBOARDING_REPLAY_ANALYSIS_DELAY`, `0s` 면 바로 답한다.
- 이 표의 범위는 "공개된 검색 결과"에서 "공개 정보와 그것으로 만든 분석 결과"로 넓어진다. 분석 결과도 가게 소속이 아닌 공개 메뉴에서 나온 것이라 `store_id` 는 계속 두지 않는다.

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| `ApifyNaverPlaceClient` 안에서 저장본을 읽음 | 기존 테스트의 `@MockitoBean ApifyNaverPlaceClient` 가 저장본을 건너뛰게 되어 목의 뜻이 바뀐다. 앞단 서비스는 표가 비어 있으면 지금과 똑같다 |
| 일반 요청 결과를 자동으로 저장(캐시) | 유효기간 · 갱신 · 무효화 규칙이 필요하고, 시연 전에 운영자가 무엇이 저장됐는지 통제할 수 없다 |
| Apify 원문(Raw) JSON 을 저장 | 변환 규칙이 바뀌면 다시 변환할 수 있지만 원문이 크고 사진 URL 등 불필요한 값이 많다. 변환 뒤 레코드가 응답과 같은 모양이라 단순하다 |
| 가게마다 `store_id` 를 붙임 | 검색 결과는 가게 것이 아니고 온보딩 전에는 가게도 없다 |

## 결과

- **좋아지는 것**: 저장본이 있는 검색어 · placeId 는 Apify 호출 없이 정해진 지연 뒤에 응답한다. 저장본이 없으면 기존 동작 그대로라 회귀가 없다.
- **감수하는 것**: 변환 규칙(`PlaceCandidateResponse` · `CollectedMenu`)이 바뀌어도 옛 결과가 남는다. 사진 URL 이 만료되면 저장본의 사진도 깨진다. 운영자가 지우기 전까지 낡은 결과가 나온다. ANALYSIS 는 프롬프트 · 재료표가 바뀌어도 옛 분석 결과가 남는다(바뀌면 지우고 다시 저장한다).
- **다시 볼 시점**: 시연 밖(실사용)에서도 저장본이 필요해지면 유효기간 · 자동 갱신을 따로 결정한다. 레코드 모양이 바뀌면 저장본을 지우고 다시 받는다.
