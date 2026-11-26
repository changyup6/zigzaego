"""가게 틀 · 1~5년 · 여러 가게 생성 (S15P31A308-240).

틀별 불변식(휴무 · BOM · 재고 · 최소 주문 · 발주 주기 · 문서 합계 · 결정성)은 test_sample_data.py 가 틀마다 매개변수로 돈다.
여기는 틀의 모양 · 배달 비중 · 기간 · CLI · 여러 가게 seed.sql 을 본다.
"""

import hashlib
import re
import runpy
import sys
from functools import cache
from pathlib import Path

import pandas as pd
import pytest

from weglee_ai.sample_data import HOLIDAYS, LOCAL_EVENTS, generate
from weglee_ai.seed_sql import DEMO_EMAIL, STORE_NAME, build_seed_sql, demo_email
from weglee_ai.store_templates import SCALE_RANGE, TEMPLATES, parse_template_keys, plan_stores

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "make_sample_data.py"
CATALOG = ROOT / "seed" / "ingredient_catalog.csv"
KEYS = list(TEMPLATES)
# 계약(지라 240)에 적힌 업종별 배달 비중. donkatsu 는 예전 값 그대로라 메뉴 가중 평균(0.38)
NOMINAL_DELIVERY = {"donkatsu": 0.38, "bunsik": 0.45, "chinese": 0.6, "korean": 0.25, "chicken": 0.8, "cafe": 0.15, "pasta": 0.35}
CSV_NAMES = {
    "menus", "ingredients", "suppliers", "supplier_products", "recipes", "pos_sales", "closed_days",
    "purchase_docs", "purchase_doc_lines", "true_stock", "events", "weather", "true_demand", "true_usage",
}


@cache
def _gen(key: str):
    return generate(template=key)


def _run_cli(monkeypatch, *args):
    monkeypatch.setattr(sys, "argv", [str(SCRIPT), *args])
    runpy.run_path(str(SCRIPT), run_name="__main__")


def _digest(folder: Path) -> dict[str, str]:
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.rglob("*.csv"))}


# ---- 틀 모양 ----


@pytest.mark.parametrize("key", KEYS)
def test_template_shape(key):
    t = TEMPLATES[key]
    assert t.key == key and t.label
    menus = [m[0] for m in t.menus]
    assert 6 <= len(menus) <= 10 and len(set(menus)) == len(menus)
    assert 2 <= len(t.suppliers) <= 4
    kinds = {i[2] for i in t.ingredients}
    assert kinds == {"BOM", "ONE_TO_ONE", "CYCLE"}
    assert {i[1] for i in t.ingredients} <= {"EA", "G", "ML"}
    names = [i[0] for i in t.ingredients]
    assert len(set(names)) == len(names)
    assert set(t.month_factor) == set(range(1, 13))
    assert t.menu_month_factor and set(t.menu_month_factor) <= set(menus)
    # 레시피: 모든 메뉴에 있고, 주기형은 없다. 1:1 재료는 한 메뉴에 1개
    cycle = {i[0] for i in t.ingredients if i[2] == "CYCLE"}
    assert set(t.recipes) == set(menus)
    used = {ing for items in t.recipes.values() for ing in items}
    assert used == set(names) - cycle
    for ing in (i[0] for i in t.ingredients if i[2] == "ONE_TO_ONE"):
        assert [items[ing] for items in t.recipes.values() if ing in items] == [1]
    assert set(t.cycle_usage) == cycle
    for follow, per_bowl in t.cycle_usage.values():
        assert set(follow) <= set(menus) and per_bowl > 0
    # 거래처: 재료마다 있는 거래처, 거래처마다 재료 1개 이상, 거래처 안 상품명은 겹치지 않는다 (DB unique)
    suppliers = {s[0] for s in t.suppliers}
    assert {i[4] for i in t.ingredients} == suppliers
    products = [(i[4], i[5]) for i in t.ingredients]
    assert len(set(products)) == len(products)
    for _, base, share, price in t.menus:
        assert base > 0 and 0 < share < 1 and price > 0


@pytest.mark.parametrize("key", KEYS)
def test_template_matches_catalog(key):
    # 카탈로그에 같은 이름의 재료가 있으면 단위 · 보관이 같다
    catalog = pd.read_csv(CATALOG).set_index("name")
    for name, unit, _, storage, *_ in TEMPLATES[key].ingredients:
        if name in catalog.index:
            assert (unit, storage) == (catalog.loc[name, "unit"], catalog.loc[name, "storage_type"]), name


