from datetime import date

from weglee_ai.seoulevent import collect_kbo as kbo


def _row(away, home, loc, etc, day=None, kind=None, time="18:30"):
    head = (f'<td title="DATE" class="bd_r data_top" rowspan="2">{day}</td>' if day else "") + \
           (f'<td title="TYPE" class="bd_r data_top" rowspan="2">{kind}</td>' if kind else "")
    return (f'<tr>{head}<td class="TIME">{time}</td><td title="GAME" class="loop_r">{away}</td>'
            f'<td title="GAME"><span class="score_schedule">3:1</span></td><td title="GAME" class="loop_l">{home}</td>'
            f'<td class="TV">SPO-T<br /></td><td class="RADIO"></td><td class="LOCATION">{loc}</td><td class="ETC">{etc}</td></tr>')


def _page(*rows, ym="2024.03"):
    return f'<div class="date"> {ym} </div><table><tbody>{"".join(rows)}</tbody></table>'


def test_parse_page_carries_day_and_type_and_maps_names():
    html = _page(_row("HANWHA", "LG", "JAMSIL", "-", day="03.23(SAT)", kind="REGULAR", time="14:00"),
                 _row("LG", "SSG", "MUNHAK", "POSTPONED"))
    out = kbo.parse_page(html)
    assert out[0] == {"date": date(2024, 3, 23), "time": "14:00", "away": "한화", "home": "LG", "stadium": "잠실", "series": "정규", "note": "-"}
    # 날짜 · 종류는 rowspan 으로 앞 줄 값, 연기(POSTPONED)는 '취소' 로 — events 가 열리지 않은 경기를 거른다
    assert (out[1]["date"], out[1]["series"], out[1]["stadium"], out[1]["note"]) == (date(2024, 3, 23), "정규", "MUNHAK", "취소 (POSTPONED)")


def test_parse_page_series_and_skips_allstar():
    html = _page(_row("KT", "DOOSAN", "JAMSIL", "-", day="10.02(WED)", kind="Wild Card"),
                 _row("Dream", "Nanum", "JAMSIL", "-", day="07.13(SAT)", kind="Allstar game"),
                 _row("KIA", "LG", "JAMSIL", "-", day="03.09(SAT)", kind="EXHIBITION"), ym="2024.10")
    out = kbo.parse_page(html)
    assert [(r["series"], r["home"]) for r in out] == [("포스트시즌", "두산"), ("시범경기", "LG")]


def test_late_known_marks_postseason_and_makeup_games():
    import pandas as pd
    g = pd.DataFrame({"date": [date(2024, 4, 2), date(2024, 4, 3), date(2024, 9, 20), date(2024, 5, 1), date(2024, 10, 9)],
                      "away": ["한화", "한화", "한화", "KIA", "KT"], "home": ["LG"] * 5,
                      "series": ["정규", "정규", "정규", "정규", "포스트시즌"], "note": ["우천취소", "-", "-", "-", "준PO 1차전"]})
    # 한화-LG 4/2 취소 → 그 뒤 마지막 경기(9/20)가 재편성 · KIA 전은 취소 없음 · 포스트시즌은 늘 늦게 확정
    assert kbo.late_known(g).tolist() == [False, False, True, False, True]
