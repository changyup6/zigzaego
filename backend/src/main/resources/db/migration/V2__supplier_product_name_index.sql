-- 거래처 상품 = 영수증 상품명 사전 (설계 4.2절). 영수증 줄의 상품명으로 가게 안 상품을 바로 찾는다.
-- 거래처를 모르는 영수증(마트 등, purchase_doc.supplier_id NULL)도 있어 uq(supplier_id, product_name) 만으로는 못 찾는다.
CREATE INDEX idx_supplier_product_store_name ON supplier_product(store_id, product_name);
