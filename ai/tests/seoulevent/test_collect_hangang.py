from weglee_ai.seoulevent import collect_hangang as ch

PAGE = """<li class="event-ind null">본부행사</li></ul></div>
<a href="javascript:goDetail('339')" class="event-tit" title="해질녘 가을 음악회 &amp; 2025"><strong>x</strong></a>
<dl><dt>기간</dt><dd>2025-10-18(토) ~ 2025-10-19(일)</dd></dl><dl><dt>시간</dt><dd></dd></dl>
<dl><dt>장소</dt><dd>반포한강공원 예빛무대</dd></dl>
<a href="javascript:void(0)" data-text="x" data-lot="126.99" data-lat="37.51" class="event-location">길찾기</a>
<li class="event-ind null">민간행사</li></ul></div>
<a href="javascript:goDetail('12')" class="event-tit" title="하루 행사"><strong>y</strong></a>
<dl><dt>기간</dt><dd>2024-05-04(토) ~ </dd></dl>
<dl><dt>장소</dt><dd>여의도 마포대교 남단</dd></dl>
<a href="javascript:void(0)" data-text="y" data-lot="126.93" data-lat="37.53" class="event-location">길찾기</a>"""


def test_parse_page_reads_period_place_and_coords():
    a, b = ch.parse_page(PAGE)
    assert (a["evnt_sn"], a["title"], a["start"], a["end"], a["park"], a["lat"]) == (339, "해질녘 가을 음악회 & 2025", "2025-10-18", "2025-10-19", "반포한강공원", 37.51)
    assert (b["end"], b["park"]) == ("2024-05-04", "여의도 마포대교 남단")   # 종료일 없으면 시작일, 공원 이름 없으면 장소 그대로


def test_session_dates_from_long_event_detail():
    text = "1회차 공연 : 4. 27.(일) / 뚝섬 2회차 공연 : 5.5.(월) 3회차 : 5. 11.(일) 접수 5.12.(금) 문의 02-6953-7027 2025. 11. 15.(토)"
    assert ch.session_dates(text, "2025-04-27", "2025-06-15") == ["2025-04-27", "2025-05-05", "2025-05-11"]
    # 5.12.(금) 은 요일이 틀려(월요일) 빠지고, 11.15 는 기간 밖이라 빠진다


def test_session_places_reads_park_after_each_date():
    # 2025 하반기 드론쇼: 대표 위치는 여의도지만 1 ~ 3회차는 뚝섬 (급증도 뚝섬 · 자양에서) — 회차마다 장소를 읽는다
    text = ("1회차 공연 : 9. 7.(일) / 뚝섬한강공원 2회차 공연 : 9.14.(일) / 뚝섬한강공원 3회차 : 9. 20.(토) 장소 미정 "
            "특별 공연 : 11.15(토) / 여의도 한강공원")
    got = ch.session_places(text, "2025-09-07", "2025-11-15", ["뚝섬", "여의도", "잠실"])
    assert got == {"2025-09-07": "뚝섬한강공원", "2025-09-14": "뚝섬한강공원", "2025-09-20": None, "2025-11-15": "여의도한강공원"}
