"""서울 7일 전 날씨 예보 이력: `python -m weglee_ai.seoulevent.collect_weather_fc` → data/raw/weather_fc7.parquet

Open-Meteo Previous Runs API (일본 기상청 GSM 모델, 키 불필요 · CC BY 4.0 비상업) — 시간별 'N일 전에 돌린 예보' 값.
7일 전 예보(previous_day7)는 2021-01-20 부터. 실측 날씨(collect_weather, ASOS)와 같은 열(date · avg_ta · sum_rn)로 만든다 —
실험에서 실측 자리에 바꿔 넣으면 '7일 앞에 실제로 알 수 있던 날씨' 로 학습 · 검증한다 (48단계: 실측 날씨 상한이 경기장 밖 +0.078).
"""

import sys
from datetime import date, timedelta

import httpx
import pandas as pd

from weglee_ai.seoulevent import config
from weglee_ai.seoulevent._http import send

URL = "https://previous-runs-api.open-meteo.com/v1/forecast"
SEOUL = (37.57, 126.98)  # 서울 (종로) — 실측 ASOS 108 지점과 같은 곳
START = date(2021, 1, 20)  # JMA GSM 7일 전 예보 시작
LEAD = 7


def fetch(start: date, end: date, client: httpx.Client, lead: int = LEAD) -> pd.DataFrame:
    """[start, end] 시간별 lead 일 전 예보 → 일별 평균기온 · 강수 합 (한국 시각). 하루 24시간 중 빈 값이 있으면 그날은 빈 값."""
    t, r = f"temperature_2m_previous_day{lead}", f"precipitation_previous_day{lead}"
    js = send(client, "GET", URL, params={"latitude": SEOUL[0], "longitude": SEOUL[1], "timezone": "Asia/Seoul", "models": "jma_gsm",
                                         "hourly": f"{t},{r}", "start_date": str(start), "end_date": str(end)}).json()
    h = pd.DataFrame({"time": pd.to_datetime(js["hourly"]["time"]), "t": js["hourly"][t], "r": js["hourly"][r]})
    g = h.assign(date=h["time"].dt.date).groupby("date")
    out = pd.DataFrame({"avg_ta": g["t"].mean(), "sum_rn": g["r"].sum(min_count=1), "n": g["t"].count()}).reset_index()
    out.loc[out["n"] < 24, ["avg_ta", "sum_rn"]] = float("nan")
    return out[["date", "avg_ta", "sum_rn"]]


def main(argv: list[str] | None = None) -> int:
    """`[앞 일수]` — 기본 7 (weather_fc7.parquet). 51단계: 1 · 3일 앞 예측용으로 1 · 3 도."""
    lead = int(argv[0]) if argv else LEAD
    cfg = config.load()
    end = date.today() + timedelta(days=lead)  # 오늘 나온 예보까지 (운영 예측표용 미래 포함)
    client = httpx.Client(timeout=120, headers={"User-Agent": "seoulevent-research"})
    parts, s = [], START
    while s <= end:  # 한 번에 1년씩 (응답 크기)
        e = min(date(s.year, 12, 31), end)
        parts.append(fetch(s, e, client, lead))
        s = e + timedelta(days=1)
    df = pd.concat(parts, ignore_index=True)
    out = cfg.data_dir / "raw" / f"weather_fc{lead}.parquet"
    df.to_parquet(out, index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
