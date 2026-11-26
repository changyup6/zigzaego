from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from weglee_ai.seoulevent.model import experiment as ex


def _day(days: int = 1100, start: date = date(2023, 1, 2)) -> pd.DataFrame:
    dates = [start + timedelta(days=i) for i in range(days)]
    return pd.DataFrame({"trdar_cd": "A", "date": dates, "ratio": 1.0})


def test_extended_features():
    day = _day()
    day.loc[day["date"] == date(2023, 10, 7), "ratio"] = 2.5   # 2025-10-04 의 728일 전
    day.loc[day["date"] == date(2024, 10, 1), "ratio"] = 1.6   # 364일 전(2024-10-05) 의 −4일, ±7일 창 안
    spikes = pd.DataFrame({"trdar_cd": ["A"], "date": [date(2023, 10, 7)], "ratio": [2.5]})
    f = ex.add_extended_features(day, spikes).set_index("date")
    r = f.loc[date(2025, 10, 4)]
    assert r["ly_win_max_ratio"] == pytest.approx(1.6)
    assert r["ly2_spike"] == 1
    assert r["nth_week"] == 1  # 10월 첫째 토요일
    assert np.isnan(f.loc[date(2023, 6, 1), "ly2_spike"])  # 2년 이력 없음


def test_hurdle_combine_and_threshold():
    p = np.array([0.1, 0.9, 0.6])
    normal = np.array([1.0, 1.1, 1.0])
    spike = np.array([2.5, 3.0, 2.0])
    assert ex.hurdle(p, normal, spike, threshold=0.5).tolist() == [1.0, 3.0, 2.0]
    actual = np.array([1.0, 3.0, 1.0])
    assert ex.choose_threshold(actual, p, normal, spike, grid=[0.5, 0.7]) == 0.7  # 0.6 을 급증으로 보면 오차가 커진다


def test_detection_scores():
    s = ex.detection_scores(actual=np.array([1.0, 2.5, 3.0, 1.0]), p=np.array([0.2, 0.8, 0.3, 0.9]), threshold=0.5)
    assert s["precision"] == pytest.approx(0.5) and s["recall"] == pytest.approx(0.5)
    assert 0 <= s["avg_precision"] <= 1


# ── ChatGPT 검토 반영 ─────────────────────────────────────────


def test_operational_features_exclude_weather():
    assert not {"avg_ta", "sum_rn", "rainy"} & set(ex.OPS_FEATURES)
    assert set(ex.OPS_FEATURES) - {"base_event_k"} < set(ex.EXT2_FEATURES)  # v2: 기준선 창 행사 수 추가 (41단계)


def test_detection_counts_rows_and_unique_dates():
    actual = np.array([2.5, 3.0, 1.0, 2.2])           # 급증: 행 0 · 1 (같은 날) · 3 (다른 날)
    dates = np.array(["d1", "d1", "d1", "d2"])
    p = np.array([0.9, 0.1, 0.1, 0.1])
    s = ex.detection_scores(actual, p, 0.5, dates=dates)
    assert (s["n_spike_rows"], s["n_spike_dates"]) == (3, 2)
    assert s["recall"] == pytest.approx(1 / 3) and s["date_recall"] == pytest.approx(0.5)


def test_threshold_for_recall_target_and_curve():
    actual = np.array([2.5, 2.5, 2.5, 2.5, 1.0, 1.0])
    p = np.array([0.95, 0.9, 0.6, 0.2, 0.7, 0.1])
    t = ex.threshold_for_recall(actual, p, target=0.5, grid=np.array([0.5, 0.8, 0.92]))
    assert t == 0.8                                    # 재현율 0.5 를 지키는 가장 높은 기준값
    curve = ex.threshold_curve(actual, p, np.array(["a"] * 6), grid=np.array([0.5, 0.8]))
    assert curve.set_index("threshold").loc[0.5, "alerts"] == 4 and curve.set_index("threshold").loc[0.5, "precision"] == pytest.approx(0.75)
    assert ex.top_k_hit_rate(actual, p, ks=(2, 4)) == {2: 1.0, 4: 0.75}


def test_at_horizon_lags_base_and_history():
    f = _day(60).assign(is_holiday=0, log_base=np.arange(60, dtype=float))
    ev = pd.DataFrame(columns=["event_id", "source", "kind", "place", "trdar_cd", "start_date", "end_date"])
    out = ex.at_horizon(f, ev, horizon=28)
    assert np.isnan(out["log_base"].iloc[27]) and out["log_base"].iloc[28] == 0.0 and out["log_base"].iloc[59] == 31.0


