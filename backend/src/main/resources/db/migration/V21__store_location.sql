-- 가게 좌표 (S15P31A308-226 · 267, ADR-0017). 이번 주 매출 예측이 가게 반경 1km 상권만 보게 한다.
-- 비어 있으면 AI 배치는 store.sigungu 의 구 전체 상권으로 본다. 채우는 경로(온보딩 · 네이버 플레이스 좌표)는 따로 정한다.
ALTER TABLE store ADD COLUMN latitude DOUBLE PRECISION;
ALTER TABLE store ADD COLUMN longitude DOUBLE PRECISION;
ALTER TABLE store ADD CONSTRAINT ck_store_location CHECK (
    (latitude IS NULL AND longitude IS NULL)
    OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

COMMENT ON COLUMN store.latitude IS '가게 위도(WGS84). longitude 와 함께 비거나 함께 찬다. 이번 주 매출 예측의 가게 반경 상권 선택에 쓴다. 근거: S15P31A308-226 · ADR-0017.';
COMMENT ON COLUMN store.longitude IS '가게 경도(WGS84). latitude 와 함께 비거나 함께 찬다. 근거: S15P31A308-226 · ADR-0017.';
