from datetime import date

from weglee_ai.seoulevent import collect_stadium as cs

PAGE = """<td class="sun" onclick="thisSchedule('2025','08','03','worldcupst');"  onmouseleave="hideThisSch(this);">
3<p><a href="#none" title="2025 쿠팡플레이시리즈대 진 : 토트넘vs 뉴캐슬">2025 쿠팡...</a></p></td>
<td onclick="thisSchedule('2025','08','04','worldcupst');">4<p></p></td>
<td onclick="thisSchedule('2025','08','08','worldcupst');">8<p><a href="#none" title="[2025 K리그1] &amp; 서울 vs 대구">x</a></p></td>"""


def test_parse_month_reads_titles_per_day():
    rows = cs.parse_month(PAGE)
    assert [r["date"] for r in rows] == [date(2025, 8, 3), date(2025, 8, 8)]
    assert rows[1]["text"] == "[2025 K리그1] & 서울 vs 대구"


def test_classify():
    assert cs.classify("[2025 K리그1] 서울 vs 대구") is None
    assert cs.classify("[2025 WK리그] 서울시청 vs 창녕WFC") is None
    assert cs.classify("경 기 명 : 남자축구 국가대표팀 친선경기 브라질전") == "국가대표"
    assert cs.classify("[2025 FC서울 친선경기] 서울 vs FC바르셀로나") == "친선 · 이벤트 경기"
    assert cs.classify("경 기 명 : FC서울 ACLE 1차전") == "클럽 대회"
    assert cs.classify("경 기 명 : 2025 코리아컵 결승 경기문의 : 대한축구협회") == "클럽 대회"
    assert cs.classify("[서울월드컵경기장] 임영웅 콘서트") == "콘서트"
    assert cs.classify("[서울월드컵경기장 공공행사] 서울 스프링 페스타 개막식") == "기타 행사"


def test_merge_keeps_old_rows_before_since():
    import pandas as pd
    old = pd.DataFrame({"date": [date(2019, 5, 1), date(2025, 6, 1)], "text": ["a", "old"], "collected_at": ["2026-11-03"] * 2})
    new = pd.DataFrame({"date": [date(2025, 6, 1)], "text": ["new"], "collected_at": ["2026-12-01"]})
    out = cs.merge(old, new, date(2025, 1, 1))
    assert out["text"].tolist() == ["a", "new"] and out["collected_at"].tolist() == ["2026-11-03", "2026-12-01"]


def test_skydome_baseball_categories():
    page = PAGE.replace("worldcupst", "skydome")
    assert [r["site"] for r in cs.parse_month(page, "skydome")] == ["skydome", "skydome"]
    assert cs.parse_month(page, "worldcupst") == []
    assert cs.classify("[2025 KBO 프로야구]대 진:롯데vs 키움") == "프로야구"
    assert cs.classify("[2024 KBO 포스트시즌] 플레이오프 1차전") == "프로야구 포스트시즌"
    assert cs.classify("  ") is None


def test_coupang_play_team_kleague_is_event_match_not_kleague():
    assert cs.classify("(1차전) 7. 27. 오후 8:00 대진 : 팀 K리그 vs AT마드리드") == "친선 · 이벤트 경기"
    assert cs.classify("[쿠팡플레이시리즈] 7/31(수) 20:00 팀K리그 vs 토트넘") == "친선 · 이벤트 경기"
    assert cs.classify("[2025 K리그1] 서울 vs 대구") is None                      # 정규 K리그는 계속 제외
    assert cs.classify("(1차전) 대진 : 팀 K리그 vs AT마드리드", legacy=True) is None  # 비교용 이전 규칙
