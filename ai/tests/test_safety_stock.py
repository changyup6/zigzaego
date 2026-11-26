"""안전재고 두 식 비교 (S15P31A308-106). 손계산과 맞는지 본다."""

import math

import pandas as pd
import pytest

from weglee_ai.safety_stock import compare, daily_consumption

END = pd.Timestamp("2026-10-18")


def _tables(kind="BOM", storage="CHILLED", R=2, L=1):
    ingredients = pd.DataFrame([{"ingredient_id": 1, "name": "등심", "unit": "G", "kind": kind,
                                 "storage_type": storage, "safety_days": 1}])
    supplier_products = pd.DataFrame([{"ingredient_id": 1, "supplier_id": 9}])
    suppliers = pd.DataFrame([{"supplier_id": 9, "order_cycle_days": R, "lead_time_days": L}])
    return ingredients, supplier_products, suppliers


def _daily(values):
    days = pd.date_range(end=END, periods=len(values), freq="D")
    return pd.DataFrame({1: values}, index=days)


def test_통계식과_기존식을_손계산대로():
    values = [100.0, 200.0] * 15  # 30일, 최근 28일 σ = 표본 표준편차
    row = compare(_daily(values), *_tables()).iloc[0]
    sigma = pd.Series(values[-28:]).std(ddof=1)
    assert row["σ"] == pytest.approx(sigma)
    assert row["통계식"] == pytest.approx(1.65 * sigma * math.sqrt(3))
    assert row["기존 식"] == pytest.approx(sum(values[-7:]) / 7)
    assert row["식"] == "STAT"


def test_휴무일은_σ에_0으로_들어가지_않는다():
    sales = pd.DataFrame({"sold_date": ["2026-10-01", "2026-10-03"], "menu_id": [1, 1], "qty": [10, 10]})
    recipes = pd.DataFrame({"menu_id": [1], "ingredient_id": [1], "qty_per_serving": [100]})
    daily = daily_consumption(sales, recipes)
    assert list(daily.index.strftime("%m-%d")) == ["10-01", "10-03"]  # 10/02 휴무 행 없음
    assert daily[1].std(ddof=1) == 0.0


def test_영업일에_안_쓴_재료는_0():
    sales = pd.DataFrame({"sold_date": ["2026-10-01", "2026-10-02"], "menu_id": [1, 2], "qty": [10, 5]})
    recipes = pd.DataFrame({"menu_id": [1, 2], "ingredient_id": [1, 2], "qty_per_serving": [100, 1]})
    daily = daily_consumption(sales, recipes)
    assert daily.loc["2026-10-02", 1] == 0 and daily.loc["2026-10-01", 2] == 0


def test_이력_28일_미만이면_기존_식():
    row = compare(_daily([100.0, 200.0] * 10), *_tables()).iloc[0]  # 20일
    assert row["식"] == "DAYS_N" and "28일 미만" in row["비고"]


def test_주기형은_비교하지_않는다():
    row = compare(_daily([100.0] * 30), *_tables(kind="CYCLE")).iloc[0]
    assert row["식"] == "DAYS_N" and "주기형" in row["비고"]
    assert row["튐"] == ""  # 튀는 재료 목록에 섞이지 않는다


def test_z는_보관_방식별():
    values = [100.0, 200.0] * 15
    by = {s: compare(_daily(values), *_tables(storage=s)).iloc[0]["z"] for s in ["FROZEN", "AMBIENT", "CHILLED", "FRESH"]}
    assert by == {"FROZEN": 1.88, "AMBIENT": 1.88, "CHILLED": 1.65, "FRESH": 1.28}


def test_기준은_가게_첫_판매일_재료를_안_쓴_영업일은_0():
    # 백엔드 RecommendationService.firstSaleDate = 가게 MIN(pos_sale.sold_date) (MR !106 #1 정정)
    # 가게 영업 10일, 재료는 마지막 3일만 300 → 최근 7영업일 중 4일이 0 → 900 / 7
    row = compare(_daily([0.0] * 7 + [300.0] * 3), *_tables()).iloc[0]
    assert row["7일 평균"] == pytest.approx(900 / 7)
    assert row["이력(일)"] == 10 and row["식"] == "DAYS_N"


