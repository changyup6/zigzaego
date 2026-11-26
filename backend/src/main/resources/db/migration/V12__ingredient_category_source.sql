-- 재료 카테고리 출처 (S15P31A308-274, DECISIONS #78).
-- 자동 분류(재료표 · 품목군 매핑 → LLM → 기타)가 붙인 카테고리와 사장님이 직접 고른 카테고리를 구분한다.
-- 자동 분류는 AUTO 인 재료만 다시 분류하고(POST /ingredient-categories/auto-assign 은 그중 '기타'만), OWNER 는 건드리지 않는다.
-- 사장님이 고르는 API(PATCH /ingredients/{id} categoryId)는 S15P31A308-275 — 그때 OWNER 로 바꾼다.
-- 기존 재료는 전부 기타(V11)였고 사장님이 고른 적이 없으니 AUTO. DB 기본값 없음 — 넣는 쪽이 명시한다 (category_id 와 같은 원칙).

ALTER TABLE ingredient ADD COLUMN category_source VARCHAR(5);

UPDATE ingredient SET category_source = 'AUTO';

ALTER TABLE ingredient
    ALTER COLUMN category_source SET NOT NULL,
    ADD CONSTRAINT ck_ingredient_category_source CHECK (category_source IN ('AUTO', 'OWNER'));

COMMENT ON COLUMN ingredient.category_source IS '카테고리를 누가 정했나: AUTO 자동 분류(재료표 · 품목군 매핑 → LLM → 기타) · OWNER 사장님이 직접 고름. 자동 분류는 AUTO 만 다시 분류한다 (S15P31A308-274).';
