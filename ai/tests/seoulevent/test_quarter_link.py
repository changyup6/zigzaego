from datetime import date

import numpy as np
import pandas as pd

from weglee_ai.seoulevent.model.quarter_link import link_quarter, summary


def _quarter(rows):
    return pd.DataFrame(rows, columns=["trdar_cd", "quarter", "sales_amt", "yoy_pct"])


def test_quarter_boundary_and_previous_year_diff():
    q = _quarter([("A", "2023Q1", 100, np.nan), ("A", "2024Q1", 120, 20.0), ("A", "2024Q2", 90, np.nan)])
    spikes = pd.DataFrame({"trdar_cd": ["A", "A", "A", "A"],
                           "date": [date(2024, 3, 31), date(2024, 4, 1), date(2024, 3, 1), date(2023, 2, 1)]})
    out = link_quarter(q, spikes).set_index("quarter")
    assert out.loc["2024Q1", "spike_days"] == 2 and out.loc["2024Q2", "spike_days"] == 1
    assert out.loc["2024Q1", "spike_days_diff"] == 1  # 2023Q1 은 1일
    assert np.isnan(out.loc["2024Q2", "spike_days_diff"])  # 2023Q2 행 없음


def test_summary_compares_quarters_with_more_spikes():
    linked = pd.DataFrame({"spike_days_diff": [2, 1, 0, -1, np.nan], "yoy_pct": [30.0, 10.0, 0.0, -5.0, 99.0]})
    s = summary(linked)
    assert s["n"] == 4
    assert s["spearman"] == 1.0
    assert s["median_yoy_more_spikes"] == 20.0 and s["median_yoy_other"] == -2.5
