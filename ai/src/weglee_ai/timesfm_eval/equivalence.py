"""동등성 검정 — 모델이 비교 대상(기준선)과 '실질적으로 같은지 / 나은지 / 못한지' 를 판정한다.

결과를 보기 전에 고정한 정의(TimesFM 채택 검증의 다음 단계로 정함):
- 비교량: 날짜별 하루 오차 차이 = 모델 오차 − 기준선 오차. 하루 오차는 그날 모든 계열의 |실제 − 예측| 합
  (WAPE 의 분자). 그래서 '평균 차이 ÷ 기준선 평균 일일 오차' = (모델 WAPE − 기준선 WAPE) ÷ 기준선 WAPE.
- 신뢰구간: 7일 블록 부트스트랩 90% 백분위 구간. 블록은 겹치는 연속 7일(시작 0 ~ n−7)을 뽑아 이어 붙이고
  길이 n 으로 자른다.
- 동등 범위: 기준선 평균 일일 오차의 ±10%. 전체 표본에서 한 번 구해 고정한다(다시 뽑을 때마다 바꾸지 않음).
- 판정: 구간 전체가 ±10% 안이고 0 을 포함 → 같다(동등), ±10% 안이지만 0 보다 위(아래끝 > 0) → 허용 범위 내(열세),
  ±10% 안이지만 0 보다 아래(위끝 < 0) → 허용 범위 내(우세), 위끝이 −10% 보다 작음 → 낫다,
  아래끝이 +10% 보다 큼 → 못하다, 그 밖 → 판단 불가. 날 수가 블록 2개(14일)보다 적으면 구간을 내지 않고 판단 불가
  (7일이면 묶음 시작점이 하나뿐이라 다시 뽑아도 매번 같은 표본이 되어 구간 폭이 0 이 된다).
- 날 수가 블록 8개(56일)보다 적으면 판정은 내되 '탐색적' 으로 표시한다(exploratory) — 묶음이 적어 구간을 믿기 어렵다.
- 동등 범위 ±10% 에는 근거가 없다 — 업계 기준이나 사장님 발주 허용 오차에서 온 값이 아니라 관례적으로 고른 값이다.
  채택 판정(엄격 기준 5% 이상 개선)과 달리 ±10% 안의 열세를 허용하므로 같다(동등)를 '써도 된다' 로 읽으면 안 된다.
- 허용 범위 내(열세·우세) 이름은 결과를 본 다음 붙였다. 판정 경계는 바꾸지 않았다.
- 실제값·예측에 빈 값(NaN)이 있으면 합치기 전에 ValueError — 그냥 합치면 빈 값이 오차 0 으로 들어간다.
90% 구간은 양쪽 경계를 각각 5% 로 검정하는 TOST 와 같은 결론을 낸다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

import numpy as np
import pandas as pd

BLOCK = 7
N_BOOT = 2000
SEED = 42
MIN_BLOCKS = 2  # 구간을 내려면 날 수 ≥ MIN_BLOCKS × block
MIN_BLOCKS_CONFIRM = 8  # 날 수 < MIN_BLOCKS_CONFIRM × block 이면 '탐색적'
MARGIN = 0.10
LEVEL = 0.90

V_SAME = "같다(동등)"
V_TOL_WORSE = "허용 범위 내(열세)"
V_TOL_BETTER = "허용 범위 내(우세)"
V_BETTER = "낫다"
V_WORSE = "못하다"
V_UNKNOWN = "판단 불가"

# 리포트 안 동등성 절의 표시 주석. run_equivalence 가 이 사이를 바꾸고, run_adoption 은 리포트를 다시 쓸 때 이 사이를 그대로 둔다.
REPORT_START, REPORT_END = "<!-- equivalence:start -->", "<!-- equivalence:end -->"
_SECTION = re.compile(re.escape(REPORT_START) + r"(?:(?!" + re.escape(REPORT_START) + r").)*?"
                      + re.escape(REPORT_END), flags=re.S)


def find_report_section(body: str) -> tuple[int, int] | None:
    """리포트 안 동등성 절의 (시작, 끝) 위치. 짝이 맞는 것 = 시작 표시 뒤에 다른 시작 표시 없이 처음 나오는 끝 표시.

    짝이 여럿이면 마지막 것, 없으면 None. 짝 없는 표시는 절로 보지 않는다(그 사이 본문을 건드리지 않는다).
    run_equivalence(절 바꿔 넣기)와 run_adoption(리포트 다시 쓸 때 절 옮기기)이 같은 규칙을 쓴다.
    """
    found = list(_SECTION.finditer(body))
    return (found[-1].start(), found[-1].end()) if found else None


@dataclass(frozen=True)
class EquivResult:
    n_days: int
    mean_model_err: float
    mean_base_err: float
    mean_diff: float          # 모델 − 기준선, 하루 오차 평균
    ci_low: float             # 평균 차이의 90% 구간 (절대값)
    ci_high: float
    margin_abs: float         # 동등 범위 = MARGIN × 기준선 평균 일일 오차
    rel_mean_diff: float      # 아래 셋은 기준선 평균 일일 오차로 나눈 비율
    rel_ci_low: float
    rel_ci_high: float
    margin: float
    level: float
    verdict: str
    exploratory: bool         # 블록 MIN_BLOCKS_CONFIRM 개 미만 → 탐색적
    block: int
    n_boot: int
    seed: int


def daily_errors(df: pd.DataFrame, model: str = "timesfm", base: str = "baseline",
                 actual: str = "actual", date: str = "date") -> pd.DataFrame:
    """날짜마다 모든 계열의 |실제 − 예측| 합. 칸: date, model_err, base_err, diff(= model_err − base_err).

    날짜마다 그날 행이 있는 계열만 더한다(계열마다 날짜 수가 달라도 된다). 빈 값(NaN)이 있으면 ValueError.
    """
    if df is None or len(df) == 0:
        raise ValueError("입력 표가 비어 있다")
    for col in (date, actual, model, base):
        bad = int(pd.isna(df[col]).sum())
        if bad:
            raise ValueError(f"'{col}' 칸에 빈 값(NaN)이 {bad}줄 있다 — 합치면 오차 0 으로 들어가므로 막는다")
    a = df[actual].astype(float)
    e = pd.DataFrame({"date": pd.to_datetime(df[date]),
                      "model_err": (a - df[model].astype(float)).abs(),
                      "base_err": (a - df[base].astype(float)).abs()})
    out = e.groupby("date", as_index=False)[["model_err", "base_err"]].sum().sort_values("date")
    out["diff"] = out["model_err"] - out["base_err"]
    return out.reset_index(drop=True)


def block_indices(n: int, block: int = BLOCK, rng: np.random.Generator | None = None) -> np.ndarray:
    """겹치는 연속 block 일 묶음을 뽑아 이어 붙인 길이 n 의 위치 배열(moving block bootstrap 한 번)."""
    if n < block:
        raise ValueError(f"날 수 {n} 이 블록 {block} 보다 적다")
    rng = rng if rng is not None else np.random.default_rng()
    k = -(-n // block)
    starts = rng.integers(0, n - block + 1, size=k)
    return (starts[:, None] + np.arange(block)[None, :]).reshape(-1)[:n]


def _judge(lo: float, hi: float, margin: float) -> str:
    if hi < -margin:
        return V_BETTER
    if lo > margin:
        return V_WORSE
    if lo >= -margin and hi <= margin:
        if lo > 0:
            return V_TOL_WORSE
        if hi < 0:
            return V_TOL_BETTER
        return V_SAME
    return V_UNKNOWN


def equivalence_test(model_err, base_err, block: int = BLOCK, n_boot: int = N_BOOT, seed: int = SEED,
                     margin: float = MARGIN, level: float = LEVEL) -> EquivResult:
    """날짜순 하루 오차 두 배열로 동등성 검정. 빈 입력·길이 다름·기준선 오차 합 0 은 ValueError."""
    m = np.asarray(model_err, dtype=float).reshape(-1)
    b = np.asarray(base_err, dtype=float).reshape(-1)
    if len(m) == 0 or len(b) == 0:
        raise ValueError("오차 배열이 비어 있다")
    if len(m) != len(b):
        raise ValueError(f"두 오차 배열의 길이가 다르다: {len(m)} vs {len(b)}")
    if not (np.isfinite(m).all() and np.isfinite(b).all()):
        raise ValueError("오차 배열에 nan·무한대가 있다")
    mean_b = float(b.mean())
    if mean_b <= 0:
        raise ValueError("기준선 오차 합이 0 이라 동등 범위(기준선 평균 일일 오차의 ±10%)를 정할 수 없다")
    n = len(m)
    d = m - b
    mean_d = float(d.mean())
    if n < MIN_BLOCKS * block:
        lo = hi = float("nan")
        verdict = V_UNKNOWN
    else:
        rng = np.random.default_rng(seed)
        means = np.empty(n_boot)
        for i in range(n_boot):
            means[i] = d[block_indices(n, block, rng)].mean()
        alpha = (1.0 - level) / 2.0
        lo, hi = (float(x) for x in np.quantile(means, [alpha, 1.0 - alpha]))
        verdict = _judge(lo / mean_b, hi / mean_b, margin)
    return EquivResult(
        n_days=n, mean_model_err=float(m.mean()), mean_base_err=mean_b, mean_diff=mean_d,
        ci_low=lo, ci_high=hi, margin_abs=margin * mean_b,
        rel_mean_diff=mean_d / mean_b, rel_ci_low=lo / mean_b, rel_ci_high=hi / mean_b,
        margin=margin, level=level, verdict=verdict, exploratory=n < MIN_BLOCKS_CONFIRM * block,
        block=block, n_boot=n_boot, seed=seed)


def equivalence_from_predictions(df: pd.DataFrame, model: str = "timesfm", base: str = "baseline",
                                 **kw) -> EquivResult:
    """예측 표(date, series, actual, 모델 칸, 기준 칸) → 날짜별로 모은 뒤 동등성 검정."""
    d = daily_errors(df, model=model, base=base)
    return equivalence_test(d["model_err"].to_numpy(), d["base_err"].to_numpy(), **kw)
