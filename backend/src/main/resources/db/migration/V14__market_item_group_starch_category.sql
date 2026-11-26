-- 품목군 전분(STARCH)의 category 를 '양념·소스' → '가루' 로 (S15P31A308-285). 정본 ai/seed/market_item_group.csv 는 284 에서 바뀌었고,
-- V8 이 그 CSV 를 INSERT 한 뒤라 이미 만들어진 DB 는 따로 고친다. 재료 분류 기준 2.1절 4번: 밀가루 · 전분 · 빵가루는 곡물·면·떡.
UPDATE market_item_group SET category = '가루' WHERE code = 'STARCH';
