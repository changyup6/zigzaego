-- 가게에 명시적으로 가져온 가격은 전역 market_product에 섞지 않는다.
ALTER TABLE ingredient ADD CONSTRAINT uq_ingredient_store_id UNIQUE (store_id, id);
CREATE TABLE store_market_quote (
    store_id BIGINT NOT NULL REFERENCES store(id),
    ingredient_id BIGINT NOT NULL,
    site VARCHAR(100) NOT NULL,
    collected_on DATE NOT NULL,
    unit_price NUMERIC(19,7) NOT NULL CHECK (unit_price >= 0),
    pack_qty NUMERIC(19,6) NOT NULL CHECK (pack_qty > 0),
    pack_price BIGINT NOT NULL CHECK (pack_price >= 0),
    product_name VARCHAR(500) NOT NULL,
    spec VARCHAR(200),
    url VARCHAR(500) NOT NULL,
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (store_id, ingredient_id, site, collected_on),
    CONSTRAINT fk_store_market_quote_ingredient FOREIGN KEY (store_id, ingredient_id) REFERENCES ingredient(store_id, id),
    CONSTRAINT ck_store_market_quote_source CHECK (site IN ('식봄','쿠팡','마켓봄','오더링크')),
    CONSTRAINT ck_store_market_quote_pack_price CHECK (abs(unit_price * pack_qty - pack_price) <= 1)
);
COMMENT ON TABLE store_market_quote IS '가게에 명시적으로 가져온 재료 단위 가격·포장 스냅샷. synthetic=true는 시연 합성값. 외부 수집이나 자동 주문 없음';
ALTER TABLE purchase_order DROP CONSTRAINT ck_purchase_order_source;
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_source CHECK (source IN ('SUPPLIER','SIKBOM','COUPANG','MARKETBOM','ORDERLINK'));
