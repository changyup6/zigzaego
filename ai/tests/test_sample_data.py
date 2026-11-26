import hashlib
import runpy
import sys
from dataclasses import fields
from functools import cache, lru_cache
from pathlib import Path

import pandas as pd
import pytest

from weglee_ai.sample_data import DAYS, END_DATE, MENUS, generate
from weglee_ai.seed_sql import build_seed_sql
from weglee_ai.store_templates import TEMPLATES

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "make_sample_data.py"


@lru_cache(maxsize=1)
def _default():
    # 읽기만 하는 새 테스트용. 값을 고치는 테스트는 generate() 를 직접 부른다
    return generate()


# 가게 틀마다 도는 불변식 (S15P31A308-240). donkatsu 가 기본 틀이라 _gen("donkatsu") 는 generate() 와 같다
TEMPLATE_KEYS = list(TEMPLATES)


@cache
def _gen(template: str):
    # 읽기만 하는 테스트용 (틀마다 730일 한 번씩)
    return _default() if template == "donkatsu" else generate(template=template)


def _write(data, out: Path) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    for f in fields(data):
        getattr(data, f.name).to_csv(out / f"{f.name}.csv", index=False, encoding="utf-8-sig")
    return out


def _digest(folder: Path) -> dict[str, str]:
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(folder.glob("*.csv"))}


def test_spec_counts():
    d = generate()
    assert len(d.menus) == 8
    assert len(d.ingredients) == 12
    assert len(d.suppliers) == 3


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_three_ingredient_kinds(template):
    kinds = _gen(template).ingredients["kind"].value_counts()
    assert set(kinds.index) == {"BOM", "ONE_TO_ONE", "CYCLE"}
    assert kinds["CYCLE"] >= 2


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_units_are_base_units(template):
    # DECISIONS #33 · 스키마 V1 ingredient.unit
    assert set(_gen(template).ingredients["unit"]) <= {"EA", "G", "ML"}


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_closed_days_have_no_sales(template):
    d = _gen(template)
    sold = set(pd.to_datetime(d.pos_sales["sold_date"]))
    closed = set(pd.to_datetime(d.closed_days["closed_date"]))
    assert closed and not (sold & closed)


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_both_channels_every_open_day(template):
    s = _gen(template).pos_sales
    assert set(s["channel"]) == {"HALL", "DELIVERY"}
    per_day = s.groupby("sold_date")["channel"].nunique()
    assert (per_day == 2).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_no_zero_qty_rows(template):
    # 포스 원본처럼 안 팔린 메뉴는 행이 없다 (판매 데이터 사전 5절)
    assert (_gen(template).pos_sales["qty"] > 0).all()


def test_some_open_day_combos_have_no_sales():
    # 영업일인데 (메뉴, 채널) 행이 없는 날이 실제로 생겨야 0 채우기를 시험할 수 있다
    s = generate().pos_sales
    open_days = s.groupby("channel")["sold_date"].nunique()
    expected = sum(open_days[ch] * s["menu_name"].nunique() for ch in open_days.index)
    assert len(s) < expected


