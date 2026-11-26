-- 자동 입고(203 배치)로 채워진 발주 줄 표시 (S15P31A308-238). 영수증 확정이 이 줄을 후보로 받아 값을 덮고 false 로 바꾼다.
ALTER TABLE purchase_order_item ADD COLUMN auto_received BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN purchase_order_item.auto_received IS '입고가 자동 입고 배치로 채워졌는가. 사람이 입고하거나 영수증이 덮으면 false. 기본 false';
