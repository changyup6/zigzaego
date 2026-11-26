from datetime import date

import pandas as pd

from weglee_ai.seoulevent.build.spikes import detect_spikes

AREAS = pd.DataFrame({"TRDAR_CD": ["A", "B", "C"], "TRDAR_CD_NM": ["가", "나", "다"]})


def _day(rows):
    return pd.DataFrame(rows, columns=["trdar_cd", "date", "riders", "riders_base", "ratio"])


def test_spikes_threshold_min_base_and_same_day_count():
    d = date(2024, 10, 5)
    day = _day([("A", d, 30000, 10000, 3.0), ("B", d, 22000, 10000, 2.2), ("C", d, 2400, 800, 3.0),
                ("A", date(2024, 10, 6), 15000, 10000, 1.5)])
    out = detect_spikes(day, AREAS)
    assert out["trdar_cd"].tolist() == ["A", "B"]  # C 는 기준 승하차가 작아 제외
    assert out["n_districts_same_day"].tolist() == [2, 2]
    assert out.iloc[0]["trdar_nm"] == "가" and out.iloc[0]["weekday"] == "토"
