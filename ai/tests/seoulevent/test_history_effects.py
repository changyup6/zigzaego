from datetime import date

import numpy as np
import pandas as pd
import pytest

from weglee_ai.seoulevent.model import history_effects as he


def _f(rows):
    return pd.DataFrame(rows, columns=["trdar_cd", "date", "ratio", "is_holiday"])


def test_venue_effect_uses_only_earlier_events_at_same_place():
    f = _f([("A", date(2025, 3, 1), 3.0, 0), ("A", date(2025, 4, 1), 2.0, 0), ("A", date(2025, 5, 1), np.nan, 0),
            ("B", date(2025, 5, 1), 1.0, 0)])
    ev = pd.DataFrame({"event_id": ["e1", "e2", "e3", "e4"], "source": ["kopis"] * 4, "kind": ["대중음악"] * 4,
                       "place": ["블루스퀘어"] * 4, "trdar_cd": ["A", "A", "A", "B"],
                       "start_date": [date(2025, 3, 1), date(2025, 4, 1), date(2025, 5, 1), date(2025, 5, 1)],
                       "end_date": [date(2025, 3, 1), date(2025, 4, 1), date(2025, 5, 1), date(2025, 5, 1)]})
    out = he.add_history_effects(f, ev).set_index(["trdar_cd", "date"])["venue_effect"]
    assert np.isnan(out[("A", date(2025, 3, 1))])                      # 이전 기록 없음
    assert out[("A", date(2025, 4, 1))] == pytest.approx(3.0)           # 그날(2.0)은 안 씀
    assert out[("A", date(2025, 5, 1))] == pytest.approx(np.sqrt(6))    # 3.0 · 2.0 의 기하평균
    assert np.isnan(out[("B", date(2025, 5, 1))])                      # 다른 상권 기록은 안 씀


def test_festival_counts_unknown_before_festival_source_starts():
    # 점검 20 §0: 문화행사 축제는 2021-11 부터 — 그 전 날짜의 축제 수는 '0' 이 아니라 '모름'
    f = _f([("A", date(2019, 5, 18), 2.5, 0), ("A", date(2023, 5, 20), 2.5, 0)]).assign(short_festival_count=[0, 1])
    ev = pd.DataFrame({"event_id": ["e1"], "source": ["culture"], "kind": ["축제-기타"], "place": ["중랑천"], "trdar_cd": ["A"],
                       "start_date": [date(2021, 11, 2)], "end_date": [date(2021, 11, 2)]})
    out = he.add_history_effects(f, ev).set_index("date")
    assert np.isnan(out.loc[date(2019, 5, 18), "short_festival_count"]) and np.isnan(out.loc[date(2019, 5, 18), "long_festival_count"])
    assert out.loc[date(2023, 5, 20), "short_festival_count"] == 1 and out.loc[date(2023, 5, 20), "long_festival_count"] == 0


def test_holiday_effect_from_previous_holidays_only():
    f = _f([("A", date(2025, 1, 1), 0.5, 1), ("A", date(2025, 3, 1), 2.0, 1), ("A", date(2025, 3, 2), 1.0, 0)])
    out = he.add_history_effects(f, pd.DataFrame(columns=["source", "trdar_cd", "start_date", "end_date", "place", "kind", "event_id"]))
    out = out.set_index("date")["holiday_effect"]
    assert np.isnan(out[date(2025, 1, 1)])
    assert out[date(2025, 3, 1)] == pytest.approx(0.5)
    assert out[date(2025, 3, 2)] == pytest.approx(1.0)  # 0.5 · 2.0 의 기하평균


