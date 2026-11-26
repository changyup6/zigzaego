from datetime import date, timedelta

import numpy as np
import pandas as pd

from weglee_ai.seoulevent.model import train
from weglee_ai.seoulevent.model.features import build_features
from weglee_ai.seoulevent.build.spikes import detect_spikes

START = date(2021, 1, 4)
TEST_START = date(2024, 1, 1)
NO_HOLIDAYS = pd.DataFrame(columns=["date", "name", "is_holiday"])


def _synthetic(n_districts=30, years=4):
    """상권마다 매년 같은 요일 같은 시기(364일 간격)에 3배 급증."""
    rows = []
    for i in range(n_districts):
        first = START + timedelta(days=100 + i)
        spike_days = {first + timedelta(days=364 * y) for y in range(years)}
        for k in range(364 * years):
            d = START + timedelta(days=k)
            rows.append({"trdar_cd": f"T{i}", "date": d, "riders": 3000 if d in spike_days else 1000,
                         "riders_base": 1000.0, "ratio": 3.0 if d in spike_days else 1.0, "short_event_count": 0, "short_festival_count": 0, "max_event_seats": 0, "big_event_count": 0, "max_event_popularity": 0, "avg_ta": np.nan, "sum_rn": np.nan})
    day = pd.DataFrame(rows)
    areas = pd.DataFrame({"TRDAR_CD": [f"T{i}" for i in range(n_districts)], "TRDAR_CD_NM": "x"})
    return build_features(day, detect_spikes(day, areas), NO_HOLIDAYS)


def test_split_is_time_ordered_and_drops_missing_target():
    feat = _synthetic(n_districts=2)
    feat.loc[feat.index[5], "y"] = np.nan
    tr, te = train.split(feat, TEST_START)
    assert tr["date"].max() < TEST_START <= te["date"].min()
    assert tr["y"].notna().all() and len(tr) + len(te) == len(feat) - 1


def test_model_beats_flat_baseline_on_recurring_spikes():
    tr, te = train.split(_synthetic(), TEST_START)
    ev = train.evaluate(train.fit(tr), te)
    assert ev.loc["모델", "mae_spike"] < ev.loc["배수1(요일 기준선)", "mae_spike"]
    assert ev.loc["모델", "n_spike"] == 30


def test_explain_sentences():
    r = pd.Series({"is_holiday": 1, "holiday_before": 0, "holiday_after": 0, "ly_spike": 1.0, "ly_max_ratio": 3.2,
                   "ly_city_spikes": 12.0})
    s = train.explain(r)
    assert "공휴일" in s and "작년 같은 시기 이 상권 급증(최대 3.2배)" in s and "서울 12개 상권" in s
    calm = pd.Series({"is_holiday": 0, "holiday_before": 0, "holiday_after": 0, "ly_spike": 0.0, "ly_max_ratio": np.nan,
                      "ly_city_spikes": 0.0})
    assert train.explain(calm) == "평소와 같음"
