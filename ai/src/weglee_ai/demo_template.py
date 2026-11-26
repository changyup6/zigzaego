"""Immutable pre-generated demo history and atomic target-store attachment."""
import datetime as dt
import hashlib
import json
from decimal import Decimal
from pathlib import Path

TOKEN = "__DEMO_STORE_ID__"
VERSION, DAYS, SEED = 1, 730, 15
TABLES = ("store", "menu", "ingredient", "recipe", "supplier", "supplier_product", "pos_sale", "store_closed_day",
          "purchase_doc", "purchase_doc_line", "stock_count", "purchase_order", "purchase_order_item", "price_history",
          "sales_forecast_week")


class TemplateError(ValueError):
    """Stable failure code safe for the preparation API."""


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _normalized(value):
    if isinstance(value, dict):
        return {k: _normalized(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_normalized(v) for v in value]
    if isinstance(value, (float, int)) and not isinstance(value, bool):
        return str(Decimal(str(value)).normalize())
    return value


def fingerprint(value) -> str:
    return hashlib.sha256(json.dumps(_normalized(value), ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def semantic_sql(sid: int) -> str:
    """IDs and query order never form part of the semantic identity."""
    return f"""jsonb_build_object(
'menu', COALESCE((SELECT jsonb_agg(jsonb_build_array(name,kind,active,price) ORDER BY name COLLATE "C") FROM menu WHERE store_id={sid}), '[]'::jsonb),
'ingredient', COALESCE((SELECT jsonb_agg(jsonb_build_array(name,unit,kind,storage_type,shelf_life_days,sourcing_type,price_sensitive,safety_days)
 ORDER BY name COLLATE "C") FROM ingredient WHERE store_id={sid}), '[]'::jsonb),
'recipe', COALESCE((SELECT jsonb_agg(jsonb_build_array(m.name,i.name,r.qty_per_serving) ORDER BY m.name COLLATE "C",i.name COLLATE "C")
 FROM recipe r JOIN menu m ON m.id=r.menu_id AND m.store_id=r.store_id
 JOIN ingredient i ON i.id=r.ingredient_id AND i.store_id=r.store_id WHERE r.store_id={sid}), '[]'::jsonb))"""


def schema_sql() -> str:
    tables = ",".join(quote(t) for t in TABLES)
    # 쓰기 열과 기존 PK/UK/FK를 검증한다. CHECK는 대상 DB가 실제 INSERT 때 강제한다.
    # enum CHECK의 PG 버전별 표기나 허용 값 확장이 유효한 캐시를 무효화하지 않게 한다.
    return f"""jsonb_build_object('columns', (SELECT jsonb_agg(jsonb_build_array(table_name,column_name,udt_name,is_nullable,column_default,
 numeric_precision,numeric_scale,character_maximum_length,is_identity,identity_generation)
 ORDER BY table_name COLLATE "C",ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ({tables})),
 'constraints', (SELECT jsonb_agg(jsonb_build_array(c.relname,con.conname,pg_get_constraintdef(con.oid)) ORDER BY c.relname COLLATE "C",con.conname COLLATE "C")
 FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND con.contype IN ('p','u','f') AND c.relname IN ({tables})))"""


def validate_forecast(forecast: dict | None, today: str) -> None:
    day = dt.date.fromisoformat(today)
    monday = day - dt.timedelta(days=day.weekday())
    week = {(monday + dt.timedelta(days=i)).isoformat() for i in range(7)}
    expected = {(day + dt.timedelta(days=i)).isoformat() for i in range(7 - day.weekday())}
    try:
        dates = [item['date'] for item in forecast['days']]
        if forecast['week_start'] != monday.isoformat() or len(dates) != len(set(dates)) or not expected.issubset(dates) or not set(dates).issubset(week):
            raise ValueError
        if not forecast['model'] or not forecast['reason'] or forecast['pct'] is None:
            raise ValueError
        if not isinstance(forecast['factors'], list):
            raise ValueError
        if any(not isinstance(item['value'], int) or not isinstance(item['label'], str) for item in forecast['days']):
            raise ValueError
        if any(not isinstance(item['pct'], int) or not isinstance(item['name'], str) or not isinstance(item['note'], str)
               for item in forecast['factors']):
            raise ValueError
    except (TypeError, KeyError, ValueError) as exc:
        raise TemplateError('DEMO_FORECAST_MISSING') from exc


def load_template(folder: Path, end: str) -> tuple[dict, str]:
    try:
        manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
    except FileNotFoundError as exc:
        raise TemplateError('DEMO_TEMPLATE_MISSING') from exc
    except (ValueError, OSError) as exc:
        raise TemplateError('DEMO_TEMPLATE_CORRUPT') from exc
    try:
        if (manifest['version'], manifest['days'], manifest['seed'], manifest['end']) != (VERSION, DAYS, SEED, end):
            raise TemplateError('DEMO_TEMPLATE_VERSION_MISMATCH')
        if not manifest['files'] or 'history.sql' not in manifest['files']:
            raise ValueError
        for filename, digest in manifest['files'].items():
            if Path(filename).name != filename or sha256(folder / filename) != digest:
                raise ValueError
        if manifest['semantic_fingerprint'] != fingerprint(manifest['semantic']):
            raise ValueError
        if manifest['schema_fingerprint'] != fingerprint(manifest['schema']):
            raise ValueError
        unsigned = {k: v for k, v in manifest.items() if k != 'template_fingerprint'}
        if manifest['template_fingerprint'] != fingerprint(unsigned):
            raise ValueError
        validate_forecast(manifest['forecast'], (dt.date.fromisoformat(end) + dt.timedelta(days=1)).isoformat())
        sql = (folder / 'history.sql').read_text(encoding='utf-8')
        if 'market.sql' in manifest['files']:
            sql += '\n' + (folder / 'market.sql').read_text(encoding='utf-8')
        return manifest, sql
    except TemplateError:
        raise
    except (KeyError, ValueError, OSError) as exc:
        raise TemplateError('DEMO_TEMPLATE_CORRUPT') from exc


def tenant_checks(sid: int) -> str:
    """The schema has simple FKs; explicitly verify the tenant side of each relation."""
    refs = {
        'recipe': [('menu_id', 'menu'), ('ingredient_id', 'ingredient')],
        'pos_sale': [('menu_id', 'menu')],
        'supplier_product': [('supplier_id', 'supplier'), ('ingredient_id', 'ingredient')],
        'purchase_doc': [('supplier_id', 'supplier'), ('purchase_order_id', 'purchase_order')],
        'purchase_doc_line': [('doc_id', 'purchase_doc'), ('ingredient_id', 'ingredient'), ('supplier_product_id', 'supplier_product')],
        'stock_count': [('ingredient_id', 'ingredient')],
        'purchase_order': [('supplier_id', 'supplier')],
        'purchase_order_item': [('po_id', 'purchase_order'), ('ingredient_id', 'ingredient'), ('supplier_product_id', 'supplier_product')],
        'price_history': [('supplier_product_id', 'supplier_product'), ('doc_line_id', 'purchase_doc_line')],
        'ingredient': [('primary_supplier_product_id', 'supplier_product')],
    }
    checks = []
    for table, links in refs.items():
        for column, parent in links:
            checks.append(f"IF EXISTS (SELECT 1 FROM {table} c JOIN {parent} p ON p.id=c.{column} "
                          f"WHERE c.store_id={sid} AND p.store_id<>c.store_id) THEN "
                          f"RAISE EXCEPTION 'DEMO_TENANT_LINK_MISMATCH:{table}.{column}'; END IF;")
    return '\n'.join(checks)


def render_copy(body: str, manifest: dict, sid: int, email: str, attempt: int) -> str:
    sid, attempt = int(sid), int(attempt)
    expected = quote(json.dumps(manifest['semantic'], ensure_ascii=False))
    schema = quote(json.dumps(manifest['schema'], ensure_ascii=False))
    f = manifest['forecast']
    values = [str(sid), quote(f['week_start']) + '::date', quote(f['model']), str(int(f['pct'])), quote(f['reason']),
              quote(json.dumps(f['days'], ensure_ascii=False)) + '::jsonb', quote(json.dumps(f['factors'], ensure_ascii=False)) + '::jsonb',
              'NULL' if f['analysis'] is None else quote(f['analysis'])]
    checks = '\n'.join(f"IF (SELECT count(*) FROM {table} WHERE store_id={sid}) <> {int(count)} THEN "
                       f"RAISE EXCEPTION 'DEMO_COUNT_MISMATCH:{table}'; END IF;" for table, count in manifest['counts'].items())
    return f"""BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
SELECT id FROM store WHERE id={sid} FOR UPDATE;
SELECT id FROM menu WHERE store_id={sid} FOR SHARE;
SELECT id FROM ingredient WHERE store_id={sid} FOR SHARE;
SELECT id FROM recipe WHERE store_id={sid} FOR SHARE;
SELECT a.id FROM account a JOIN store_member sm ON sm.account_id=a.id AND sm.role='OWNER'
 WHERE a.email={quote(email)} AND sm.store_id={sid} FOR SHARE OF a,sm;
SELECT status IN ('DATA_READY','READY') AS demo_done FROM demo_preparation WHERE store_id={sid} FOR UPDATE \\gset
\\if :demo_done
ROLLBACK;
\\else
DO $$ BEGIN
IF pg_is_in_recovery() THEN RAISE EXCEPTION 'DEMO_NOT_PRIMARY'; END IF;
IF NOT EXISTS (SELECT 1 FROM demo_preparation WHERE store_id={sid} AND status='PREPARING' AND phase='ATTACH' AND attempt = {attempt})
 THEN RAISE EXCEPTION 'DEMO_PREPARATION_STALE'; END IF;
IF NOT EXISTS (SELECT 1 FROM account a JOIN store_member sm ON sm.account_id=a.id AND sm.role='OWNER'
 WHERE a.email = {quote(email)} AND sm.store_id={sid}) THEN RAISE EXCEPTION 'DEMO_OWNER_CHANGED'; END IF;
IF ({schema_sql()})->'columns' IS DISTINCT FROM ({schema}::jsonb)->'columns'
 OR EXISTS (SELECT 1 FROM jsonb_array_elements(({schema}::jsonb)->'constraints') e
 WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(({schema_sql()})->'constraints') a WHERE a=e))
 THEN RAISE EXCEPTION 'DEMO_SCHEMA_MISMATCH'; END IF;
IF ({semantic_sql(sid)}) IS DISTINCT FROM {expected}::jsonb THEN RAISE EXCEPTION 'DEMO_FINGERPRINT_MISMATCH'; END IF;
END $$;
{body.replace(TOKEN, str(sid))}
INSERT INTO sales_forecast_week (store_id,week_start,model,pct,reason,days,factors,analysis)
VALUES ({','.join(values)}) ON CONFLICT (store_id,week_start) DO UPDATE SET model=excluded.model,pct=excluded.pct,
 reason=excluded.reason,days=excluded.days,factors=excluded.factors,analysis=excluded.analysis;
DO $$ BEGIN
{checks}
{tenant_checks(sid)}
IF (SELECT min(sold_date) FROM pos_sale WHERE store_id={sid}) IS DISTINCT FROM {quote(manifest['start'])}::date
 OR (SELECT max(sold_date) FROM pos_sale WHERE store_id={sid}) IS DISTINCT FROM {quote(manifest['end'])}::date
 THEN RAISE EXCEPTION 'DEMO_DATE_MISMATCH'; END IF;
END $$;
UPDATE demo_preparation SET status='DATA_READY',phase='RECOMMENDATION',error_code=NULL,retryable=false,updated_at=now()
 WHERE store_id={sid} AND status='PREPARING' AND attempt = {attempt};
COMMIT;
\\endif
"""