@pytest.mark.parametrize("key", KEYS)
def test_template_delivery_share(key):
    t = TEMPLATES[key]
    assert abs(t.delivery_share - NOMINAL_DELIVERY[key]) <= 0.05
    s = _gen(key).pos_sales
    measured = s.loc[s["channel"] == "DELIVERY", "qty"].sum() / s["qty"].sum()
    # 비 · 공휴일 · 일요일 배달 배수로 조금 오르지만 틀 값 ±0.1 안
    assert abs(measured - t.delivery_share) <= 0.1, measured
    assert abs(measured - NOMINAL_DELIVERY[key]) <= 0.1, measured


def test_template_weekend_shapes():
    # 한식(평일 점심 상권)은 금 · 토가 평일보다 적고, 치킨은 가장 크다
    assert TEMPLATES["korean"].weekend_factor < 1
    assert TEMPLATES["chicken"].weekend_factor == max(t.weekend_factor for t in TEMPLATES.values())
    s = _gen("korean").pos_sales.copy()
    s["dow"] = pd.to_datetime(s["sold_date"]).dt.dayofweek
    daily = s.groupby(["sold_date", "dow"])["qty"].sum().reset_index()
    assert daily[daily["dow"].isin([4, 5])]["qty"].mean() < daily[daily["dow"].isin([0, 1, 2, 3])]["qty"].mean()


def test_template_cafe_iced_drink_summer():
    td = _gen("cafe").true_demand.copy()
    td["month"] = pd.to_datetime(td["sold_date"]).dt.month
    tea = td[td["menu_name"] == "아이스티"].groupby("month")["expected_qty"].mean()
    assert tea[7] > tea[1] * 1.5


def test_scale_multiplies_demand():
    a = generate(days=60, template="pasta")
    b = generate(days=60, template="pasta", scale=1.5)
    # 휴무 · 비는 scale 보다 먼저 뽑으므로 같은 날 · 같은 행이다
    assert a.closed_days.equals(b.closed_days)
    ratio = b.true_demand["expected_qty"] / a.true_demand["expected_qty"]
    assert ratio.between(1.5 - 1e-3, 1.5 + 1e-3).all()
    assert (b.menus["base_qty"] == a.menus["base_qty"] * 1.5).all()


def test_generate_rejects_unknown_template_and_bad_scale():
    with pytest.raises(ValueError, match="sushi"):
        generate(template="sushi")
    with pytest.raises(ValueError, match="scale"):
        generate(scale=0)


# ---- 기간 1~5년 ----


def test_five_year_holidays_closed(tmp_path, monkeypatch):
    out = tmp_path / "y5"
    _run_cli(monkeypatch, "--out", str(out), "--days", "1826")
    w = pd.read_csv(out / "weather.csv")
    assert len(w) == 1826 and w["date"].min() == "2021-10-12" and w["date"].max() == "2026-10-11"
    ev = pd.read_csv(out / "events.csv")
    assert not ev.duplicated(["event_date"]).any()
    shut = ev[ev["kind"] == "HOLIDAY_CLOSED"]
    closed = set(pd.read_csv(out / "closed_days.csv")["closed_date"])
    sold = set(pd.read_csv(out / "pos_sales.csv")["sold_date"])
    # 2021 추석(09-21)은 시작일 앞이라 없다. 2022~2026 은 해마다 설 · 추석 두 번
    per_year = shut["event_date"].str[:4].value_counts().to_dict()
    assert per_year == {"2022": 2, "2023": 2, "2024": 2, "2025": 2, "2026": 2}
    assert set(shut["event_date"]) <= closed
    assert not set(shut["event_date"]) & sold
    local = ev[ev["kind"] == "LOCAL_EVENT"]["event_date"].str[:4].value_counts()
    assert all(local.get(y, 0) >= 4 for y in ["2022", "2023", "2025"])


@pytest.mark.parametrize("key", KEYS)
def test_five_year_stock_never_negative(key):
    d = generate(template=key, days=1826)
    assert (d.true_stock["qty"] >= 0).all()
    assert len(d.weather) == 1826