def test_weekend_is_higher():
    s = generate().pos_sales.copy()
    s["dow"] = pd.to_datetime(s["sold_date"]).dt.dayofweek
    daily = s.groupby(["sold_date", "dow"])["qty"].sum().reset_index()
    weekend = daily[daily["dow"].isin([4, 5])]["qty"].mean()
    weekday = daily[daily["dow"].isin([0, 1, 2, 3])]["qty"].mean()
    assert 1.25 < weekend / weekday < 1.55


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_every_recipe_ingredient_exists(template):
    d = _gen(template)
    assert set(d.recipes["ingredient_id"]) <= set(d.ingredients["ingredient_id"])
    assert set(d.recipes["menu_id"]) == set(d.menus["menu_id"])


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_cycle_ingredients_not_in_recipes(template):
    # 주기형은 메뉴와 연결하지 않는다 (DECISIONS #26)
    d = _gen(template)
    cycle = set(d.ingredients.loc[d.ingredients["kind"] == "CYCLE", "ingredient_id"])
    assert not cycle & set(d.recipes["ingredient_id"])


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_cycle_ingredients_have_3_receipts_or_more(template):
    d = _gen(template)
    cycle = d.ingredients.loc[d.ingredients["kind"] == "CYCLE", "ingredient_id"]
    counts = d.purchase_doc_lines.groupby("ingredient_id")["doc_id"].nunique()
    assert (counts.reindex(cycle, fill_value=0) >= 3).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_every_ingredient_has_receipts(template):
    d = _gen(template)
    assert set(d.purchase_doc_lines["ingredient_id"]) == set(d.ingredients["ingredient_id"])


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_purchase_doc_lines_match_supplier(template):
    d = _gen(template)
    lines = d.purchase_doc_lines.merge(d.purchase_docs, on="doc_id").merge(
        d.supplier_products, on="supplier_product_id", suffixes=("", "_sp")
    )
    assert (lines["supplier_id"] == lines["supplier_id_sp"]).all()
    assert (lines["ingredient_id"] == lines["ingredient_id_sp"]).all()
    assert (lines["qty"] >= lines["moq"]).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_receipts_within_sales_period(template):
    d = _gen(template)
    dates = pd.to_datetime(d.purchase_docs["doc_date"])
    sold = pd.to_datetime(d.pos_sales["sold_date"])
    assert dates.min() >= sold.min() and dates.max() <= pd.Timestamp(END_DATE)


# 29 · 214 · 523 은 첫날이 휴무인 시드 (리뷰 재현)
@pytest.mark.parametrize("template", TEMPLATE_KEYS)
@pytest.mark.parametrize("seed", [15, 1, 2, 3, 7, 29, 214, 523])
def test_stock_never_negative(seed, template):
    assert (generate(seed=seed, template=template).true_stock["qty"] >= 0).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_no_receipts_on_closed_days(template):
    d = _gen(template)
    closed = set(pd.to_datetime(d.closed_days["closed_date"]))
    assert not set(pd.to_datetime(d.purchase_docs["doc_date"])) & closed


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_receipts_meet_min_order_amount(template):
    d = _gen(template)
    lines = d.purchase_doc_lines.merge(d.supplier_products[["supplier_product_id", "price"]], on="supplier_product_id")
    totals = (lines["qty"] * lines["price"]).groupby(lines["doc_id"]).sum()
    minimum = d.purchase_docs.merge(d.suppliers, on="supplier_id").set_index("doc_id")["min_order_amount"]
    assert (totals >= minimum.loc[totals.index]).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_receipts_follow_order_cycle(template):
    # 같은 거래처의 입고 간격은 발주 주기 이상 (도착일이 휴무로 밀리면 더 길어질 수 있다)
    d = _gen(template)
    r = d.purchase_docs.merge(d.suppliers, on="supplier_id")
    r["doc_date"] = pd.to_datetime(r["doc_date"])
    closed = set(pd.to_datetime(d.closed_days["closed_date"]))
    for _, g in r.groupby("supplier_id"):
        cycle = int(g["order_cycle_days"].iloc[0])
        arrivals = list(g["doc_date"].sort_values())
        for prev, cur in zip(arrivals, arrivals[1:], strict=False):
            # 앞 입고가 휴무로 k 일 밀렸으면(바로 앞 k 일이 휴무) 다음 입고와의 간격은 그만큼 줄 수 있다.
            # 주문 간격은 늘 발주 주기 이상이다 (S15P31A308-240: 주기 7일 거래처가 생겨 예전의 "주기 − 1" 여유로는 모자랐다)
            delayed = 0
            while prev - pd.Timedelta(days=delayed + 1) in closed:
                delayed += 1
            assert (cur - prev).days >= cycle - max(1, delayed), (g["supplier_id"].iloc[0], prev, cur)


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_doc_lines_numbered_and_totals_match(template):
    d = _gen(template)
    lines = d.purchase_doc_lines
    assert not lines.duplicated(["doc_id", "line_no"]).any()
    assert (lines.groupby("doc_id")["line_no"].min() == 1).all()
    assert (lines["amount"] == lines["qty"] * lines["unit_price"]).all()
    totals = lines.groupby("doc_id")["amount"].sum()
    assert (d.purchase_docs.set_index("doc_id")["total_amount"] == totals).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_deterministic(template):
    a, b = generate(seed=1, template=template), generate(seed=1, template=template)
    assert a.pos_sales.equals(b.pos_sales)
    assert a.purchase_doc_lines.equals(b.purchase_doc_lines)


