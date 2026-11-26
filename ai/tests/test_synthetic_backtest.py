"""합성 데이터 백테스트 (S15P31A308-165). TimesFM 없이 요일 평균 · 가짜 예측기로 돈다."""

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from weglee_ai.batch.predictors import DowAvgPredictor
from weglee_ai.sample_data import RECIPES, generate
from weglee_ai.synthetic_backtest import (
    ALL_CHANNELS,
    ORACLE,
    actuals,
    event_label,
    ingredient_rows,
    join_actual,
    oracle_forecasts,
    rolling_forecasts,
    run_backtest,
    score,
    split,
)

START = date(2026, 1, 1)


class RecordingPredictor:
    """받은 계열의 마지막 날짜를 기록하고 상수를 낸다 — 누수 검사용."""

    name = "fake"

    def __init__(self, value=1.0):
        self.value = value
        self.last_seen: list[date] = []

    def predict(self, series, targets):
        self.last_seen.append(max(s.index.max() for s in series).date())
        return [[self.value] * len(targets) for _ in series]


def _sales(days, menus=(("A", 1),), channels=("HALL",), qty=10, skip=()):
    rows = []
    for k in range(days):
        d = START + timedelta(days=k)
        if d in skip:
            continue
        for name, _ in menus:
            for ch in channels:
                rows.append({"sold_date": d, "menu_name": name, "qty": qty, "channel": ch})
    return pd.DataFrame(rows, columns=["sold_date", "menu_name", "qty", "channel"])


MENU_IDS = {"A": 1, "B": 2}


# ---- 분리 ----

def test_split_holdout_is_last_n_days_and_train_ends_day_before():
    s = split(START, START + timedelta(days=99), holdout_days=56)
    assert s.holdout_end == START + timedelta(days=99)
    assert s.holdout_start == START + timedelta(days=44)
    assert (s.holdout_end - s.holdout_start).days + 1 == 56
    assert s.train_start == START
    assert s.train_end == s.holdout_start - timedelta(days=1)


def test_split_rejects_holdout_not_shorter_than_data():
    with pytest.raises(ValueError):
        split(START, START + timedelta(days=55), holdout_days=56)


# ---- rolling ----

def test_rolling_origins_horizons_and_no_leakage():
    sales = _sales(60)
    s = split(START, START + timedelta(days=59), holdout_days=14)
    fake = RecordingPredictor()
    fc, failed = rolling_forecasts(sales, MENU_IDS, set(), s, [fake])
    assert failed == []
    assert fc["origin"].nunique() == 14
    assert fc["target_date"].min() == s.holdout_start
    assert fc["target_date"].max() == s.holdout_end  # 홀드아웃 밖으로 나가는 날은 버린다
    assert set(fc["horizon"]) == set(range(1, 8))
    # 지평 h 의 행 수: 기준일 14 - (h - 1)
    counts = fc.groupby("horizon").size()
    assert counts[1] == 14 and counts[7] == 8
    assert (fc["target_date"] - fc["origin"]).map(lambda x: x.days + 1).eq(fc["horizon"]).all()
    # 기준일 d 의 입력은 d 전날까지만
    origins = sorted(fc["origin"].unique())
    assert fake.last_seen == [o - timedelta(days=1) for o in origins]


def test_rolling_skips_closed_targets():
    closed_day = START + timedelta(days=55)
    sales = _sales(60, skip={closed_day})
    s = split(START, START + timedelta(days=59), holdout_days=14)
    fc, _ = rolling_forecasts(sales, MENU_IDS, {closed_day}, s, [RecordingPredictor()])
    assert closed_day not in set(fc["target_date"])


def test_rolling_reports_failed_predictor_and_keeps_others():
    class Broken:
        name = "timesfm"

        def predict(self, series, targets):
            raise RuntimeError("모델 없음")

    s = split(START, START + timedelta(days=59), holdout_days=7)
    fc, failed = rolling_forecasts(_sales(60), MENU_IDS, set(), s, [Broken(), RecordingPredictor()])
    assert set(failed) == {"timesfm"}
    assert set(fc["model"]) == {"fake"}


# ---- 채점 (영업일 = 그 채널 판매 합 > 0) ----

def test_actuals_open_day_rule_zero_for_unsold_menu_and_closed_excluded():
    sales = pd.DataFrame(
        [
            {"sold_date": START, "menu_name": "A", "qty": 3, "channel": "HALL"},
            {"sold_date": START, "menu_name": "B", "qty": 2, "channel": "HALL"},
            {"sold_date": START + timedelta(days=1), "menu_name": "A", "qty": 4, "channel": "HALL"},
            # 이틀째 B 는 행 없음 → 0. 사흘째는 행이 아예 없음 → 휴무
        ]
    )
    act = actuals(sales, START, START + timedelta(days=2))
    day2 = act[act["target_date"] == START + timedelta(days=1)].set_index("menu_name")["actual"]
    assert day2["B"] == 0
    assert (START + timedelta(days=2)) not in set(act["target_date"])


def test_join_drops_closed_days_and_fills_zero():
    act = pd.DataFrame(
        {"target_date": [START, START], "menu_name": ["A", "B"], "channel": ["HALL", "HALL"], "actual": [5.0, 0.0]}
    )
    fc = pd.DataFrame(
        {
            "model": ["m"] * 3,
            "origin": [START] * 3,
            "target_date": [START, START, START + timedelta(days=1)],
            "horizon": [1, 1, 2],
            "menu_name": ["A", "B", "A"],
            "channel": ["HALL"] * 3,
            "predicted": [4.0, 1.0, 9.0],
        }
    )
    joined = join_actual(fc, act)
    assert len(joined) == 2  # 휴무(실제 표에 없는 날)는 뺀다
    assert joined["actual"].sum() == 5.0


