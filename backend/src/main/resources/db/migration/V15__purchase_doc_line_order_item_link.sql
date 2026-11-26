-- 영수증 줄을 발주 줄에 줄 단위로 잇는다 (S15P31A308-301, DECISIONS #41 ②).
-- purchase_doc.purchase_order_id 는 문서 전체가 한 발주서에 맞을 때만 채워져, 일부만 맞는 영수증은 맞는 줄도 ② 로 재고에 들어가고
-- 입고 예정 줄은 남아 자동 입고 · 탭 입고 확인이 같은 양을 한 번 더 더했다. 이어진 줄은 추정 재고 ② 에서 뺀다.
ALTER TABLE purchase_doc_line ADD COLUMN purchase_order_item_id BIGINT REFERENCES purchase_order_item(id);
COMMENT ON COLUMN purchase_doc_line.purchase_order_item_id IS '이 영수증 줄로 입고 확인한 발주 줄. 있으면 재고는 발주 줄(①)로만 센다. 발주 줄 하나에 영수증 줄 하나';

-- 발주 줄 하나는 영수증 줄 하나에만 이어진다. NULL 은 여러 개 허용
CREATE UNIQUE INDEX uq_doc_line_order_item ON purchase_doc_line(purchase_order_item_id);

-- 기존 데이터: 문서 단위로 이어진 영수증의 줄은 이미 ② 에서 빠지고 있다(문서 purchase_order_id). 줄 단위 값은 비워 두어도
-- 계산이 같으므로 채우지 않는다
