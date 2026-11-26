"""예측 모델 백테스팅 — 감 · 요일 평균 · TimesFM 을 같은 기간 · 같은 행에서 채점한다 (지라 S15P31A308-57).

평가 지표 정의 4절 그대로:

- rolling origin: 평가 기간의 날마다(기준일 t) t 까지의 데이터만으로 t+1 ~ t+7 을 예측한다
- 평가 기간: 마지막 8주. 그 앞은 이력으로만 쓴다
- 기준 지표 h = 1, h = 1~7 도 같이. 단위는 (날짜, 채널, 메뉴)와 채널을 더한 (날짜, 메뉴)
- 행 없는 날: 휴무는 빼고 영업일 미판매는 0 (baseline.fill_daily)
- 채택: 같은 행에서 TimesFM WAPE < 요일 평균 WAPE. Bias ±10% 는 채택 모델의 별도 합격 조건

예측기는 같은 모양(predict(계열들, 대상일들))으로 끼운다. 요일 평균은 baseline.DowAvgPredictor, TimesFM 은 batch 의존성
그룹에만 있는 batch/predictors.TimesFmPredictor (선택). TimesFM 은 DECISIONS #91 로 서비스 예측에서 내렸다 — 다시 쓸 근거를 잰다.
"""

from collections.abc import Sequence
from datetime import date

import pandas as pd

from weglee_ai.baseline import fill_daily
from weglee_ai.metrics import bias, wape

EVAL_WEEKS = 8
HORIZON = 7
ALL_CHANNELS = "합계"
BIAS_LIMIT = 0.10
BASELINE = "dow_avg"
CANDIDATE = "timesfm"
KEYS = ["origin", "target", "h", "channel", "menu_name"]


class LastWeekPredictor:
    """감: 지난주 같은 요일 판매를 그대로 낸다. 그날이 휴무였으면 그 전 같은 요일, 같은 요일이 없으면 마지막 영업일 값."""

    name = "last_week"

    def predict(self, series: Sequence[pd.Series], targets: Sequence[date]) -> list[list[float] | None]:
        out: list[list[float] | None] = []
        for s in series:
            if s.empty:
                out.append(None)
                continue
            row = []
            for t in targets:
                same = s[s.index.dayofweek == pd.Timestamp(t).dayofweek]
                row.append(float(same.iloc[-1]) if len(same) else float(s.iloc[-1]))
            out.append(row)
        return out


def eval_period(sales: pd.DataFrame, eval_weeks: int = EVAL_WEEKS) -> tuple[date, date]:
    """평가 기간 (첫날, 마지막 날). 마지막 날 = 판매 데이터의 마지막 날."""
    end = pd.to_datetime(sales["sold_date"]).max()
    return (end - pd.Timedelta(weeks=eval_weeks) + pd.Timedelta(days=1)).date(), end.date()


def backtest(sales: pd.DataFrame, predictors: Sequence, eval_weeks: int = EVAL_WEEKS,
             horizon: int = HORIZON) -> pd.DataFrame:
    """평가 기간의 기준일마다 예측기들을 돌려 (기준일, 대상일, h, 채널, 메뉴, 모델, 실제, 예측) 행을 낸다.

    sales: sold_date · menu_name · qty [· channel]. 휴무일 · 안 팔린 메뉴는 행이 없어도 된다.
    대상일은 평가 기간 안의 그 채널 영업일만 (실제가 있는 날). 예측기가 None 을 낸 계열은 그 모델 행만 빠진다.
    음수 예측은 0 으로 자른다 (판매는 0 아래가 없다).
    """
    columns = KEYS + ["model", "actual", "predicted"]
    daily = fill_daily(sales)
    if daily.empty:
        return pd.DataFrame(columns=columns)
    daily["sold_date"] = pd.to_datetime(daily["sold_date"])
    first, last = (pd.Timestamp(d) for d in eval_period(daily, eval_weeks))
    actual = daily.set_index(["channel", "menu_name", "sold_date"])["qty"]
    groups = {key: g.set_index("sold_date")["qty"].sort_index() for key, g in daily.groupby(["channel", "menu_name"])}

    rows = []
    origin = first - pd.Timedelta(days=1)
    while origin < last:
        targets = [(origin + pd.Timedelta(days=h)).date() for h in range(1, horizon + 1)
                   if origin + pd.Timedelta(days=h) <= last]
        keys, series = [], []
        for key, s in groups.items():
            past = s[s.index <= origin].rename(key)  # 기준일까지만 — 대상일 실제를 보지 않는다. 이름 = (채널, 메뉴)
            if not past.empty:
                keys.append(key)
                series.append(past)
        for predictor in predictors:
            for (channel, menu), values in zip(keys, predictor.predict(series, targets), strict=True):
                if values is None:
                    continue
                for t, value in zip(targets, values, strict=True):
                    ts = pd.Timestamp(t)
                    if (channel, menu, ts) not in actual.index:
                        continue  # 그 채널 휴무
                    rows.append((origin, ts, (ts - origin).days, channel, menu, predictor.name,
                                 float(actual[(channel, menu, ts)]), max(0.0, float(value))))
        origin += pd.Timedelta(days=1)
    return pd.DataFrame(rows, columns=columns)