# 새 틀 6종은 기대 소비로 발주한다(order_policy EXPECTED). 음수가 났던 시드(5·8·9·11·12·13·24·25·27·30)를 꼭 넣는다
NEW_KEYS = [k for k in KEYS if TEMPLATES[k].order_policy == "EXPECTED"]
AUDIT_SEEDS = [5, 8, 9, 11, 12, 13, 24, 25, 27, 30]


def test_stock_never_negative_policy_split():
    assert NEW_KEYS == ["bunsik", "chinese", "korean", "chicken", "cafe", "pasta"]
    assert TEMPLATES["donkatsu"].order_policy == "PERIOD"  # 기본 출력 보존


@pytest.mark.parametrize("seed", [1, 2, 3, *AUDIT_SEEDS])
@pytest.mark.parametrize("key", NEW_KEYS)
def test_stock_never_negative_730(key, seed):
    assert generate(template=key, seed=seed).true_stock["qty"].min() >= 0


@pytest.mark.parametrize("scale", [0.6, 1.8])
@pytest.mark.parametrize("seed", [9, 11])
@pytest.mark.parametrize("key", NEW_KEYS)
def test_stock_never_negative_1826_scaled(key, seed, scale):
    assert generate(template=key, seed=seed, days=1826, scale=scale).true_stock["qty"].min() >= 0


def test_holiday_range_table():
    days = {d for d, *_ in HOLIDAYS}
    assert min(days) == "2021-01-01" and max(days) == "2026-12-25"
    closed = sorted(d for d, _, k, _ in HOLIDAYS if k == "HOLIDAY_CLOSED")
    assert closed == [
        "2021-02-12", "2021-09-21", "2022-02-01", "2022-09-10", "2023-01-22", "2023-09-29",
        "2024-02-10", "2024-09-17", "2025-01-29", "2025-10-06", "2026-02-17", "2026-09-25",
    ]
    for day in ["2022-03-09", "2022-06-01", "2024-04-10", "2023-05-29", "2023-10-02", "2024-10-01",
                "2021-08-16", "2021-10-04", "2021-10-11", "2022-09-12", "2022-10-10", "2023-01-24", "2024-02-12", "2024-05-06"]:
        assert day in days, day
    assert len(days) == len(HOLIDAYS)
    assert not days & {d for d, *_ in LOCAL_EVENTS}


@pytest.mark.parametrize("kwargs", [{"end_date": "2027-01-05", "days": 10}, {"days": 2200}, {"end_date": "2021-01-05", "days": 10}])
def test_holiday_range_generate_error(kwargs):
    with pytest.raises(ValueError, match=r"공휴일 표 범위\(2021-01-01~2026-12-31\) 밖"):
        generate(**kwargs)


@pytest.mark.parametrize("args", [["--end", "2027-01-05"], ["--days", "2200"]])
def test_holiday_range_cli_exit_2(tmp_path, monkeypatch, capsys, args):
    out = tmp_path / "x"
    with pytest.raises(SystemExit) as e:
        _run_cli(monkeypatch, "--out", str(out), *args)
    assert e.value.code == 2
    err = capsys.readouterr().err.strip().splitlines()
    assert len(err) == 1 and "공휴일 표 범위(2021-01-01~2026-12-31) 밖" in err[0]
    assert not out.exists()


# ---- 여러 가게 ----


def test_multi_store_plan():
    plan = plan_stores(parse_template_keys("all"), 14, 15)
    assert [p["template"] for p in plan] == KEYS * 2
    assert plan[4] == {"store_key": "chicken-05", "template": "chicken", "store_name": "샘플 치킨 5호점",
                       "scale": plan[4]["scale"], "seed": 20}
    assert plan == plan_stores(KEYS, 14, 15)
    assert plan_stores(["cafe"], 2, 15)[1]["store_key"] == "cafe-02"


