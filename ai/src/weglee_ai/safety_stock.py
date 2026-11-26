"""안전재고 두 식 비교 (지라 S15P31A308-106, 설계 5.3, DECISIONS #10 · #28).

기존 식:  SS = 최근 7일 평균 일 소비 × N일
통계식:   SS = z × σ × √(R + L)
          σ = 최근 28일 중 영업일 일 소비량의 표본 표준편차(n−1). 휴무일은 0 으로 넣지 않고 뺀다. 표본 2개 미만이면 0
          z = 보관 방식별 (냉동 · 상온 1.88 / 냉장 1.65 / 신선 1.28). 보관 방식이 비어 있으면 냉장(1.65)
          R · L = 재료 대표 상품(primary_supplier_product_id)의 거래처 발주 주기 · 리드타임. 없으면 1일 · 1일
가게 판매 이력 28일 미만 · 주기형 → 기존 식(DAYS_N)

기준일은 **가게** 첫 판매일이다 — 재료를 처음 쓴 날이 아니다. 이력 28일 판단 · 7일 평균 · σ 모두 가게 영업일에서 재고,
그 재료를 안 쓴 영업일은 0 이다. 백엔드 RecommendationService(firstSaleDate = MIN(pos_sale.sold_date)) ·
SafetyStockCalculator(S15P31A308-108)와 같은 기준이고, 기준을 바꾸면 -108 과 함께 바꾼다.
비교 범위는 DECISIONS #28 의 두 기본식이다. 배송 요일 · 마감 시각에 따른 실제 R · L(#89),
가격에 따른 R 조정 · 실사 손실 가산(#93)은 계산하지 않는다. 운영 추천 전체를 재현하는 도구가 아니다.
휴무일은 백엔드 store_closed_day 표 대신 판매가 없는 날로 본다 (데이터 출처 차이).
"""

import math

import pandas as pd

Z_BY_STORAGE = {"FROZEN": 1.88, "AMBIENT": 1.88, "CHILLED": 1.65, "FRESH": 1.28}
DEFAULT_Z = 1.65  # 보관 방식 없음 → 냉장. 백엔드 zOf(null) 과 같다
SIGMA_DAYS = 28
RECENT_DAYS = 7
MIN_HISTORY_DAYS = 28
DEFAULT_CYCLE_DAYS = 1  # 거래처 설정 없음 → 매일 배송 식자재몰 기준 (백엔드 RecommendationService 와 같다)
DEFAULT_LEAD_TIME_DAYS = 1
# 통계식 ÷ 기존 식이 이 범위를 벗어나면 "튀는 재료" (검증용 기준, 실데이터를 보고 조정)
HIGH_RATIO = 2.0
LOW_RATIO = 0.5


def daily_consumption(sales: pd.DataFrame, recipes: pd.DataFrame) -> pd.DataFrame:
    """영업일 × 재료 일 소비량 (기준 단위). 행 = 판매가 있는 날(영업일), 열 = ingredient_id.

    sales: sold_date · menu_id · qty (channel 은 합산). recipes: menu_id · ingredient_id · qty_per_serving.
    영업일인데 그 재료를 안 쓴 날은 0, 휴무일은 행이 없다.
    """
    s = sales.assign(sold_date=pd.to_datetime(sales["sold_date"]))
    open_days = pd.DatetimeIndex(sorted(s["sold_date"].unique()))
    used = s.merge(recipes, on="menu_id")
    used = used.assign(amount=used["qty"] * used["qty_per_serving"])
    table = used.pivot_table(index="sold_date", columns="ingredient_id", values="amount", aggfunc="sum", fill_value=0)
    return table.reindex(index=open_days, fill_value=0)


def _window(daily: pd.DataFrame, as_of: pd.Timestamp, days: int) -> pd.DataFrame:
    return daily[(daily.index > as_of - pd.Timedelta(days=days)) & (daily.index <= as_of)]


def _reason(cv: float, z: float, cover: int) -> str:
    """비율이 튄 이유 한 줄. 비율 ≈ z × CV × √(R+L) ÷ N 이라 큰 요인을 적는다."""
    parts = []
    if z * cv >= 1.0:
        parts.append(f"하루 소비가 들쭉날쭉(CV {cv:.2f})")
    elif z * cv < 0.4:
        parts.append(f"하루 소비가 고름(CV {cv:.2f})")
    if cover >= 5:
        parts.append(f"다음 입고까지 김(R+L {cover}일)")
    elif cover <= 2:
        parts.append(f"다음 입고까지 짧음(R+L {cover}일)")
    return " · ".join(parts) or f"CV {cv:.2f} · R+L {cover}일"


