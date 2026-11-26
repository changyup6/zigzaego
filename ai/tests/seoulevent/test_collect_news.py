import pandas as pd
import pytest

from weglee_ai.seoulevent import collect_news as cn


def test_parse_raises_on_error_payload():
    with pytest.raises(RuntimeError):
        cn.parse({"error": {"errorCode": "300", "message": "Not Found Exception"}})
    assert cn.parse({"items": [{"title": "a"}], "total": 1}) == [{"title": "a"}]


def test_to_rows_converts_pubdate_to_iso():
    rows = cn.to_rows([{"title": "t", "description": "d", "link": "L", "originallink": "O", "pubDate": "Mon, 09 Nov 2026 13:46:38 +0900"}], "q", "2026-11-09")
    assert rows[0]["pub_date"] == "2026-11-09T13:46:38+09:00" and rows[0]["query"] == "q" and rows[0]["collected_at"] == "2026-11-09"


def test_merge_keeps_first_seen_per_query_and_link():
    old = pd.DataFrame([{"query": "q", "link": "L", "collected_at": "2026-11-02"}])
    new = pd.DataFrame([{"query": "q", "link": "L", "collected_at": "2026-11-09"}, {"query": "q2", "link": "L", "collected_at": "2026-11-09"}])
    m = cn.merge(old, new)
    assert len(m) == 2 and m.loc[m["query"] == "q", "collected_at"].item() == "2026-11-02"
