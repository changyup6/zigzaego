"""Real PostgreSQL atomicity tests (DEMO_TEST_POSTGRES=1, local Docker only)."""
import datetime as dt
import importlib.util
import json
import os
import re
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

from weglee_ai.demo_template import load_template, render_copy

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('demo_attach_integration', ROOT / 'ai/scripts/demo_attach.py')
da = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(da)
pytestmark = pytest.mark.skipif(os.environ.get('DEMO_TEST_POSTGRES') != '1', reason='requires explicit local Docker PostgreSQL')


@pytest.fixture(scope='module')
def database():
    name = 'demo-template-test-' + uuid.uuid4().hex[:10]
    subprocess.run(['docker', 'run', '--rm', '-d', '--name', name, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:18'],
                   check=True, capture_output=True, timeout=45)
    def sql(text=None, *, stdin=None):
        p = subprocess.run(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1', '-qAt', '-f', '-'],
                           input=text if stdin is None else stdin, text=True, capture_output=True, timeout=45)
        if p.returncode:
            raise da.AttachError(p.stderr)
        return p.stdout.strip()
    try:
        for _ in range(100):
            p = subprocess.run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'], capture_output=True, timeout=5)
            if p.returncode == 0:
                break
            time.sleep(.1)
        migrations = sorted((ROOT / 'backend/src/main/resources/db/migration').glob('V*.sql'),
                            key=lambda p: int(re.match(r'V(\d+)', p.name)[1]))
        for path in migrations:
            sql(path.read_text())
        # 다른 MR의 실제 마이그레이션도 명시해 검증한다. 로컬 Docker에서만 실행한다.
        preparation_migration = os.environ.get('DEMO_PREPARATION_MIGRATION')
        if preparation_migration:
            sql(Path(preparation_migration).read_text())
        else:
            sql("""CREATE TABLE demo_preparation(store_id bigint PRIMARY KEY REFERENCES store(id) ON DELETE CASCADE,
        status varchar(24) NOT NULL, phase varchar(24) NOT NULL DEFAULT 'ATTACH', attempt integer NOT NULL DEFAULT 1,
        error_code varchar(80), retryable boolean NOT NULL DEFAULT false, updated_at timestamptz NOT NULL DEFAULT now());""")
        if market_migration := os.environ.get('DEMO_MARKET_MIGRATION'):
            sql(Path(market_migration).read_text())
        yield sql
    finally:
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=15)


@pytest.fixture(scope='module')
def cache(database, tmp_path_factory):
    from dataclasses import fields

    from weglee_ai.sample_data import generate
    from weglee_ai.seed_sql import build_seed_sql

    root = tmp_path_factory.mktemp('demo-cache')
    sample = root / 'sample'
    sample.mkdir()
    for field in fields(data := generate(end_date='2026-11-11', days=730, seed=15)):
        getattr(data, field.name).to_csv(sample / f'{field.name}.csv', index=False)
    previous = os.environ.get('WEGLEE_DEMO_PASSWORD_HASH')
    os.environ['WEGLEE_DEMO_PASSWORD_HASH'] = '$2a$10$' + 'Ab1.' * 13 + 'A'
    try:
        database(build_seed_sql(sample))
    finally:
        if previous is None:
            del os.environ['WEGLEE_DEMO_PASSWORD_HASH']
        else:
            os.environ['WEGLEE_DEMO_PASSWORD_HASH'] = previous
    monday = dt.date(2026, 11, 9)
    days = [{'date': (monday + dt.timedelta(days=i)).isoformat(), 'label': '', 'value': 120} for i in range(7)]
    database("INSERT INTO sales_forecast_week(store_id,week_start,model,pct,reason,days,factors) VALUES "
             f"(1,'2026-11-09','fixture',120,'event','{json.dumps(days)}','[]')")
    da.run_psql = database
    folder = root / 'cache'
    da.prepare_template(1, '2026-11-11', folder)
    print(f'cache generation: {json.loads((folder / "manifest.json").read_text())["generation_seconds"]}s')
    return folder


