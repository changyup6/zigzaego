from datetime import date

import pandas as pd

from weglee_ai.seoulevent import collect_kopis_rank as kr


def test_parse_boxoffice():
    xml = ("<boxofs><boxof><cate>뮤지컬</cate><rnum>1</rnum><prfnm>a</prfnm><mt20id>PF1</mt20id></boxof>"
           "<boxof><rnum>2</rnum><mt20id>PF2</mt20id></boxof></boxofs>")
    assert kr.parse_boxoffice(xml) == [{"rnum": 1, "mt20id": "PF1"}, {"rnum": 2, "mt20id": "PF2"}]


def test_pre_show_popularity_uses_only_weeks_7_to_56_days_before_start():
    perf = pd.DataFrame({"mt20id": ["PF1", "PF2", "PF3"], "start_date": [date(2024, 6, 8)] * 3})
    box = pd.DataFrame({"mt20id": ["PF1", "PF1", "PF2", "PF3"],
                        "week_end": [date(2024, 5, 26), date(2024, 6, 2), date(2024, 6, 9), date(2024, 3, 1)],
                        "rnum": [10, 2, 1, 1]})
    # PF1: 13일 전 10위 O, 6일 전 2위 X (7일 앞엔 모름) -> 41 / PF2: 시작 뒤 X -> 0 / PF3: 99일 전 X -> 0
    assert kr.pre_show_popularity(perf, box).tolist() == [41.0, 0.0, 0.0]
