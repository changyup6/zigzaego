"""상권 × 분기: 외식업 매출 · 전년 동기 대비 증감률 · 그 분기 이벤트 수."""

import pandas as pd

FOOD_PREFIX = "CS1000"  # 외식업 CS100001~CS100010


def _label(yyqu: str) -> str:
    return f"{yyqu[:4]}Q{yyqu[4]}"


def _quarters(start, end) -> list[str]:
    """start~end 가 걸친 분기 라벨 전부."""
    y, q = start.year, (start.month - 1) // 3 + 1
    last = (end.year, (end.month - 1) // 3 + 1)
    out = []
    while (y, q) <= last:
        out.append(f"{y}Q{q}")
        y, q = (y + 1, 1) if q == 4 else (y, q + 1)
    return out


MIN_PREV_AMT = 100_000_000  # 전년 매출이 이보다 작으면 증감률이 수천 % 로 튀어 분석을 망친다 (실데이터 +444,588% 사례)


def build_district_quarter(sales: pd.DataFrame, events: pd.DataFrame, food_only: bool = True,
                           min_prev_amt: float = MIN_PREV_AMT) -> pd.DataFrame:
    s = sales[sales["SVC_INDUTY_CD"].str.startswith(FOOD_PREFIX)] if food_only else sales
    g = s.groupby(["TRDAR_CD", "TRDAR_CD_NM", "STDR_YYQU_CD"], as_index=False).agg(
        sales_amt=("THSMON_SELNG_AMT", "sum"), sales_cnt=("THSMON_SELNG_CO", "sum"), weekend_amt=("WKEND_SELNG_AMT", "sum"),
        n_industries=("SVC_INDUTY_CD", "nunique"))  # 분기마다 업종 구성이 달라 전년 비교가 같은 업종끼리가 아님을 드러낸다
    amt = dict(zip(zip(g["TRDAR_CD"], g["STDR_YYQU_CD"]), g["sales_amt"]))
    prev = [amt.get((t, f"{int(c[:4]) - 1}{c[4]}")) for t, c in zip(g["TRDAR_CD"], g["STDR_YYQU_CD"])]
    g["prev_amt"] = pd.Series(prev, index=g.index, dtype=float)
    g["yoy_pct"] = ((g["sales_amt"] / g["prev_amt"] - 1) * 100).where(g["prev_amt"] >= min_prev_amt)
    g["quarter"] = g["STDR_YYQU_CD"].map(_label)
    g = g.rename(columns={"TRDAR_CD": "trdar_cd", "TRDAR_CD_NM": "trdar_nm"}).drop(columns="STDR_YYQU_CD")

    local = events[(events["source"] != "holiday") & events["trdar_cd"].notna()]
    spans = [(r.trdar_cd, q, r.kind) for r in local.itertuples(index=False) for q in _quarters(r.start_date, r.end_date)]
    ev = pd.DataFrame(spans, columns=["trdar_cd", "quarter", "kind"])
    counts = ev.groupby(["trdar_cd", "quarter"]).size().rename("event_count")
    by_kind = ev.assign(n=1).pivot_table(index=["trdar_cd", "quarter"], columns="kind", values="n", aggfunc="sum")
    by_kind.columns = [f"kind_{k}" for k in by_kind.columns]
    g = g.join(counts, on=["trdar_cd", "quarter"]).join(by_kind, on=["trdar_cd", "quarter"])
    count_cols = ["event_count", *by_kind.columns]
    g[count_cols] = g[count_cols].fillna(0).astype("int64")

    hol_days = events.loc[events["source"] == "holiday", "start_date"].drop_duplicates()  # 같은 날 공휴일 둘이면 하루로
    per_q = pd.Series([_quarters(d, d)[0] for d in hol_days], dtype=object).value_counts()
    g["holiday_count"] = g["quarter"].map(per_q).fillna(0).astype("int64")
    lead = ["trdar_cd", "trdar_nm", "quarter", "sales_amt", "sales_cnt", "weekend_amt", "n_industries", "prev_amt", "yoy_pct"]
    return g[lead + count_cols + ["holiday_count"]].sort_values(["trdar_cd", "quarter"]).reset_index(drop=True)
