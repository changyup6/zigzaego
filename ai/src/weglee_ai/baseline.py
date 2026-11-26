"""요일 평균 예측식 — 최근 4주 같은 요일 평균, 휴무일 제외. 3주 계획 4.3절.

서비스에서는 TimesFM 값이 없을 때 쓰는 대체 경로이자 모든 모델의 비교 기준이다 (DECISIONS #24).
백엔드 구현과 결과가 같아야 비교가 의미 있다. 식을 바꾸면 백엔드와 문서도 같이 바꾼다.

배달 · 홀은 따로 예측한다 (설계 5.2절). 행이 없는 날의 의미는 판매 데이터 사전 5절을 따른다.
"""

from collections.abc import Sequence
from datetime import date

import pandas as pd

WEEKS = 4
MIN_SAME_WEEKDAY = 2
FALLBACK_DAYS = 14
NO_CHANNEL = "NONE"


def forecast_day(history: pd.Series, target: pd.Timestamp) -> float:
    """한 메뉴 · 채널의 일별 판매량(index=날짜, 휴무일은 행이 없음)으로 target 날짜 판매량을 예측한다.

    target 이전 데이터만 쓴다. 같은 요일이 2개 미만이면 최근 14일 평균으로 대체.
    """
    target = pd.Timestamp(target)
    past = history[history.index < target]

    window_start = target - pd.Timedelta(weeks=WEEKS)
    same = past[(past.index >= window_start) & (past.index.dayofweek == target.dayofweek)]
    if len(same) >= MIN_SAME_WEEKDAY:
        return float(same.mean())

    recent = past[past.index >= target - pd.Timedelta(days=FALLBACK_DAYS)]
    return float(recent.mean()) if len(recent) else 0.0


class DowAvgPredictor:
    """forecast_day 를 예측기 모양(predict(계열들, 대상일들))으로. 백테스트 · 야간 배치가 같이 쓴다 (batch/ 없이도 쓰게 여기 둔다)."""

    name = "dow_avg"

    def predict(self, series: Sequence[pd.Series], targets: Sequence[date]) -> list[list[float] | None]:
        return [[forecast_day(s, pd.Timestamp(t)) for t in targets] for s in series]


def fill_daily(sales: pd.DataFrame) -> pd.DataFrame:
    """포스 원본처럼 판매가 있는 행만 있는 표를 (채널, 메뉴, 영업일) 빈칸 없는 표로 만든다.

    - 채널마다 그날 판매가 하나도 없으면 휴무 → 행을 만들지 않는다
    - 영업일인데 메뉴 행이 없으면 0개 판매 → qty = 0 행을 채운다
    - 메뉴의 첫 판매일(채널 합산) 이전은 채우지 않는다 (신메뉴). 홀에서 팔던 메뉴의 배달 첫 주문이 늦어도
      그 사이 배달은 0개 판매로 본다

    한계: 판매 기록만으로는 "문을 열었는데 한 건도 안 팔린 날"과 휴무를 구분할 수 없어 둘 다 휴무로 본다.
    판매가 아주 적은 채널에서는 예측이 조금 커질 수 있다. 가게 영업일 캘린더(정기 휴무)를 받으면 정확해진다.

    sales: columns = sold_date, menu_name, qty [, channel]. channel이 없으면 NONE 한 계열.
    반환: channel, menu_name, sold_date, qty
    """
    df = sales.copy()
    df["sold_date"] = pd.to_datetime(df["sold_date"])
    if "channel" not in df:
        df["channel"] = NO_CHANNEL
    df = df.groupby(["channel", "menu_name", "sold_date"], as_index=False)["qty"].sum()

    first_sale = df[df["qty"] > 0].groupby("menu_name")["sold_date"].min()

    filled = []
    for channel, g in df.groupby("channel"):
        open_days = pd.DatetimeIndex(sorted(g.loc[g["qty"] > 0, "sold_date"].unique()))
        for menu, m in g.groupby("menu_name"):
            days = open_days[open_days >= first_sale.get(menu, m["sold_date"].min())]
            qty = m.set_index("sold_date")["qty"].reindex(days, fill_value=0)
            filled.append(pd.DataFrame({"channel": channel, "menu_name": menu, "sold_date": days, "qty": qty.to_numpy()}))
    columns = ["channel", "menu_name", "sold_date", "qty"]
    return pd.concat(filled, ignore_index=True)[columns] if filled else pd.DataFrame(columns=columns)


def rolling_forecast(sales: pd.DataFrame, start, end) -> pd.DataFrame:
    """[start, end] 각 영업일 · 채널 · 메뉴를 그날 이전 데이터만으로 예측한다 (rolling origin).

    sales: columns = sold_date, menu_name, qty [, channel]. 휴무일 · 안 팔린 메뉴는 행이 없어도 된다.
    반환: sold_date, channel, menu_name, actual, predicted
    """
    daily = fill_daily(sales)
    start, end = pd.Timestamp(start), pd.Timestamp(end)
    rows = []
    for (channel, menu), g in daily.groupby(["channel", "menu_name"]):
        series = g.set_index("sold_date")["qty"].sort_index()
        targets = series[(series.index >= start) & (series.index <= end)]
        for day, actual in targets.items():
            rows.append(
                {
                    "sold_date": day,
                    "channel": channel,
                    "menu_name": menu,
                    "actual": float(actual),
                    "predicted": forecast_day(series, day),
                }
            )
    return pd.DataFrame(rows, columns=["sold_date", "channel", "menu_name", "actual", "predicted"])
