import json
from datetime import date

import pandas as pd

from weglee_ai.seoulevent import weekly


def test_snapshot_copies_existing_schedule_files(tmp_path):
    (tmp_path / "stadium").mkdir()
    (tmp_path / "stadium" / "events.parquet").write_bytes(b"x")
    assert weekly.snapshot(tmp_path, date(2026, 11, 9)) == ["stadium"]
    assert (tmp_path / "_snapshots" / "2026-11-09" / "stadium.parquet").read_bytes() == b"x"


def test_snapshot_covers_every_collector_with_a_file():
    # 수집기마다 스냅샷 파일이 있어야 '그날 알던 일정' 을 복원하고 실패도 manifest 에 남는다 (문화행사는 날짜 폴더라 따로)
    assert {n for n, _ in weekly.COLLECTORS} - set(weekly.SNAPSHOT_FILES) == {"cultural_events", "bus"}  # 둘은 snapshot 안에서 따로 (날짜 폴더 · 최근 시트)


def test_snapshot_includes_latest_cultural_events(tmp_path):
    (tmp_path / "cultural_events").mkdir()
    (tmp_path / "cultural_events" / "2026-11-02.parquet").write_bytes(b"old")
    (tmp_path / "cultural_events" / "2026-11-09.parquet").write_bytes(b"new")
    assert weekly.snapshot(tmp_path, date(2026, 11, 9)) == ["cultural_events"]
    assert (tmp_path / "_snapshots" / "2026-11-09" / "cultural_events.parquet").read_bytes() == b"new"


def test_snapshot_writes_manifest_with_failed_sources(tmp_path):
    (tmp_path / "kbo").mkdir()
    pd.DataFrame({"a": [1, 2]}).to_parquet(tmp_path / "kbo" / "games.parquet")
    (tmp_path / "stadium").mkdir()
    pd.DataFrame({"a": [1]}).to_parquet(tmp_path / "stadium" / "events.parquet")
    copied = weekly.snapshot(tmp_path, date(2026, 11, 9), failed=("stadium",))
    m = json.loads((tmp_path / "_snapshots" / "2026-11-09" / "_manifest.json").read_text(encoding="utf-8"))
    assert set(copied) == {"kbo", "stadium"}
    assert m["sources"]["kbo"] == {"collected": True, "rows": 2}
    assert m["sources"]["stadium"] == {"collected": False, "rows": 1}   # 실패 → 지난 수집본
    assert m["snapshot_at"].startswith(str(date.today()))