def new_store(database):
    sid = int(database("INSERT INTO store(name,display_name) VALUES ('fixture','fixture') RETURNING id").splitlines()[0])
    database(f"""INSERT INTO account(email,password_hash,name) VALUES ('demo-{sid}@example.invalid','hash','fixture');
    INSERT INTO store_member(account_id,store_id,role) SELECT id,{sid},'OWNER' FROM account WHERE email='demo-{sid}@example.invalid';
    INSERT INTO menu(store_id,name,kind,source,active,price) SELECT {sid},name,kind,source,active,price FROM menu WHERE store_id=1;
    INSERT INTO ingredient_category(store_id,code,name,sort_order) VALUES ({sid},'OTHER','기타',99);
    INSERT INTO ingredient(store_id,name,unit,kind,storage_type,shelf_life_days,sourcing_type,price_sensitive,safety_days,
        storage_location,category_id,category_source)
    SELECT {sid},name,unit,kind,storage_type,shelf_life_days,sourcing_type,price_sensitive,safety_days,storage_location,
        (SELECT id FROM ingredient_category WHERE store_id={sid} AND code='OTHER'),'AUTO' FROM ingredient WHERE store_id=1;
    INSERT INTO recipe(store_id,menu_id,ingredient_id,qty_per_serving)
    SELECT {sid},m.id,i.id,r.qty_per_serving FROM recipe r JOIN menu oldm ON oldm.id=r.menu_id
    JOIN ingredient oldi ON oldi.id=r.ingredient_id JOIN menu m ON m.store_id={sid} AND m.name=oldm.name
    JOIN ingredient i ON i.store_id={sid} AND i.name=oldi.name WHERE r.store_id=1;
    INSERT INTO demo_preparation(store_id,status) VALUES ({sid},'PREPARING');""")
    return sid


def test_three_new_stores_reuse_one_730day_artifact(database, cache, monkeypatch):
    manifest, body = load_template(cache, '2026-11-11')
    monkeypatch.setattr(da, '_run_script', lambda *args: pytest.fail('runtime generation prohibited'))
    times = []
    for _ in range(3):
        sid = new_store(database)
        before = time.monotonic()
        path = da.build_sql(sid, '2026-11-11', cache.parent / f'run-{sid}', email=f'demo-{sid}@example.invalid', template_dir=cache, attempt=1)
        database(stdin=path.read_text())
        times.append(round(time.monotonic() - before, 3))
        assert database(f'SELECT status FROM demo_preparation WHERE store_id={sid}') == 'DATA_READY'
        assert int(database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}')) == manifest['counts']['pos_sale']
        assert database(f'SELECT min(sold_date),max(sold_date) FROM pos_sale WHERE store_id={sid}') == '2024-11-12|2026-11-11'
        # Second copy serializes and exits without modifying anything.
        database(stdin=path.read_text())
        assert int(database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}')) == manifest['counts']['pos_sale']
    print(f'local PostgreSQL copy seconds x3: {times}')


def test_semantic_changes_fail_before_any_history(database, cache):
    manifest, body = load_template(cache, '2026-11-11')
    for mutation in ("UPDATE ingredient SET unit='ML'", "UPDATE recipe SET qty_per_serving=qty_per_serving+1", "UPDATE menu SET price=price+1"):
        sid = new_store(database)
        database(mutation + f' WHERE store_id={sid}')
        with pytest.raises(da.AttachError, match='DEMO_FINGERPRINT_MISMATCH'):
            database(stdin=render_copy(body, manifest, sid, f'demo-{sid}@example.invalid', 1))
        assert database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}') == '0'
        assert database(f'SELECT count(*) FROM supplier_product WHERE store_id={sid}') == '0'


