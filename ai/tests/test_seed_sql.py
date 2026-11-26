import re
from dataclasses import fields

import pandas as pd
import pytest

from weglee_ai.sample_data import generate
from weglee_ai.seed_sql import CATEGORIES_FILE, DEMO_EMAIL, DEMO_PASSWORD_HASH_ENV, STORE_NAME, build_seed_sql


def _write_sample(data, out):
    for f in fields(data):
        getattr(data, f.name).to_csv(out / f"{f.name}.csv", index=False, encoding="utf-8-sig")
    return out


TEST_HASH = "$2a$10$" + "Ab1." * 13 + "A"


@pytest.fixture(autouse=True, scope="module")
def demo_hash_env():
    mp = pytest.MonkeyPatch()
    mp.setenv(DEMO_PASSWORD_HASH_ENV, TEST_HASH)
    yield
    mp.undo()


@pytest.fixture(scope="module")
def sample_dir(tmp_path_factory):
    return _write_sample(generate(), tmp_path_factory.mktemp("sample"))


@pytest.fixture(scope="module")
def sql(sample_dir):
    return build_seed_sql(sample_dir)


def _rows_per_table(sql: str) -> dict[str, int]:
    """INSERT 문마다 VALUES 튜플 줄("    (" 로 시작)을 세어 표별로 더한다."""
    counts: dict[str, int] = {}
    for stmt in sql.split("INSERT INTO ")[1:]:
        table = stmt.split(maxsplit=1)[0]
        body = stmt.split(";\n", 1)[0]
        counts[table] = counts.get(table, 0) + sum(1 for line in body.splitlines() if line.startswith("    ("))
    return counts


def test_row_counts_match_csv(sample_dir, sql):
    csv = {name: len(pd.read_csv(sample_dir / f"{name}.csv")) for name in [
        "menus", "ingredients", "suppliers", "supplier_products", "recipes",
        "pos_sales", "closed_days", "purchase_docs", "purchase_doc_lines",
    ]}
    ts = pd.read_csv(sample_dir / "true_stock.csv")
    first_day = ts[ts["stock_date"] == ts["stock_date"].min()]
    last_day = ts[ts["stock_date"] == ts["stock_date"].max()]
    counts = _rows_per_table(sql)
    # 화면 충족(S15P31A308-240): price_history 는 INSERT … SELECT(문서 줄에서) 라 VALUES 줄이 없다
    assert counts.pop("price_history") == 0
    # 발주 이력(S15P31A308-242)도 문서 · 줄에서 INSERT … SELECT 한다
    assert counts.pop("purchase_order") == 0 and counts.pop("purchase_order_item") == 0
    assert counts == {
        "store": 1,
        "account": 1,
        "store_member": 1,
        "ingredient_category": 11,  # 가게마다 기본 11종 (S15P31A308-273)
        "menu": csv["menus"],
        "menu_alias": csv["menus"],  # 샘플은 메뉴마다 pos_name 이 있다
        "ingredient": csv["ingredients"],
        "supplier": csv["suppliers"],
        "supplier_product": csv["supplier_products"],
        "recipe": csv["recipes"],
        "pos_sale": csv["pos_sales"],
        "store_closed_day": csv["closed_days"],
        "purchase_doc": csv["purchase_docs"],
        "purchase_doc_line": csv["purchase_doc_lines"],
        "stock_count": len(first_day) + len(last_day),  # 첫날 실사 + 마지막 날 실사(화면 충족)
    }


def test_wrapped_in_one_transaction(sql):
    code = [line for line in sql.splitlines() if line.strip() and not line.startswith("--")]
    assert code[0] == "BEGIN;"
    assert code[-1] == "COMMIT;"
    assert sql.count("BEGIN;") == 1 and sql.count("COMMIT;") == 1


