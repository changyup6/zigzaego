from datetime import date

from weglee_ai.seoulevent import collect_marathon as cm

# view.php?no=10000 축약 (2026-11-09 받은 화면)
PAGE = """
<tr><td width="55" height="25" bgcolor="#86B7DF"><p align="center">대회명</p>
    </td>
    <td width="430" height="25" bgcolor="white">
        <p><B>2024 디랙스 인도어 마라톤</B></p>
    </td></tr>
<tr><td width="55" height="25" bgcolor="#86B7DF">
        <p align="center">대회일시</p>
    </td>
    <td width="430" height="25" bgcolor="white">
        <p>2024년3월23일 출발시간:홈페이지 참조			</p>
    </td></tr>
<tr><td width="55" height="25" bgcolor="#86B7DF">
        <p align="center">대회종목</p>
    </td>
    <td width="430" height="25" bgcolor="white">하프,10km,5km,팀전 21km</td></tr>
<tr><td width="55" height="25" bgcolor="#86B7DF">
        <p align="center">대회지역</p>
    </td>
    <td width="430" height="25" bgcolor="white">경기</td></tr>
<tr><td width="55" height="25" bgcolor="#86B7DF">
        <p align="center">대회장소</p>
    </td>
    <td width="430" height="25" bgcolor="white">
        <p>광명 IVEX 하이퍼홀</p>
    </td></tr>
<tr><td width="55" height="25" bgcolor="#86B7DF">
        <p align="center">주최단체</p>
    </td>
    <td width="430" height="25" bgcolor="white">
        <p>주식회사 디랙스</p>
    </td></tr>
<script>
	var marker = new naver.maps.Marker({
	position: new naver.maps.LatLng(37.4186807, 126.8848208),
	map: map
	});
</script>
"""
EMPTY = PAGE.replace("2024년3월23일 출발시간:홈페이지 참조", "")


def test_parse_page_reads_fields():
    assert cm.parse_page(PAGE) == {"name": "2024 디랙스 인도어 마라톤", "date": date(2024, 3, 23), "region": "경기",
                                   "place": "광명 IVEX 하이퍼홀", "organizer": "주식회사 디랙스", "events": "하프,10km,5km,팀전 21km",
                                   "lat": 37.4186807, "lon": 126.8848208}


def test_parse_page_empty_number_is_none():
    assert cm.parse_page(EMPTY) is None


def test_is_seoul():
    assert cm.is_seoul("서울") and cm.is_seoul(" 서울 ")
    assert not cm.is_seoul("경기") and not cm.is_seoul(None)
    # 지역 칸이 '지역' 이면 장소로 (실제: 제17회 서울신문하프마라톤 · 천사랑마라톤)
    assert cm.is_seoul("지역", "상암 월드컵공원 평화의 광장")
    assert not cm.is_seoul("지역", "과천서울대공원") and not cm.is_seoul("경기", "서울 근교")