def test_by_year_splits_detection():
    test = pd.DataFrame({"date": [date(2025, 1, 1), date(2025, 1, 2), date(2026, 1, 1)], "ratio": [3.0, 1.0, 3.0]})
    out = ex.by_year(test, np.array([0.9, 0.1, 0.1]), 0.5).set_index("year")
    assert out.loc[2025, "recall"] == 1.0 and out.loc[2026, "recall"] == 0.0


def test_signal_ids_group_districts_sharing_a_station():
    test = pd.DataFrame({"date": [date(2025, 3, 1)] * 3 + [date(2025, 3, 2)],
                         "riders": [9000.0, 9000.0, 500.0, 9000.0], "riders_base": [3000.0, 3000.0, 400.0, 3000.0]})
    ids = ex.signal_ids(test)
    assert ids[0] == ids[1] and len(set(ids)) == 3


def test_detection_by_independent_signal():
    actual = np.array([3.0, 3.0, 3.0, 2.5, 1.0])      # 신호 s1 = 행 0 · 1 · 2 (같은 역 3상권), s2 = 행 3, s3 = 행 4 (급증 아님)
    signals = np.array([1, 1, 1, 2, 3])
    p = np.array([0.9, 0.9, 0.9, 0.1, 0.9])
    s = ex.detection_scores(actual, p, 0.5, signals=signals)
    assert s["recall"] == pytest.approx(0.75)                       # 행 단위는 같은 역 3번이 부풀린다
    assert (s["n_spike_signals"], s["signal_recall"], s["signal_precision"]) == (2, 0.5, 0.5)


def test_known_share_counts_schedules_collected_before_origin():
    ev = pd.DataFrame({"source": ["kopis", "kopis", "stadium", "holiday"], "trdar_cd": ["A", "A", "A", None],
                       "start_date": [date(2025, 3, 1), date(2025, 3, 1), date(2025, 3, 1), date(2025, 3, 1)],
                       "collected_at": ["2025-01-15", "2026-11-03", None, None]})
    assert ex.known_share(ev, horizon=28) == pytest.approx(1 / 3)     # 공휴일은 고정 일정이라 제외, 수집 시각 없음은 모름


def test_boosting_models_fit_and_predict_with_missing_values():
    rng = np.random.default_rng(0)
    df = pd.DataFrame({"a": rng.normal(size=400), "b": np.where(rng.random(400) < 0.3, np.nan, rng.normal(size=400))})
    df["ratio"] = np.where(df["a"] > 1.5, 3.0, 1.0)
    df["y"] = np.log(df["ratio"])
    reg, cols = ex._reg(df, ["a", "b"])
    clf, _ = ex._clf(df, ["a", "b"])
    assert np.abs(reg.predict(df[cols]) - df["y"]).mean() < 0.2
    assert clf.predict_proba(df[cols])[:, 1][df["ratio"] >= 2].mean() > 0.5


def test_mask_unannounced_keeps_only_days_announced_before_origin():
    f = pd.DataFrame({"date": [date(2025, 9, 27), date(2024, 10, 5), date(2025, 3, 16)], "citywide_effect": [3.0, 2.0, 1.5]})
    city = pd.DataFrame({"date": [date(2025, 9, 27), date(2024, 10, 5), date(2025, 3, 16)],
                         "announced": ["2025-08-28", None, "2025-03-01"]})
    out = ex.mask_unannounced(f, city, horizon=28)
    assert out.iloc[0] == 3.0                      # 30일 전 공개 → 28일 앞에 앎
    assert np.isnan(out.iloc[1]) and np.isnan(out.iloc[2])   # 공개일 모름 · 15일 전 공개


def test_alert_outcomes_split_hits_false_alarms_and_misses():
    test = pd.DataFrame({"trdar_cd": list("ABCD"), "date": [date(2025, 5, 1)] * 4, "ratio": [3.0, 1.0, 2.5, 1.0],
                         "riders": [1.0, 2.0, 3.0, 4.0], "riders_base": [1.0] * 4,
                         "big_event_count": [1, 0, 0, 0], "semester_week": 0, "suneung_week": 0, "is_holiday": 0,
                         "short_festival_count": 0, "citywide_effect": np.nan, "bloom_day": np.nan, "venue_effect": np.nan})
    out = ex.alert_outcomes(test, np.array([0.9, 0.8, 0.1, 0.1]), 0.5).set_index("trdar_cd")
    assert out["outcome"].to_dict() == {"A": "적중", "B": "헛경보", "C": "놓침"}       # D 는 경보도 급증도 아님
    assert out.loc["A", "cause"] == "큰 행사(경기 · 공연)" and out.loc["C", "cause"] == "원인 모름"


