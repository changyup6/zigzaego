"""로컬 PC 에서 실행: 수집 → 데이터 만들기 → 저장 모델로 예측 → 예측표(CSV)를 DB(sales_forecast_week)에 적재 (S15P31A308-226).

서버(CronJob)에서 돌지 않는다. 로컬에서 하루 한 번 돌리면 BE(GET /sales-forecast/this-week)가 DB 의 결과를 읽는다 (ADR-0017).
학습은 하지 않는다 — 데이터 폴더의 저장 모델(models/<SEOULEVENT_MODEL, 기본 model-v3>, model.joblib + meta.json)로 예측만 한다.
수집 하나가 실패해도 나머지는 받고, 이전 자료로 예측을 이어 간다 (weekly 와 같은 규칙).

실행: README.md 참고.
"""

import os
import sys
import traceback
from datetime import date

from weglee_ai import sales_forecast
from weglee_ai.seoulevent import config, weekly
from weglee_ai.seoulevent.build.__main__ import main as build
from weglee_ai.seoulevent.model import forecast

# 모델 입력이 아닌 원천은 매일 받지 않는다 — 뉴스(하루 API 한도) · 버스(45단계 미채택). 주간 수집(weekly)이 쌓는다
SKIP = {"news", "bus"}


def collect() -> list[str]:
    failed = []
    for name, mod in weekly.COLLECTORS:
        if name in SKIP:
            continue
        try:
            if mod.main() not in (0, None):
                failed.append(name)
        except Exception:
            traceback.print_exc()
            failed.append(name)
    return failed


def main(today: date | None = None) -> int:
    today = today or date.today()
    data = config.load().data_dir
    failed = collect()
    if today.weekday() == 0:  # 월요일만 스냅샷 (그날 알던 일정 — 사후 백테스트 재료)
        weekly.snapshot(data / "raw", today, tuple(failed))
    build()
    forecast.main(["--model", str(data / "models" / os.getenv("SEOULEVENT_MODEL", "model-v3"))])  # 오늘부터 7일
    sales_forecast.main([str(forecast.forecast_dir(data / "out") / "forecast.csv")])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
