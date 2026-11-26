-- 발주서 주문처(거래처 · 식봄 · 쿠팡)와 줄 단가 저장 (S15P31A308-201, DECISIONS #90)
-- 식봄 · 쿠팡 발주서는 거래처 행이 없다 — supplier_id 가 비는 것은 source 가 SUPPLIER 가 아닐 때뿐이다.
ALTER TABLE purchase_order ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'SUPPLIER';
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_source CHECK (source IN ('SUPPLIER', 'SIKBOM', 'COUPANG'));
ALTER TABLE purchase_order ALTER COLUMN supplier_id DROP NOT NULL;
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_source_supplier CHECK ((source = 'SUPPLIER') = (supplier_id IS NOT NULL));

ALTER TABLE purchase_order_item ADD COLUMN unit_price NUMERIC(12, 2);
ALTER TABLE purchase_order_item ADD COLUMN product_url VARCHAR(500);

COMMENT ON COLUMN purchase_order.source IS '주문처. SUPPLIER = 등록 거래처(supplier_id 필수), SIKBOM = 식봄, COUPANG = 쿠팡(둘은 supplier_id NULL). 기본 SUPPLIER';
COMMENT ON COLUMN purchase_order_item.unit_price IS '발주서를 만들 때 저장한 포장 1개 상당 원 단가. 모르면(쿠팡 · 가격 없는 거래처 상품 · 이전 줄) NULL. 발주 이력 금액 = 이 값 × 주문 포장 수';
COMMENT ON COLUMN purchase_order_item.product_url IS '식봄 · 쿠팡 줄의 상품 링크(쿠팡은 재료 이름 검색 주소). 거래처 줄은 NULL';