def test_true_stock_first_and_last_day_only(sample_dir, sql):
    # 첫날 실사(추정 재고 출발점 · 매입 문서 kind 기준) + 마지막 날 실사(화면 충족, S15P31A308-240). 그 사이 날은 없다
    ts = pd.read_csv(sample_dir / "true_stock.csv")
    days = sorted(ts["stock_date"].unique())
    first, second, last = days[0], days[1], days[-1]
    stmts = [s.split(";\n", 1)[0] for s in sql.split("INSERT INTO stock_count")[1:]]
    assert len(stmts) == 2
    assert first in stmts[0] and second not in stmts[0] and last not in stmts[0]
    # 마지막 날 마감 재고는 다음 날 0시 실사로 넣는다 — 백엔드가 실사일 당일 RECEIVE 문서를 다시 더하지 않게
    next_day = f"{pd.Timestamp(last) + pd.Timedelta(days=1):%Y-%m-%d} 00:00:00+09"
    assert next_day in stmts[1] and f"{last} 23:59:59" not in stmts[1] and first not in stmts[1]


def test_last_count_is_after_every_last_day_receive_doc(sample_dir, sql):
    # 마지막 실사(가게 시간대 날짜)가 마지막 매입 문서 날짜보다 뒤여야 백엔드 doc_date >= 실사일 조건에 걸리는 문서가 없다
    ts = pd.read_csv(sample_dir / "true_stock.csv")
    docs = pd.read_csv(sample_dir / "purchase_docs.csv")
    last_count_day = pd.Timestamp(ts["stock_date"].max()) + pd.Timedelta(days=1)
    assert pd.to_datetime(docs["doc_date"]).max() < last_count_day


def test_quote_in_names_is_escaped(tmp_path):
    d = generate()
    old = d.menus.loc[0, "name"]
    new = "할머니's 돈까스"
    d.menus.loc[0, ["name", "pos_name"]] = new
    d.pos_sales.loc[d.pos_sales["menu_name"] == old, "menu_name"] = new
    d.suppliers.loc[0, "name"] = "O'Brien 축산"
    sql = build_seed_sql(_write_sample(d, tmp_path))
    assert "'할머니''s 돈까스'" in sql
    assert "'O''Brien 축산'" in sql
    # 이스케이프 안 된 따옴표가 남아 있지 않다: 문자열 리터럴을 지우고 나면 따옴표가 없어야 한다
    stripped = re.sub(r"'(?:[^']|'')*'", "", sql)
    assert "'" not in stripped


def test_unknown_menu_in_sales_fails(tmp_path):
    d = generate()
    d.pos_sales.loc[0, "menu_name"] = "없는메뉴"
    with pytest.raises(ValueError, match="없는메뉴"):
        build_seed_sql(_write_sample(d, tmp_path))


def test_demo_account(sql):
    assert len(TEST_HASH) == 60
    assert f"'{DEMO_EMAIL}'" in sql and TEST_HASH in sql
    assert "WEGLEE_DEMO_PASSWORD 로 공유" in sql
    assert "'OWNER'" in sql


@pytest.mark.parametrize("value", [None, "", "$2y$10$" + "x" * 53, "$2a$10$short"])
def test_missing_or_bad_hash_raises_with_var_name(sample_dir, monkeypatch, value):
    if value is None:
        monkeypatch.delenv(DEMO_PASSWORD_HASH_ENV)
    else:
        monkeypatch.setenv(DEMO_PASSWORD_HASH_ENV, value)
    with pytest.raises(ValueError, match=DEMO_PASSWORD_HASH_ENV):
        build_seed_sql(sample_dir)


def test_no_csv_ids_inserted(sql):
    # 모든 PK 는 IDENTITY 가 매긴다 — INSERT 칸 목록에 id 가 없다
    for stmt in sql.split("INSERT INTO ")[1:]:
        cols = stmt.split("(", 1)[1].split(")", 1)[0]
        assert "id" not in [c.strip() for c in cols.split(",")]