def test_daily_top_k_alerts_best_districts_each_day():
    test = pd.DataFrame({"date": [date(2025, 5, 1)] * 3 + [date(2025, 5, 2)] * 2, "ratio": [3.0, 1.0, 1.0, 1.0, 2.5],
                         "riders": [1.0, 2.0, 3.0, 4.0, 5.0], "riders_base": [1.0] * 5})
    out = ex.daily_top_k(test, np.array([0.9, 0.5, 0.1, 0.8, 0.2]), ks=(1, 2)).set_index("k")
    assert out.loc[1, "alerts"] == 2 and out.loc[1, "recall"] == 0.5 and out.loc[1, "date_recall"] == 0.5
    assert out.loc[2, "recall"] == 1.0 and out.loc[2, "precision"] == 0.5


def test_size_regressors_use_full_holiday_history_classifier_recent():
    assert ex.size_features(["weekday", "holiday_name_effect"]) == ["weekday", "holiday_name_full"]
    rng = np.random.default_rng(1)
    df = pd.DataFrame({"holiday_name_effect": rng.normal(size=300), "holiday_name_full": rng.normal(size=300)})
    df["ratio"] = np.where(df["holiday_name_effect"] > 1.2, 3.0, 1.0)
    df["y"] = np.log(df["ratio"])
    p, _, _ = ex._hurdle_parts(df, df, ["holiday_name_effect"])
    spike = df["ratio"].to_numpy() >= 2
    assert p[spike].mean() > p[~spike].mean() + 0.5        # 분류기는 최근 반응(holiday_name_effect)을 본다


def test_candidate_districts_and_scores_against_targets():
    train = pd.DataFrame({"trdar_cd": ["A", "B", "C"], "ratio": [2.5, 1.0, 1.9]})
    assert ex.candidate_districts(train) == {"A"}
    test = pd.DataFrame({"trdar_cd": ["A", "A", "A", "B"], "date": [date(2025, 5, 1), date(2025, 5, 2), date(2025, 5, 3), date(2025, 5, 1)],
                         "ratio": [3.0, 1.4, 1.0, 3.0], "riders": [1.0, 2.0, 3.0, 4.0], "riders_base": [1.0] * 4})
    s = ex.cand_scores(test, np.array([0.9, 0.8, 0.1, 0.9]), 0.5, {"A"})   # B 는 후보 밖이라 안 셈
    assert (s["cand_precision"], s["cand_recall"]) == (0.5, 1.0)
    assert s["cand_busy_hit"] == 1.0                                         # 경보 2건 모두 1.3배 이상
    assert s["gap_precision"] == 0.0 and s["gap_recall"] == 0.0          # 1차 목표 정밀도 0.5 충족


def test_precision_at_recall_picks_best_threshold_meeting_recall():
    test = pd.DataFrame({"trdar_cd": ["A"] * 5, "date": [date(2025, 5, d) for d in range(1, 6)],
                         "ratio": [3.0, 3.0, 1.0, 1.0, 1.0], "riders": [1.0, 2.0, 3.0, 4.0, 5.0], "riders_base": [1.0] * 5})
    p = np.array([0.9, 0.6, 0.7, 0.2, 0.1])
    # 재현율 1.0 을 지키는 가장 높은 기준값은 0.6 → 경보 3 중 급증 2 → 정밀도 2/3
    assert ex.precision_at_recall(test, p, {"A"}, target=1.0, grid=np.array([0.05, 0.5, 0.6, 0.8])) == pytest.approx(2 / 3)


def test_without_covid_drops_distancing_period():
    f = pd.DataFrame({"date": [date(2020, 2, 22), date(2021, 6, 1), date(2022, 4, 18)]})
    assert ex.without_covid(f)["date"].tolist() == [date(2020, 2, 22), date(2022, 4, 18)]


