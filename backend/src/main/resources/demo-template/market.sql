-- Portable optional market fixture for demo template preparation (requires Flyway V23). v1 + every-ingredient fill.
-- __DEMO_STORE_ID__ is replaced by demo template tooling; no transaction wrapper. Re-running is safe (upsert / DO NOTHING).
-- Fixed StoreClock today=2026-11-12. Product prices below are SYNTHETIC, never global market_product.
-- The runtime attachment policy must authorize the configured demo email and remap ingredient ids by exact semantic name/unit.
DO $fixture$
BEGIN
  IF (SELECT count(*) FROM ingredient WHERE store_id=__DEMO_STORE_ID__ AND unit='G' AND name IN ('돼지고기','부추')) <> 2 THEN
    RAISE EXCEPTION 'pao-market-v1 requires exactly 돼지고기/G and 부추/G in target store';
  END IF;
END $fixture$;
WITH ingredients(name,unit,per_base) AS (VALUES ('돼지고기','G',8.2::numeric),('부추','G',3.8::numeric)),
sites(site,multiplier,pack_qty) AS (VALUES ('쿠팡',0.95::numeric,1000),('식봄',1.00::numeric,2000),('마켓봄',1.05::numeric,1000),('오더링크',0.90::numeric,2000)),
weeks(age,multiplier) AS (VALUES (0,1.00::numeric),(1,1.02),(2,1.01),(3,1.04),(4,1.03),(5,1.06),(6,1.05),(7,1.08))
INSERT INTO store_market_quote(store_id,ingredient_id,site,collected_on,unit_price,pack_qty,pack_price,product_name,spec,url,synthetic)
SELECT __DEMO_STORE_ID__,i.id,s.site,DATE '2026-11-12'-w.age*7,
       round(x.per_base*s.multiplier*w.multiplier*s.pack_qty)::numeric/s.pack_qty,
       s.pack_qty,round(x.per_base*s.multiplier*w.multiplier*s.pack_qty)::bigint,
       '[시연 합성] '||i.name||' '||(s.pack_qty/1000)||'kg', (s.pack_qty/1000)||'kg',
       'https://example.invalid/demo-market', true
FROM ingredients x JOIN ingredient i ON i.store_id=__DEMO_STORE_ID__ AND i.name=x.name AND i.unit=x.unit
CROSS JOIN sites s CROSS JOIN weeks w
ON CONFLICT(store_id,ingredient_id,site,collected_on) DO UPDATE SET
 unit_price=EXCLUDED.unit_price,pack_qty=EXCLUDED.pack_qty,pack_price=EXCLUDED.pack_price,
 product_name=EXCLUDED.product_name,spec=EXCLUDED.spec,url=EXCLUDED.url,synthetic=EXCLUDED.synthetic;
DO $fixture$
DECLARE actual_count integer;
BEGIN
  SELECT count(*) INTO actual_count
  FROM store_market_quote q JOIN ingredient i ON i.id=q.ingredient_id AND i.store_id=q.store_id
  WHERE q.store_id=__DEMO_STORE_ID__ AND i.name IN ('돼지고기','부추') AND i.unit='G'
    AND q.site IN ('쿠팡','식봄','마켓봄','오더링크') AND q.synthetic
    AND q.collected_on IN (SELECT DATE '2026-11-12'-n*7 FROM generate_series(0,7) n);
  IF actual_count <> 64 THEN RAISE EXCEPTION 'pao-market-v1 expected 64 scoped quotes, actual %',actual_count; END IF;
END $fixture$;