def _verify_block(sql: str) -> str:
    blocks = [b.split("$$;", 1)[0] for b in sql.split("DO $$")[1:]]
    blocks = [b for b in blocks if "seed 검증" in b]
    assert len(blocks) == 1, "검증 DO 블록이 하나 있어야 한다"
    return blocks[0]


def _verify_counts(block: str) -> dict[str, int]:
    """검증 블록의 'SELECT count(*) INTO n FROM <표>; IF n <> <기대>' 를 {표: 기대} 로."""
    return {t: int(n) for t, n in re.findall(r"count\(\*\) INTO n FROM (\w+);\s*IF n <> (\d+)", block)}


def test_verify_block_counts_match_csv(sample_dir, sql):
    # 검증 블록의 기대 건수 = 생성 시점 데이터에서 센 건수 (하드코딩 아님)
    block = _verify_block(sql)
    want = _rows_per_table(sql)
    want.pop("price_history")  # 화면 충족 블록이 따로 센다
    want.pop("purchase_order"), want.pop("purchase_order_item")  # 발주 이력 블록이 따로 센다
    ts = pd.read_csv(sample_dir / "true_stock.csv")
    want["stock_count"] = int((ts["stock_date"] == ts["stock_date"].min()).sum())  # 검증 블록은 마지막 날 실사 전에 돈다
    assert _verify_counts(block) == want
    # 블록은 COMMIT 직전, 트랜잭션 안에 있어 실패하면 전체가 되돌려진다
    assert sql.index("seed 검증") < sql.index("COMMIT;")
    assert "RAISE EXCEPTION" in block


def test_verify_block_checks_null_supplier_product(sample_dir, sql):
    lines = pd.read_csv(sample_dir / "purchase_doc_lines.csv")
    block = _verify_block(sql)
    m = re.search(r"FROM purchase_doc_line WHERE supplier_product_id IS NULL;\s*IF n <> (\d+)", block)
    assert m, "supplier_product_id IS NULL 검사가 없다"
    assert int(m.group(1)) == int(lines["supplier_product_id"].isna().sum())
    m = re.search(r"FROM purchase_doc_line WHERE ingredient_id IS NULL;\s*IF n <> (\d+)", block)
    assert m, "ingredient_id IS NULL 검사가 없다"
    assert int(m.group(1)) == int(lines["ingredient_id"].isna().sum())


def _doc_kinds(sql: str) -> list[tuple[str, str]]:
    """purchase_doc VALUES 줄에서 [(문서 날짜, kind)]. 날짜는 거래처마다 겹칠 수 있어 목록으로 둔다."""
    stmt = sql.split("INSERT INTO purchase_doc ", 1)[1].split(";\n", 1)[0]
    return list(re.findall(r"'(\d{4}-\d{2}-\d{2})', '(PAST|RECEIVE|[A-Z]+)'", stmt))


def test_doc_kind_by_stock_count_date(tmp_path):
    d = generate()
    first_day = pd.Timestamp(d.true_stock["stock_date"].min()).strftime("%Y-%m-%d")
    before = (pd.Timestamp(first_day) - pd.Timedelta(days=1)).strftime("%Y-%m-%d")
    # 경계: 실사일 당일 · 전날 문서를 하나씩 만든다 (CSV 의 kind 는 일부러 반대로 둔다)
    d.purchase_docs.loc[0, ["doc_date", "kind"]] = [first_day, "RECEIVE"]
    d.purchase_docs.loc[1, ["doc_date", "kind"]] = [before, "RECEIVE"]
    d.purchase_docs.loc[2, "kind"] = "PAST"
    kinds = _doc_kinds(build_seed_sql(_write_sample(d, tmp_path)))
    assert len(kinds) == len(d.purchase_docs)
    for doc_date, kind in kinds:
        expected = "RECEIVE" if doc_date > first_day else "PAST"
        assert kind == expected, (doc_date, kind)
    by_date = {}
    for doc_date, kind in kinds:
        by_date.setdefault(doc_date, set()).add(kind)
    assert by_date[first_day] == {"PAST"} and by_date[before] == {"PAST"}
    assert by_date[str(d.purchase_docs.loc[2, "doc_date"])] == {"RECEIVE"}