# ---- S15P31A308-162: 2년치 · 계절성 · 공휴일 · 행사 · 비 · BOM 일치 · 재현성 ----


def test_default_is_730_days_ending_end_date():
    d = _default()
    assert DAYS == 730
    w = pd.to_datetime(d.weather["date"])
    assert len(w) == 730
    assert w.min() == pd.Timestamp("2024-10-12") and w.max() == pd.Timestamp(END_DATE)
    sold = pd.to_datetime(d.pos_sales["sold_date"])
    assert sold.min() >= pd.Timestamp("2024-10-12") and sold.max() <= pd.Timestamp(END_DATE)


def test_180_days_still_runs():
    d = generate(days=180)
    assert len(d.weather) == 180
    assert len(d.pos_sales) > 0 and len(d.purchase_docs) > 0
    sold = pd.to_datetime(d.pos_sales["sold_date"])
    assert sold.min() >= pd.Timestamp(END_DATE) - pd.Timedelta(days=179)


def test_open_day_count_and_rows():
    # 영업일 = 전체 일수 − 휴무일. 판매가 있는 날 = 영업일
    d = _default()
    closed = set(pd.to_datetime(d.closed_days["closed_date"]))
    sold_days = set(pd.to_datetime(d.pos_sales["sold_date"]))
    assert len(sold_days) == 730 - len(closed)
    # 기대 수요는 영업일 × 메뉴 × 채널 한 행씩
    assert len(d.true_demand) == len(sold_days) * len(MENUS) * 2


def test_same_seed_is_byte_identical(tmp_path):
    a = _digest(_write(generate(seed=7), tmp_path / "a"))
    b = _digest(_write(generate(seed=7), tmp_path / "b"))
    assert a == b
    assert {"pos_sales.csv", "events.csv", "weather.csv", "true_demand.csv", "true_usage.csv"} <= set(a)
    c = _digest(_write(generate(seed=8), tmp_path / "c"))
    assert c["pos_sales.csv"] != a["pos_sales.csv"]


def test_pos_sales_columns_unchanged():
    # 파서 시험 형식(판매 데이터 사전) 그대로. 판매가는 menus 에만 둔다
    d = _default()
    assert list(d.pos_sales.columns) == ["sold_date", "menu_name", "qty", "channel"]
    assert "price" in d.menus.columns
    assert (d.menus["price"] > 0).all() and d.menus["price"].dtype.kind == "i"


def test_seollal_chuseok_closed_and_no_sales():
    d = _default()
    closed = set(pd.to_datetime(d.closed_days["closed_date"]))
    for day in ["2025-01-29", "2025-10-06", "2026-02-17", "2026-09-25"]:
        assert pd.Timestamp(day) in closed, day
    ev = d.events
    shut = set(pd.to_datetime(ev.loc[ev["kind"] == "HOLIDAY_CLOSED", "event_date"]))
    assert shut and shut <= closed
    assert (ev.loc[ev["kind"] == "HOLIDAY_CLOSED", "multiplier"] == 0).all()
    assert not shut & set(pd.to_datetime(d.pos_sales["sold_date"]))
    # 연휴 앞뒤 날은 문을 열되 판매가 준다
    eve = ev[ev["event_date"].astype(str) == "2025-01-28"]
    assert len(eve) == 1 and eve["kind"].iloc[0] == "HOLIDAY" and 0 < eve["multiplier"].iloc[0] < 1


