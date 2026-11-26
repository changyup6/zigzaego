import numpy as np
import pandas as pd

from weglee_ai.seoulevent.model import experiment as ex


def test_base_event_count_counts_event_days_in_prior_8_same_weekdays():
    days = pd.date_range("2025-01-04", periods=70)  # 토요일부터
    f = pd.DataFrame({"trdar_cd": "A", "date": days, "big_event_count": 0.0})
    sat = days[days.day_of_week == 5]
    f.loc[f.date.isin(sat[[0, 2, 9]]), "big_event_count"] = 1  # 1 · 3 · 10번째 토요일
    k = ex.add_base_event_count(f).set_index("date")["base_event_k"]
    assert k[sat[8]] == 2  # 직전 8주(1 ~ 8번째) 안에 1 · 3번째
    assert k[sat[9]] == 1  # 2 ~ 9번째 안에 3번째만, 자기 날(10번째)은 안 셈
    assert k[sat[0] + pd.Timedelta(days=1)] == 0  # 다른 요일


def test_base_partial_uses_weeks_2_to_8_only():
    days = pd.date_range("2025-01-04", periods=63)  # 토요일부터 9주
    f = pd.DataFrame({"trdar_cd": "A", "date": days, "riders": 100.0})
    sat = days[days.day_of_week == 5]
    f.loc[f["date"] == sat[7], "riders"] = 10000.0  # 1주 전(예측 시점에 아직 모름) 값은 크게 — 쓰면 안 된다
    g = ex.add_base_partial(f).set_index("date")["log_base_part"]
    assert abs(np.exp(g[sat[8]]) - 100.0) < 1e-9  # 2 ~ 8주 전(7개)만의 중앙값
    assert np.isnan(g[sat[3]])  # 2주 전부터 셀 수 있는 값이 4개 미만


def test_bus_features_lagged_base_and_last_year_ratio():
    days = pd.date_range("2024-01-01", periods=420)
    bus = pd.DataFrame({"trdar_cd": "A", "date": days.date, "bus_riders": 100.0, "bus_base": 100.0})
    bus.loc[bus["date"] == pd.Timestamp("2024-03-02").date(), "bus_riders"] = 300.0  # 작년 같은 시기 버스 3배
    f = pd.DataFrame({"trdar_cd": "A", "date": [pd.Timestamp("2025-03-01")]})  # 364일 뒤 = 2024-03-02
    g = ex.add_bus_features(f, bus, 14)
    assert abs(np.exp(g.loc[0, "log_bus_base"]) - 100.0) < 1e-9 and g.loc[0, "ly_bus_max_ratio"] == 3.0


def test_build_bus_day_sums_stops_within_radius():
    from weglee_ai.seoulevent.build import geo
    from weglee_ai.seoulevent.build.district_day import build_bus_day
    areas = pd.DataFrame({"TRDAR_CD": ["A"], "x": [200000.0], "y": [450000.0]})
    lat, lon = geo.to_4326(200100.0, 450000.0)  # 100m — 안
    lat2, lon2 = geo.to_4326(200500.0, 450000.0)  # 500m — 밖
    stops = pd.DataFrame({"stop_id": ["s1", "s2"], "lat": [lat, lat2], "lon": [lon, lon2]})
    days = pd.date_range("2024-01-01", periods=70).date
    bus = pd.concat([pd.DataFrame({"date": days, "stop_id": s, "board": 10, "alight": 10}) for s in ("s1", "s2")])
    out = build_bus_day(bus, stops, areas).set_index("date")
    assert out["bus_riders"].iloc[-1] == 20 and out["bus_base"].iloc[-1] == 20  # s2 는 빠짐


def test_signal_ids_group_by_station_key_not_by_equal_counts():
    df = pd.DataFrame({"date": ["d1", "d1", "d1"], "station_key": ["강남", "강남", "역삼"], "riders": [100, 100, 100], "riders_base": [50.0, 50.0, 50.0]})
    s = ex.signal_ids(df)
    assert s[0] == s[1] and s[0] != s[2]  # 합계가 같아도 역이 다르면 다른 신호
    assert len(set(ex.signal_ids(df.drop(columns="station_key")))) == 1  # 역 묶음 없는 옛 표는 예전 방식


def test_base_nowcast_is_last_year_times_recent_growth():
    days = pd.date_range("2024-01-01", periods=500)
    base = np.where(days < pd.Timestamp("2025-01-01"), 100.0, 120.0)  # 2025 부터 20% 늘어남
    f = pd.DataFrame({"trdar_cd": "A", "date": days, "riders_base": base})
    g = ex.add_base_nowcast(f, 56).set_index("date")["log_base_now"]
    t = pd.Timestamp("2025-04-01")  # 작년 같은 날 100, 56일 전 120, 그 1년 전 100 → 120
    assert abs(np.exp(g[t]) - 120.0) < 1e-6
    assert np.isnan(g[pd.Timestamp("2024-06-01")])  # 1년 전 기록 없음


def test_align_ly_to_lunar_holidays_matches_same_holiday_last_year():
    hol = pd.DataFrame({"date": ["2024-05-15", "2025-05-05", "2025-05-05"], "name": ["부처님오신날", "부처님오신날", "어린이날"]})
    days = pd.to_datetime(["2024-05-15", "2025-05-05"])
    f = pd.DataFrame({"trdar_cd": "A", "date": days, "ratio": [2.2, 2.1], "ly_spike": 0.0, "ly_max_ratio": float("nan"), "ly_win_max_ratio": 1.0})
    spikes = pd.DataFrame({"trdar_cd": ["A"], "date": ["2024-05-15"], "ratio": [2.2]})
    g = ex.align_ly_to_lunar_holidays(f, spikes, hol).set_index("date")
    assert g.loc["2025-05-05", "ly_spike"] == 1 and g.loc["2025-05-05", "ly_win_max_ratio"] == 2.2
    assert g.loc["2024-05-15", "ly_spike"] == 0  # 직전 해 기록 없음 → 그대로


def test_align_ly_to_citywide_uses_previous_same_named_day():
    city = pd.DataFrame({"date": ["2024-10-05", "2025-09-27"], "name": ["불꽃축제", "불꽃축제"], "announced": [None, "2025-08-28"]})
    days = pd.to_datetime(["2024-10-05", "2025-09-27", "2025-09-28"])
    f = pd.DataFrame({"trdar_cd": "A", "date": days, "ratio": [3.0, 2.5, 1.0], "ly_spike": 0.0, "ly_max_ratio": float("nan"), "ly_win_max_ratio": 1.0})
    spikes = pd.DataFrame({"trdar_cd": ["A"], "date": ["2024-10-05"], "ratio": [3.0]})
    g = ex.align_ly_to_citywide(f, spikes, city).set_index("date")
    assert g.loc["2025-09-27", "ly_spike"] == 1 and g.loc["2025-09-27", "ly_max_ratio"] == 3.0 and g.loc["2025-09-27", "ly_win_max_ratio"] == 3.0
    assert g.loc["2024-10-05", "ly_spike"] == 0  # 직전 행사 없음 → 그대로
    assert g.loc["2025-09-28", "ly_win_max_ratio"] == 1.0  # 행사 아닌 날 그대로
    late = city.assign(announced=[None, "2025-09-25"])  # 7일 전보다 늦게 공개 → 정렬 안 함 (ChatGPT 27)
    assert ex.align_ly_to_citywide(f, spikes, late).set_index("date").loc["2025-09-27", "ly_spike"] == 0
    assert ex.align_ly_to_citywide(f, spikes, city.drop(columns="announced")).set_index("date").loc["2025-09-27", "ly_spike"] == 0