def test_pos_sale_source_is_excel(sql):
    stmt = sql.split("INSERT INTO pos_sale ", 1)[1].split(";\n", 1)[0]
    assert "source" in stmt.split("\n", 1)[0]
    assert "'EXCEL'" in stmt


# ---- 화면 충족 (S15P31A308-240): 판매가 · 대표 상품 · 거래처 요일 · 단가 이력 · 마지막 날 실사 ----


def _screen(sql: str) -> str:
    return sql[sql.index("-- ===== 화면 충족"):sql.index("COMMIT;")]


def _screen_counts(sql: str) -> dict[str, int]:
    block = sql.split("-- seed 화면 충족 검증")[1]
    return {
        what.strip(): int(n)
        for what, n in re.findall(r"화면 충족 검증 실패: \S+ ([^%]+?) % 행 \(기대 (\d+)\)", block)
    }


def test_screen_section_after_develop_statements(sql):
    # develop 출력 줄을 바꾸지 않고 기존 검증 블록 뒤 · COMMIT 앞에 더한다
    assert sql.index("seed 검증") < sql.index("-- ===== 화면 충족") < sql.index("COMMIT;")
    assert sql.count("-- ===== 화면 충족") == 1 and sql.count("BEGIN;") == 1 and sql.count("COMMIT;") == 1


def test_screen_menu_price_and_primary_product(sample_dir, sql):
    screen = _screen(sql)
    menus = pd.read_csv(sample_dir / "menus.csv")
    stmt = screen.split("UPDATE menu ", 1)[1].split(";\n", 1)[0]
    for name, price in zip(menus["name"], menus["price"], strict=False):
        assert f"('{name}', {int(price)})" in stmt
    sp = pd.read_csv(sample_dir / "supplier_products.csv")
    stmt = screen.split("UPDATE ingredient ", 1)[1].split(";\n", 1)[0]
    assert "unit_status = 'LOCKED'" in stmt and "primary_supplier_product_id = sp.id" in stmt
    for name in sp["product_name"]:
        assert f"'{name}'" in stmt
    assert "delivery_days = string_to_array" in screen and "cutoff_time" in screen


def test_screen_verify_counts(sample_dir, sql):
    lines = pd.read_csv(sample_dir / "purchase_doc_lines.csv")
    ts = pd.read_csv(sample_dir / "true_stock.csv")
    counts = _screen_counts(sql)
    priced = int((lines["supplier_product_id"].notna() & (lines["unit_price"] > 0)).sum())
    assert priced > 0
    assert counts == {
        "menu.price NULL": 0,
        "ingredient.primary_supplier_product_id NULL": 0,
        "ingredient.unit_status LOCKED 아님": 0,
        "supplier.delivery_days NULL": 0,
        "price_history DOC": priced,
        "stock_count": int((ts["stock_date"] == ts["stock_date"].min()).sum() + (ts["stock_date"] == ts["stock_date"].max()).sum()),
    }
    ph = _screen(sql).split("INSERT INTO price_history", 1)[1].split(";\n", 1)[0]
    assert "'DOC'" in ph and "doc_line_id" in ph and "AT TIME ZONE st.timezone" in ph


def test_screen_statements_scoped_to_store(sql):
    screen = _screen(sql)
    store = re.escape("(SELECT id FROM store WHERE name = '샘플 돈까스')")
    for stmt in re.findall(r"(?m)^(?:UPDATE|INSERT INTO) [^;]+;", screen):
        assert re.search(store, stmt) or "CROSS JOIN (SELECT id FROM store WHERE name = '샘플 돈까스') AS st" in stmt, stmt[:80]