def test_events_csv_shape():
    ev = _default().events
    assert list(ev.columns) == ["event_date", "name", "kind", "multiplier", "scope"]
    assert set(ev["kind"]) == {"HOLIDAY", "HOLIDAY_CLOSED", "LOCAL_EVENT"}
    assert set(ev["scope"]) == {"NATIONAL", "STORE"}
    assert (ev.loc[ev["kind"] == "LOCAL_EVENT", "scope"] == "STORE").all()
    assert (ev.loc[ev["kind"] != "LOCAL_EVENT", "scope"] == "NATIONAL").all()
    assert 4 <= ev.loc[ev["kind"] == "LOCAL_EVENT", "name"].nunique() <= 6
    dates = pd.to_datetime(ev["event_date"])
    assert dates.min() >= pd.Timestamp("2024-10-12") and dates.max() <= pd.Timestamp(END_DATE)
    assert not ev.duplicated(["event_date"]).any()
    got = set(ev["event_date"].astype(str))
    for day in ["2024-12-25", "2025-01-01", "2025-01-27", "2025-03-03", "2025-05-06", "2025-06-03",
                "2025-10-03", "2025-10-08", "2025-10-09", "2026-03-02", "2026-06-03", "2026-08-17"]:
        assert day in got, day


def test_weather_rain_only_flag_and_summer_wetter():
    w = _default().weather
    assert list(w.columns) == ["date", "rain"]
    assert set(w["rain"]) <= {0, 1}
    month = pd.to_datetime(w["date"]).dt.month
    assert w.loc[month.isin([7, 8]), "rain"].mean() > w.loc[~month.isin([7, 8]), "rain"].mean()
    assert 0 < w["rain"].sum() < len(w)


def _shares(d):
    td = d.true_demand.copy()
    td["sold_date"] = pd.to_datetime(td["sold_date"])
    piv = td.pivot_table(index=["sold_date", "menu_name"], columns="channel", values="expected_qty", aggfunc="sum")
    share = (piv["DELIVERY"] / (piv["DELIVERY"] + piv["HALL"])).rename("share").reset_index()
    rain = d.weather.assign(date=pd.to_datetime(d.weather["date"])).set_index("date")["rain"]
    share["rain"] = share["sold_date"].map(rain)
    ev = set(pd.to_datetime(d.events["event_date"]))
    hol = set(pd.to_datetime(d.events.loc[d.events["kind"] == "HOLIDAY", "event_date"]))
    share["event"] = share["sold_date"].isin(ev)
    share["holiday"] = share["sold_date"].isin(hol)
    share["dow"] = share["sold_date"].dt.dayofweek
    return share


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_menu_delivery_share_kept_on_plain_days(template):
    # 비 · 행사 없는 월~목: 기대값의 배달 비중이 틀 menus 3번째 값 그대로
    s = _shares(_gen(template))
    plain = s[(s["rain"] == 0) & ~s["event"] & s["dow"].isin([0, 1, 2, 3])]
    assert len(plain) > 0
    want = {m[0]: m[2] for m in TEMPLATES[template].menus}
    got = plain.groupby("menu_name")["share"].agg(["min", "max"])
    assert set(got.index) == set(want)
    for name, row in got.iterrows():
        # expected_qty 는 소수 4자리로 반올림해 낸다
        assert row["min"] == pytest.approx(want[name], abs=1e-4) and row["max"] == pytest.approx(want[name], abs=1e-4)


def test_delivery_share_rises_on_rain_and_holiday():
    s = _shares(_default())
    weekday = s[s["dow"].isin([0, 1, 2, 3])]
    base = weekday[(weekday["rain"] == 0) & ~weekday["event"]].groupby("menu_name")["share"].mean().rename("base")
    rainy = weekday[(weekday["rain"] == 1) & ~weekday["event"]]
    holiday = weekday[(weekday["rain"] == 0) & weekday["holiday"]]
    assert len(rainy) and len(holiday)
    for other in (rainy, holiday):
        merged = other.merge(base, on="menu_name")
        assert (merged["share"] > merged["base"]).all()


def test_seasonality_and_trend():
    d = _default()
    td = d.true_demand.copy()
    td["sold_date"] = pd.to_datetime(td["sold_date"])
    td = td[~td["sold_date"].isin(set(pd.to_datetime(d.events["event_date"])))]
    daily = td.groupby(["sold_date", "menu_name"])["expected_qty"].sum().reset_index()
    daily["month"] = daily["sold_date"].dt.month
    daily["year"] = daily["sold_date"].dt.year
    udon = daily[daily["menu_name"] == "우동"].groupby("month")["expected_qty"].mean()
    assert udon[1] > udon[7] * 1.3  # 우동: 겨울 > 여름
    per_day = daily.groupby(["year", "month"])["expected_qty"].sum() / daily.groupby(["year", "month"])["sold_date"].nunique()
    assert per_day[(2025, 12)] > per_day[(2025, 11)]  # 연말 증가
    assert per_day[(2025, 3)] < per_day[(2026, 3)] < per_day[(2025, 3)] * 1.2  # 완만한 성장