def test_재료를_늦게_써도_가게_이력이_28일이면_통계식():
    values = [0.0] * 25 + [100.0, 200.0, 100.0, 200.0, 100.0]  # 가게 30일, 재료는 5일만
    row = compare(_daily(values), *_tables()).iloc[0]
    assert row["이력(일)"] == 30 and row["식"] == "STAT"
    assert row["σ"] == pytest.approx(pd.Series(values[-28:]).std(ddof=1))


def test_한_번도_안_쓴_재료는_σ_0_통계식_0():
    daily = pd.DataFrame({2: [100.0] * 30}, index=pd.date_range(end=END, periods=30, freq="D"))  # 다른 재료만 팔림
    row = compare(daily, *_tables()).iloc[0]
    assert row["7일 평균"] == 0.0 and row["σ"] == 0.0 and row["통계식"] == 0.0 and row["식"] == "STAT"


def test_표본이_1개면_σ_0():
    # 리뷰 MR !106 #4: 백엔드 standardDeviation 은 표본 2개 미만이면 0 (pandas std 는 NaN)
    daily = pd.DataFrame({1: [300.0]}, index=pd.DatetimeIndex([END]))
    row = compare(daily, *_tables()).iloc[0]
    assert row["σ"] == 0.0 and row["통계식"] == 0.0


def test_R_L은_대표_상품의_거래처():
    # 리뷰 MR !106 #3: 백엔드는 ingredient.primary_supplier_product_id 의 거래처
    ingredients, _, _ = _tables()
    ingredients = ingredients.assign(primary_supplier_product_id=[22])
    supplier_products = pd.DataFrame([
        {"supplier_product_id": 21, "ingredient_id": 1, "supplier_id": 8},  # 첫 줄이지만 대표 아님
        {"supplier_product_id": 22, "ingredient_id": 1, "supplier_id": 9},
    ])
    suppliers = pd.DataFrame([{"supplier_id": 8, "order_cycle_days": 7, "lead_time_days": 3},
                              {"supplier_id": 9, "order_cycle_days": 2, "lead_time_days": 1}])
    row = compare(_daily([100.0, 200.0] * 15), ingredients, supplier_products, suppliers).iloc[0]
    assert (row["R"], row["L"]) == (2, 1)


def test_거래처를_못_찾으면_R_L_1일():
    ingredients, _, suppliers = _tables()
    no_product = pd.DataFrame(columns=["supplier_product_id", "ingredient_id", "supplier_id"])
    row = compare(_daily([100.0, 200.0] * 15), ingredients, no_product, suppliers).iloc[0]
    assert (row["R"], row["L"]) == (1, 1) and "거래처 없음" in row["비고"]
    assert row["통계식"] == pytest.approx(1.65 * pd.Series([100.0, 200.0] * 14).std(ddof=1) * math.sqrt(2))


@pytest.mark.parametrize("storage", [None, float("nan")])
def test_보관_방식이_없으면_냉장_z(storage):
    # 리뷰 MR !106 #2: 백엔드 zOf(null) = 1.65. CSV 빈 칸은 NaN 으로 들어온다
    row = compare(_daily([100.0, 200.0] * 15), *_tables(storage=storage)).iloc[0]
    assert row["z"] == 1.65


def test_튀는_재료와_이유():
    big = compare(_daily([0.0, 300.0] * 15), *_tables(R=7, L=2)).iloc[0]  # 들쭉날쭉 + 입고 간격 김
    small = compare(_daily([100.0, 101.0] * 15), *_tables(R=1, L=1)).iloc[0]  # 고름 + 짧음
    assert big["튐"] == "크다" and "들쭉날쭉" in big["비고"] and "김" in big["비고"]
    assert small["튐"] == "작다" and "고름" in small["비고"]


@pytest.mark.parametrize("primary", [None, float("nan")])
def test_대표상품이_명시적으로_없으면_첫상품을_쓰지_않는다(primary):
    ingredients, products, suppliers = _tables(R=7, L=3)
    ingredients = ingredients.assign(primary_supplier_product_id=[primary])
    products = products.assign(supplier_product_id=[21])

    row = compare(_daily([100.0, 200.0] * 15), ingredients, products, suppliers).iloc[0]

    assert (row["R"], row["L"]) == (1, 1)
    assert row["통계식"] == pytest.approx(1.65 * pd.Series([100.0, 200.0] * 14).std(ddof=1) * math.sqrt(2))