def test_score_uses_metrics_wape_and_bias():
    df = pd.DataFrame({"model": ["m", "m"], "actual": [10.0, 10.0], "predicted": [8.0, 14.0]})
    out = score(df, ["model"]).iloc[0]
    assert out["wape"] == pytest.approx(6 / 20)
    assert out["bias"] == pytest.approx(2 / 20)
    assert out["rows"] == 2


def test_score_zero_actual_is_nan():
    df = pd.DataFrame({"model": ["m"], "actual": [0.0], "predicted": [3.0]})
    assert np.isnan(score(df, ["model"]).iloc[0]["wape"])


# ---- 정답 상한 ----

def test_oracle_uses_true_demand_on_same_keys():
    fc = pd.DataFrame(
        {
            "model": ["m", "m"],
            "origin": [START, START],
            "target_date": [START, START + timedelta(days=1)],
            "horizon": [1, 2],
            "menu_name": ["A", "A"],
            "channel": ["HALL", "HALL"],
            "predicted": [1.0, 1.0],
        }
    )
    td = pd.DataFrame(
        {"sold_date": [START, START + timedelta(days=1)], "menu_name": ["A", "A"], "channel": ["HALL", "HALL"],
         "expected_qty": [7.5, 8.5]}
    )
    oc = oracle_forecasts(fc, td)
    assert set(oc["model"]) == {ORACLE}
    assert list(oc.sort_values("target_date")["predicted"]) == [7.5, 8.5]
    assert len(oc) == len(fc)


# ---- BOM 환산 ----

def test_ingredient_rows_bom_sums_menus_and_channels():
    joined = pd.DataFrame(
        {
            "model": ["m"] * 3,
            "origin": [START] * 3,
            "target_date": [START] * 3,
            "horizon": [1] * 3,
            "menu_name": ["A", "A", "B"],
            "channel": ["HALL", "DELIVERY", "HALL"],
            "actual": [2.0, 1.0, 4.0],
            "predicted": [3.0, 1.0, 5.0],
        }
    )
    recipes = {"A": {"고기": 100, "쌀": 200}, "B": {"쌀": 250}, "C": {"면": 1}}
    ing = ingredient_rows(joined, recipes).set_index("ingredient")
    assert ing.loc["고기", "actual"] == 300 and ing.loc["고기", "predicted"] == 400
    assert ing.loc["쌀", "actual"] == 3 * 200 + 4 * 250
    assert ing.loc["쌀", "predicted"] == 4 * 200 + 5 * 250
    assert "면" not in ing.index  # 안 팔린 메뉴의 재료는 행이 없다


# ---- 이벤트 ----

def test_event_label_kinds():
    events = pd.DataFrame(
        {
            "event_date": [START, START + timedelta(days=1), START + timedelta(days=2), START + timedelta(days=3)],
            "name": ["신정", "추석 연휴", "추석", "축제"],
            "kind": ["HOLIDAY", "HOLIDAY", "HOLIDAY_CLOSED", "LOCAL_EVENT"],
            "multiplier": [1.1, 0.6, 0.0, 1.4],
            "scope": ["NATIONAL", "NATIONAL", "NATIONAL", "STORE"],
        }
    )
    labels = event_label(pd.Series([START + timedelta(days=k) for k in range(5)]), events)
    assert list(labels) == ["공휴일", "연휴(설·추석)", "휴무", "지역 행사", "행사 없음"]


# ---- 전체 · 재현 ----

@pytest.fixture(scope="module")
def small():
    return generate(end_date="2026-10-11", days=120, seed=3)


def test_run_backtest_tables(small):
    r = run_backtest(small, [DowAvgPredictor()], holdout_days=14)
    assert set(r.summary["model"]) == {"dow_avg", ORACLE}
    assert set(r.summary["channel"]) == {"HALL", "DELIVERY", ALL_CHANNELS}
    assert set(r.by_horizon["horizon"]) == set(range(1, 8))
    assert len(r.menu_channel[r.menu_channel["model"] == "dow_avg"]) == 16  # 메뉴 8 × 채널 2
    assert "이벤트 전체" in set(r.by_event["day_type"]) and "행사 없음" in set(r.by_event["day_type"])
    assert set(r.ingredients["ingredient"]) == {i for items in RECIPES.values() for i in items}
    # 정답 상한은 노이즈 바닥 — 요일 평균보다 덜 틀린다
    s = r.summary[r.summary["channel"] == ALL_CHANNELS].set_index("model")["wape"]
    assert s[ORACLE] < s["dow_avg"]


def test_same_seed_same_result(small):
    a = run_backtest(small, [DowAvgPredictor()], holdout_days=14)
    b = run_backtest(generate(end_date="2026-10-11", days=120, seed=3), [DowAvgPredictor()], holdout_days=14)
    pd.testing.assert_frame_equal(a.forecasts, b.forecasts)
    pd.testing.assert_frame_equal(a.summary, b.summary)
    c = run_backtest(generate(end_date="2026-10-11", days=120, seed=4), [DowAvgPredictor()], holdout_days=14)
    assert not a.summary["wape"].equals(c.summary["wape"])
