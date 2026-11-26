from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from weglee_ai.seoulevent.build import geo
from weglee_ai.seoulevent.build.district_day import build_district_day
from weglee_ai.seoulevent.build.district_quarter import build_district_quarter

LAT0, LON0 = 37.5720, 126.9746


def _sales(rows):
    return pd.DataFrame(rows, columns=["STDR_YYQU_CD", "TRDAR_CD", "TRDAR_CD_NM", "SVC_INDUTY_CD",
                                       "THSMON_SELNG_AMT", "THSMON_SELNG_CO", "WKEND_SELNG_AMT"])


def _events(rows):
    return pd.DataFrame(rows, columns=["event_id", "source", "kind", "name", "start_date", "end_date", "trdar_cd"])


NO_EVENTS = _events([])


def test_yoy_pct_and_missing_previous_year():
    sales = _sales([["20231", "A", "가", "CS100001", 100, 10, 30], ["20241", "A", "가", "CS100001", 130, 13, 40]])
    q = build_district_quarter(sales, NO_EVENTS, min_prev_amt=0).set_index("quarter")
    assert q.loc["2024Q1", "yoy_pct"] == pytest.approx(30.0)
    assert np.isnan(q.loc["2023Q1", "yoy_pct"])
    assert q.loc["2024Q1", "weekend_amt"] == 40


def test_food_only_filters_non_food():
    sales = _sales([["20241", "A", "가", "CS100001", 100, 1, 0], ["20241", "A", "가", "CS200001", 999, 1, 0]])
    assert build_district_quarter(sales, NO_EVENTS)["sales_amt"].tolist() == [100]
    assert build_district_quarter(sales, NO_EVENTS, food_only=False)["sales_amt"].tolist() == [1099]


def test_event_spanning_quarters_counted_in_each_and_holidays():
    sales = _sales([["20241", "A", "가", "CS100001", 1, 1, 0], ["20242", "A", "가", "CS100001", 1, 1, 0]])
    ev = _events([
        ["e1", "culture", "축제", "봄축제", date(2024, 3, 30), date(2024, 4, 2), "A"],
        ["h1", "holiday", "holiday", "삼일절", date(2024, 3, 1), date(2024, 3, 1), None],
        ["h2", "holiday", "holiday", "설날", date(2024, 2, 10), date(2024, 2, 10), None],
    ])
    q = build_district_quarter(sales, ev).set_index("quarter")
    assert q.loc["2024Q1", "event_count"] == 1 and q.loc["2024Q2", "event_count"] == 1
    assert q.loc["2024Q1", "kind_축제"] == 1
    assert q.loc["2024Q1", "holiday_count"] == 2 and q.loc["2024Q2", "holiday_count"] == 0


def _areas():
    x, y = geo.to_5181(LAT0, LON0)
    return pd.DataFrame({"TRDAR_CD": ["A", "FAR"], "TRDAR_CD_NM": ["가", "먼곳"], "x": [x, x + 5000], "y": [y, y]})


def _subway(day_values: dict[date, int]):
    return pd.DataFrame([{"date": d, "line": "1호선", "station": "역", "board": v, "alight": 0, "lat": LAT0, "lon": LON0}
                         for d, v in day_values.items()])


EMPTY_WEATHER = pd.DataFrame(columns=["date", "avg_ta", "sum_rn"])
EMPTY_HOLIDAYS = pd.DataFrame(columns=["date", "name", "is_holiday"])