def test_screen_multi_store(tmp_path):
    out = tmp_path / "m"
    for i, key in enumerate(["chicken-01", "pasta-02"], 1):
        d = generate(days=20, template=key.split("-")[0], seed=15 + i)
        (out / key).mkdir(parents=True)
        _write_sample(d, out / key)
    pd.DataFrame(
        {"store_key": ["chicken-01", "pasta-02"], "template": ["chicken", "pasta"],
         "store_name": ["샘플 치킨 1호점", "샘플 파스타 2호점"], "scale": [1.0, 1.0], "seed": [16, 17]}
    ).to_csv(out / "stores.csv", index=False, encoding="utf-8-sig")
    for append in (False, True):
        sql = build_seed_sql(out, append=append)
        assert sql.count("-- ===== 화면 충족") == 2
        # 가게별 검증 블록(stock_count 첫날만) 뒤에 화면 충족이 온다
        assert sql.rindex("seed 검증") < sql.index("-- ===== 화면 충족")
        for name in ["샘플 치킨 1호점", "샘플 파스타 2호점"]:
            assert f"WHERE m.store_id = (SELECT id FROM store WHERE name = '{name}')" in sql
            assert f"JOIN store st ON st.id = l.store_id AND st.id = (SELECT id FROM store WHERE name = '{name}')" in sql


# ---- 발주 이력 (S15P31A308-242): 매입 문서마다 보내고 입고 끝난 발주서 1장 ----


def _orders(sql: str) -> str:
    return sql[sql.index("-- ===== 발주 이력"):sql.index("COMMIT;")]


def _order_counts(sql: str, label: str = DEMO_EMAIL) -> dict[str, int]:
    """발주 이력 검증 블록의 {설명: 기대 수} (label 가게 것만)."""
    return {
        what.strip(): int(n)
        for who, what, n in re.findall(r"발주 이력 검증 실패: (\S+) ([^%]+?) % 행 \(기대 (\d+)\)", sql)
        if who == label
    }


def _expected_orders(lines: pd.DataFrame, n_docs: int) -> dict[str, int]:
    itemable = lines["supplier_product_id"].notna() & lines["ingredient_id"].notna() & (lines["qty"].fillna(0) > 0)
    bad = lines["ingredient_id"].notna() & ~itemable  # 재고에 들어갈 줄인데 발주 줄이 될 수 없다 → 그 문서는 잇지 않는다
    docs = set(lines.loc[itemable, "doc_id"]) - set(lines.loc[bad, "doc_id"])
    return {
        "purchase_order": len(docs),
        "purchase_order SENT 아님": 0,
        "purchase_order_item": int((itemable & lines["doc_id"].isin(docs)).sum()),
        "purchase_order_item 입고 안 됨": 0,
        "purchase_order_item 마지막 실사 뒤 입고": 0,
        "purchase_doc 발주서 미연결": n_docs - len(docs),
    }


def test_orders_one_per_doc_and_line(sample_dir, sql):
    lines = pd.read_csv(sample_dir / "purchase_doc_lines.csv")
    docs = pd.read_csv(sample_dir / "purchase_docs.csv")
    counts = _order_counts(sql)
    assert counts == _expected_orders(lines, len(docs))
    # 샘플은 줄마다 상품 · 재료가 있다 — 발주서 수 = 문서 수, 품목 수 = 줄 수, 모든 문서가 발주서에 이어진다
    assert counts["purchase_order"] == len(docs) > 0
    assert counts["purchase_order_item"] == len(lines)
    assert counts["purchase_doc 발주서 미연결"] == 0
    assert sql.index("-- ===== 화면 충족") < sql.index("-- ===== 발주 이력") < sql.index("COMMIT;")


