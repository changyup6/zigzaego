import pandas as pd

from weglee_ai.seoulevent.model.forecast import _reason, status


def test_publish_merge_prefers_three_day_and_digest():
    import pandas as pd
    from datetime import date
    from weglee_ai.seoulevent.model import experiment as ex
    from weglee_ai.seoulevent.model.publish import build_reason, day_items, merge
    row = lambda d, s, p: {"날짜": d, "요일": "토", "상권": s, "평소 인원": 100.0, "예상 인원": 300.0, "예측 배수": 3.0, "상태": "급증",
                           "급증 점수": p, "경보": ex.ALERT_HIGH if p > 0.5 else "", "근거": "콘서트"}
    f7 = pd.DataFrame([row("2026-11-11", "가", 0.1), row("2026-11-12", "가", 0.2), row("2026-11-15", "가", 0.9)])
    f3 = pd.DataFrame([row("2026-11-12", "가", 0.7)])
    m = merge(f7, f3, date(2026, 11, 12))
    assert list(m["날짜"]) == ["2026-11-12", "2026-11-15"]                      # 오늘 전(11/11) 은 빠진다
    assert list(m["예측 시점"]) == ["3일 앞", "7일 앞"] and m["급증 점수"].iloc[0] == 0.7   # 겹치는 날은 3일 앞
    items = day_items(m, {"2026-11-12": (13.2, 26.2), "2026-11-15": (10.0, 0.0)})
    assert items[0]["높은 위험"] == 1 and items[0]["주요 상권"][0]["예상 인원"] == 300 and items[0]["날씨 예보"] == {"평균기온": 13.2, "강수mm": 26.2}
    assert items[1]["날씨 예보"] is None                                      # 날씨는 3일 앞 날짜에만
    r = build_reason(items, {"주간 설명": "GMS 문단"}, "GMS (x)", date(2026, 11, 12))
    assert r["주간 설명"] == "GMS 문단" and "설명" not in r["날짜별"][0]          # 설명은 일주일 통합 하나
    fb = build_reason(items, None, "규칙", date(2026, 11, 12))["주간 설명"]
    assert "2026-11-12(토)" in fb and "콘서트" in fb                               # GMS 없으면 규칙 문장 (경보 날 · 가장 붐빌 곳)
    assert r["기간"] == ["2026-11-12", "2026-11-15"] and r["주의"]


def test_area_gu_gives_venue_the_nearest_gu():
    from weglee_ai.seoulevent.model.forecast import area_gu
    areas = pd.DataFrame({"TRDAR_CD": ["1", "2", "9000001"], "SIGNGU_CD_NM": ["송파구", "마포구", None],
                          "x": [0.0, 100.0, 5.0], "y": [0.0, 0.0, 0.0]})
    assert area_gu(areas).to_dict() == {"1": "송파구", "2": "마포구", "9000001": "송파구"}


def test_score_one_counts_alert_hits_and_skips_unknown_days():
    import pandas as pd
    from datetime import date
    from weglee_ai.seoulevent.model import experiment as ex
    from weglee_ai.seoulevent.model.score import score_one
    fc = pd.DataFrame({"날짜": ["2026-11-01", "2026-11-01", "2026-11-02", "2026-11-03"], "상권": ["가", "고척", "가", "가"],
                       "상권 코드": ["A", "9000002", "A", "A"], "경보": [ex.ALERT_HIGH, ex.ALERT_VENUE, "", ex.ALERT_HIGH],
                       "예상 인원": [200.0, 300.0, 100.0, 100.0]})
    day = pd.DataFrame({"trdar_cd": ["A", "9000002", "A"], "date": [date(2026, 11, 1), date(2026, 11, 1), date(2026, 11, 2)], "riders": [100, 300, 100]})
    spikes = pd.DataFrame({"trdar_cd": ["A", "9000002"], "date": [date(2026, 11, 1), date(2026, 11, 1)]})
    r = score_one(fc, day, spikes, pd.Series(dtype=str))
    assert r["채점 날수"] == 2                                     # 11/3 은 실제 승하차가 아직 없어 빠진다
    assert (r["높은 위험 경보"], r["높은 위험 적중"], r["행사장 참고 경보"], r["행사장 참고 적중"]) == (1, 1, 1, 1)
    assert (r["붐비는 날 경보"], r["붐비는 날 적중"], r["급증 있던 날"]) == (1, 1, 1)
    assert r["예상 인원 오차 중앙값"] == 0.0