def test_classifier_can_train_on_candidate_districts_only():
    rng = np.random.default_rng(2)
    df = pd.DataFrame({"trdar_cd": np.where(np.arange(400) < 200, "A", "B"), "x": rng.normal(size=400)})
    df["ratio"] = np.where((df["trdar_cd"] == "A") & (df["x"] > 1.0), 3.0, np.where((df["trdar_cd"] == "B") & (df["x"] < -1.0), 3.0, 1.0))
    df["y"] = np.log(df["ratio"])
    p, _, _ = ex._hurdle_parts(df, df, ["x"], clf_cand={"A"})
    a = df["trdar_cd"].to_numpy() == "A"
    assert p[a & (df["x"].to_numpy() > 1.0)].mean() > p[a & (df["x"].to_numpy() < -1.0)].mean()   # A 의 규칙만 배움


def test_signal_threshold_and_signal_level_average_precision():
    test = pd.DataFrame({"trdar_cd": ["A", "B", "A", "A"], "date": [date(2025, 5, 1)] * 2 + [date(2025, 5, 2), date(2025, 5, 3)],
                         "ratio": [3.0, 3.0, 1.0, 2.5], "riders": [9.0, 9.0, 3.0, 4.0], "riders_base": [1.0] * 4})
    p = np.array([0.9, 0.2, 0.6, 0.4])        # 행 0 · 1 은 같은 역(같은 신호)
    assert ex.signal_threshold(test, p, {"A", "B"}, target=1.0, grid=np.array([0.3, 0.5])) == (0.3, True)
    assert ex.signal_threshold(test, p, {"A", "B"}, target=1.0, grid=np.array([0.5, 0.95])) == (0.5, False)   # 달성 불가 → 최저값
    assert ex.cand_scores(test, p, 0.5, {"A", "B"})["cand_avg_precision"] == pytest.approx((1 + 2 / 3) / 2)  # 신호 3개: 0.9✓ 0.6✗ 0.4✓


def test_precision_threshold_and_blend():
    test = pd.DataFrame({"trdar_cd": ["A"] * 4, "date": [date(2025, 5, d) for d in range(1, 5)],
                         "ratio": [3.0, 1.0, 3.0, 1.0], "riders": [1.0, 2.0, 3.0, 4.0], "riders_base": [1.0] * 4})
    p = np.array([0.9, 0.7, 0.5, 0.2])
    assert ex.precision_threshold(test, p, {"A"}, target=0.6, grid=np.array([0.1, 0.4, 0.6, 0.8])) == (0.4, True)   # 0.4: 2/3
    assert ex.precision_threshold(test, p, {"A"}, target=0.9, grid=np.array([0.1, 0.4])) == (0.4, False)
    rng = np.random.default_rng(3)
    df = pd.DataFrame({"trdar_cd": np.where(np.arange(300) < 150, "A", "B"), "x": rng.normal(size=300)})
    df["ratio"] = np.where(df["x"] > 1.0, 3.0, 1.0)
    df["y"] = np.log(df["ratio"])
    pb, _, _ = ex._hurdle_parts(df, df, ["x"], clf_cand={"A"}, blend=True)
    pa, _, _ = ex._hurdle_parts(df, df, ["x"], clf_cand={"A"})
    pw, _, _ = ex._hurdle_parts(df, df, ["x"])
    assert np.allclose(pb, (pa + pw) / 2)


def test_band_scores_exclude_high_alerts():
    test = pd.DataFrame({"trdar_cd": ["A"] * 4, "date": [date(2025, 5, d) for d in range(1, 5)],
                         "ratio": [3.0, 3.0, 1.0, 1.0], "riders": [1.0, 2.0, 3.0, 4.0], "riders_base": [1.0] * 4})
    s = ex.band_scores(test, np.array([0.9, 0.3, 0.2, 0.01]), 0.1, 0.5, {"A"})
    assert (s["band_alerts"], s["band_precision"], s["band_recall"]) == (2, 0.5, 0.5)   # 0.9 는 높은 위험 이라 빠짐


def test_candidates_exclude_venue_districts():
    train = pd.DataFrame({"trdar_cd": ["A", "9000001"], "ratio": [2.5, 3.0]})
    assert ex.candidate_districts(train) == {"A"}