def test_orders_sent_and_received(sql):
    orders = _orders(sql)
    po = orders.split("INSERT INTO purchase_order ", 1)[1].split("RETURNING", 1)[0]
    assert "'SENT'" in po and "sent_at" in po and "created_at" in po
    # 주문일 = 문서 날짜 − 거래처 리드타임(모르면 1일), 가게 시간대
    assert "COALESCE(s.lead_time_days, 1)" in orders and "AT TIME ZONE st.timezone" in orders
    item = orders.split("INSERT INTO purchase_order_item", 1)[1].split(";\n", 1)[0]
    for col in ("expected_date", "received_qty", "received_at", "received_unit_price"):
        assert col in item.split("\n", 1)[0]
    assert "recommendation_id" not in item and "cancelled_at" not in item
    # 미입고 SENT(추천의 입고 예정)는 만들지 않는다 — 검증 블록이 0 을 확인한다
    assert "received_at IS NULL" in orders


def test_orders_skip_doc_with_unlinkable_line(tmp_path):
    # 경계: 재고에 들어갈 줄 하나가 거래처 상품이 없으면 그 문서는 발주서를 만들지 않고 잇지도 않는다
    # (이으면 그 줄이 주기형 입고에서 빠진다 — ReceiptConfirmService.linkPurchaseOrder 와 같은 규칙)
    d = generate()
    doc_id = d.purchase_doc_lines["doc_id"].iloc[0]
    first = d.purchase_doc_lines.index[d.purchase_doc_lines["doc_id"] == doc_id][0]
    d.purchase_doc_lines.loc[first, "supplier_product_id"] = None
    sql = build_seed_sql(_write_sample(d, tmp_path))
    lines = pd.read_csv(tmp_path / "purchase_doc_lines.csv")
    counts = _order_counts(sql)
    assert counts == _expected_orders(lines, len(d.purchase_docs))
    assert counts["purchase_order"] == len(d.purchase_docs) - 1
    assert counts["purchase_doc 발주서 미연결"] == 1
    assert counts["purchase_order_item"] == int((lines["doc_id"] != doc_id).sum())


def test_orders_scoped_to_store(sql):
    st = "(SELECT id FROM store WHERE name = '샘플 돈까스')"
    orders = _orders(sql)
    for stmt in re.findall(r"(?m)^(?:WITH|UPDATE|INSERT INTO) [^;]+;", orders):
        assert st in stmt, stmt[:80]
    for check in re.findall(r"SELECT count\(\*\) INTO n FROM [^;]+;", orders):
        assert st in check, check


def test_orders_multi_store_and_append(tmp_path):
    out = tmp_path / "m"
    names = {"chicken-01": "샘플 치킨 1호점", "pasta-02": "샘플 파스타 2호점"}
    for i, key in enumerate(names, 1):
        d = generate(days=20, template=key.split("-")[0], seed=15 + i)
        (out / key).mkdir(parents=True)
        _write_sample(d, out / key)
    pd.DataFrame(
        {"store_key": list(names), "template": ["chicken", "pasta"],
         "store_name": list(names.values()), "scale": [1.0, 1.0], "seed": [16, 17]}
    ).to_csv(out / "stores.csv", index=False, encoding="utf-8-sig")
    for append in (False, True):
        sql = build_seed_sql(out, append=append)
        assert sql.count("-- ===== 발주 이력") == 2
        for key, name in names.items():
            lines = pd.read_csv(out / key / "purchase_doc_lines.csv")
            docs = pd.read_csv(out / key / "purchase_docs.csv")
            assert _order_counts(sql, f"demo+{key}@wiggly.local") == _expected_orders(lines, len(docs))
            st = f"(SELECT id FROM store WHERE name = '{name}')"
            assert f"JOIN store st ON st.id = pd.store_id AND st.id = {st}" in sql
            assert f"JOIN store st ON st.id = l.store_id AND st.id = {st}" in sql


# ---- 재료 카테고리 (S15P31A308-273 · 274): 가게마다 기본 11종, 재료는 ingredient_categories.csv 값 아니면 기타 ----


