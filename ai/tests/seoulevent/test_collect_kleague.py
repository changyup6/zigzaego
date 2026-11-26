from datetime import date

import pandas as pd

from weglee_ai.seoulevent import collect_kleague as kl


def _game(d, home, field, audience, end="Y", league=1):
    return {"gameDate": d, "gameTime": "19:30", "leagueId": league, "homeTeamName": home, "awayTeamName": "상대",
            "fieldName": field, "fieldNameFull": field + " 경기장", "audienceQty": audience, "endYn": end}


def test_parse_keeps_seoul_venues_and_blank_future_audience():
    body = {"data": {"scheduleList": [_game("2026.11.01", "서울", "서울 월드컵", 12050),
                                      _game("2026.11.07", "서울", "서울 월드컵", 0, end="N"),
                                      _game("2026.11.01", "울산", "울산 문수", 7038)]}}
    rows = kl.parse_schedule(body)
    assert [(r["date"], r["field"], r["audience"]) for r in rows] == [(date(2026, 11, 1), "서울 월드컵", 12050),
                                                                        (date(2026, 11, 7), "서울 월드컵", None)]
    assert rows[0]["lat"] > 37 and rows[0]["capacity"] == 66704


def test_expected_audience_uses_previous_home_games_only():
    games = pd.DataFrame({"date": [date(2025, 3, 1), date(2025, 4, 1), date(2025, 5, 1), date(2025, 6, 1)],
                          "home": ["서울"] * 4, "audience": [10000, 20000, 30000, None]})
    out = kl.add_expected_audience(games)
    assert pd.isna(out["expected_audience"].iloc[0])           # 이전 경기 없음
    assert out["expected_audience"].tolist()[1:] == [10000, 15000, 20000]  # 그날 관중은 안 씀