def test_ratio_against_same_weekday_last_4_weeks():
    target = date(2024, 5, 29)
    values = {target - timedelta(weeks=w): 100 for w in (1, 2, 3, 4)} | {target: 150}
    day = build_district_day(_subway(values), EMPTY_WEATHER, NO_EVENTS, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[target, "trdar_cd"] == "A"
    assert day.loc[target, "riders"] == 150
    assert day.loc[target, "ratio"] == pytest.approx(1.5)
    assert np.isnan(day.loc[target - timedelta(weeks=1), "ratio"])  # 4주 이력 없음
    assert day["station_key"].notna().all() and day["station_key"].nunique() == 1  # 상권의 역 묶음 (43단계 신호 단위)


def test_same_name_far_stations_stay_separate():
    # 5호선 양평(서울) · 중앙선 양평(경기)처럼 이름만 같은 먼 역: 서울 쪽 승하차만 그 상권에, 먼 역 좌표로 서울 역을 옮기지 않는다
    d = date(2024, 5, 1)
    sub = pd.DataFrame([{"date": d, "line": "5호선", "station": "양평", "board": 100, "alight": 0, "lat": LAT0, "lon": LON0},
                        {"date": d, "line": "중앙선", "station": "양평", "board": 7, "alight": 0, "lat": LAT0 - 0.03, "lon": LON0 + 0.6}])
    day = build_district_day(sub, EMPTY_WEATHER, NO_EVENTS, EMPTY_HOLIDAYS, _areas())
    assert day.set_index("trdar_cd").loc["A", "riders"] == 100


def test_far_district_has_no_rows():
    day = build_district_day(_subway({date(2024, 5, 1): 1}), EMPTY_WEATHER, NO_EVENTS, EMPTY_HOLIDAYS, _areas())
    assert set(day["trdar_cd"]) == {"A"}


def test_day_events_holiday_weather_joined():
    days = {date(2024, 3, 29) + timedelta(days=i): 10 for i in range(5)}
    ev = _events([["e1", "culture", "축제", "봄축제", date(2024, 3, 30), date(2024, 4, 2), "A"]])
    hol = pd.DataFrame({"date": [date(2024, 4, 1)], "name": ["가짜공휴일"], "is_holiday": [True]})
    weather = pd.DataFrame({"date": [date(2024, 3, 30)], "avg_ta": [12.5], "sum_rn": [3.0]})
    day = build_district_day(_subway(days), weather, ev, hol, _areas()).set_index("date")
    assert day["event_count"].to_dict() == {date(2024, 3, 29): 0, date(2024, 3, 30): 1, date(2024, 3, 31): 1,
                                            date(2024, 4, 1): 1, date(2024, 4, 2): 1}
    assert list(day.loc[date(2024, 3, 30), "events"]) == ["봄축제"]
    assert bool(day.loc[date(2024, 4, 1), "is_holiday"]) and not bool(day.loc[date(2024, 3, 29), "is_holiday"])
    assert day.loc[date(2024, 3, 30), "avg_ta"] == 12.5


# ── 리뷰 수정 ─────────────────────────────────────────────────


def test_holiday_count_counts_distinct_days():
    sales = _sales([["20252", "A", "가", "CS100001", 1, 1, 0]])
    ev = _events([["h1", "holiday", "holiday", "어린이날", date(2025, 5, 5), date(2025, 5, 5), None],
                  ["h2", "holiday", "holiday", "부처님오신날", date(2025, 5, 5), date(2025, 5, 5), None]])
    assert build_district_quarter(sales, ev)["holiday_count"].tolist() == [1]


def test_yoy_suppressed_for_tiny_previous_and_context_columns():
    sales = _sales([["20221", "A", "가", "CS100001", 100, 1, 0], ["20231", "A", "가", "CS100001", 5_000_000_000, 1, 0],
                    ["20231", "A", "가", "CS100002", 1, 1, 0]])
    q = build_district_quarter(sales, NO_EVENTS).set_index("quarter")
    assert np.isnan(q.loc["2023Q1", "yoy_pct"])  # 전년 100원 → 극단값 대신 NaN
    assert q.loc["2023Q1", "prev_amt"] == 100
    assert q.loc["2023Q1", "n_industries"] == 2


def test_short_event_count_ignores_long_exhibitions():
    days = {date(2024, 3, 1) + timedelta(days=i): 10 for i in range(40)}
    ev = _events([["long", "culture", "전시", "긴전시", date(2024, 3, 1), date(2024, 4, 9), "A"],
                  ["short", "culture", "축제", "짧은축제", date(2024, 3, 20), date(2024, 3, 21), "A"]])
    day = build_district_day(_subway(days), EMPTY_WEATHER, ev, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[date(2024, 3, 10), "event_count"] == 1 and day.loc[date(2024, 3, 10), "short_event_count"] == 0
    assert day.loc[date(2024, 3, 20), "short_event_count"] == 1


def test_baseline_is_robust_to_holiday_weeks():
    # 직전 같은 요일 8번 중 3번이 공휴일(10)이어도 기준선은 평소(100)
    target = date(2021, 10, 18)
    values = {target - timedelta(weeks=w): (10 if w in (1, 2, 4) else 100) for w in range(1, 9)} | {target: 100}
    day = build_district_day(_subway(values), EMPTY_WEATHER, NO_EVENTS, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[target, "riders_base"] == 100
    assert day.loc[target, "ratio"] == pytest.approx(1.0)


def test_short_festival_count_only_festival_kinds():
    days = {date(2024, 3, 18) + timedelta(days=i): 10 for i in range(5)}
    ev = _events([["f", "culture", "축제-문화/예술", "봄축제", date(2024, 3, 20), date(2024, 3, 20), "A"],
                  ["c", "culture", "클래식", "공연", date(2024, 3, 20), date(2024, 3, 20), "A"]])
    day = build_district_day(_subway(days), EMPTY_WEATHER, ev, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[date(2024, 3, 20), "short_event_count"] == 2
    assert day.loc[date(2024, 3, 20), "short_festival_count"] == 1
    assert day.loc[date(2024, 3, 19), "short_festival_count"] == 0


def test_event_seats_features():
    days = {date(2024, 3, 18) + timedelta(days=i): 10 for i in range(3)}
    ev = _events([["k1", "kopis", "대중음악", "콘서트", date(2024, 3, 19), date(2024, 3, 19), "A"],
                  ["k2", "kopis", "클래식", "작은공연", date(2024, 3, 19), date(2024, 3, 19), "A"]]).assign(seats=[15000, 300])
    day = build_district_day(_subway(days), EMPTY_WEATHER, ev, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[date(2024, 3, 19), "max_event_seats"] == 15000 and day.loc[date(2024, 3, 19), "big_event_count"] == 1
    assert day.loc[date(2024, 3, 18), "max_event_seats"] == 0


def test_big_event_only_popular_music():
    days = {date(2024, 3, 18) + timedelta(days=i): 10 for i in range(3)}
    ev = _events([["c", "kopis", "서양음악(클래식)", "교향곡", date(2024, 3, 19), date(2024, 3, 19), "A"]]).assign(seats=[3022])
    day = build_district_day(_subway(days), EMPTY_WEATHER, ev, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[date(2024, 3, 19), "max_event_seats"] == 3022 and day.loc[date(2024, 3, 19), "big_event_count"] == 0


def test_big_event_includes_football():
    days = {date(2024, 3, 18) + timedelta(days=i): 10 for i in range(3)}
    ev = _events([["g", "kleague", "축구", "서울 vs 전북", date(2024, 3, 19), date(2024, 3, 19), "A"]]).assign(seats=[18000])
    day = build_district_day(_subway(days), EMPTY_WEATHER, ev, EMPTY_HOLIDAYS, _areas()).set_index("date")
    assert day.loc[date(2024, 3, 19), "big_event_count"] == 1


def test_venue_areas_cover_venue_stations():
    from weglee_ai.seoulevent.build.district_day import _station_district_pairs, with_venue_areas
    areas = pd.DataFrame({"TRDAR_CD": ["3110001"], "TRDAR_CD_NM": ["광화문역"], "x": [0.0], "y": [0.0]})
    out = with_venue_areas(areas)
    assert len(out) == 4 and "9000001" in set(out["TRDAR_CD"])
    assert len(with_venue_areas(out)) == 4                                  # 두 번 더해도 그대로
    sub = pd.DataFrame({"station": ["종합운동장"], "lat": [37.511022], "lon": [127.073704]})
    pairs = _station_district_pairs(sub, out, 500)
    assert pairs["trdar_cd"].tolist() == ["9000001"]                         # 종합운동장역 승하차가 행사장 상권으로


def test_station_coord_uses_earliest_record_not_row_order():
    # 43단계 회귀: 최근 시트 행이 표 앞에 오자 환승역 좌표가 다른 노선 것으로 바뀌어 상권이 빠졌다
    from weglee_ai.seoulevent.build import geo
    from weglee_ai.seoulevent.build.district_day import _station_district_pairs
    areas = pd.DataFrame({"TRDAR_CD": ["NEAR_1HO"], "x": [200000.0], "y": [450000.0]})
    lat0, lon0 = geo.to_4326(200000.0, 450000.0)
    lat9, lon9 = geo.to_4326(200900.0, 450000.0)  # 다른 노선 좌표는 900m 떨어짐
    sub = pd.DataFrame({"station": ["서울역", "서울역"], "line": ["경부선", "1호선"], "date": [date(2026, 11, 5), date(2019, 1, 1)],
                        "lat": [lat9, lat0], "lon": [lon9, lon0]})
    assert _station_district_pairs(sub, areas, 500)["trdar_cd"].tolist() == ["NEAR_1HO"]  # 2019 기록(1호선) 좌표



def test_year_artifact_spikes_are_not_spikes():
    from weglee_ai.seoulevent.build.spikes import detect_spikes, year_artifact
    day = pd.DataFrame({"trdar_cd": ["A", "A"], "date": [date(2025, 3, 3), date(2025, 5, 5)], "riders": [2000, 6000],
                        "riders_base": [1000.0, 1000.0], "ratio": [2.0, 6.0], "riders_base_year": [1800.0, 2000.0]})
    assert year_artifact(day).tolist() == [True, False]          # 개강: 1년 기준선의 1.1배 → 착시
    areas = pd.DataFrame({"TRDAR_CD": ["A"], "TRDAR_CD_NM": ["한양대"]})
    assert detect_spikes(day, areas)["date"].tolist() == [date(2025, 5, 5)]
