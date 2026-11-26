-- 온보딩 저장본에 분석 결과 종류 ANALYSIS 추가 (S15P31A308-333, ADR-0016 개정).
-- 가게 찾기 · 메뉴 수집(V18)에 더해, 분석 job 결과(OnboardingResult JSON)도 미리 담아 시연 온보딩이 LLM 호출 없이 끝나게 한다.
-- 칼럼 · 키는 그대로이고 kind CHECK 제약만 넓힌다. 되돌리려면 ANALYSIS 행을 지우고 제약을 되돌린다.
ALTER TABLE onboarding_replay DROP CONSTRAINT ck_onboarding_replay_kind;
ALTER TABLE onboarding_replay ADD CONSTRAINT ck_onboarding_replay_kind CHECK (kind IN ('SEARCH', 'MENUS', 'ANALYSIS'));

COMMENT ON TABLE onboarding_replay IS '온보딩 저장본. 운영자가 미리 받아 둔 가게 찾기 후보 · 메뉴 목록 · 분석 결과. 있으면 Apify · LLM 을 부르지 않고 이것으로 응답한다. 가게 소속이 아닌 전역 표(ADR-0016, ADR-0002 예외). 지울 때까지 남는다. 근거: S15P31A308-321, S15P31A308-333.';
COMMENT ON COLUMN onboarding_replay.kind IS 'SEARCH = GET /onboarding/places 결과, MENUS = 분석 job 의 메뉴 수집 결과, ANALYSIS = 분석 job 의 최종 결과(OnboardingResult).';
COMMENT ON COLUMN onboarding_replay.replay_key IS 'SEARCH 는 정규화한 검색어(앞뒤 공백 제거, 안쪽 연속 공백 한 칸, 대소문자 유지), MENUS · ANALYSIS 는 placeId.';
COMMENT ON COLUMN onboarding_replay.payload IS 'JSON. SEARCH = PlaceCandidate[], MENUS = CollectedMenu[], ANALYSIS = OnboardingResult(job result_json 과 같은 모양). 변환 규칙 · 프롬프트 · 재료표가 바뀌어도 옛 결과가 남는다.';
