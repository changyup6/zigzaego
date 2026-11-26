"""합성 데이터 기준 예측 백테스트 (지라 S15P31A308-165).

sample_data.generate() 의 판매로 요일 평균 · TimesFM 을 서비스 배치와 같은 방식으로 굴려 정답과 맞대 본다.

- 분리: 마지막 holdout_days(기본 56일 = 8주)가 홀드아웃, 그 앞이 학습 구간. 두 모델 모두 학습하지 않으므로
  학습 구간은 "첫 기준일 전에 쌓여 있는 이력"이다
- rolling: 홀드아웃의 날마다 기준일 d 를 두고 d 전날까지의 판매만으로 d .. d+6 을 예측한다
  (batch.run.forecast_store 를 그대로 부른다 — 서비스 03:00 배치와 같은 계열 · 휴무 · 0 판매 처리).
  홀드아웃 끝을 넘는 대상일은 버린다. 지평 h = 대상일 − 기준일 + 1 (1 = 당일 아침 예측)
- 채점: 실제 판매 대비. 영업일 = 그 채널의 그날 판매 합 > 0 (baseline.fill_daily, 19ab8ec 채점 규칙과 같음),
  영업일에 행이 없는 메뉴는 실제 0. WAPE · Bias 는 metrics.wape · bias, 모든 행을 합쳐 한 번에(pooled)
- 정답 상한: true_demand(노이즈 전 기대값)를 예측으로 넣은 줄. 판매 노이즈 때문에 어떤 모델도 이보다 덜 틀리기 어렵다
- 재료: 메뉴 예측 × RECIPES 합 vs 실제 판매 × RECIPES 합. true_usage 의 주기형 잡음(식용유 · 카레 블록)은 섞지 않는다
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta

import pandas as pd

from weglee_ai.baseline import fill_daily
from weglee_ai.batch.predictors import Predictor
from weglee_ai.batch.run import forecast_store
from weglee_ai.metrics import bias, wape
from weglee_ai.sample_data import RECIPES, SampleData

HOLDOUT_DAYS = 56
ORACLE = "정답 상한"
ALL_CHANNELS = "합산"
EVENT_ALL = "이벤트 전체"
NO_EVENT = "행사 없음"

FORECAST_COLUMNS = ["model", "origin", "target_date", "horizon", "menu_name", "channel", "predicted"]
SCORE_COLUMNS = ["rows", "actual", "predicted", "wape", "bias"]


@dataclass(frozen=True)
class Split:
    train_start: date
    train_end: date
    holdout_start: date
    holdout_end: date


def split(first: date, last: date, holdout_days: int = HOLDOUT_DAYS) -> Split:
    """[first, last] 의 마지막 holdout_days 일이 홀드아웃. 학습 구간이 하루도 없으면 ValueError."""
    total = (last - first).days + 1
    if holdout_days < 1 or holdout_days >= total:
        raise ValueError(f"홀드아웃 {holdout_days}일은 1 이상, 전체 {total}일보다 짧아야 한다")
    holdout_start = last - timedelta(days=holdout_days - 1)
    return Split(first, holdout_start - timedelta(days=1), holdout_start, last)


def rolling_forecasts(
    sales: pd.DataFrame,
    menu_ids: Mapping[str, int],
    closed: set[date],
    period: Split,
    predictors: Sequence[Predictor],
) -> tuple[pd.DataFrame, list[str]]:
    """홀드아웃의 기준일마다 forecast_store 를 부른다.

    sales: sold_date · menu_name · qty · channel (판매가 있는 행만). closed: 미리 알려진 휴무일(대상에서 빠짐).
    반환: (FORECAST_COLUMNS 표, 한 번이라도 실패한 예측기 이름 — 실패한 기준일의 그 예측기 행은 없다)
    """
    names = {v: k for k, v in menu_ids.items()}
    frame = sales.assign(sold_date=pd.to_datetime(sales["sold_date"]).dt.date, menu_id=sales["menu_name"].map(menu_ids))
    frame = frame[["sold_date", "menu_id", "channel", "qty"]]
    rows, failed = [], []
    origin = period.holdout_start
    while origin <= period.holdout_end:
        history = frame[frame["sold_date"] < origin]
        out, bad = forecast_store(history, closed, origin, predictors)
        failed += [b for b in bad if b not in failed]
        for r in out:
            if r.target_date > period.holdout_end:
                continue
            rows.append((r.model, origin, r.target_date, (r.target_date - origin).days + 1,
                         names[r.menu_id], r.channel, r.predicted_qty))
        origin += timedelta(days=1)
    return pd.DataFrame(rows, columns=FORECAST_COLUMNS), failed


def actuals(sales: pd.DataFrame, start: date, end: date) -> pd.DataFrame:
    """[start, end] 영업일의 실제 판매. 반환: target_date · menu_name · channel · actual (휴무일은 행이 없다)."""
    daily = fill_daily(sales)
    daily["sold_date"] = daily["sold_date"].dt.date
    daily = daily[(daily["sold_date"] >= start) & (daily["sold_date"] <= end)]
    return daily.rename(columns={"sold_date": "target_date", "qty": "actual"})[
        ["target_date", "menu_name", "channel", "actual"]
    ].reset_index(drop=True)


def join_actual(forecasts: pd.DataFrame, actual: pd.DataFrame) -> pd.DataFrame:
    """예측에 실제를 붙인다. 휴무일(그 채널의 영업일이 아닌 날) 행은 빠지고, 영업일인데 실제 표에 없는 메뉴는 0."""
    open_days = actual[["target_date", "channel"]].drop_duplicates()
    f = forecasts.merge(open_days, on=["target_date", "channel"])
    joined = f.merge(actual, on=["target_date", "menu_name", "channel"], how="left")
    joined["actual"] = joined["actual"].fillna(0.0).astype(float)
    return joined


def oracle_forecasts(forecasts: pd.DataFrame, true_demand: pd.DataFrame) -> pd.DataFrame:
    """forecasts 와 같은 (기준일, 대상일, 메뉴, 채널) 에 true_demand 를 예측값으로 넣은 표. 모델 이름 ORACLE."""
    keys = forecasts[["origin", "target_date", "horizon", "menu_name", "channel"]].drop_duplicates()
    td = true_demand.assign(target_date=pd.to_datetime(true_demand["sold_date"]).dt.date)
    td = td[["target_date", "menu_name", "channel", "expected_qty"]].rename(columns={"expected_qty": "predicted"})
    out = keys.merge(td, on=["target_date", "menu_name", "channel"], how="left")
    out["predicted"] = out["predicted"].fillna(0.0)
    return out.assign(model=ORACLE)[FORECAST_COLUMNS].reset_index(drop=True)


def score(rows: pd.DataFrame, by: Sequence[str]) -> pd.DataFrame:
    """by 묶음마다 행 수 · 실제 합 · 예측 합 · WAPE · Bias (pooled)."""
    out = []
    for key, g in rows.groupby(list(by), sort=True):
        key = key if isinstance(key, tuple) else (key,)
        out.append((*key, len(g), float(g["actual"].sum()), float(g["predicted"].sum()),
                    wape(g["actual"], g["predicted"]), bias(g["actual"], g["predicted"])))
    return pd.DataFrame(out, columns=[*by, *SCORE_COLUMNS])


def channel_summary(joined: pd.DataFrame) -> pd.DataFrame:
    """모델 × 채널, 그리고 채널을 더한 메뉴 단위 합산(ALL_CHANNELS)."""
    per_channel = score(joined, ["model", "channel"])
    summed = joined.groupby(["model", "origin", "target_date", "menu_name"], as_index=False)[["actual", "predicted"]].sum()
    total = score(summed, ["model"]).assign(channel=ALL_CHANNELS)
    return pd.concat([per_channel, total[per_channel.columns]], ignore_index=True).sort_values(
        ["model", "channel"], ignore_index=True
    )


def event_label(days: pd.Series, events: pd.DataFrame) -> pd.Series:
    """날짜 → 공휴일 · 연휴(설·추석) · 지역 행사 · 휴무 · 행사 없음."""
    label = {}
    for e in events.itertuples():
        if e.kind == "LOCAL_EVENT":
            label[e.event_date] = "지역 행사"
        elif e.kind == "HOLIDAY_CLOSED":
            label[e.event_date] = "휴무"
        elif e.multiplier < 1:
            label[e.event_date] = "연휴(설·추석)"
        else:
            label[e.event_date] = "공휴일"
    return days.map(lambda d: label.get(d, NO_EVENT))


def event_table(joined: pd.DataFrame, events: pd.DataFrame) -> pd.DataFrame:
    """모델 × 날 종류. EVENT_ALL = 행사 없음이 아닌 날 전부."""
    labeled = joined.assign(day_type=event_label(joined["target_date"], events))
    by_kind = score(labeled, ["model", "day_type"])
    any_event = score(labeled[labeled["day_type"] != NO_EVENT], ["model"]).assign(day_type=EVENT_ALL)
    return pd.concat([by_kind, any_event[by_kind.columns]], ignore_index=True).sort_values(
        ["model", "day_type"], ignore_index=True
    )


def ingredient_rows(joined: pd.DataFrame, recipes: Mapping[str, Mapping[str, float]]) -> pd.DataFrame:
    """메뉴 단위 실제 · 예측 × 1인분 재료량 → (모델, 기준일, 대상일, 재료) 합. 채널은 더한다."""
    bom = pd.DataFrame(
        [(m, ing, q) for m, items in recipes.items() for ing, q in items.items()],
        columns=["menu_name", "ingredient", "per_serving"],
    )
    x = joined.merge(bom, on="menu_name")
    x["actual"] = x["actual"] * x["per_serving"]
    x["predicted"] = x["predicted"] * x["per_serving"]
    return x.groupby(["model", "origin", "target_date", "horizon", "ingredient"], as_index=False)[
        ["actual", "predicted"]
    ].sum()


@dataclass
class BacktestResult:
    period: Split
    forecasts: pd.DataFrame  # 예측기 + 정답 상한, 실제 붙음(휴무 제외)
    summary: pd.DataFrame  # 모델 × 채널(+합산)
    menu_channel: pd.DataFrame  # 모델 × 메뉴 × 채널
    by_horizon: pd.DataFrame  # 모델 × 지평
    by_event: pd.DataFrame  # 모델 × 날 종류
    ingredients: pd.DataFrame  # 모델 × 재료
    failed: list[str] = field(default_factory=list)


def run_backtest(
    data: SampleData, predictors: Sequence[Predictor], holdout_days: int = HOLDOUT_DAYS
) -> BacktestResult:
    days = pd.to_datetime(data.weather["date"]).dt.date
    period = split(days.min(), days.max(), holdout_days)
    menu_ids = dict(zip(data.menus["name"], data.menus["menu_id"], strict=False))
    closed = set(pd.to_datetime(data.closed_days["closed_date"]).dt.date)

    fc, failed = rolling_forecasts(data.pos_sales, menu_ids, closed, period, predictors)
    fc = pd.concat([fc, oracle_forecasts(fc, data.true_demand)], ignore_index=True)
    joined = join_actual(fc, actuals(data.pos_sales, period.holdout_start, period.holdout_end))
    joined = joined.sort_values(["model", "origin", "target_date", "menu_name", "channel"], ignore_index=True)

    return BacktestResult(
        period=period,
        forecasts=joined,
        summary=channel_summary(joined),
        menu_channel=score(joined, ["model", "menu_name", "channel"]),
        by_horizon=score(joined, ["model", "horizon"]),
        by_event=event_table(joined, data.events),
        ingredients=score(ingredient_rows(joined, RECIPES), ["model", "ingredient"]),
        failed=failed,
    )