STORAGE_LOCATION = {"FROZEN": "FREEZER", "CHILLED": "FRIDGE", "FRESH": "FRIDGE", "AMBIENT": "SHELF"}


def _ingredient_rows(sql: str) -> list[str]:
    stmt = sql.split("INSERT INTO ingredient (")[1].split(";\n", 1)[0]
    assert "category_id, category_source" in stmt
    assert "JOIN ingredient_category c ON c.store_id = st.id AND c.code = v.category_code" in stmt
    return [line.rstrip(",") for line in stmt.splitlines() if line.startswith("    (")]


def test_ingredient_category_defaults_other_and_storage_location(sample_dir, sql):
    cats = sql.split("INSERT INTO ingredient_category (")[1].split(";\n", 1)[0]
    assert cats.count("    (") == 11 and "('OTHER', '기타', 99)" in cats and "('MEAT', '육류', 1)" in cats
    rows = _ingredient_rows(sql)
    ingredients = pd.read_csv(sample_dir / "ingredients.csv")
    assert len(rows) == len(ingredients)
    for line, (kind, storage) in zip(rows, ingredients[["kind", "storage_type"]].itertuples(index=False), strict=False):
        location = "WAREHOUSE" if kind == "CYCLE" or pd.isna(storage) else STORAGE_LOCATION[storage]
        assert line.endswith(f"'{location}', 'OTHER', 'AUTO')"), line  # 파일이 없으면 전부 기타 · AUTO


def test_ingredient_categories_file_restores_codes(tmp_path):
    d = generate()
    first, second = d.ingredients.loc[0, "name"], d.ingredients.loc[1, "name"]
    pd.DataFrame({
        "store_name": [STORE_NAME, STORE_NAME, "다른 가게"],
        "ingredient_name": [first, second, first],
        "category_code": ["SAUCE", "", "MEAT"],
        "category_name": ["양념·소스", "디저트", "육류"],
        "category_source": ["OWNER", "OWNER", "AUTO"],
    }).to_csv(tmp_path / CATEGORIES_FILE, index=False, encoding="utf-8-sig")
    rows = _ingredient_rows(build_seed_sql(_write_sample(d, tmp_path)))
    by_name = {line.split("', '", 1)[0].removeprefix("    ('"): line for line in rows}
    assert by_name[first].endswith("'SAUCE', 'OWNER')")   # 파일 값 그대로 — 사장님이 고른 출처도
    assert by_name[second].endswith("'OTHER', 'AUTO')")   # 사장님이 만든 카테고리(코드 없음)는 시드가 만들지 않아 기타 · 출처는 AUTO 로 낮춘다
    assert sum(line.endswith("'OTHER', 'AUTO')") for line in rows) == len(rows) - 1  # 다른 가게 줄은 쓰지 않는다


def test_ingredient_categories_file_next_to_stores_csv(tmp_path):
    out = tmp_path / "m"
    d = generate(days=20, template="chicken", seed=21)
    (out / "chicken-01").mkdir(parents=True)
    _write_sample(d, out / "chicken-01")
    pd.DataFrame(
        {"store_key": ["chicken-01"], "template": ["chicken"], "store_name": ["샘플 치킨 1호점"], "scale": [1.0], "seed": [21]}
    ).to_csv(out / "stores.csv", index=False, encoding="utf-8-sig")
    first = d.ingredients.loc[0, "name"]
    pd.DataFrame({
        "store_name": ["샘플 치킨 1호점"], "ingredient_name": [first], "category_code": ["MEAT"],
        "category_name": ["육류"], "category_source": ["AUTO"],
    }).to_csv(out / "ingredient_categories_latest.csv", index=False, encoding="utf-8-sig")  # 여러 가게면 stores.csv 옆, 서버 파일 이름 그대로도 된다
    rows = _ingredient_rows(build_seed_sql(out))
    assert rows[0].startswith(f"    ('{first}'") and rows[0].endswith("'MEAT', 'AUTO')")