def test_horizon_uses_only_records_at_least_horizon_days_before():
    f = _f([("A", date(2025, 3, 1), 3.0, 1), ("A", date(2025, 4, 1), 2.0, 1), ("A", date(2025, 5, 1), 1.0, 1)])
    ev = pd.DataFrame({"event_id": ["e1", "e2", "e3"], "source": ["kopis"] * 3, "kind": ["대중음악"] * 3,
                       "place": ["블루스퀘어"] * 3, "trdar_cd": ["A"] * 3, "start_date": f["date"], "end_date": f["date"]})
    out = he.add_history_effects(f, ev, horizon=40).set_index("date")
    assert np.isnan(out.loc[date(2025, 4, 1), "venue_effect"])                      # 3/1 은 31일 전 → 40일 앞 예측엔 아직 모름
    assert out.loc[date(2025, 5, 1), "venue_effect"] == pytest.approx(3.0)           # 3/1 만 (4/1 은 30일 전)
    assert out.loc[date(2025, 5, 1), "holiday_effect"] == pytest.approx(3.0)


def test_holiday_name_effect_uses_same_named_holiday_in_earlier_years():
    f = _f([("A", date(2024, 3, 1), 3.0, 1), ("A", date(2024, 5, 5), 0.8, 1), ("A", date(2025, 3, 1), 2.0, 1),
            ("A", date(2025, 5, 5), 1.0, 1), ("A", date(2025, 5, 6), 1.0, 1)])
    ev = pd.DataFrame({"event_id": list("abcde"), "source": "holiday", "kind": "holiday", "place": None, "trdar_cd": None,
                       "name": ["삼일절", "어린이날", "삼일절", "어린이날", "대체공휴일(어린이날)"],
                       "start_date": f["date"], "end_date": f["date"]})
    out = he.add_history_effects(f, ev).set_index("date")["holiday_name_effect"]
    assert np.isnan(out[date(2024, 3, 1)])
    assert out[date(2025, 3, 1)] == pytest.approx(3.0)          # 작년 삼일절만 (어린이날 0.8 은 안 씀)
    assert out[date(2025, 5, 5)] == pytest.approx(0.8)
    assert np.isnan(out[date(2025, 5, 6)])                       # 대체공휴일은 '대체공휴일' 끼리 — 이전 기록 없음


def test_overlapping_holidays_feed_every_name_group():
    # 2025-05-05 는 어린이날 · 부처님오신날이 겹친다 → 그날 반응은 두 이름 이력에 모두 들어가야 한다
    f = _f([("A", date(2024, 5, 5), 2.0, 1), ("A", date(2024, 5, 15), 1.0, 1), ("A", date(2025, 5, 5), 3.0, 1),
            ("A", date(2026, 5, 5), 1.0, 1), ("A", date(2026, 5, 24), 1.0, 1)])
    ev = pd.DataFrame({"event_id": list("abcdef"), "source": "holiday", "kind": "holiday", "place": None, "trdar_cd": None,
                       "name": ["어린이날", "부처님오신날", "어린이날", "부처님오신날", "어린이날", "부처님오신날"],
                       "start_date": [date(2024, 5, 5), date(2024, 5, 15), date(2025, 5, 5), date(2025, 5, 5), date(2026, 5, 5), date(2026, 5, 24)]})
    ev["end_date"] = ev["start_date"]
    out = he.add_history_effects(f, ev).set_index("date")["holiday_name_effect"]
    assert out[date(2025, 5, 5)] == pytest.approx(np.sqrt(2.0 * 1.0))   # 어린이날 이력 2.0 · 부처님오신날 이력 1.0 의 평균(기하)
    assert out[date(2026, 5, 5)] == pytest.approx(np.sqrt(2.0 * 3.0))   # 어린이날: 2024 · 2025
    assert out[date(2026, 5, 24)] == pytest.approx(np.sqrt(1.0 * 3.0))  # 부처님오신날: 2024 · 2025 (겹친 날도 포함)


def test_holiday_name_groups():
    assert [he.holiday_group(n) for n in ["대체공휴일(설날)", "임시공휴일(제21대 대통령 선거)", "대통령선거일", "추석"]] ==         ["대체공휴일", "임시공휴일", "선거", "추석"]


