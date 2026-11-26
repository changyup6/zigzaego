-- 재료 보관 위치 (S15P31A308-268).
-- 재료 탭이 재료를 냉장고(냉동 · 냉장) · 선반 · 창고 그림에 나눠 보여주고 끌어서 옮긴다. 화면 배치용 칸이다.
-- storage_type(냉동 · 상온 · 냉장 · 신선)은 안전재고 z · 유통기한 계산에 쓰이므로 그대로 두고 따로 둔다.
-- 기본값 규칙은 StorageLocation.defaultFor 와 같다: 주기형(CYCLE) 또는 보관 방식 없음 → WAREHOUSE,
-- FROZEN → FREEZER, CHILLED · FRESH → FRIDGE, AMBIENT → SHELF.
-- DB 기본값 없음 — 넣는 쪽이 명시한다 (V1 kind · sourcing_type 과 같은 원칙).

ALTER TABLE ingredient ADD COLUMN storage_location VARCHAR(10);

UPDATE ingredient
SET storage_location = CASE
    WHEN kind = 'CYCLE' OR storage_type IS NULL THEN 'WAREHOUSE'
    WHEN storage_type = 'FROZEN' THEN 'FREEZER'
    WHEN storage_type IN ('CHILLED', 'FRESH') THEN 'FRIDGE'
    WHEN storage_type = 'AMBIENT' THEN 'SHELF'
END;

ALTER TABLE ingredient
    ALTER COLUMN storage_location SET NOT NULL,
    ADD CONSTRAINT ck_ingredient_storage_location
        CHECK (storage_location IN ('FREEZER', 'FRIDGE', 'SHELF', 'WAREHOUSE'));

COMMENT ON COLUMN ingredient.storage_location IS '보관 위치(화면 배치): FREEZER 냉동 · FRIDGE 냉장 · SHELF 선반 · WAREHOUSE 창고. 계산에는 쓰지 않는다 — 계산은 storage_type (S15P31A308-268).';
