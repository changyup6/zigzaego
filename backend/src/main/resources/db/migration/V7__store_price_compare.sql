-- 가게별 가격 비교 켬/끔 (S15P31A308-205, DECISIONS #48).
-- 더보기의 "식봄, 쿠팡 가격 비교" 스위치. 발주 화면 품목 상세에서 식자재몰 최저가를 보일지 가게 설정으로 저장한다.
-- 저장 · 조회만 하고 다른 API 응답은 바꾸지 않는다(FE 가 이 값을 보고 숨긴다). 기존 가게는 모두 켜짐(true)으로 시작한다.
ALTER TABLE store ADD COLUMN price_compare BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN store.price_compare IS '식자재몰 가격 비교(발주 화면 품목 상세의 최저가)를 보일지. 기본 true. 저장 · 조회만 하고 다른 API 동작은 바꾸지 않는다. 근거: DECISIONS #48 · S15P31A308-205.';