def test_multi_store_cli_all_14(tmp_path, monkeypatch):
    a, b = tmp_path / "a", tmp_path / "b"
    _run_cli(monkeypatch, "--out", str(a), "--template", "all", "--stores", "14", "--seed", "15")
    _run_cli(monkeypatch, "--out", str(b), "--template", "all", "--stores", "14", "--seed", "15")
    stores = pd.read_csv(a / "stores.csv")
    assert list(stores.columns) == ["store_key", "template", "store_name", "scale", "seed"]
    assert len(stores) == 14 and stores["store_key"].is_unique and stores["store_name"].is_unique
    assert stores["template"].value_counts().to_dict() == {k: 2 for k in KEYS}
    assert stores["scale"].between(*SCALE_RANGE).all() and stores["scale"].is_unique
    assert list(stores["seed"]) == list(range(16, 30))
    folders = sorted(p.name for p in a.iterdir() if p.is_dir())
    assert folders == sorted(stores["store_key"])
    for key in folders:
        assert {p.stem for p in (a / key).glob("*.csv")} == CSV_NAMES
    assert not (a / "pos_sales.csv").exists()  # 여러 가게면 맨 위에 평평한 CSV 가 없다
    # 같은 명령 두 번이면 바이트 단위로 같다
    assert _digest(a) == _digest(b) and len(_digest(a)) == 14 * 14 + 1
    # 규모 배수가 판매량에 들어간다: 같은 틀 두 가게의 base_qty 비가 scale 비와 같다
    row = stores.set_index("store_key")
    m1 = pd.read_csv(a / "chicken-05" / "menus.csv")["base_qty"]
    m2 = pd.read_csv(a / "chicken-12" / "menus.csv")["base_qty"]
    want = row.loc["chicken-12", "scale"] / row.loc["chicken-05", "scale"]
    assert ((m2 / m1) - want).abs().max() < 1e-2


def test_multi_store_single_non_donkatsu(tmp_path, monkeypatch):
    # 돈까스가 아닌 틀 하나면 1곳이어도 가게 폴더 + stores.csv (seed.sql 가게 이름이 맞게)
    out = tmp_path / "c"
    _run_cli(monkeypatch, "--out", str(out), "--template", "chicken", "--days", "30")
    stores = pd.read_csv(out / "stores.csv")
    assert stores[["store_key", "store_name", "seed"]].values.tolist() == [["chicken-01", "샘플 치킨 1호점", 16]]
    assert (out / "chicken-01" / "pos_sales.csv").exists() and not (out / "pos_sales.csv").exists()
    sql = build_seed_sql(out)
    assert "'샘플 치킨 1호점'" in sql and f"'{STORE_NAME}'" not in sql


@pytest.fixture(scope="module")
def multi_dir(tmp_path_factory):
    out = tmp_path_factory.mktemp("multi")
    mp = pytest.MonkeyPatch()
    try:
        # chicken · pasta 는 둘 다 "콜라" 재료 · 메뉴가 있다 (가게 사이 같은 이름)
        _run_cli(mp, "--out", str(out), "--template", "chicken,pasta", "--stores", "3", "--days", "40", "--seed", "7")
    finally:
        mp.undo()
    return out


def _stores(folder):
    return pd.read_csv(folder / "stores.csv")


def test_multi_store_seed_sql(multi_dir):
    sql = build_seed_sql(multi_dir)
    stores = _stores(multi_dir)
    assert sql.count("BEGIN;") == 1 and sql.count("COMMIT;") == 1
    assert "store 표에 이미 행이 있다" in sql  # 빈 DB 검사
    assert sql.count("INSERT INTO store ") == 3 and sql.count("INSERT INTO account ") == 3
    for s in stores.itertuples():
        email = demo_email(s.store_key)
        assert email == f"demo+{s.store_key}@wiggly.local" and f"'{email}'" in sql
        pos = len(pd.read_csv(multi_dir / s.store_key / "pos_sales.csv"))
        sid = re.escape(f"(SELECT id FROM store WHERE name = '{s.store_name}')")
        assert re.search(rf"FROM pos_sale WHERE store_id = {sid}; IF n <> {pos} THEN", sql), s.store_key
        assert re.search(rf"FROM store WHERE name = '{re.escape(s.store_name)}'; IF n <> 1 THEN", sql)
    # CROSS JOIN 가게는 stores.csv 의 이름뿐
    assert set(re.findall(r"CROSS JOIN \(SELECT id FROM store WHERE name = '([^']+)'\) AS st", sql)) == set(stores["store_name"])
    assert DEMO_EMAIL not in sql and f"'{STORE_NAME}'" not in sql


