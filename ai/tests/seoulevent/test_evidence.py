import numpy as np
import pandas as pd
from xgboost import XGBRegressor

from weglee_ai.seoulevent.model.forecast import evidence


def _model(x, y):
    return XGBRegressor(n_estimators=50, max_depth=2, learning_rate=0.3, device="cpu").fit(x, y), list(x.columns)


def test_evidence_names_the_feature_that_moved_the_multiplier():
    rng = np.random.default_rng(0)
    x = pd.DataFrame({"big_event_count": rng.integers(0, 2, 400).astype(float), "weekday": rng.integers(0, 7, 400).astype(float)})
    y = np.log(np.where(x["big_event_count"] == 1, 2.0, 1.0))  # 큰 행사 날만 2배 (log 배수)
    hurdle = {"normal": _model(x, y), "spike": _model(x, y * 1.5)}
    target = pd.DataFrame({"big_event_count": [1.0, 0.0], "weekday": [5.0, 5.0]})

    out = evidence(hurdle, target, np.array([False, True]))

    assert out[0].startswith("근처 큰 행사(1건) +")  # 값도 싣는다
    assert "근처 큰 행사" not in out[1]  # 행사 0건인 날은 '행사' 를 근거로 내세우지 않는다
    assert "요일" not in out[0]  # 2% 미만 기여는 근거로 쓰지 않는다
