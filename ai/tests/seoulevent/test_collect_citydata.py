import csv
import json
from datetime import datetime

import pandas as pd
import pytest

from weglee_ai.seoulevent import collect_citydata, places

NOW = datetime(2026, 11, 2, 14, 0, 5)
PLACES = [("POI001", "강남 MICE 관광특구"), ("POI002", "동대문 관광특구"), ("POI009", "광화문·덕수궁")]


def _fetch(fail: set[str] = frozenset()):
    def fetch(area_cd: str) -> dict:
        if area_cd in fail:
            raise RuntimeError("ERROR-500 key=SECRET123")
        return {"RESULT": {"RESULT.CODE": "INFO-000"}, "AREA_CD": area_cd, "LIVE_CMRCL_STTS": {"AREA_SH_PAYMENT_CNT": 10}}

    return fetch


def _lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def test_partial_failure_saves_successes_and_reports_failed(tmp_path):
    result = collect_citydata.run(PLACES, NOW, _fetch({"POI002"}), tmp_path, pause=0)
    assert result.ok == 2
    assert result.failed == ["POI002"]
    rows = _lines(tmp_path / "2026-11-02" / "1400.jsonl")
    assert [r["area_cd"] for r in rows] == ["POI001", "POI009"]
    assert rows[0]["area_nm"] == "강남 MICE 관광특구"
    assert rows[0]["fetched_at"] == "2026-11-02T14:00:05"
    assert rows[0]["body"]["LIVE_CMRCL_STTS"]["AREA_SH_PAYMENT_CNT"] == 10


def test_log_row_appended_each_run(tmp_path):
    collect_citydata.run(PLACES, NOW, _fetch({"POI002"}), tmp_path, pause=0)
    collect_citydata.run(PLACES, NOW.replace(hour=15), _fetch(), tmp_path, pause=0)
    with open(tmp_path / "_log.csv", encoding="utf-8") as f:
        log = list(csv.DictReader(f))
    assert [(r["ok"], r["failed_count"], r["failed_places"]) for r in log] == [("2", "1", "POI002"), ("3", "0", "")]
    assert log[0]["fetched_at"] == "2026-11-02T14:00:05"


def test_same_slot_twice_does_not_overwrite(tmp_path):
    collect_citydata.run(PLACES, NOW, _fetch(), tmp_path, pause=0)
    collect_citydata.run(PLACES, NOW, _fetch({"POI001"}), tmp_path, pause=0)
    day = tmp_path / "2026-11-02"
    assert len(_lines(day / "1400.jsonl")) == 3
    assert len(_lines(day / "1400_2.jsonl")) == 2


def test_all_failed_writes_log_but_no_snapshot(tmp_path):
    result = collect_citydata.run(PLACES, NOW, _fetch({p for p, _ in PLACES}), tmp_path, pause=0)
    assert result.ok == 0
    assert not (tmp_path / "2026-11-02").exists()
    assert (tmp_path / "_log.csv").exists()


def test_key_not_in_files(tmp_path):
    collect_citydata.run(PLACES, NOW, _fetch({"POI002"}), tmp_path, pause=0)
    for f in tmp_path.rglob("*"):
        if f.is_file():
            assert "SECRET123" not in f.read_text(encoding="utf-8")


def test_places_load_reads_code_and_name(tmp_path):
    path = tmp_path / "places.xlsx"
    pd.DataFrame(
        {"CATEGORY": ["관광특구"], "NO": [1], "AREA_CD": ["POI001"], "AREA_NM": ["강남 MICE 관광특구"], "ENG_NM": ["x"]}
    ).to_excel(path, index=False)
    assert places.load(path) == [("POI001", "강남 MICE 관광특구")]


def test_places_load_missing_column_fails_loudly(tmp_path):
    path = tmp_path / "places.xlsx"
    pd.DataFrame({"이름": ["x"]}).to_excel(path, index=False)
    with pytest.raises(KeyError):
        places.load(path)


# ── 리뷰 수정 ─────────────────────────────────────────────────

from weglee_ai.seoulevent.seoul_api import SeoulApiError


def _log_rows(out_dir):
    with open(out_dir / "_log.csv", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def test_saved_incrementally_and_logged_even_if_killed(tmp_path):
    def fetch(area_cd):
        if area_cd == "POI009":
            raise KeyboardInterrupt  # 작업 스케줄러가 프로세스를 끊는 상황
        return {"AREA_CD": area_cd}

    with pytest.raises(KeyboardInterrupt):
        collect_citydata.run(PLACES, NOW, fetch, tmp_path, pause=0)
    assert len(_lines(tmp_path / "2026-11-02" / "1400.jsonl")) == 2
    assert _log_rows(tmp_path)[0]["ok"] == "2"


def test_deadline_stops_and_marks_rest_failed(tmp_path):
    clock = iter([0, 10, 999, 999, 999])
    result = collect_citydata.run(PLACES, NOW, _fetch(), tmp_path, pause=0, deadline_s=100, clock=lambda: next(clock))
    assert result.ok == 1
    assert result.failed == ["POI002", "POI009"]
    assert "POI002:DEADLINE" in _log_rows(tmp_path)[0]["errors"]


def test_failure_reason_logged_without_key(tmp_path):
    def fetch(area_cd):
        if area_cd == "POI001":
            raise SeoulApiError("INFO-100", "인증키 오류")
        if area_cd == "POI002":
            raise RuntimeError("boom key=SECRET123")
        return {"AREA_CD": area_cd}

    collect_citydata.run(PLACES, NOW, fetch, tmp_path, pause=0)
    errors = _log_rows(tmp_path)[0]["errors"]
    assert "POI001:INFO-100" in errors
    assert "POI002:RuntimeError" in errors
    assert "SECRET123" not in errors


def test_unknown_response_body_saved_but_counted_failed(tmp_path):
    def fetch(area_cd):
        raise SeoulApiError("UNKNOWN", "RESULT 없음", body={"AREA_CD": area_cd, "X": 1})

    result = collect_citydata.run(PLACES[:1], NOW, fetch, tmp_path, pause=0)
    assert result.failed == ["POI001"]
    row = _lines(tmp_path / "2026-11-02" / "1400.jsonl")[0]
    assert row["body"] == {"AREA_CD": "POI001", "X": 1}
    assert row["result_unknown"] is True


def test_main_without_key_leaves_log_row(tmp_path, monkeypatch):
    from weglee_ai.seoulevent import config

    monkeypatch.setattr(config, "load", lambda: config.Config(None, None, tmp_path))
    assert collect_citydata.main() == 2
    row = _log_rows(tmp_path / "raw" / "citydata_cmrcl")[0]
    assert row["ok"] == "0" and row["errors"] == "startup:NO_KEY"


def test_main_startup_exception_leaves_log_row(tmp_path, monkeypatch):
    from weglee_ai.seoulevent import config, places

    monkeypatch.setattr(config, "load", lambda: config.Config("K", None, tmp_path))
    monkeypatch.setattr(places, "ensure", lambda d: (_ for _ in ()).throw(OSError("disk")))
    assert collect_citydata.main() == 1
    assert _log_rows(tmp_path / "raw" / "citydata_cmrcl")[0]["errors"] == "startup:OSError"