def test_multi_store_seed_sql_lookups_scoped(multi_dir):
    sql = build_seed_sql(multi_dir)
    joins = [line for line in sql.splitlines() if re.match(r"(LEFT )?JOIN (menu|ingredient|supplier|purchase_doc) ", line)]
    assert joins
    names = set(_stores(multi_dir)["store_name"])
    for line in joins:
        # 데이터 INSERT 는 st, 화면 충족 UPDATE 는 가게 이름 서브쿼리로 가게를 건다
        m = re.search(r"store_id = \(SELECT id FROM store WHERE name = '([^']+)'\)", line)
        assert "store_id = st.id" in line or (m and m.group(1) in names), line
    sp = [line for line in sql.splitlines() if line.startswith("LEFT JOIN supplier_product ")]
    assert sp and all("sp.supplier_id = ss.id" in line for line in sp)


def test_multi_store_seed_sql_append(multi_dir):
    sql = build_seed_sql(multi_dir, append=True)
    stores = _stores(multi_dir)
    assert "store 표에 이미 행이 있다" not in sql
    pre = next(line for line in sql.splitlines() if line.startswith("DO $$ BEGIN IF EXISTS"))
    for s in stores.itertuples():
        assert f"'{s.store_name}'" in pre and f"'{demo_email(s.store_key)}'" in pre
    # 검증은 가게마다 — 표 전체를 세는 줄이 없다
    assert not re.search(r"count\(\*\) INTO n FROM \w+; IF", sql)


def test_multi_store_single_append_differs_only_in_checks(tmp_path):
    folder = tmp_path / "s"
    folder.mkdir()
    d = generate(days=20)
    for name in ["menus", "ingredients", "suppliers", "supplier_products", "recipes", "pos_sales", "closed_days",
                 "purchase_docs", "purchase_doc_lines", "true_stock"]:
        getattr(d, name).to_csv(folder / f"{name}.csv", index=False, encoding="utf-8-sig")
    plain, app = build_seed_sql(folder), build_seed_sql(folder, append=True)
    assert "store 표에 이미 행이 있다" in plain and "store 표에 이미 행이 있다" not in app
    assert f"name IN ('{STORE_NAME}')" in app and f"email IN ('{DEMO_EMAIL}')" in app
    body = lambda s: s[s.index("INSERT INTO store "):s.index("-- seed 검증")]  # noqa: E731
    assert body(plain) == body(app)


def test_multi_store_duplicate_names_fail(multi_dir, tmp_path):
    stores = _stores(multi_dir)
    stores.loc[1, "store_name"] = stores.loc[0, "store_name"]
    bad = tmp_path / "bad"
    bad.mkdir()
    stores.to_csv(bad / "stores.csv", index=False, encoding="utf-8-sig")
    with pytest.raises(ValueError, match="store_name"):
        build_seed_sql(bad)


# ---- CLI 경계 ----


@pytest.mark.parametrize(
    "args, needle",
    [(["--template", "sushi"], "sushi"), (["--stores", "0"], "--stores"), (["--days", "0"], "--days"), (["--template", " , "], "비었다")],
)
def test_cli_error_exit_2_and_no_csv(tmp_path, monkeypatch, capsys, args, needle):
    out = tmp_path / "out"
    with pytest.raises(SystemExit) as e:
        _run_cli(monkeypatch, "--out", str(out), *args)
    assert e.value.code == 2
    cap = capsys.readouterr()
    err = cap.err.strip().splitlines()
    assert len(err) == 1 and needle in err[0], cap.err
    assert cap.out == ""
    assert not out.exists() or not list(out.rglob("*.csv"))


def test_cli_list_templates(monkeypatch, capsys, tmp_path):
    _run_cli(monkeypatch, "--list-templates", "--out", str(tmp_path / "none"))
    lines = capsys.readouterr().out.strip().splitlines()
    assert [line.split()[0] for line in lines] == KEYS
    assert not (tmp_path / "none").exists()


def test_cli_default_still_flat(tmp_path, monkeypatch):
    # 돈까스 하나 · 1곳이면 예전처럼 평평하게. 예전 여러 가게 목록(stores.csv)이 남아 있으면 지운다
    out = tmp_path / "d"
    out.mkdir()
    (out / "stores.csv").write_text("store_key\n", encoding="utf-8")
    _run_cli(monkeypatch, "--out", str(out), "--days", "20")
    assert (out / "pos_sales.csv").exists() and not (out / "stores.csv").exists()
