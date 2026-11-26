from datetime import date

import pandas as pd

from weglee_ai.seoulevent import holidays_kr


def test_library_fallback_has_lunar_new_year_with_substitute(tmp_path):
    h = holidays_kr.load(tmp_path, range(2024, 2025))
    feb = set(h.loc[h["date"].between(date(2024, 2, 1), date(2024, 2, 29)), "date"])
    assert {date(2024, 2, 9), date(2024, 2, 10), date(2024, 2, 11), date(2024, 2, 12)} <= feb
    assert (h["source"] == "holidays_lib").all() and h["is_holiday"].all()


def test_datagokr_file_preferred(tmp_path):
    pd.DataFrame({"date": [date(2026, 10, 3)], "name": ["개천절"], "is_holiday": [True]}).to_parquet(tmp_path / "holidays.parquet")
    h = holidays_kr.load(tmp_path, range(2026, 2027))
    assert h.to_dict("records") == [{"date": date(2026, 10, 3), "name": "개천절", "is_holiday": True, "source": "datagokr"}]