def test_forecast_copy_failure_rolls_back_all_history(database, cache):
    manifest, body = load_template(cache, '2026-11-11')
    sid = new_store(database)
    broken = dict(manifest, forecast=dict(manifest['forecast'], model='x' * 51))
    with pytest.raises(da.AttachError, match='value too long'):
        database(stdin=render_copy(body, broken, sid, f'demo-{sid}@example.invalid', 1))
    assert database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}') == '0'
    assert database(f'SELECT count(*) FROM purchase_order WHERE store_id={sid}') == '0'
    da.fail_preparation(sid, 1, 'DEMO_ATTACH_FAILED')
    assert database(f'SELECT status,retryable FROM demo_preparation WHERE store_id={sid}') == 'FAILED|t'


def test_concurrent_copy_is_idempotent_and_new_ids_are_scoped(database, cache):
    manifest, body = load_template(cache, '2026-11-11')
    sid = new_store(database)
    sql = render_copy(body, manifest, sid, f'demo-{sid}@example.invalid', 1)
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda _: database(stdin=sql), range(2)))
    assert int(database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}')) == manifest['counts']['pos_sale']
    assert database(f'''SELECT count(*) FROM pos_sale s JOIN menu m ON s.menu_id=m.id WHERE s.store_id={sid} AND m.store_id<>s.store_id''') == '0'
    assert database(f'''SELECT count(*) FROM supplier_product s JOIN ingredient i ON s.ingredient_id=i.id
        JOIN supplier p ON s.supplier_id=p.id WHERE s.store_id={sid} AND (i.store_id<>s.store_id OR p.store_id<>s.store_id)''') == '0'


def test_cache_missing_status_is_retryable_failure(database, cache, monkeypatch):
    sid = new_store(database)
    monkeypatch.setattr(da, 'find_store_ids', lambda email: [sid])
    monkeypatch.setattr(da, '_sleep', lambda sec: None)
    assert da.attach(f'demo-{sid}@example.invalid', watch=False, interval=1, timeout=30, end='2026-11-11', dry_run=False,
                     work_root=cache.parent / 'failed-run', template_dir=cache / 'missing') == 1
    assert database(f'SELECT status,error_code,retryable FROM demo_preparation WHERE store_id={sid}') == 'FAILED|DEMO_TEMPLATE_MISSING|t'
    assert database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}') == '0'


