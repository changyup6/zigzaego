"""실시간 상권현황 스냅샷: `python -m weglee_ai.seoulevent.collect_citydata`

과거 데이터가 제공되지 않으므로 매시 실행해 쌓는다. 저장: data/raw/citydata_cmrcl/<날짜>/<HHMM>.jsonl
받는 즉시 한 줄씩 쓰고 로그는 finally 에서 남겨, 중간에 죽어도 받은 만큼과 그 시각의 기록이 남는다.
"""

import csv
import json
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from weglee_ai.seoulevent import config, places, seoul_api
from weglee_ai.seoulevent.seoul_api import SeoulApiError

SERVICE = "citydata_cmrcl"
LOG_FIELDS = ["fetched_at", "ok", "failed_count", "failed_places", "errors"]
DEADLINE_S = 25 * 60  # 작업 스케줄러 제한(30분) 전에 스스로 멈춘다


@dataclass
class RunResult:
    ok: int = 0
    failed: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)  # "AREA_CD:이유" — 키가 섞이지 않는 코드 · 예외 이름만


def _free_path(day_dir: Path, slot: str) -> Path:
    path, n = day_dir / f"{slot}.jsonl", 2
    while path.exists():
        path, n = day_dir / f"{slot}_{n}.jsonl", n + 1
    return path


def _append_log(out_dir: Path, stamp: str, ok: int, failed: list[str], errors: list[str]) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    log = out_dir / "_log.csv"
    new = not log.exists()
    with open(log, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, LOG_FIELDS)
        if new:
            w.writeheader()
        w.writerow({"fetched_at": stamp, "ok": ok, "failed_count": len(failed),
                    "failed_places": ";".join(failed), "errors": ";".join(errors)})


def run(
    places_: list[tuple[str, str]],
    now: datetime,
    fetch: Callable[[str], dict],
    out_dir: Path,
    pause: float = 0.2,
    deadline_s: float = DEADLINE_S,
    clock: Callable[[], float] = time.monotonic,
) -> RunResult:
    result = RunResult()
    stamp = now.isoformat(timespec="seconds")
    out = None
    start = clock()

    def write(record: dict) -> None:
        nonlocal out
        if out is None:
            day_dir = out_dir / now.strftime("%Y-%m-%d")
            day_dir.mkdir(parents=True, exist_ok=True)
            out = open(_free_path(day_dir, now.strftime("%H%M")), "w", encoding="utf-8")
        out.write(json.dumps({"area_cd": area_cd, "area_nm": area_nm, "fetched_at": stamp, **record}, ensure_ascii=False) + "\n")
        out.flush()

    try:
        for i, (area_cd, area_nm) in enumerate(places_):
            if clock() - start > deadline_s:
                rest = [cd for cd, _ in places_[i:]]
                result.failed += rest
                result.errors += [f"{cd}:DEADLINE" for cd in rest]
                break
            try:
                body = fetch(area_cd)
            except SeoulApiError as e:
                result.failed.append(area_cd)
                result.errors.append(f"{area_cd}:{e.code}")
                if e.body is not None:
                    write({"body": e.body, "result_unknown": True})
            except Exception as e:  # 한 곳 실패로 나머지를 잃지 않는다. 메시지는 키가 섞일 수 있어 남기지 않는다.
                result.failed.append(area_cd)
                result.errors.append(f"{area_cd}:{type(e).__name__}")
            else:
                result.ok += 1
                write({"body": body})
            if pause:
                time.sleep(pause)
    finally:
        if out is not None:
            out.close()
        _append_log(out_dir, stamp, result.ok, result.failed, result.errors)
    return result


def main() -> int:
    cfg = config.load()
    out_dir = cfg.data_dir / "raw" / SERVICE
    now = datetime.now()
    stamp = now.isoformat(timespec="seconds")
    if not cfg.seoul_key:
        print("SEOUL_API_KEY 가 .env 에 없습니다.", file=sys.stderr)
        _append_log(out_dir, stamp, 0, [], ["startup:NO_KEY"])
        return 2
    try:
        place_list = places.load(places.ensure(cfg.data_dir))
    except Exception as e:  # 작업 스케줄러는 출력을 버리므로 시작 실패도 로그에 남긴다
        print(f"장소 목록 실패: {type(e).__name__}", file=sys.stderr)
        _append_log(out_dir, stamp, 0, [], [f"startup:{type(e).__name__}"])
        return 1
    result = run(
        place_list,
        now,
        lambda area_cd: seoul_api.fetch_one(SERVICE, area_cd, key=cfg.seoul_key),
        out_dir,
    )
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