def test_precision_uncertainty_by_year():
    sig = pd.DataFrame({"year": 2025, "threshold": 0.5, "date": [date(2025, 5, 1), date(2025, 5, 1), date(2025, 10, 1), date(2025, 6, 1)],
                        "y": [True, False, True, True], "p": [0.9, 0.8, 0.7, 0.1]})
    u = ex.precision_uncertainty(sig, n_boot=200).iloc[0]
    assert (u["alerts"], u["hits"]) == (3, 2) and u["precision"] == pytest.approx(2 / 3)
    assert u["ci_low"] <= u["precision"] <= u["ci_high"]
    assert (u["jan_sep_alerts"], u["jan_sep_precision"]) == (2, 0.5)          # 10월 경보는 빠짐


def test_candidates_min_spikes_counts_distinct_days():
    train = pd.DataFrame({"trdar_cd": ["A", "A", "B", "C"], "ratio": [2.5, 3.0, 2.2, 1.0],
                          "date": [date(2024, 1, 1), date(2024, 2, 1), date(2024, 1, 1), date(2024, 1, 1)]})
    assert ex.candidate_districts(train) == {"A", "B"}
    assert ex.candidate_districts(train, min_spikes=2) == {"A"}


def test_bad_districts_and_filtered_scores():
    pool = pd.DataFrame({"trdar_cd": ["A"] * 5 + ["B"] * 3, "ratio": [1, 1, 1, 1, 3, 3, 3, 1]})
    pp = np.array([0.9] * 8)
    assert ex.bad_districts(pool, pp, 0.5) == {"A"}           # A: 경보 5 · 적중 1/5 = 0.2 < 0.25, B: 2/3
    frame = pd.DataFrame({"trdar_cd": ["A", "B"], "big_event_count": [0, 1], "semester_week": 0, "suneung_week": 0, "is_holiday": 0,
                          "short_festival_count": 0, "citywide_effect": np.nan, "bloom_day": np.nan, "venue_effect": np.nan})
    p = np.array([0.9, 0.9])
    assert ex.filtered_scores("원인 있는 날만", frame, p, set()).tolist() == [0.0, 0.9]
    assert ex.filtered_scores("자주 틀린 상권 제외", frame, p, {"B"}).tolist() == [0.9, 0.0]


def test_tree_override_does_not_collide_and_is_restored():
    rng = np.random.default_rng(4)
    df = pd.DataFrame({"x": rng.normal(size=200)})
    df["ratio"] = np.where(df["x"] > 1, 3.0, 1.0)
    df["y"] = np.log(df["ratio"])
    before = dict(ex.TREE)
    ex.TREE.update(min_child_weight=50)
    try:
        ex._reg(df, ["x"])                                      # min_child_weight 중복 인자 오류가 없어야 한다
    finally:
        ex.TREE.clear(); ex.TREE.update(before)
    assert ex.TREE == before


def test_cause_tags_keep_every_overlapping_cause():
    d = pd.DataFrame({"big_event_count": [1, 0, 0], "semester_week": [0, 0, 0], "suneung_week": [0, 0, 0], "is_holiday": [1, 0, 0],
                      "short_festival_count": [0, 1, 0], "citywide_effect": [np.nan, np.nan, np.nan], "bloom_day": [np.nan, 5.0, np.nan],
                      "venue_effect": [np.nan, np.nan, np.nan]})
    assert list(ex.cause_tags(d)) == ["큰 행사(경기 · 공연) + 공휴일", "축제 + 봄꽃 개화기", "원인 모름"]
    assert list(ex.cause_label(d))[:2] == ["큰 행사(경기 · 공연)", "축제"]  # 주원인 하나는 그대로 (호환)


def test_overlap_features_holiday_run_outdoor_rain_and_prior_year_anomaly():
    days = pd.date_range("2024-05-01", "2025-05-08")
    f = pd.DataFrame({"trdar_cd": "A", "date": days.date, "is_holiday": 0, "rainy": 0.0, "short_festival_count": 0.0,
                      "avg_ta": 15.0, "bloom_day": np.nan})
    f.loc[f["date"] == date(2025, 5, 5), "is_holiday"] = 1          # 월요일 어린이날 → 토 · 일 · 월 3일 연휴
    f.loc[f["date"] == date(2025, 5, 3), ["short_festival_count", "rainy"]] = [1.0, 1.0]
    f.loc[f["date"] == date(2025, 5, 4), "avg_ta"] = 25.0
    out = ex.add_overlap_features(f).set_index("date")
    assert out.loc[date(2025, 5, 3), "off_run"] == 3 and out.loc[date(2025, 5, 6), "off_run"] == 0
    assert out.loc[date(2025, 5, 3), "outdoor_rain"] == 1 and out.loc[date(2025, 5, 4), "outdoor_rain"] == 0
    assert np.isnan(out.loc[date(2024, 5, 4), "temp_anom"])                         # 첫 해: 이전 해가 없어 빈 값
    assert out.loc[date(2025, 5, 4), "temp_anom"] == pytest.approx(10.0)            # 이전 해 같은 철 15℃ 대비 (그해 값은 안 씀)


