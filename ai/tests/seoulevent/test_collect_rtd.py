import csv
from datetime import date

import httpx
import pandas as pd
import pytest

from weglee_ai.seoulevent import collect_rtd

PLACES = [("POI001", "강남 MICE 관광특구"), ("POI072", "여의도")]


def _body(start_day: int, n: int = 3) -> dict:
    return {"lowestConsumption": "x", "card28DayList": [
        {"apvTsD": f"202610{start_day + i:02d}", "dailyAmtSum": 1000 * (i + 1), "dailyCntSum": 10 * (i + 1)} for i in range(n)]}


def test_parse_rows():
    rows = collect_rtd.parse(_body(6, 2))
    assert rows == [{"date": date(2026, 10, 6), "amt": 1000, "cnt": 10}, {"date": date(2026, 10, 7), "amt": 2000, "cnt": 20}]


def test_run_saves_raw_and_upserts_latest(tmp_path):
    collect_rtd.run(PLACES, lambda nm: _body(6), tmp_path, today=date(2026, 11, 1), pause=0)
    # 다음 수집: 10/8 값이 정정되어 오고 10/9 가 새로 붙는다
    collect_rtd.run(PLACES, lambda nm: {"card28DayList": [{"apvTsD": "20261008", "dailyAmtSum": 9999, "dailyCntSum": 1},
                                                          {"apvTsD": "20261009", "dailyAmtSum": 4000, "dailyCntSum": 40}]},
                    tmp_path, today=date(2026, 11, 2), pause=0)
    df = pd.read_parquet(tmp_path / "rtd_daily.parquet")
    yeouido = df[df["area_cd"] == "POI072"].set_index("date")
    assert list(yeouido.index) == [date(2026, 10, d) for d in (6, 7, 8, 9)]
    assert yeouido.loc[date(2026, 10, 8), "amt"] == 9999
    assert yeouido.loc[date(2026, 10, 8), "collected_at"] == "2026-11-02"
    assert (tmp_path / "raw" / "2026-11-01" / "POI072.json").exists()


def test_partial_failure_logged_with_reason(tmp_path):
    def fetch(nm):
        if nm == "여의도":
            raise collect_rtd.SessionExpired()
        return _body(6)

    result = collect_rtd.run(PLACES, fetch, tmp_path, today=date(2026, 11, 1), pause=0)
    assert result.ok == 1 and result.failed == ["POI072"]
    with open(tmp_path / "_log.csv", encoding="utf-8") as f:
        log = list(csv.DictReader(f))
    assert log[0]["errors"] == "POI072:SessionExpired"


def test_gap_between_collections_detected(tmp_path):
    collect_rtd.run(PLACES, lambda nm: {"card28DayList": [{"apvTsD": "20260901", "dailyAmtSum": 1, "dailyCntSum": 1}]},
                    tmp_path, today=date(2026, 9, 2), pause=0)
    result = collect_rtd.run(PLACES, lambda nm: _body(6), tmp_path, today=date(2026, 11, 1), pause=0)
    assert result.gap_days == 34  # 9/2 ~ 10/5 가 빈다
    with open(tmp_path / "_log.csv", encoding="utf-8") as f:
        assert list(csv.DictReader(f))[-1]["gap_days"] == "34"


def test_session_fetch_reopens_once_after_expiry():
    opened, calls = [], []

    def fetch(client, nm):
        calls.append((client, nm))
        if client == 0:  # 첫 세션은 만료
            raise collect_rtd.SessionExpired()
        return {"ok": nm}

    get = collect_rtd.session_fetch(open_=lambda: opened.append(1) or len(opened) - 1, fetch=fetch)
    assert get("여의도") == {"ok": "여의도"} and get("강남") == {"ok": "강남"}
    assert len(opened) == 2 and calls == [(0, "여의도"), (1, "여의도"), (1, "강남")]  # 다시 연 세션을 계속 쓴다


def test_session_fetch_gives_up_after_second_expiry():
    def fetch(client, nm):
        raise collect_rtd.SessionExpired()

    with pytest.raises(collect_rtd.SessionExpired):
        collect_rtd.session_fetch(open_=lambda: object(), fetch=fetch)("여의도")


def test_fetch_raises_on_redirect_to_error_page():
    client = httpx.Client(transport=httpx.MockTransport(
        lambda r: httpx.Response(302, headers={"Location": "https://data.seoul.go.kr/SeoulRtd/error"})))
    with pytest.raises(collect_rtd.SessionExpired):
        collect_rtd.fetch_28days(client, "여의도")