def with_channel_sum(rows: pd.DataFrame) -> pd.DataFrame:
    """채널별 행에 채널을 더한 (기준일, 대상일, 메뉴) 행을 channel = 합계 로 붙인다. 재료 소비로 이어지는 건 이쪽이다."""
    summed = (rows.groupby(["origin", "target", "h", "menu_name", "model"], as_index=False)[["actual", "predicted"]]
              .sum().assign(channel=ALL_CHANNELS))
    return pd.concat([rows, summed[rows.columns]], ignore_index=True)


def common_rows(rows: pd.DataFrame, models: Sequence[str]) -> pd.DataFrame:
    """비교할 모델이 모두 예측한 행만 (같은 행 비교). TimesFM 이 건너뛴 계열은 다른 모델에서도 뺀다."""
    picked = rows[rows["model"].isin(models)]
    counts = picked.groupby(KEYS)["model"].nunique()
    full = set(counts[counts == len(set(models))].index)
    keep = [tuple(k) in full for k in picked[KEYS].itertuples(index=False)]
    return picked[keep].reset_index(drop=True)


def score(rows: pd.DataFrame) -> pd.DataFrame:
    """모델 × 채널(합계 포함) × 구간(h=1 · h=1~7) 의 pooled WAPE · Bias · 행 수."""
    rows = with_channel_sum(rows)
    out = []
    for span, part in (("h=1", rows[rows["h"] == 1]), (f"h=1~{HORIZON}", rows)):
        for (model, channel), g in part.groupby(["model", "channel"]):
            out.append({"구간": span, "모델": model, "채널": channel, "WAPE": wape(g["actual"], g["predicted"]),
                        "Bias": bias(g["actual"], g["predicted"]), "행": len(g)})
    table = pd.DataFrame(out, columns=["구간", "모델", "채널", "WAPE", "Bias", "행"])
    return table.sort_values(["구간", "채널", "WAPE"], ignore_index=True)


def verdict(rows: pd.DataFrame) -> str:
    """채택 규칙(평가 지표 정의 4.2)으로 한 줄: TimesFM 을 다시 쓸 근거가 있는지(DECISIONS #91 로 서비스에서 내림, 지금은
    요일 평균). 채널 합계 · h=1 · 두 모델 공통 행 기준."""
    if CANDIDATE not in set(rows["model"]):
        return "TimesFM 결과 없음 — 판정 보류 (batch 의존성 그룹으로 --timesfm 실행 필요)"
    both = with_channel_sum(common_rows(rows, [BASELINE, CANDIDATE]))
    both = both[(both["h"] == 1) & (both["channel"] == ALL_CHANNELS)]
    if both.empty:
        return "공통 행 없음 — 판정 보류"
    w = {m: wape(g["actual"], g["predicted"]) for m, g in both.groupby("model")}
    b = {m: bias(g["actual"], g["predicted"]) for m, g in both.groupby("model")}
    if any(pd.isna(v) for v in w.values()):
        return "실제 판매 합이 0 — 판정 보류"  # WAPE 를 잴 수 없다 (NaN 끼리 비교하면 '낫지 않다' 로 떨어진다)
    numbers = f"WAPE TimesFM {w[CANDIDATE]:.3f} · 요일 평균 {w[BASELINE]:.3f}, TimesFM Bias {b[CANDIDATE]:+.3f}"
    if not w[CANDIDATE] < w[BASELINE]:
        return f"요일 평균 유지 — TimesFM 이 낫지 않다 ({numbers})"
    if abs(b[CANDIDATE]) > BIAS_LIMIT:
        side = "과대 예측(과잉 발주)" if b[CANDIDATE] > 0 else "과소 예측(품절 위험)"
        return f"요일 평균 유지 — TimesFM 이 더 맞지만 Bias ±{BIAS_LIMIT:.0%} 초과, {side} ({numbers})"
    return f"TimesFM 이 요일 평균보다 낫다 — 다시 쓸 근거 있음 ({numbers})"


def to_markdown(table: pd.DataFrame) -> str:
    """비교표를 md 표로 (tabulate 없이). WAPE · Bias 는 소수 셋째 자리."""
    lines = ["| " + " | ".join(table.columns) + " |",
             "|" + "|".join("---:" if c in ("WAPE", "Bias", "행") else "---" for c in table.columns) + "|"]
    for row in table.itertuples(index=False):
        cells = []
        for col, value in zip(table.columns, row, strict=True):
            if col == "Bias":
                cells.append("" if pd.isna(value) else f"{value:+.3f}")
            elif col == "WAPE":
                cells.append("" if pd.isna(value) else f"{value:.3f}")
            else:
                cells.append(str(value))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)
