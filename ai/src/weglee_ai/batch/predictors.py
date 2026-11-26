"""야간 예측 배치의 예측기 두 개 (설계 5.2, DECISIONS #24).

둘 다 같은 모양으로 부른다: 계열 목록(영업일만, 0 판매 포함) + 예측할 날짜 → 계열마다 날짜별 값.

- DowAvgPredictor: 최근 4주 같은 요일 평균. TimesFM 이 없을 때 서비스가 쓰는 값이자 비교 기준
- TimesFmPredictor: TimesFM 2.5 zero-shot. 학습 없이 과거 계열만 넣어 예측한다
"""

import logging
from collections.abc import Sequence
from datetime import date, timedelta
from typing import Protocol

import numpy as np
import pandas as pd

from weglee_ai.baseline import DowAvgPredictor  # noqa: F401 — baseline 으로 옮김, 이 경로로 쓰던 곳은 그대로

log = logging.getLogger(__name__)


class Predictor(Protocol):
    """forecast.model 에 들어갈 이름과 예측 함수."""

    name: str

    def predict(self, series: Sequence[pd.Series], targets: Sequence[date]) -> list[list[float] | None]:
        """series[i] = 한 (메뉴, 채널)의 영업일 판매(index=날짜). 반환[i][j] = series[i] 의 targets[j] 예측.

        예측할 수 없는 계열은 None — 그 계열만 저장하지 않는다.
        """
        ...


def to_calendar(series: pd.Series, end: date) -> np.ndarray:
    """영업일만 있는 계열 → 첫 날부터 end 까지 하루도 빠짐없는 배열.

    TimesFM 은 날짜가 이어진 계열을 기대한다. 휴무로 빠진 날은 그 요일의 평균(없으면 전체 평균)으로 메운다.
    """
    s = series.sort_index()
    days = pd.date_range(s.index.min(), pd.Timestamp(end), freq="D")
    by_weekday = s.groupby(s.index.dayofweek).mean()
    overall = float(s.mean())
    full = s.reindex(days)
    gaps = full.isna()
    full[gaps] = [by_weekday.get(d.dayofweek, overall) for d in full.index[gaps]]
    return full.to_numpy(dtype=float)


# TimesFM 예측 설정 — 서비스 배치와 채택 검증 실험(timesfm_eval.adoption)이 같이 쓴다. 여기 한 곳에서만 바꾼다.
# max_context 는 부르는 쪽이 정한다(배치 512, 실험은 묶음마다 입력 길이에 맞춤).
FORECAST_CONFIG = dict(
    max_horizon=128,
    normalize_inputs=True,
    use_continuous_quantile_head=True,
    force_flip_invariance=True,
    infer_is_positive=True,
    fix_quantile_crossing=True,
)


class TimesFmPredictor:
    """TimesFM 2.5 (200M, PyTorch, CPU). 모델은 처음 predict 할 때 한 번만 불러온다."""

    name = "timesfm"
    MODEL_ID = "google/timesfm-2.5-200m-pytorch"
    MAX_CONTEXT = 512
    MAX_HORIZON = FORECAST_CONFIG["max_horizon"]

    def __init__(self) -> None:
        self._model = None

    def _load(self):
        if self._model is None:
            import timesfm  # batch 의존성 그룹에만 있다 (uv sync --group batch)

            model = timesfm.TimesFM_2p5_200M_torch.from_pretrained(self.MODEL_ID)
            model.compile(timesfm.ForecastConfig(max_context=self.MAX_CONTEXT, **FORECAST_CONFIG))
            self._model = model
        return self._model

    def predict(self, series: Sequence[pd.Series], targets: Sequence[date]) -> list[list[float] | None]:
        if not series:
            return []
        last_target = max(targets)
        # 계열마다 마지막 판매일 다음 날부터가 예측 구간이다. 판매가 오래 끊긴 계열(구간이 MAX_HORIZON 초과,
        # 예: 배달을 넉 달 쉼)은 그 계열만 건너뛴다 — 한 계열 때문에 가게 전체 TimesFM 이 빠지지 않게
        usable, inputs, starts = [], [], []
        for i, s in enumerate(series):
            end = s.index.max().date()
            start = end + timedelta(days=1)
            if (last_target - start).days + 1 > self.MAX_HORIZON:
                continue
            usable.append(i)
            inputs.append(to_calendar(s, end)[-self.MAX_CONTEXT :])
            starts.append(start)
        if len(usable) < len(series):
            log.warning("TimesFM: 판매가 %d일 넘게 끊긴 계열 %d개는 건너뜀", self.MAX_HORIZON, len(series) - len(usable))
        result: list[list[float] | None] = [None] * len(series)
        if not usable:
            return result
        horizon = max((last_target - start).days + 1 for start in starts)
        point, _ = self._load().forecast(horizon=horizon, inputs=inputs)
        for k, i in enumerate(usable):
            result[i] = [float(point[k][(t - starts[k]).days]) for t in targets]
        return result
