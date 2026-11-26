-- 메뉴 대표 사진은 원본 URL 한 개로 유지한다 (DECISIONS #45 · #114, ADR-0022).
-- 이전 메뉴는 NULL 그대로 두며 다른 가게의 수집 기록을 이름으로 추측해 연결하지 않는다.
ALTER TABLE menu ADD COLUMN photo_url TEXT;
COMMENT ON COLUMN menu.photo_url IS '대표 메뉴 사진의 원본 HTTP(S) URL. 없으면 NULL. 온보딩 수집→분석 초안→확정 저장에서 전달한다 (ADR-0022).';
