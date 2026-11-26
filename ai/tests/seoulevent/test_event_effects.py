from datetime import date

import pandas as pd
import pytest

from weglee_ai.seoulevent.model.event_effects import effect_table, event_days


def _events():
    return pd.DataFrame([
        {"event_id": "a", "source": "culture", "kind": "콘서트", "place": "체조경기장", "start_date": date(2025, 5, 3), "end_date": date(2025, 5, 4), "trdar_cd": "A"},
        {"event_id": "b", "source": "culture", "kind": "콘서트", "place": "구민회관", "start_date": date(2025, 5, 3), "end_date": date(2025, 5, 3), "trdar_cd": "B"},
        {"event_id": "c", "source": "culture", "kind": "전시", "place": "미술관", "start_date": date(2025, 1, 1), "end_date": date(2025, 3, 1), "trdar_cd": "A"},  # 7일 넘음
        {"event_id": "h", "source": "holiday", "kind": "holiday", "place": None, "start_date": date(2025, 5, 5), "end_date": date(2025, 5, 5), "trdar_cd": None},
        {"event_id": "z", "source": "culture", "kind": "콘서트", "place": "체조경기장", "start_date": date(2025, 6, 1), "end_date": date(2025, 6, 1), "trdar_cd": "A"},
    ])


DAY = pd.DataFrame({"trdar_cd": ["A", "A", "B", "A"], "date": [date(2025, 5, 3), date(2025, 5, 4), date(2025, 5, 3), date(2025, 6, 1)],
                    "ratio": [3.0, 2.0, 1.0, 1.2]})


def test_event_days_short_events_only_and_before_cutoff():
    ed = event_days(_events(), DAY, until=date(2025, 6, 1))
    assert sorted(ed["event_id"].unique()) == ["a", "b"]  # 긴 전시 · 공휴일 · 기준일 이후(z) 제외
    assert ed.set_index(["event_id", "date"]).loc[("a", date(2025, 5, 4)), "ratio"] == 2.0


def test_effect_table_by_kind_and_place():
    ed = event_days(_events(), DAY)
    by_kind = effect_table(ed, "kind").set_index("kind")
    assert by_kind.loc["콘서트", "n_events"] == 3 and by_kind.loc["콘서트", "n_days"] == 4
    assert by_kind.loc["콘서트", "ratio_median"] == pytest.approx(1.6)  # 3.0 · 2.0 · 1.0 · 1.2
    assert by_kind.loc["콘서트", "share_2x"] == pytest.approx(0.5)
    by_place = effect_table(ed, "place", min_days=2).set_index("place")
    assert list(by_place.index) == ["체조경기장"] and by_place.loc["체조경기장", "ratio_median"] == pytest.approx(2.0)
