from datetime import date

from weglee_ai.seoulevent import collect_kfa as kfa

# 2023-12 를 date_div=pre 로 받은 화면(= 2023-11 경기) 일부를 줄인 것
PAGE = """
<tr><td>
    <p class="count_title" style="padding-right: unset;">
    2026 FIFA World Cup 아시아 2차 예선&nbsp;&nbsp;<a href="//youtu.be/x" target="_blank"><img src="/images/btn_play_on.png"></a>    <span class="count_date">목요일 11-16</span>
    <span class="count_from">대한민국&nbsp;서울월드컵경기장</span>
    </p>
    <ul class="vs_from"  onclick="match_schedule_national_result('7105');" style="cursor:pointer;">
        <li class="vs_korea">남자 국가대표팀</li>
        <li class="vs_mexico" style="background-image: url('https://img.kfa.or.kr/nation_flag/172_nation_singapore@1x.png');">싱가포르</li>
    </ul>
    <ul class="vs_num"><li  class="vs_win_num">5 </li><li >0 </li></ul>
</td>
<td>
    <p class="count_title" style="padding-right: unset;">
    2023 FIFA U-17 월드컵 인도네시아    <span class="count_date">수요일 11-15</span>
    <span class="count_from">인도네시아&nbsp;자카르타스타디움</span>
    </p>
    <ul class="vs_from"><li class="vs_korea">남자 U-17 대표팀</li>
        <li class="vs_mexico" style="background-image: url('x.png');">프랑스</li></ul>
</td></tr>
"""


def test_parse_month_reads_competition_date_team_opponent_venue():
    rows = kfa.parse_month(PAGE, 2023)
    assert rows[0] == {"date": date(2023, 11, 16), "competition": "2026 FIFA World Cup 아시아 2차 예선", "team": "남자 국가대표팀",
                       "opponent": "싱가포르", "country": "대한민국", "venue": "서울월드컵경기장"}
    assert (rows[1]["date"], rows[1]["country"], rows[1]["venue"], rows[1]["opponent"]) == (date(2023, 11, 15), "인도네시아", "자카르타스타디움", "프랑스")


def test_months_runs_through_this_month():
    assert kfa.months((2025, 11), date(2026, 2, 3)) == [(2025, 11), (2025, 12), (2026, 1), (2026, 2)]
