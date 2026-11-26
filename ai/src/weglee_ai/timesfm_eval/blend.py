"""기준선 × TimesFM 섞기 — 저장된 예측값만으로, 주 단위로 굴리며 섞는 비율 w 를 고르고 다음 주를 채점한다.

섞은 예측 = (1 − w) × 기준선 + w × TimesFM (음수는 0 으로).
시험 기간을 시험 첫날부터 7일 단위 주로 나눈다. j 번째 주(j ≥ 2)마다 그 앞 1 ~ j−1 주만 보고
w 를 {0, 0.05, …, 1} 에서 WAPE 가 가장 작은 값으로 고르고(같으면 작은 w), j 번째 주만 채점한다. 1 주차는 채점하지 않는다.
같은 채점 주에서 기준선·TimesFM·50:50(w = 0.5) 도 채점한다.

판정 규칙(결과를 보기 전에 고정한 것이 아니다 — 앞·뒤 절반으로 한 번 나눈 결과를 본 뒤 정했다. 그래서 판정은
탐색적이다: 같은 기간 안에서 규칙을 정하고 잰 것이라 독립 확인이 아니다):
# 채점 주 전체를 합친 WAPE 로 섞은 쪽 < min(기준선, TimesFM) 이고 |섞은 쪽 Bias| ≤ 10% 이면,
# 개선폭 (min − 섞은 쪽) ÷ min 이 1% 이상이면 `섞기가 낫다`, 1% 미만이면 `차이 미미`. 그 밖은 `아니다`.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from weglee_ai import metrics

W_GRID = tuple(float(x) for x in np.round(np.arange(0, 1.0001, 0.05), 2))
W_HALF = 0.5
BIAS_LIMIT = 0.10
MIN_IMPROVE = 0.01
_EPS = 1e-12

V_BETTER = "섞기가 낫다"
V_TINY = "차이 미미"
V_NO = "아니다"
RULE = ("채점 주 전체를 합친 WAPE 로 섞은 쪽 < min(기준선, TimesFM) 이고 |섞은 쪽 Bias| ≤ 10% 이면, "
        "개선폭 (min − 섞은 쪽) ÷ min 이 1% 이상이면 `섞기가 낫다`, 1% 미만이면 `차이 미미`. 그 밖은 `아니다`.")

wape = metrics.wape
bias = metrics.bias


def blend(baseline, timesfm, w: float) -> np.ndarray:
    b, t = np.asarray(baseline, float), np.asarray(timesfm, float)
    return np.clip((1 - w) * b + w * t, 0.0, None)


def week_of(dates, start) -> np.ndarray:
    """시험 첫날부터 7일 단위 주 번호(1부터)."""
    d = pd.to_datetime(pd.Series(dates)).reset_index(drop=True)
    return ((d - pd.Timestamp(start)).dt.days // 7 + 1).to_numpy()


def pick_w(train: pd.DataFrame) -> float:
    """train(actual·baseline·timesfm)에서 섞은 WAPE 가 가장 작은 w. 같으면 작은 w. 계산 불가면 0."""
    a = train["actual"].to_numpy(float)
    scores = []
    for w in W_GRID:
        s = wape(a, blend(train["baseline"], train["timesfm"], w)) if len(a) else float("nan")
        scores.append(math.inf if s is None or math.isnan(s) else s)
    best = min(scores)
    if math.isinf(best):
        return 0.0
    return next(w for w, s in zip(W_GRID, scores, strict=False) if s <= best + _EPS)


def choose_weights(g: pd.DataFrame, start) -> dict[int, float]:
    """주 j(≥2) → 1 ~ j−1 주로 고른 w. g 의 j 주 이후 값은 보지 않는다."""
    wk = week_of(g["date"], start)
    out = {}
    for j in sorted(set(wk)):
        if j < 2:
            continue
        out[int(j)] = pick_w(g[wk < j])
    return out


def score_week(gw: pd.DataFrame, w: float) -> tuple[float, float]:
    a = gw["actual"].to_numpy(float)
    p = blend(gw["baseline"], gw["timesfm"], w)
    return wape(a, p), bias(a, p)


def evaluate_unit(g: pd.DataFrame, start) -> tuple[pd.DataFrame, dict]:
    """한 단위(데이터셋 × 방식 × 이력 × 0 비율 구간)의 주별 표와 합친 요약."""
    g = g.reset_index(drop=True)
    wk = week_of(g["date"], start)
    weights = choose_weights(g, start)
    rows, scored = [], []
    for j, w in weights.items():
        gw = g[wk == j]
        if gw.empty:
            continue
        bw, _ = score_week(gw, 0.0)
        tw, _ = score_week(gw, 1.0)
        hw, _ = score_week(gw, W_HALF)
        mw, mb = score_week(gw, w)
        rows.append({"week": j, "w": w, "n_rows": len(gw), "baseline_wape": bw, "timesfm_wape": tw,
                     "half_wape": hw, "blend_wape": mw, "blend_bias": mb,
                     "blend_beats_both": bool(mw < min(bw, tw))})
        scored.append(gw.assign(blend=blend(gw["baseline"], gw["timesfm"], w)))
    weeks = pd.DataFrame(rows, columns=["week", "w", "n_rows", "baseline_wape", "timesfm_wape", "half_wape",
                                        "blend_wape", "blend_bias", "blend_beats_both"])
    if not scored:
        return weeks, {"n_weeks": 0, "verdict": V_NO}
    sc = pd.concat(scored)
    a = sc["actual"].to_numpy(float)
    s = {
        "n_weeks": len(weeks), "n_rows": len(sc), "n_series": sc["series"].nunique(),
        "baseline_wape": wape(a, sc["baseline"]), "baseline_bias": bias(a, sc["baseline"]),
        "timesfm_wape": wape(a, sc["timesfm"]), "timesfm_bias": bias(a, sc["timesfm"]),
        "half_wape": wape(a, blend(sc["baseline"], sc["timesfm"], W_HALF)),
        "half_bias": bias(a, blend(sc["baseline"], sc["timesfm"], W_HALF)),
        "blend_wape": wape(a, sc["blend"]), "blend_bias": bias(a, sc["blend"]),
        "weeks_won": int(weeks["blend_beats_both"].sum()),
        "weights": " · ".join(f"{j}주 {w:.2f}" for j, w in zip(weeks["week"], weeks["w"], strict=False)),
    }
    s["improve"], s["verdict"] = verdict(s["blend_wape"], s["blend_bias"], s["baseline_wape"], s["timesfm_wape"])
    return weeks, s


def verdict(blend_wape: float, blend_bias: float, base_wape: float, tfm_wape: float) -> tuple[float, str]:
    """(개선폭, 판정). 규칙은 RULE 문장과 같다."""
    lo = min(base_wape, tfm_wape)
    if any(x is None or math.isnan(x) for x in (blend_wape, blend_bias, lo)) or lo == 0:
        return float("nan"), V_NO
    imp = (lo - blend_wape) / lo
    if blend_wape < lo and abs(blend_bias) <= BIAS_LIMIT + _EPS:
        return imp, (V_BETTER if imp >= MIN_IMPROVE - _EPS else V_TINY)
    return imp, V_NO
