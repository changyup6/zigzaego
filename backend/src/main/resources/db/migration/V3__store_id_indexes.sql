-- 가게 조건으로만 읽는 표 세 개에 store_id 로 시작하는 인덱스가 없어 매번 표 전체를 읽었다 (S15P31A308-159).
-- 기존 인덱스는 ingredient_id 로 시작해서 "가게의 재료 전부" 를 묻는 쿼리(InventoryEstimateService 의
-- LAST_COUNT · RECEIVED_SINCE_COUNT · CYCLE_RECEIPTS)에는 쓰이지 않았다. 비용이 전체 가게 수에 비례해 늘어난다.

-- 재고 실사: 가게 안 재료별 최신 실사 1건 (DISTINCT ON (ingredient_id) ... ORDER BY ingredient_id, counted_at DESC)
CREATE INDEX idx_stock_count_store_ingredient_time ON stock_count(store_id, ingredient_id, counted_at DESC);

-- 재료는 한 가게에만 속하므로 (ingredient_id, counted_at) 인덱스는 위 인덱스가 전부 대체한다.
-- stock_count 를 읽는 쿼리는 모두 store_id 조건을 가진다 (ADR-0002).
DROP INDEX idx_stock_count_ingredient_time;

-- 발주 품목 줄: 가게 안 재료별 입고량 합산
CREATE INDEX idx_po_item_store_ingredient ON purchase_order_item(store_id, ingredient_id);

-- 매입 문서 줄: 가게 안 재료별 입고량 합산 (ingredient_id 는 매칭 전이면 NULL)
CREATE INDEX idx_purchase_doc_line_store_ingredient ON purchase_doc_line(store_id, ingredient_id);
