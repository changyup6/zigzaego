"""KBO 잠실 경기 일정: `python -m weglee_ai.seoulevent.collect_kbo`

KBO 영문 공식 사이트의 월별 일정 화면(eng.koreabaseball.com/Schedule/DailySchedule.aspx)에서 받는다. 미래 경기도 나온다.
한글 사이트(www.koreabaseball.com)는 robots.txt 가 모든 봇을 막고(Disallow: /), 영문 사이트도 데이터 주소 /ws/ 는 막는다 →
robots.txt 가 허용하는 일정 화면 HTML 만 읽는다 (2026-11-06 확인: Disallow 는 /Common/ · /Help/ · /Member/ · /ws/).
잠실종합운동장 상권이 3월 주말마다 5년 연속 '원인 모름' 급증 (25단계 반복 급증 탐색) — 시범경기 · 개막 시리즈.
고척은 서울시설공단 경기장 달력(collect_stadium)에 이미 있어 잠실만.
저장: data/raw/kbo/games.parquet (date, time, away, home, stadium, series, note, collected_at)
"""

import re
import sys
import time
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

PAGE = "https://eng.koreabaseball.com/Schedule/DailySchedule.aspx"
_BTN = "ctl00$ctl00$ctl00$ctl00$cphContainer$cphContainer$cphContent$cphContent$"
FIRST_MONTH = (2019, 2)
AHEAD_MONTHS = 8  # 발표된 다음 시즌 일정까지 (일정은 보통 전년 12월 ~ 1월 발표)
STADIUMS = ("잠실",)
# shortcut: 2027 ~ 2031 홈경기는 잠실 주경기장(대체 구장) — 영문 표기를 모른다. 처음 나오면 STADIUM_KO 와 좌표를 더한다
STADIUM_KO = {"JAMSIL": "잠실"}
TEAM_KO = {"DOOSAN": "두산", "HANWHA": "한화", "SAMSUNG": "삼성", "LOTTE": "롯데", "KIWOOM": "키움"}  # 나머지(LG · KT · NC · KIA · SSG · SK)는 같다
JAMSIL = (37.512200, 127.071900, 23750)  # 잠실야구장 좌표 · 좌석 (shortcut: 대략값, 공식 좌표 확인 시 교체)
_TD = re.compile(r"<td([^>]*)>(.*?)</td>", re.S)


def series_of(kind: str) -> str | None:
    """영문 경기 종류 → 정규 · 시범경기 · 포스트시즌. 올스타는 뺀다 (예전 한글 수집도 안 받음)."""
    k = kind.lower()
    if k.startswith(("regular", "tie breaker")):
        return "정규"
    if k.startswith("exhibition"):
        return "시범경기"
    if k.startswith(("wild card", "playoff", "korean series")):
        return "포스트시즌"
    return None


def parse_page(html: str) -> list[dict]:
    """월 일정 화면 → 경기 행. 날짜 · 종류 칸은 그날 첫 경기에만 있어(rowspan) 앞 줄 값을 이어 쓴다. 연도는 화면 제목(2025.04)."""
    ym = re.search(r"(\d{4})\.(\d{2})", re.sub(r"<[^>]+>", " ", html))
    body = html.split("<tbody>", 1)[-1].split("</tbody>", 1)[0]
    rows, day, kind = [], None, ""
    for tr in re.findall(r"<tr>(.*?)</tr>", body, re.S):
        cells = {}
        for attrs, text in _TD.findall(tr):
            key = re.search(r'title="(DATE|TYPE)"', attrs) or re.search(r'class="([\w-]+)', attrs)
            cells[key.group(1) if key else ""] = re.sub(r"<[^>]+>", " ", text).strip()
        if "DATE" in cells:
            m = re.match(r"(\d+)\.(\d+)", cells["DATE"])
            day = date(int(ym.group(1)), int(m.group(1)), int(m.group(2))) if (m and ym) else None
        kind = cells.get("TYPE", kind)
        series = series_of(kind)
        if day is None or series is None or "loop_r" not in cells or "loop_l" not in cells:
            continue
        loc = cells.get("LOCATION", "")
        note = cells.get("ETC", "")
        rows.append({"date": day, "time": cells.get("TIME", ""), "away": TEAM_KO.get(cells["loop_r"], cells["loop_r"]),
                     "home": TEAM_KO.get(cells["loop_l"], cells["loop_l"]), "stadium": STADIUM_KO.get(loc, loc), "series": series,
                     "note": "취소 (POSTPONED)" if note == "POSTPONED" else note})  # '취소' — events · late_known 이 한글로 거른다
    return rows


def _hidden(html: str) -> dict:
    return dict(re.findall(r'<input type="hidden" name="([^"]+)" id="[^"]*" value="([^"]*)"', html))


def _month(html: str) -> tuple[int, int]:
    m = re.search(r"(\d{4})\.(\d{2})", re.sub(r"<[^>]+>", " ", html))
    return int(m.group(1)), int(m.group(2))


def fetch_pages(client: httpx.Client, first: tuple[int, int] = FIRST_MONTH, ahead: int = AHEAD_MONTHS, pause: float = 0.5) -> list[str]:
    """이번 달 화면에서 '이전 달' 버튼으로 first 까지, '다음 달' 버튼으로 ahead 달 뒤까지 (화면이 달 이동을 폼 전송으로만 한다)."""
    now = client.get(PAGE)
    now.raise_for_status()
    pages = [now.text]
    for button, stop in (("btnBefore", lambda ym, n: ym <= first), ("btnNext", lambda ym, n: n >= ahead)):
        html, n = now.text, 0
        while not stop(_month(html), n):
            time.sleep(pause)
            resp = client.post(PAGE, data=_hidden(html) | {f"{_BTN}{button}.x": "5", f"{_BTN}{button}.y": "5"})
            resp.raise_for_status()
            html, n = resp.text, n + 1
            pages.append(html)
    return pages


def late_known(games: pd.DataFrame) -> pd.Series:
    """7일 전에 몰랐을 수 있는 경기: 포스트시즌(대진이 며칠 전 확정) · 우천 등 취소 뒤 재편성 경기.
    재편성은 일정표에 따로 표시가 없어, 같은 해 같은 대진에서 취소된 수만큼 취소일 뒤의 마지막 경기들을 재편성으로 본다 (ChatGPT 18: 7일 전 가용성).
    shortcut: 실제 재편성 발표일은 모름, KBO 재편성 공지를 수집하면 교체."""
    g = games.assign(_d=pd.to_datetime(games["date"]), _y=pd.to_datetime(games["date"]).dt.year)
    late = pd.Series(g["series"].eq("포스트시즌").to_numpy(), index=games.index)
    cancel = g["note"].astype(str).str.contains("취소|그라운드|기타")
    for _, grp in g[g["series"].eq("정규")].groupby(["_y", "away", "home"]):
        c = grp[cancel.loc[grp.index]]
        if c.empty:
            continue
        played = grp[~cancel.loc[grp.index] & (grp["_d"] > c["_d"].min())].sort_values("_d")
        late.loc[played.index[-len(c):]] = True
    return late


def main() -> int:
    cfg = config.load()
    client = httpx.Client(timeout=30, headers={"User-Agent": "Mozilla/5.0"})
    games = pd.DataFrame([r for page in fetch_pages(client) for r in parse_page(page)])
    games = games[games["stadium"].isin(STADIUMS)].drop_duplicates(["date", "home", "time"]).assign(collected_at=date.today().isoformat())
    out = cfg.data_dir / "raw" / "kbo"
    out.mkdir(parents=True, exist_ok=True)
    games.to_parquet(out / "games.parquet", index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