def _cycle_and_lead(ing, supplier_products: pd.DataFrame, suppliers_by_id: pd.DataFrame) -> tuple[int, int, str]:
    """재료 대표 상품의 거래처 R · L. 백엔드 RecommendationService 와 같이 못 찾으면 1일 · 1일."""
    if hasattr(ing, "primary_supplier_product_id"):
        primary = ing.primary_supplier_product_id
        if pd.isna(primary):
            return DEFAULT_CYCLE_DAYS, DEFAULT_LEAD_TIME_DAYS, "거래처 없음 — R · L 1일"
        product = supplier_products[supplier_products["supplier_product_id"] == primary]
    else:
        product = supplier_products[supplier_products["ingredient_id"] == ing.ingredient_id].head(1)
    if product.empty or product["supplier_id"].iloc[0] not in suppliers_by_id.index:
        return DEFAULT_CYCLE_DAYS, DEFAULT_LEAD_TIME_DAYS, "거래처 없음 — R · L 1일"
    sup = suppliers_by_id.loc[product["supplier_id"].iloc[0]]
    cycle = DEFAULT_CYCLE_DAYS if pd.isna(sup["order_cycle_days"]) else int(sup["order_cycle_days"])
    lead = DEFAULT_LEAD_TIME_DAYS if pd.isna(sup["lead_time_days"]) else int(sup["lead_time_days"])
    return cycle, lead, ""


def compare(
    daily: pd.DataFrame,
    ingredients: pd.DataFrame,
    supplier_products: pd.DataFrame,
    suppliers: pd.DataFrame,
    as_of=None,
) -> pd.DataFrame:
    """재료별 기존 식 vs 통계식 표.

    ingredients: ingredient_id · name · unit · kind · storage_type · safety_days [· primary_supplier_product_id]
    supplier_products: supplier_product_id · ingredient_id · supplier_id
    suppliers: supplier_id · order_cycle_days · lead_time_days
    as_of: 기준일(그날 마감까지 포함). 기본 = 마지막 영업일

    대표 상품 열이 없으면(샘플 데이터) 그 재료의 supplier_products 첫 줄을 대표로 본다. 거래처를 못 찾으면 R · L 1일.
    """
    as_of = pd.Timestamp(as_of) if as_of is not None else daily.index.max()
    hist = daily[daily.index <= as_of]
    sigma_win, recent_win = _window(hist, as_of, SIGMA_DAYS), _window(hist, as_of, RECENT_DAYS)
    store_first = hist.index.min()  # 가게 첫 판매일 (백엔드 MIN(pos_sale.sold_date))
    history_days = (as_of - store_first).days + 1 if pd.notna(store_first) else 0
    suppliers_by_id = suppliers.set_index("supplier_id")

    rows = []
    for ing in ingredients.itertuples():
        base = {"ingredient_id": ing.ingredient_id, "재료": ing.name, "단위": ing.unit, "종류": ing.kind,
                "보관": ing.storage_type}
        if ing.kind == "CYCLE":
            rows.append({**base, "식": "DAYS_N", "튐": "", "비고": "주기형 — 입고 기록으로 소진 속도를 잰다. 비교 대상 아님"})
            continue
        R, L, supplier_note = _cycle_and_lead(ing, supplier_products, suppliers_by_id)
        z = Z_BY_STORAGE.get(ing.storage_type, DEFAULT_Z)
        zeros = pd.Series(0.0, index=hist.index)
        used_28 = sigma_win[ing.ingredient_id] if ing.ingredient_id in hist.columns else zeros.loc[sigma_win.index]
        used_7 = recent_win[ing.ingredient_id] if ing.ingredient_id in hist.columns else zeros.loc[recent_win.index]
        mean7 = float(used_7.mean()) if len(used_7) else 0.0
        mean28 = float(used_28.mean()) if len(used_28) else 0.0
        sigma = float(used_28.std(ddof=1)) if len(used_28) >= 2 else 0.0  # 백엔드: 표본 2개 미만이면 0
        ss_old = mean7 * ing.safety_days
        ss_stat = z * sigma * math.sqrt(R + L)
        method = "STAT" if history_days >= MIN_HISTORY_DAYS else "DAYS_N"
        ratio = ss_stat / ss_old if ss_old > 0 else math.nan
        cv = sigma / mean28 if mean28 > 0 else math.nan
        flag = "크다" if ratio > HIGH_RATIO else "작다" if ratio < LOW_RATIO else ""
        notes = [_reason(cv, z, R + L) if flag else ("가게 이력 28일 미만 — 기존 식" if method == "DAYS_N" else "")]
        note = " · ".join(n for n in notes + [supplier_note] if n)
        rows.append({
            **base, "z": z, "R": R, "L": L, "이력(일)": history_days, "영업일(28일)": len(sigma_win),
            "7일 평균": mean7, "σ": sigma, "CV": cv, "기존 식": ss_old, "통계식": ss_stat, "비율": ratio,
            "식": method, "튐": flag, "비고": note,
        })
    return pd.DataFrame(rows)