def test_known_cause_flags_any_scheduled_or_named_cause():
    f = pd.DataFrame({"short_event_count": [0, 2, 0, 0], "is_holiday": [0, 0, 0, 0], "suneung_week": [0, 0, 1, 0],
                      "venue_effect": [np.nan, np.nan, np.nan, np.nan], "bloom_day": [np.nan, np.nan, np.nan, 3.0]})
    assert ex.known_cause(f).tolist() == [0.0, 1.0, 1.0, 1.0]


def test_recent_uses_same_weekday_14_to_35_days_before_for_7_day_horizon():
    d = pd.date_range("2025-01-01", periods=60).date
    f = pd.DataFrame({"trdar_cd": "A", "date": d, "ratio": 1.0})
    f.loc[f["date"] == date(2025, 1, 15), "ratio"] = 4.0   # 2/12 기준 28일 전 (같은 요일)
    f.loc[f["date"] == date(2025, 2, 6), "ratio"] = 9.0    # 2/12 기준 6일 전 → 아직 모름
    out = ex.add_recent(f, horizon=7).set_index("date")
    r = out.loc[date(2025, 2, 12)]
    assert r["recent_ratio_max"] == 4.0 and r["recent_ratio_mean"] == pytest.approx(4.0 ** 0.25)
    assert r["recent_spikes"] == 1.0   # 6주 창(14 ~ 55일 전)에 1/15 하나


def _synthetic_hist(seed: int = 5) -> pd.DataFrame:
    """두 상권 · 2023 ~ 2025 매일, x 가 크면 급증 — rolling · fit_calibrator 용 (원인 열은 0 · 빈 값)."""
    rng = np.random.default_rng(seed)
    dates = [date(2023, 1, 1) + timedelta(days=i) for i in range(1096)]
    df = pd.DataFrame({"trdar_cd": np.repeat(["A", "B"], len(dates)), "date": dates * 2, "x": rng.normal(size=2 * len(dates))})
    df["ratio"] = np.where(df["x"] > 1.3, 3.0, 1.0)
    df["y"] = np.log(df["ratio"])
    df["riders"], df["riders_base"] = df["ratio"] * 100 + np.arange(len(df)), 100.0   # 행마다 다른 신호
    for c in ("big_event_count", "semester_week", "suneung_week", "is_holiday", "short_festival_count"):
        df[c] = 0
    for c in ("citywide_effect", "bloom_day", "venue_effect"):
        df[c] = np.nan
    return df


def test_rolling_passes_conf_target_to_precision_threshold(monkeypatch):
    seen = []
    real = ex.precision_threshold

    def spy(*a, target=ex.TARGET_PRECISION, **k):
        seen.append(target)
        return real(*a, target=target, **k)
    monkeypatch.setattr(ex, "precision_threshold", spy)
    ex.rolling(_synthetic_hist(), years=range(2024, 2026), features=["x"], blend=True, conf_target=0.4, tree={"n_estimators": 10})
    assert seen and set(seen) == {0.4}                       # tree 재귀 호출에도 전달


def test_fit_calibrator_learns_from_prior_year_scores():
    hist = _synthetic_hist()
    cal = ex.fit_calibrator(hist, ["x"], years=range(2024, 2026))
    assert cal is not None and cal.coef_[0][0] > 0           # 원래 점수가 높을수록 급증
    p = ex._stack_apply(cal, hist, np.full(len(hist), 0.5))
    assert p.shape == (len(hist),) and ((p > 0) & (p < 1)).all()
    assert ex.fit_calibrator(hist[hist["date"] < date(2024, 3, 1)], ["x"], years=range(2024, 2026)) is None   # 급증 신호 부족