def _bom_usage(d) -> pd.Series:
    s = d.pos_sales.merge(d.menus[["menu_id", "name"]], left_on="menu_name", right_on="name").merge(d.recipes, on="menu_id")
    s["use"] = s["qty"] * s["qty_per_serving"]
    s["d"] = s["sold_date"].astype(str)
    return s.groupby(["d", "ingredient_id"])["use"].sum()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_usage_equals_sales_times_bom(template):
    d = _gen(template)
    assert list(d.true_usage.columns) == ["usage_date", "ingredient_id", "qty"]
    want = _bom_usage(d)
    u = d.true_usage.assign(d=d.true_usage["usage_date"].astype(str)).set_index(["d", "ingredient_id"])["qty"]
    non_cycle = set(d.ingredients.loc[d.ingredients["kind"] != "CYCLE", "ingredient_id"])
    u = u[u.index.get_level_values(1).isin(non_cycle) & (u > 0)]
    assert len(u) == len(want)
    assert (u.reindex(want.index) - want).abs().max() < 1e-6
    closed = set(d.closed_days["closed_date"].astype(str))
    on_closed = d.true_usage[d.true_usage["usage_date"].astype(str).isin(closed)]
    assert len(on_closed) and (on_closed["qty"] == 0).all()


@pytest.mark.parametrize("template", TEMPLATE_KEYS)
def test_stock_balance_sales_usage_receipts(template):
    # 판매 → 소비 → 입고: 오늘 재고 = 어제 재고 + 오늘 입고 − 오늘 소비 (재고는 0.1 단위 반올림)
    d = _gen(template)
    lines = d.purchase_doc_lines.merge(d.purchase_docs[["doc_id", "doc_date"]], on="doc_id").merge(
        d.supplier_products[["supplier_product_id", "base_qty_per_pack"]], on="supplier_product_id"
    )
    lines["recv"] = lines["qty"] * lines["base_qty_per_pack"]
    recv = lines.assign(d=lines["doc_date"].astype(str)).groupby(["d", "ingredient_id"])["recv"].sum().to_dict()
    use = d.true_usage.assign(d=d.true_usage["usage_date"].astype(str)).set_index(["d", "ingredient_id"])["qty"].to_dict()
    st = d.true_stock.assign(d=d.true_stock["stock_date"].astype(str)).set_index(["d", "ingredient_id"])["qty"].to_dict()
    days = sorted({k[0] for k in st})
    for ing in d.ingredients["ingredient_id"]:
        for prev, day in zip(days, days[1:], strict=False):
            delta = st[(day, ing)] - st[(prev, ing)]
            want = recv.get((day, ing), 0.0) - use[(day, ing)]
            assert abs(delta - want) < 0.11, (ing, day)


def test_seed_sql_ignores_generation_only_files(tmp_path):
    # events · weather · true_demand · true_usage 는 seed.sql 에 영향이 없다
    folder = _write(_default(), tmp_path / "s")
    with_files = build_seed_sql(folder)
    for name in ["events", "weather", "true_demand", "true_usage"]:
        (folder / f"{name}.csv").unlink()
    assert build_seed_sql(folder) == with_files


def _run_cli(monkeypatch, *args):
    monkeypatch.setattr(sys, "argv", [str(SCRIPT), *args])
    runpy.run_path(str(SCRIPT), run_name="__main__")


def test_cli_default_730_and_180(tmp_path, monkeypatch):
    _run_cli(monkeypatch, "--out", str(tmp_path / "d730"))
    assert len(pd.read_csv(tmp_path / "d730" / "weather.csv")) == 730
    _run_cli(monkeypatch, "--out", str(tmp_path / "d180"), "--days", "180", "--seed", "3")
    assert len(pd.read_csv(tmp_path / "d180" / "weather.csv")) == 180
    assert (tmp_path / "d180" / "events.csv").exists()