def test_citywide_and_bloom_effects():
    f = _f([("A", date(2024, 10, 5), 3.0, 0), ("A", date(2025, 9, 27), 2.0, 0), ("A", date(2025, 4, 6), 2.5, 0),
            ("A", date(2026, 4, 1), 1.0, 0), ("A", date(2026, 6, 1), 1.0, 0), ("A", date(2026, 4, 4), 1.0, 0)])
    city = pd.DataFrame({"date": [date(2024, 10, 5), date(2025, 9, 27)], "name": ["불꽃축제"] * 2})
    bloom = pd.DataFrame({"year": [2025, 2026], "bloom_date": [date(2025, 4, 4), date(2026, 3, 29)]})
    out = he.add_history_effects(f, pd.DataFrame(columns=["source", "trdar_cd", "start_date", "end_date", "place"]),
                                 citywide=city, bloom=bloom).set_index("date")
    assert out.loc[date(2025, 9, 27), "citywide_effect"] == pytest.approx(3.0)       # 작년 불꽃축제 날 이 상권
    assert np.isnan(out.loc[date(2024, 10, 5), "citywide_effect"])
    assert (out.loc[date(2025, 4, 6), "bloom_day"], out.loc[date(2026, 4, 1), "bloom_day"]) == (2, 3)
    assert np.isnan(out.loc[date(2026, 6, 1), "bloom_day"])                          # 개화 기간 밖
    assert out.loc[date(2026, 4, 4), "bloom_effect"] == pytest.approx(2.5)           # 작년 개화 기간 '주말' 반응 (4/6 일요일)
    assert np.isnan(out.loc[date(2026, 4, 1), "bloom_effect"])                       # 수요일 — 과거 개화 기간 평일 기록 없음


def test_holiday_names_use_full_history():
    days = [date(2019, 3, 1), date(2021, 3, 1), date(2022, 6, 1), date(2023, 3, 1), date(2024, 3, 1), date(2025, 3, 1), date(2026, 3, 1)]
    f = _f([("A", d, r, 1) for d, r in zip(days, [4.0, 0.2, 1.0, 1.0, 2.0, 4.0, 1.0])])
    ev = pd.DataFrame({"event_id": list("abcdefg"), "source": "holiday", "kind": "holiday", "place": None, "trdar_cd": None,
                       "name": ["삼일절"] * 7, "start_date": days, "end_date": days})
    ev.loc[2, "name"] = "임시공휴일"
    res = he.add_history_effects(f, ev).set_index("date")
    out = res["holiday_name_full"]
    assert out[date(2023, 3, 1)] == pytest.approx(np.sqrt(4.0 * 0.2))    # 전체 기간 (2019 · 2021)
    assert out[date(2026, 3, 1)] == pytest.approx(np.exp(np.log([4.0, 0.2, 1.0, 2.0, 4.0]).mean()))
    recent = res["holiday_name_effect"]                                   # 기본(7단계): 코로나 빼고 최근 3번
    assert recent[date(2023, 3, 1)] == pytest.approx(4.0)
    assert recent[date(2026, 3, 1)] == pytest.approx((1.0 * 2.0 * 4.0) ** (1 / 3))
    rate = res["holiday_spike_rate"]                                      # 같은 이름 공휴일에 2배+ 였던 비율 (전체 기간)
    assert rate[date(2023, 3, 1)] == pytest.approx(0.5)                   # 2019 4.0 ✓ · 2021 0.2 ✗
    assert rate[date(2026, 3, 1)] == pytest.approx(3 / 5)                 # 4.0 · 2.0 · 4.0 / 5번
    allh = he.add_history_effects(f, ev).set_index("date")["holiday_effect"]
    assert allh[date(2026, 3, 1)] == pytest.approx(np.exp(np.log([4.0, 0.2, 1.0, 1.0, 2.0, 4.0]).mean()))  # 모든 공휴일 평균은 전체 기간