@pytest.mark.skipif(not os.environ.get('DEMO_PAO_CACHE'), reason='optional prepared canonical Pao artifact')
def test_prepared_pao_artifact_three_signups(database, monkeypatch):
    """Uses an already prepared, real canonical menu snapshot; never contacts staging."""
    from weglee_ai.demo_template import quote, schema_sql

    cache = Path(os.environ['DEMO_PAO_CACHE'])
    manifest, body = load_template(cache, '2026-11-11')
    actual_schema = json.loads(database(f'SELECT {schema_sql()}'))
    differences = {kind: {
        'expected_only': [row for row in manifest['schema'][kind] if row not in actual_schema[kind]][:3],
        'actual_only': [row for row in actual_schema[kind] if row not in manifest['schema'][kind]][:3],
    } for kind in ('columns', 'constraints')}
    assert actual_schema['columns'] == manifest['schema']['columns'], differences
    assert all(row in actual_schema['constraints'] for row in manifest['schema']['constraints']), differences
    # The optional market fixture is verified by its separate V23 integration tests.
    if not os.environ.get('DEMO_MARKET_MIGRATION'):
        body = (cache / 'history.sql').read_text()
    def literal(value):
        if value is None:
            return 'NULL'
        if isinstance(value, bool):
            return 'TRUE' if value else 'FALSE'
        return str(value) if isinstance(value, (int, float)) else quote(value)
    times = []
    monkeypatch.setattr(da, '_run_script', lambda *args: pytest.fail('runtime generator prohibited'))
    for _ in range(3):
        sid = int(database("INSERT INTO store(name,display_name) VALUES ('canonical','canonical') RETURNING id"))
        database(f"""INSERT INTO account(email,password_hash,name) VALUES ('demo-{sid}@example.invalid','hash','fixture');
        INSERT INTO store_member(account_id,store_id,role) SELECT id,{sid},'OWNER' FROM account WHERE email='demo-{sid}@example.invalid';
        INSERT INTO ingredient_category(store_id,code,name,sort_order) VALUES ({sid},'OTHER','기타',99);
        INSERT INTO demo_preparation(store_id,status) VALUES ({sid},'PREPARING');""")
        for row in manifest['semantic']['menu']:
            database(f"INSERT INTO menu(store_id,name,kind,active,price,source) VALUES ({sid},{','.join(map(literal,row))},'NAVER')")
        for row in manifest['semantic']['ingredient']:
            database(f"""INSERT INTO ingredient(store_id,name,unit,kind,storage_type,shelf_life_days,sourcing_type,price_sensitive,safety_days,
            storage_location,category_id,category_source) VALUES ({sid},{','.join(map(literal,row))},'WAREHOUSE',
            (SELECT id FROM ingredient_category WHERE store_id={sid}),'AUTO')""")
        for menu, ingredient, qty in manifest['semantic']['recipe']:
            database(f"""INSERT INTO recipe(store_id,menu_id,ingredient_id,qty_per_serving) SELECT {sid},m.id,i.id,{qty}
            FROM menu m JOIN ingredient i ON i.store_id=m.store_id WHERE m.store_id={sid} AND m.name={quote(menu)} AND i.name={quote(ingredient)}""")
        sql = render_copy(body, manifest, sid, f'demo-{sid}@example.invalid', 1)
        start = time.monotonic()
        database(stdin=sql)
        times.append(round(time.monotonic() - start, 3))
        database(stdin=sql)
        assert database(f'SELECT status FROM demo_preparation WHERE store_id={sid}') == 'DATA_READY'
        for table, count in manifest['counts'].items():
            assert int(database(f'SELECT count(*) FROM {table} WHERE store_id={sid}')) == count
        if os.environ.get('DEMO_MARKET_MIGRATION'):
            assert database(f'SELECT count(*) FROM store_market_quote WHERE store_id={sid}') == '64'
    print(f'Pao canonical 730days pre-generated once / runtime0generators / copy seconds x3={times}')


def test_stale_attempt_and_owner_change_never_write(database, cache):
    manifest, body = load_template(cache, '2026-11-11')
    sid = new_store(database)
    sql = render_copy(body, manifest, sid, f'demo-{sid}@example.invalid', 1)
    database(f'UPDATE demo_preparation SET attempt=2 WHERE store_id={sid}')
    with pytest.raises(da.AttachError, match='DEMO_PREPARATION_STALE'):
        database(stdin=sql)
    da.fail_preparation(sid, 1, 'DEMO_ATTACH_FAILED')
    assert database(f'SELECT status,attempt FROM demo_preparation WHERE store_id={sid}') == 'PREPARING|2'
    sql = render_copy(body, manifest, sid, 'changed@example.invalid', 2)
    with pytest.raises(da.AttachError, match='DEMO_OWNER_CHANGED'):
        database(stdin=sql)
    assert database(f'SELECT count(*) FROM pos_sale WHERE store_id={sid}') == '0'


def test_count_validation_failure_rolls_back_every_table(database, cache):
    manifest, body = load_template(cache, '2026-11-11')
    sid = new_store(database)
    broken = dict(manifest, counts=dict(manifest['counts'], pos_sale=1))
    with pytest.raises(da.AttachError, match='DEMO_COUNT_MISMATCH'):
        database(stdin=render_copy(body, broken, sid, f'demo-{sid}@example.invalid', 1))
    for table in manifest['counts']:
        assert database(f'SELECT count(*) FROM {table} WHERE store_id={sid}') == '0'