def test_clean_baseline_skips_event_days():
    # 같은 요일 매주 1,000명, 직전 3주가 경기 날(3,000명) — 지금 기준선은 오염되지만 깨끗한 기준선은 1,000
    dates = [date(2025, 1, 6) + timedelta(weeks=w) for w in range(16)]
    day = pd.DataFrame({"trdar_cd": "A", "date": dates, "riders": 1000, "big_event_count": 0})
    day.loc[12:14, "riders"], day.loc[12:14, "big_event_count"] = 3000, 1
    day.loc[15, "riders"] = 3000  # 그날도 경기 인파
    c = ex.clean_baseline(day).set_index("date")
    assert c.loc[dates[15], "riders_base"] == 1000 and c.loc[dates[15], "ratio"] == 3.0
    assert np.isnan(c.loc[dates[2], "riders_base"])           # 깨끗한 주 4번 미만
    assert ex.clean_baseline(day, skip_dates=[dates[11]]).set_index("date").loc[dates[15], "riders_base"] == 1000


def test_district_attrs_nearest_big_event(monkeypatch):
    areas = pd.DataFrame({"TRDAR_CD": ["A", "B"], "TRDAR_SE_CD": ["D", "A"], "x": [0.0, 5000.0], "y": [0.0, 0.0]})
    f = pd.DataFrame({"trdar_cd": ["A", "B", "A"], "date": [date(2025, 5, 1), date(2025, 5, 1), date(2025, 5, 2)]})
    ev = pd.DataFrame({"event_id": ["e"], "kind": ["축구"], "seats": [60000.0], "lat": [0.0], "lon": [0.0],
                       "start_date": [pd.Timestamp(2025, 5, 1)], "end_date": [pd.Timestamp(2025, 5, 1)]})
    monkeypatch.setattr(ex.geo, "to_5181", lambda la, lo: (1000.0, 0.0))
    out = ex.add_district_attrs(f, areas, ev).set_index(["trdar_cd", "date"])
    assert out.loc[("A", date(2025, 5, 1)), "big_dist_km"] == pytest.approx(1.0)
    assert out.loc[("A", date(2025, 5, 1)), "big_near_seats"] == 60000
    assert np.isnan(out.loc[("B", date(2025, 5, 1)), "big_dist_km"])   # 4km — 3km 밖
    assert np.isnan(out.loc[("A", date(2025, 5, 2)), "big_dist_km"])   # 행사 없는 날
    assert out.loc[("A", date(2025, 5, 2)), "trdar_se"] == 1 and out.loc[("B", date(2025, 5, 1)), "trdar_se"] == 0


def test_weather_sensitivity_from_past_weekends_only():
    # 상권 A: 맑은 주말 2배 · 비 온 주말 1배 (토요일 12번, 짝수 번째가 비)
    sats = pd.date_range("2025-01-04", periods=12, freq="7D")
    f = pd.DataFrame({"trdar_cd": "A", "date": sats.date, "ratio": [1.0 if i % 2 else 2.0 for i in range(12)],
                      "avg_ta": 20.0, "sum_rn": 0.0})
    obs = pd.DataFrame({"date": sats.date, "avg_ta": 20.0, "sum_rn": [5.0 if i % 2 else 0.0 for i in range(12)]})
    out = ex.add_weather_sensitivity(f, obs, lag=7).set_index("date")
    assert np.isnan(out.loc[sats[5].date(), "rain_sens"])                       # 이전 주말이 조건마다 5번 미만
    assert out.loc[sats[11].date(), "rain_sens"] == pytest.approx(np.log(2.0))  # 그 전 주까지: 맑은 날 2배 vs 비 1배
    assert out.loc[sats[11].date(), "rain_sens_x"] == 0.0                       # 그날 예보는 비 아님


def test_alert_level_venue_tier():
    from weglee_ai.seoulevent.model.forecast import alert_level
    assert alert_level(0.6, False, 0.2, True, 0.5) == ex.ALERT_VENUE
    assert alert_level(0.4, False, 0.2, True, 0.5) == "" and alert_level(0.9, False, 0.2, True, None) == ""


def test_hourly_cutoff_follows_yearly_publication():
    c = ex.hourly_cutoff(pd.Series(pd.to_datetime(["2026-10-29", "2026-10-30", "2026-03-01"])))
    assert list(c.dt.date) == [date(2024, 12, 31), date(2025, 12, 31), date(2024, 12, 31)]


