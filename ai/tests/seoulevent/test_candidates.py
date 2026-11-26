from datetime import date, timedelta

import pandas as pd
import pytest

from weglee_ai.seoulevent.build.candidates import rank_candidates

AS_OF = date(2026, 11, 2)
AREAS = pd.DataFrame({"TRDAR_CD": ["A", "B", "C"], "TRDAR_CD_NM": ["가", "나", "다"]})


def _ev(trdar, kind, days_ago, n=1):
    d = AS_OF - timedelta(days=days_ago)
    return [{"event_id": f"{trdar}{kind}{days_ago}{i}", "source": "culture", "kind": kind, "name": "x",
             "start_date": d, "end_date": d, "trdar_cd": trdar} for i in range(n)]


def _day(rows):
    return pd.DataFrame(rows, columns=["trdar_cd", "date", "ratio", "event_count", "short_event_count"])


def test_window_kinds_subway_order_and_ratio():
    events = pd.DataFrame(_ev("A", "축제", 10, 2) + _ev("A", "sports", 20) + _ev("A", "축제", 366)
                          + _ev("B", "축제", 5, 5) + _ev("C", "축제", 5, 2))
    day = _day([("A", AS_OF - timedelta(days=10), 1.4, 2, 2), ("A", AS_OF - timedelta(days=20), 1.2, 1, 1),
                ("A", AS_OF - timedelta(days=1), 0.9, 1, 0), ("C", AS_OF, 1.0, 0, 0)])
    out = rank_candidates(events, day, AREAS, AS_OF)
    assert out["trdar_cd"].tolist() == ["A", "C", "B"]  # B 는 이벤트 최다지만 지하철 없음 → 뒤로
    a = out.set_index("trdar_cd").loc["A"]
    assert a["events_last_365d"] == 3
    assert a["kinds"] == "축제:2, sports:1"
    assert a["median_event_ratio"] == pytest.approx(1.3)
    assert a["trdar_nm"] == "가"
    assert not out.set_index("trdar_cd").loc["B", "has_subway"]


def test_citydata_flag_and_top_n():
    events = pd.DataFrame(_ev("A", "축제", 1) + _ev("B", "축제", 1))
    out = rank_candidates(events, _day([]), AREAS, AS_OF, top_n=1, citydata_trdars={"A"})
    assert len(out) == 1 and bool(out.iloc[0]["has_citydata"])


def test_spike_days_rank_districts_without_events():
    spikes = pd.DataFrame({"trdar_cd": ["C", "C", "A", "C"], "date": [AS_OF - timedelta(days=d) for d in (1, 2, 3, 400)]})
    day = _day([("A", AS_OF, 1.0, 0, 0), ("C", AS_OF, 1.0, 0, 0)])
    out = rank_candidates(pd.DataFrame(columns=["source", "trdar_cd", "start_date", "kind"]), day, AREAS, AS_OF, spikes=spikes)
    assert out["trdar_cd"].tolist() == ["C", "A"]
    assert out["spike_days_365d"].tolist() == [2, 1]