def test_status_labels():
    assert [status(r) for r in (2.5, 1.5, 1.0, 0.5)] == ["급증", "붐빔", "평소", "한산"]


def test_reason_mentions_scheduled_events():
    row = pd.Series({"is_holiday": 0, "holiday_before": 0, "holiday_after": 0, "ly_spike": 0.0, "ly_max_ratio": float("nan"),
                     "ly_city_spikes": 0.0, "semester_week": 0})
    assert _reason(row, ["서울 vs 울산"]) == "예정: 서울 vs 울산"
    assert _reason(row, []) == "특이 일정 없음"


def test_alert_level_high_only():
    from weglee_ai.seoulevent.model import experiment as ex
    from weglee_ai.seoulevent.model.forecast import alert_level
    assert alert_level(0.7, True, 0.55) == ex.ALERT_HIGH
    assert alert_level(0.3, True, 0.55) == ""
    assert alert_level(0.9, False, 0.55) == ""      # 후보 상권 밖은 경보 없음


def test_precision_note_splits_overall_and_same_threshold():
    import pandas as pd
    from weglee_ai.seoulevent.model.forecast import precision_note
    r = pd.DataFrame({"conf_threshold": [0.55, 0.65, 0.50, 0.50], "conf_alerts": [51, 74, 136, 91],
                      "conf_precision": [24 / 51, 57 / 74, 58 / 136, 37 / 91]})
    assert precision_note(r, 0.50) == "2배 급증 전체 50% (176/352) · 같은 기준값 42% (95/227) · 연도별 41% ~ 77%"
    r["conf_busy"] = 0.75
    assert precision_note(r, 0.50).endswith("· 1.3배 이상 붐빔 75% (264/352)")


def test_thresholds_prefer_ops_label_row(tmp_path):
    from weglee_ai.seoulevent.model import experiment as ex
    from weglee_ai.seoulevent.model.forecast import thresholds
    row = dict(conf_alerts=10, conf_precision=0.5)
    r = pd.DataFrame([{"variant": ex.BLEND_LABEL, "year": 2026, "conf_threshold": 0.6, **row},
                      {"variant": ex.OPS_LABEL, "year": 2025, "conf_threshold": 0.3, **row},
                      {"variant": ex.OPS_LABEL, "year": 2026, "conf_threshold": 0.4, **row}])
    r.to_csv(tmp_path / "experiment_rolling.csv", index=False, encoding="utf-8-sig")
    assert thresholds(tmp_path)[::2] == (0.4, True)
    r[r["variant"] == ex.BLEND_LABEL].to_csv(tmp_path / "experiment_rolling.csv", index=False, encoding="utf-8-sig")
    assert thresholds(tmp_path)[::2] == (0.6, False)        # 운영 줄 없으면 보정 없는 기준값 · 보정 안 함


def test_save_and_load_model_roundtrip(tmp_path):
    import numpy as np
    import pytest
    from weglee_ai.seoulevent.model import experiment as ex
    from weglee_ai.seoulevent.model.forecast import load_model, save_model
    rng = np.random.default_rng(0)
    df = pd.DataFrame({"trdar_cd": "A", "x": rng.normal(size=200)})
    df["ratio"] = np.where(df["x"] > 1, 3.0, 1.0)
    df["y"] = np.log(df["ratio"])
    h = ex.fit_hurdle(df, ["x"])
    bundle = {"version": "test", "created_at": "now", "features": ex.OPS_FEATURES, "threshold": 0.3, "hit_range": "",
              "train_until": "2026-11-06", "hurdle": h, "calibrator": None, "cand": ["A"]}
    save_model(tmp_path / "m", bundle)
    assert (tmp_path / "m" / "meta.json").exists()
    back = load_model(tmp_path / "m")
    assert np.allclose(ex.predict_hurdle(back["hurdle"], df)[0], ex.predict_hurdle(h, df)[0])  # 저장 전후 같은 예측
    save_model(tmp_path / "old", bundle | {"features": ["다른 특징"]})
    with pytest.raises(ValueError):
        load_model(tmp_path / "old")  # 운영 특징이 바뀌면 옛 모델로 예측하지 않음