def test_semester_effect_from_same_term_week_in_earlier_years():
    f = _f([("A", date(2025, 3, 3), 2.0, 0), ("A", date(2025, 4, 14), 1.5, 0), ("A", date(2026, 3, 2), 1.0, 0),
            ("A", date(2026, 4, 13), 1.0, 0), ("A", date(2026, 3, 7), 1.0, 0)])
    out = he.add_history_effects(f, pd.DataFrame(columns=["source", "trdar_cd", "start_date", "end_date", "place"])).set_index("date")
    assert out.loc[date(2026, 3, 2), "semester_effect"] == pytest.approx(2.0)    # 봄학기 1주 (2025-03-03)
    assert out.loc[date(2026, 4, 13), "semester_effect"] == pytest.approx(1.5)   # 봄학기 7주
    assert np.isnan(out.loc[date(2026, 3, 7), "semester_effect"])                # 토요일
    assert he.term_weeks(pd.Series([date(2026, 9, 1), date(2026, 12, 21), date(2026, 7, 1)])).tolist()[:2] == ["가을학기 1주", "가을학기 16주"]


def test_named_effects_use_recent_three_and_skip_covid():
    days = [date(2019, 10, 5), date(2021, 10, 5), date(2022, 10, 8), date(2023, 10, 7), date(2024, 10, 5), date(2025, 9, 27)]
    f = _f([("A", d, r, 0) for d, r in zip(days, [4.0, 0.2, 1.0, 2.0, 4.0, 1.0])])
    city = pd.DataFrame({"date": days, "name": ["불꽃축제"] * 6})
    out = he.add_history_effects(f, pd.DataFrame(columns=["source", "trdar_cd", "start_date", "end_date", "place"]), citywide=city,
                                 bloom=pd.DataFrame(columns=["year", "bloom_date"])).set_index("date")["citywide_effect"]
    assert out[date(2022, 10, 8)] == pytest.approx(4.0)                     # 2021(코로나) 은 빼고 2019 만
    assert out[date(2025, 9, 27)] == pytest.approx((1.0 * 2.0 * 4.0) ** (1 / 3))   # 최근 3번 (2022 · 2023 · 2024)


def test_suneung_weekend_week():
    days = pd.Series([date(2025, 11, 14), date(2025, 11, 15), date(2025, 11, 22), date(2025, 11, 29), date(2025, 12, 6)])
    assert he.suneung_weekend(days).tolist() == [0, 1, 2, 3, 0]


def test_opponent_effect_per_kleague_matchup_only_earlier_games():
    d = [date(2025, 3, 1), date(2025, 4, 1), date(2025, 5, 1)]
    f = _f([("A", d[0], 3.0, 0), ("A", d[1], 1.5, 0), ("A", d[2], 1.0, 0)])
    ev = pd.DataFrame({"event_id": ["k1", "k2", "k3"], "source": ["kleague"] * 3, "kind": ["축구"] * 3,
                       "name": ["서울 vs 전북", "서울 vs 강원", "서울 vs 전북"], "place": ["서울월드컵경기장"] * 3,
                       "trdar_cd": ["A"] * 3, "start_date": d, "end_date": d})
    out = he.add_history_effects(f, ev).set_index("date")["opponent_effect"]
    assert np.isnan(out[d[0]]) and np.isnan(out[d[1]])   # 강원은 처음, 전북 첫 경기도 이전 기록 없음
    assert out[d[2]] == pytest.approx(3.0)               # 이전 전북 경기만 (강원 1.5 안 섞음)


def test_long_festival_count_only_8_to_31_day_festivals():
    d = [date(2025, 5, 1), date(2025, 5, 10), date(2025, 7, 1)]
    f = _f([("A", x, 1.0, 0) for x in d])
    ev = pd.DataFrame({"event_id": ["f1", "f2", "f3"], "source": ["culture"] * 3, "kind": ["축제-기타", "축제-기타", "전시/미술"],
                       "name": ["장미축제", "1년 축제", "긴 전시"], "place": ["p"] * 3, "trdar_cd": ["A"] * 3,
                       "start_date": [date(2025, 5, 5), date(2025, 1, 1), date(2025, 5, 1)],
                       "end_date": [date(2025, 5, 20), date(2025, 12, 31), date(2025, 5, 30)]})
    out = he.add_history_effects(f, ev).set_index("date")["long_festival_count"]
    assert out.tolist() == [0, 1, 0]   # 16일 축제만 (1년짜리 · 축제 아닌 긴 전시 제외)