def test_evening_features_use_only_published_years():
    # 상권 A(역 '가(1)'), 2023-01-02 부터 매주 월요일 저녁 100명, 2024-01-01 · 2025-01-06 은 행사(300명)
    mons = pd.date_range("2023-01-02", "2025-12-29", freq="7D")
    hourly = pd.DataFrame({"station": "가(1)", "date": mons.date, **{h: 100.0 / 6 for h in ex.EVE_HOURS}})
    hourly.loc[hourly["date"].isin([date(2024, 1, 1), date(2025, 1, 6)]), ex.EVE_HOURS] = 300.0 / 6
    f = pd.DataFrame({"trdar_cd": "A", "station_key": "가", "date": [date(2025, 1, 6), date(2026, 1, 5)]})
    ev = pd.DataFrame({"source": "kopis", "trdar_cd": "A", "place": "홀", "start_date": pd.to_datetime(["2024-01-01", "2025-01-06", "2026-01-05"]),
                       "end_date": pd.to_datetime(["2024-01-01", "2025-01-06", "2026-01-05"])})
    out = ex.add_evening_features(f, ev, hourly, horizon=7).set_index("date")
    assert np.isnan(out.loc[date(2025, 1, 6), "eve_venue_effect"])                 # 2024년분은 2025-10-30 공개 → 아직 모름
    assert out.loc[date(2026, 1, 5), "eve_venue_effect"] == pytest.approx(3.0)     # 예측 시점 2025-12-29 엔 2024년분까지 — 2024-01-01 저녁 3배


def test_ops_lag_rounds_to_same_weekday():
    assert ex.ops_lag(7) == ex.OPS_LAG == 14 and ex.ops_lag(3) == 7 and ex.ops_lag(1) == 7 and ex.ops_lag(4) == 14


def test_candidates_include_venues_when_switched_on():
    train = pd.DataFrame({"trdar_cd": ["A", "9000001"], "date": [date(2024, 1, 1)] * 2, "ratio": [3.0, 3.0]})
    assert ex.candidate_districts(train) == {"A"}
    ex.VENUES["on"] = True
    try:
        assert ex.candidate_districts(train) == {"A", "9000001"}
    finally:
        ex.VENUES["on"] = False


def test_with_forecast_weather_replaces_observed():
    day = pd.DataFrame({"trdar_cd": "A", "date": [date(2021, 1, 1), date(2021, 2, 1)], "avg_ta": [1.0, 2.0], "sum_rn": [0.0, 5.0]})
    fc = pd.DataFrame({"date": ["2021-02-01"], "avg_ta": [9.0], "sum_rn": [0.5]})
    out = ex.with_forecast_weather(day, fc).set_index("date")
    assert out.loc[date(2021, 2, 1), "avg_ta"] == 9.0 and out.loc[date(2021, 2, 1), "sum_rn"] == 0.5
    assert np.isnan(out.loc[date(2021, 1, 1), "avg_ta"])          # 예보 없는 날은 실측으로 채우지 않는다


def test_relabel_and_paired_ap():
    old = pd.DataFrame({"trdar_cd": ["A", "B"], "date": [date(2025, 1, 1)] * 2, "x": [1.0, 2.0], "ratio": [1.0, 1.9], "y": [0.0, 0.6]})
    new = old.assign(x=[9.0, 9.0], ratio=[1.0, 3.0], y=[0.0, 1.1])
    r = ex.relabel(old, new)
    assert list(r["x"]) == [1.0, 2.0] and list(r["ratio"]) == [1.0, 3.0]          # 입력은 old, 정답은 new
    sig = lambda p: pd.DataFrame({"date": [date(2024, 1, d) for d in range(1, 9)], "key": "k", "year": 2024, "threshold": 0.5,
                                  "y": [True, False] * 4, "p": p})
    good, bad = sig([0.9, 0.1] * 4), sig([0.1, 0.9] * 4)
    out = ex.paired_ap(bad, good, n_boot=20)
    assert out["ap_b"] == 1.0 and out["diff"] > 0 and out["precision_b"] == 1.0 and out["recall_a"] == 0.0


def test_rolling_stack_extra_and_halflife_run():
    r = ex.rolling(_synthetic_hist(), years=range(2024, 2026), features=["x"], blend=True, stack=True,
                   stack_extra=("_mu", "_q95"), stack_halflife=1.0, tree={"n_estimators": 10})
    assert len(r) == 2 and r["cand_avg_precision"].notna().all()
