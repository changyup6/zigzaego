from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from weglee_ai.seoulevent.model.features import FEATURES, build_features

START = date(2023, 9, 1)


def _day(trdar="A", days=800):
    dates = [START + timedelta(days=i) for i in range(days)]
    return pd.DataFrame({"trdar_cd": trdar, "date": dates, "riders": 100, "riders_base": 100.0, "ratio": 1.0,
                         "short_event_count": 0, "short_festival_count": 0, "max_event_seats": 0, "big_event_count": 0, "max_event_popularity": 0, "avg_ta": 15.0, "sum_rn": 0.0})


SPIKES = pd.DataFrame({"trdar_cd": ["A", "B"], "date": [date(2024, 10, 5), date(2024, 10, 5)], "ratio": [3.0, 2.5]})
HOLIDAYS = pd.DataFrame({"date": [date(2025, 10, 3)], "name": ["개천절"], "is_holiday": [True]})


def _row(f, d):
    return f.set_index("date").loc[d]


def test_last_year_spike_same_weekday_window():
    f = build_features(_day(), SPIKES, HOLIDAYS)
    r = _row(f, date(2025, 10, 4))  # 364일 뒤 = 같은 토요일
    assert r["ly_spike"] == 1 and r["ly_max_ratio"] == 3.0 and r["ly_city_spikes"] == 2
    assert r["ly_ratio"] == 1.0  # 364일 전 이 상권의 실제 배수 (픽스처는 전부 1.0)
    far = _row(f, date(2025, 10, 20))
    assert far["ly_spike"] == 0 and np.isnan(far["ly_max_ratio"]) and far["ly_city_spikes"] == 0


def test_first_year_has_no_last_year_features():
    r = _row(build_features(_day(), SPIKES, HOLIDAYS), date(2024, 3, 1))
    assert np.isnan(r["ly_spike"]) and np.isnan(r["ly_city_spikes"])


def test_holiday_flags_and_target():
    day = _day()
    day.loc[day["date"] == date(2025, 10, 2), "ratio"] = 2.0
    day.loc[day["date"] == date(2025, 10, 6), "ratio"] = np.nan
    f = build_features(day, SPIKES, HOLIDAYS)
    assert _row(f, date(2025, 10, 2))["holiday_before"] == 1 and _row(f, date(2025, 10, 2))["holiday_after"] == 0
    assert _row(f, date(2025, 10, 4))["holiday_after"] == 1
    assert _row(f, date(2025, 10, 3))["is_holiday"] == 1 and _row(f, date(2025, 10, 4))["weekday"] == 5
    assert _row(f, date(2025, 10, 2))["y"] == pytest.approx(np.log(2.0))
    assert np.isnan(_row(f, date(2025, 10, 6))["y"])  # 기준선 없는 행도 남김
    assert set(FEATURES) <= set(f.columns)


def test_weather_passed_through_as_features():
    f = build_features(_day(), SPIKES, HOLIDAYS)
    assert {"avg_ta", "sum_rn", "rainy", "short_festival_count", "max_event_seats", "big_event_count"} <= set(FEATURES)
    assert _row(f, date(2025, 10, 4))["avg_ta"] == 15.0 and _row(f, date(2025, 10, 4))["rainy"] == 0


def test_semester_week():
    f = build_features(_day(days=1100, ), SPIKES, HOLIDAYS).set_index("date")
    assert f.loc[date(2025, 3, 4), "semester_week"] == 1     # 화요일, 개강 첫 주
    assert f.loc[date(2025, 3, 24), "semester_week"] == 4
    assert f.loc[date(2025, 3, 8), "semester_week"] == 0     # 토요일
    assert f.loc[date(2025, 4, 15), "semester_week"] == 0
    assert f.loc[date(2025, 9, 2), "semester_week"] == 1
    assert "semester_week" in FEATURES