-- v2 fill: every ingredient of the target store gets 2~3 priced vendors (odd id 3, even id 2; S15P31A308-338).
-- vendors = existing supplier with a known unit price (0/1) + distinct store_market_quote sites (max 4, CHECK) -> never above 5.
-- Missing sites are synthetic: base = supplier unit price (price / base_qty_per_pack), else the cheapest existing quote.
-- Site multipliers 0.92~1.10 (within ±5~15%), same 8-week shape as v1. Ingredients with no price basis are left as-is.
WITH base AS (
  SELECT i.id, i.name, i.unit,
         sp.price / sp.base_qty_per_pack AS supplier_unit,
         sp.base_qty_per_pack AS pack_qty,
         (SELECT min(q.unit_price) FROM store_market_quote q
           WHERE q.store_id=i.store_id AND q.ingredient_id=i.id) AS quote_unit,
         (SELECT count(DISTINCT q.site) FROM store_market_quote q
           WHERE q.store_id=i.store_id AND q.ingredient_id=i.id) AS sites_now
  FROM ingredient i
  LEFT JOIN LATERAL (
    SELECT p.price, p.base_qty_per_pack FROM supplier_product p
    WHERE p.store_id=i.store_id AND p.ingredient_id=i.id AND p.price > 0 AND p.base_qty_per_pack > 0
    ORDER BY (p.id = i.primary_supplier_product_id) DESC, p.id LIMIT 1
  ) sp ON true
  WHERE i.store_id=__DEMO_STORE_ID__
),
need AS (
  SELECT b.*, coalesce(b.supplier_unit, b.quote_unit) AS unit_base,
         greatest(0, 2 + (b.id % 2)::int - (b.supplier_unit IS NOT NULL)::int - b.sites_now::int) AS missing,
         CASE b.unit WHEN 'EA' THEN coalesce(b.pack_qty, 10) WHEN 'ML' THEN coalesce(b.pack_qty, 1000) ELSE coalesce(b.pack_qty, 1000) END AS pq
  FROM base b
),
sites(site, multiplier, ord) AS (VALUES ('식봄',0.95::numeric,0),('쿠팡',0.92::numeric,1),('마켓봄',1.06::numeric,2),('오더링크',1.10::numeric,3)),
picked AS (
  SELECT n.*, s.site, s.multiplier,
         row_number() OVER (PARTITION BY n.id ORDER BY (s.ord + n.id) % 4) AS rk
  FROM need n CROSS JOIN sites s
  WHERE n.missing > 0 AND n.unit_base IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM store_market_quote q
                    WHERE q.store_id=__DEMO_STORE_ID__ AND q.ingredient_id=n.id AND q.site=s.site)
),
weeks(age, multiplier) AS (VALUES (0,1.00::numeric),(1,1.02),(2,1.01),(3,1.04),(4,1.03),(5,1.06),(6,1.05),(7,1.08))
INSERT INTO store_market_quote(store_id,ingredient_id,site,collected_on,unit_price,pack_qty,pack_price,product_name,spec,url,synthetic)
SELECT __DEMO_STORE_ID__, p.id, p.site, DATE '2026-11-12' - w.age*7,
       round(p.unit_base*p.multiplier*w.multiplier*p.pq)::numeric / p.pq,
       p.pq, round(p.unit_base*p.multiplier*w.multiplier*p.pq)::bigint,
       '[시연 합성] '||p.name||' '||sp.spec, sp.spec,
       'https://example.invalid/demo-market', true
FROM picked p
CROSS JOIN weeks w
CROSS JOIN LATERAL (SELECT CASE
    WHEN p.unit='G' AND p.pq >= 1000 THEN trim_scale(round(p.pq/1000, 3))::text||'kg'
    WHEN p.unit='G' THEN trim_scale(p.pq)::text||'g'
    WHEN p.unit='ML' AND p.pq >= 1000 THEN trim_scale(round(p.pq/1000, 3))::text||'L'
    WHEN p.unit='ML' THEN trim_scale(p.pq)::text||'ml'
    ELSE trim_scale(p.pq)::text||'개' END AS spec) sp
WHERE p.rk <= p.missing
ON CONFLICT(store_id,ingredient_id,site,collected_on) DO NOTHING;
DO $fixture$
DECLARE short_count integer; over_count integer;
BEGIN
  WITH v AS (
    SELECT i.id,
           (EXISTS (SELECT 1 FROM supplier_product p WHERE p.store_id=i.store_id AND p.ingredient_id=i.id
                     AND p.price > 0 AND p.base_qty_per_pack > 0))::int
           + (SELECT count(DISTINCT q.site) FROM store_market_quote q WHERE q.store_id=i.store_id AND q.ingredient_id=i.id) AS vendors,
           (EXISTS (SELECT 1 FROM supplier_product p WHERE p.store_id=i.store_id AND p.ingredient_id=i.id
                     AND p.price > 0 AND p.base_qty_per_pack > 0)
            OR EXISTS (SELECT 1 FROM store_market_quote q WHERE q.store_id=i.store_id AND q.ingredient_id=i.id)) AS priced
    FROM ingredient i WHERE i.store_id=__DEMO_STORE_ID__)
  SELECT count(*) FILTER (WHERE priced AND vendors < 2), count(*) FILTER (WHERE vendors > 5)
  INTO short_count, over_count FROM v;
  IF short_count > 0 OR over_count > 0 THEN
    RAISE EXCEPTION 'pao-market-v2 vendor range violated: short %, over %', short_count, over_count;
  END IF;
END $fixture$;
