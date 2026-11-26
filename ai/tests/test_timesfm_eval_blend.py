"""섞기 실험 시험 (-k blend)."""
import numpy as np
import pandas as pd
import pytest

from weglee_ai.timesfm_eval import blend as bl

START = pd.Timestamp("2024-03-01")


def _unit(n_weeks=4, seed=0):
    rng = np.random.default_rng(seed)
    d = pd.date_range(START, periods=7 * n_weeks)
    rows = []
    for s in ("A", "B", "C"):
        a = rng.integers(5, 30, len(d)).astype(float)
        rows.append(pd.DataFrame({"series": s, "date": d, "actual": a,
                                  "baseline": a + rng.normal(0, 6, len(d)),
                                  "timesfm": a * 0.8 + rng.normal(0, 3, len(d))}))
    return pd.concat(rows, ignore_index=True)


def test_blend_weight_ignores_scored_week_and_later():
    g = _unit()
    w1 = bl.choose_weights(g, START)
    assert set(w1) == {2, 3, 4}
    wk = bl.week_of(g["date"], START)
    for j in (2, 3, 4):
        g2 = g.copy()
        m = wk >= j
        g2.loc[m, "actual"] = 1000.0  # 채점 주와 그 뒤 값을 크게 바꾼다
        g2.loc[m, "timesfm"] = 0.0
        assert bl.choose_weights(g2, START)[j] == w1[j]


def test_blend_w0_w1_equal_baseline_and_timesfm_alone():
    g = _unit()
    wk = bl.week_of(g["date"], START)
    for j in (2, 3, 4):
        gw = g[wk == j]
        a = gw["actual"].to_numpy()
        assert bl.score_week(gw, 0.0)[0] == bl.wape(a, np.clip(gw["baseline"], 0, None))
        assert bl.score_week(gw, 1.0)[0] == bl.wape(a, np.clip(gw["timesfm"], 0, None))
    # 기준선·TimesFM 이 음수가 아니면 자르기 없이 단독과 같다
    gp = g.assign(baseline=g["baseline"].clip(lower=0), timesfm=g["timesfm"].clip(lower=0))
    weeks, _ = bl.evaluate_unit(gp, START)
    for _, r in weeks.iterrows():
        gw = gp[bl.week_of(gp["date"], START) == r["week"]]
        assert r["baseline_wape"] == bl.wape(gw["actual"], gw["baseline"])
        assert r["timesfm_wape"] == bl.wape(gw["actual"], gw["timesfm"])


def test_blend_tie_picks_smallest_w():
    g = _unit()
    g["timesfm"] = g["baseline"].clip(lower=0)  # 모든 w 의 WAPE 가 같다
    g["baseline"] = g["timesfm"]
    assert set(bl.choose_weights(g, START).values()) == {0.0}


def test_blend_week_one_not_scored_and_counts():
    weeks, s = bl.evaluate_unit(_unit(n_weeks=6), START)
    assert weeks["week"].tolist() == [2, 3, 4, 5, 6]
    assert s["n_weeks"] == 5


def test_blend_verdict_rule():
    assert bl.verdict(0.30, 0.0, 0.40, 0.35)[1] == bl.V_BETTER
    assert bl.verdict(0.348, 0.0, 0.40, 0.35)[1] == bl.V_TINY  # 개선 0.57%
    assert bl.verdict(0.30, 0.11, 0.40, 0.35)[1] == bl.V_NO  # Bias 초과
    assert bl.verdict(0.36, 0.0, 0.40, 0.35)[1] == bl.V_NO  # 못 이김


# ---------------------------------------------------------------- 손계산 대조·주 경계·판정 경계
def _hand_unit():
    """계열 1개, 3주(하루 1행). 1·2주는 기준선이 정확(→ 2·3주 모두 w = 0), 3주는 TimesFM 이 정확.

    채점 주(2·3주) 합친 손계산: 실제 합 70 + 700 = 770
      기준선 오차 0 + 350 = 350 → WAPE 350/770, Bias −350/770
      TimesFM 오차 70 + 0 = 70 → WAPE 70/770, Bias +70/770
      50:50 오차 35 + 175 = 210 → WAPE 210/770
      섞기(w = 0) = 기준선 → WAPE 350/770, Bias −350/770
    주별 WAPE 평균으로 잘못 합치면 기준선 0.25, TimesFM 0.5, 50:50 0.375 가 된다.
    """
    d = pd.date_range(START, periods=21)
    actual = [10.0] * 14 + [100.0] * 7
    base = [10.0] * 14 + [50.0] * 7
    tfm = [20.0] * 14 + [100.0] * 7
    return pd.DataFrame({"series": "A", "date": d, "actual": actual, "baseline": base, "timesfm": tfm})


def test_blend_summary_matches_hand_calculation():
    weeks, s = bl.evaluate_unit(_hand_unit(), START)
    assert weeks["w"].tolist() == [0.0, 0.0]
    assert s["baseline_wape"] == pytest.approx(350 / 770)
    assert s["timesfm_wape"] == pytest.approx(70 / 770)
    assert s["half_wape"] == pytest.approx(210 / 770)
    assert s["blend_wape"] == pytest.approx(350 / 770)
    assert s["baseline_bias"] == pytest.approx(-350 / 770)
    assert s["timesfm_bias"] == pytest.approx(70 / 770)
    assert s["blend_bias"] == pytest.approx(-350 / 770)
    assert s["n_rows"] == 14 and s["n_weeks"] == 2


def test_blend_week_tie_is_not_a_win():
    weeks, s = bl.evaluate_unit(_hand_unit(), START)
    # w = 0 → 섞기 = 기준선. 2주: 섞기 0 = 기준선 0 (동점) → 이긴 주 아님
    assert weeks["blend_beats_both"].tolist() == [False, False]
    assert s["weeks_won"] == 0


def test_blend_week_boundaries_fixed_dates():
    days = [START, START + pd.Timedelta(days=6), START + pd.Timedelta(days=7),
            START + pd.Timedelta(days=13), START + pd.Timedelta(days=14)]
    assert bl.week_of(days, START).tolist() == [1, 1, 2, 2, 3]
    # evaluate_unit 의 주 나누기도 같은 경계: 첫날+7일 행은 2주 채점에 들어간다
    g = _hand_unit()
    weeks, _ = bl.evaluate_unit(g, START)
    assert weeks["n_rows"].tolist() == [7, 7]
    assert weeks["week"].tolist() == [2, 3]


def test_blend_verdict_exact_boundaries():
    # 개선폭 = (100 − 99) / 100 = 0.01 정확히 → '1% 이상' 이므로 낫다
    assert bl.verdict(99.0, 0.0, 100.0, 120.0) == (0.01, bl.V_BETTER)
    # 경계 바로 안쪽(개선폭 0.99%) → 차이 미미
    assert bl.verdict(99.01, 0.0, 100.0, 120.0)[1] == bl.V_TINY
    # |Bias| = 0.1 정확히 → '10% 이하' 이므로 통과
    assert bl.verdict(90.0, 0.1, 100.0, 120.0)[1] == bl.V_BETTER
    assert bl.verdict(90.0, -0.1, 100.0, 120.0)[1] == bl.V_BETTER
    # 바로 바깥 → 아니다
    assert bl.verdict(90.0, 0.1001, 100.0, 120.0)[1] == bl.V_NO
    # 섞은 쪽 = min (동점) → 아니다
    assert bl.verdict(100.0, 0.0, 100.0, 120.0)[1] == bl.V_NO
