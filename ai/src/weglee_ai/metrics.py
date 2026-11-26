"""예측 평가 지표. 아키텍처 바이블 12절."""

import numpy as np


def wape(actual, predicted) -> float:
    """Σ|실제 − 예측| ÷ Σ실제. 실제 합이 0이면 nan."""
    a = np.asarray(actual, dtype=float)
    p = np.asarray(predicted, dtype=float)
    total = np.abs(a).sum()
    if total == 0:
        return float("nan")
    return float(np.abs(a - p).sum() / total)


def bias(actual, predicted) -> float:
    """Σ(예측 − 실제) ÷ Σ실제. 양수면 과대 예측(과잉 발주 쪽)."""
    a = np.asarray(actual, dtype=float)
    p = np.asarray(predicted, dtype=float)
    total = np.abs(a).sum()
    if total == 0:
        return float("nan")
    return float((p - a).sum() / total)
