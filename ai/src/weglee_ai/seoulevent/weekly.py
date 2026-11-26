"""주간 일정 수집 + 스냅샷: `python -m weglee_ai.seoulevent.weekly` (scripts/register_weekly_task.ps1 로 매주 실행)

경기장 · 한강 · KOPIS(공연 · 예매 순위) · K리그 · 잠실 프로야구 · 문화행사 · 국가대표 경기 · 마라톤 · 뉴스 기사를 받은 뒤 원본 일정 파일을 날짜별로 복사해 둔다. KOPIS · K리그 · 한강 수집기는 파일을 통째로
다시 써서 collected_at 이 매번 오늘로 바뀌므로, '그 일정을 처음 안 날' 은 스냅샷 중 가장 이른 것으로 복원한다 (07 검토 3: 7일 앞 시점 평가).
저장: data/raw/_snapshots/YYYY-MM-DD/<원천>.parquet + _manifest.json (저장 시각 · 원천별 수집 성공 여부 · 행 수, ChatGPT 20 권고 1)
"""

import json
import shutil
import sys
import traceback
from datetime import date, datetime

from weglee_ai.seoulevent import (collect_bus, collect_events, collect_hangang, collect_kbo, collect_kfa, collect_kleague, collect_kopis, collect_kopis_rank,
                        collect_marathon, collect_stadium, collect_news, collect_std_events, collect_subway, config)

# subway: v2 (41단계) — 승하차 최근 시트(3일 전까지)로 기준선을 최신으로. 예측표는 이 수집 · 빌드 뒤에 만든다
# bus: 45단계 데이터셋 (모델 특징으로는 미채택) — 스냅샷은 200MB 합본 대신 최근 시트 합계만 (snapshot)
COLLECTORS = [("subway", collect_subway), ("bus", collect_bus), ("stadium", collect_stadium), ("hangang", collect_hangang), ("kopis", collect_kopis), ("kopis_rank", collect_kopis_rank), ("kleague", collect_kleague), ("kbo", collect_kbo),
              ("cultural_events", collect_events), ("std_events", collect_std_events), ("kfa", collect_kfa),
              ("marathon", collect_marathon), ("news", collect_news)]
SNAPSHOT_FILES = {"subway": "subway_daily.parquet", "stadium": "stadium/events.parquet", "hangang": "hangang/events.parquet", "kopis": "kopis/performances.parquet",
                  "kleague": "kleague/games.parquet", "kbo": "kbo/games.parquet",
                  "kopis_rank": "kopis/boxoffice.parquet",  # 예매 순위(인기 특징) — 빠져 있으면 실패해도 manifest 에 안 남는다
                  "std_events": "std_events/events.parquet", "kfa": "kfa/matches.parquet",  # std_events: 전국문화축제 · 공연행사 표준데이터 (과거가 없어 스냅샷으로 쌓는다)
                  "marathon": "marathon/races.parquet", "news": "news/articles.parquet"}  # news: 누적 기사(collected_at = 처음 안 날) — 실패 기록용으로 함께 복사


def _rows(path) -> int | None:
    try:
        import pandas as pd
        return len(pd.read_parquet(path))
    except Exception:  # 읽을 수 없는 파일도 복사는 했으니 행 수만 비운다
        return None


def snapshot(raw, day: date, failed: tuple = ()) -> list[str]:
    dest = raw / "_snapshots" / day.isoformat()
    dest.mkdir(parents=True, exist_ok=True)
    copied = []
    for name, rel in SNAPSHOT_FILES.items():
        if (raw / rel).exists():
            shutil.copy2(raw / rel, dest / f"{name}.parquet")
            copied.append(name)
    # 문화행사는 수집일마다 따로 저장되지만, 같은 날짜 폴더에 모아 둬야 '그날 알던 일정' 을 한 번에 복원한다 (14 검토 2)
    cultural = sorted((raw / "cultural_events").glob("*.parquet"))
    if cultural:
        shutil.copy2(cultural[-1], dest / "cultural_events.parquet")
        copied.append("cultural_events")
    bus = sorted((raw / "bus" / "stop_day").glob("0_RECENT_*.parquet"))  # 버스: 그날 받은 최근 시트 합계 (약 40일)
    if bus:
        shutil.copy2(bus[-1], dest / "bus.parquet")
        copied.append("bus")
    # 실패한 원천의 파일은 지난번 수집본이다 → as-of 복원 때 '이날 새로 안 일정' 으로 쓰지 않도록 표시
    manifest = {"snapshot_at": datetime.now().isoformat(timespec="seconds"),
                "sources": {n: {"collected": n not in failed, "rows": _rows(dest / f"{n}.parquet")} for n in copied}}
    (dest / "_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    return copied


def main(argv: list[str] | None = None) -> int:
    """수집 → 스냅샷 → (v2 운영) 빌드 → 예측표(학습 + data/models/latest 저장). `--collect-only` 면 수집 · 스냅샷만.
    예측 API(`python -m weglee_ai.seoulevent.api`)는 바뀐 forecast.csv 를 다음 요청에서 다시 읽는다."""
    argv = argv or []
    failed = []
    for name, mod in COLLECTORS:
        try:  # 한 원천이 실패해도 나머지는 받는다
            if mod.main() not in (0, None):
                failed.append(name)
        except Exception:
            traceback.print_exc()
            failed.append(name)
    copied = snapshot(config.load().data_dir / "raw", date.today(), tuple(failed))
    if "--collect-only" not in argv:
        from weglee_ai.seoulevent.build.__main__ import main as build_main
        from weglee_ai.seoulevent.model import forecast
        try:  # 51단계: 3일 앞 예측표용 3일 전 예보 날씨 (매일은 weglee_ai.seoulevent.daily)
            from weglee_ai.seoulevent import collect_weather_fc
            collect_weather_fc.main(["3"])
        except Exception:
            traceback.print_exc()
            failed.append("weather_fc3")
        build_main()
        forecast.main([])  # 기본 앞으로 7일 · 학습한 모델은 data/models/latest 에 저장
        forecast.main(["--horizon", "3"])  # 3일 앞 · data/models/latest_h3 에 저장 (매일 daily 가 이 모델로 예측)
        from weglee_ai.seoulevent.model import publish, score
        publish.main([])  # 3 · 7일 합친 예측표 + GMS 근거 설명 (data/out/forecast/)
        score.main([])  # 보관된 예측표 중 실제 승하차가 나온 날 채점 → forecast_scores.csv
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
