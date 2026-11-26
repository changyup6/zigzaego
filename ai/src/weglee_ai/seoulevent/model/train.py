"""226 모델 학습 · 백테스트 · 근거 문장."""

from datetime import date

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

from weglee_ai.seoulevent.model.features import FEATURES

SPIKE = 2.0
CITY_WIDE = 5  # 작년 같은 시기 서울에서 이만큼 이상 급증했으면 도시 규모 행사로 본다


def split(feat: pd.DataFrame, test_start: date) -> tuple[pd.DataFrame, pd.DataFrame]:
    """시간 순 분할. 기준선이 없어 목표가 빈 행은 뺀다."""
    f = feat[feat["y"].notna()]
    return f[f["date"] < test_start], f[f["date"] >= test_start]


def fit(train: pd.DataFrame) -> HistGradientBoostingRegressor:
    # 원천이 아직 없어 통째로 빈 특징(키 발급 전 날씨 등)은 빼고 학습한다 — 전부 NaN 이면 구간을 못 나눠 오류
    features = [c for c in FEATURES if train[c].notna().any()]
    model = HistGradientBoostingRegressor(max_iter=300, learning_rate=0.1, min_samples_leaf=20, random_state=0)
    model.fit(train[features], train["y"])
    model.used_features_ = features
    return model


def predict_ratio(model: HistGradientBoostingRegressor, df: pd.DataFrame) -> np.ndarray:
    return np.exp(model.predict(df[model.used_features_]))


def evaluate(model: HistGradientBoostingRegressor, test: pd.DataFrame) -> pd.DataFrame:
    actual = test["ratio"].to_numpy()
    preds = {
        "배수1(요일 기준선)": np.ones(len(test)),
        "작년 같은 요일": test["ly_ratio"].fillna(1.0).to_numpy(),
        "모델": predict_ratio(model, test),
    }
    masks = {"all": np.ones(len(test), bool), "spike": actual >= SPIKE, "holiday": test["is_holiday"].to_numpy() == 1}
    rows = {}
    for name, p in preds.items():
        err = np.abs(p - actual)
        rows[name] = {f"mae_{k}": (err[m].mean() if m.any() else np.nan) for k, m in masks.items()}
        rows[name] |= {f"n_{k}": int(m.sum()) for k, m in masks.items()}
    return pd.DataFrame.from_dict(rows, orient="index")


def explain(row: pd.Series) -> str:
    """예측의 근거가 된 특징을 문장으로. 원인을 단정하지 않는다."""
    parts = []
    if row["is_holiday"] == 1:
        parts.append("공휴일")
    elif row["holiday_before"] == 1 or row["holiday_after"] == 1:
        parts.append("연휴 전후")
    if row["ly_spike"] == 1:
        parts.append(f"작년 같은 시기 이 상권 급증(최대 {row['ly_max_ratio']:.1f}배)")
    if pd.notna(row["ly_city_spikes"]) and row["ly_city_spikes"] >= CITY_WIDE:
        parts.append(f"작년 같은 시기 서울 {int(row['ly_city_spikes'])}개 상권 급증")
    return ", ".join(parts) or "평소와 같음"
