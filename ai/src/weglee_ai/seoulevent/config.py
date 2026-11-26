import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

# 참조표(역 좌표 · 도시 행사일 · 개화일 등)는 패키지 안에 둔다 — 작업 폴더와 상관없이 읽힌다
REF_DIR = Path(__file__).resolve().parent / "ref"


@dataclass(frozen=True)
class Config:
    seoul_key: str | None
    data_go_kr_key: str | None
    data_dir: Path
    kopis_key: str | None = None
    naver_id: str | None = None  # NAVER API HUB(네이버 클라우드) 검색 키 — 개발자센터 키와 다르다
    naver_secret: str | None = None


def load() -> Config:
    load_dotenv()
    return Config(
        seoul_key=os.getenv("SEOUL_API_KEY") or None,
        data_go_kr_key=os.getenv("DATA_GO_KR_KEY") or None,
        data_dir=Path(os.getenv("SEOULEVENT_DATA_DIR", "data")),
        kopis_key=os.getenv("KOPIS_API_KEY") or None,
        naver_id=os.getenv("NAVER_CLIENT_ID") or None,
        naver_secret=os.getenv("NAVER_CLIENT_SECRET") or None,
    )


def write_manifest(out_dir: Path, name: str, extra: dict | None = None, outputs: tuple[str, ...] = ()) -> Path:
    """산출물 기록 (ChatGPT 25 — 신호 파일과 입력 데이터가 다른 빌드였다): 만든 시각 · 코드 커밋 · data/out 입력 파일 크기 · 수정 시각.
    outputs 를 주면 그 출력 파일의 크기 · SHA-256 도 (ChatGPT 26 — 공유한 결과가 그 실행의 것인지 확인)."""
    import hashlib
    import json
    import subprocess
    from datetime import datetime

    try:
        commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        commit = None
    inputs = {p.name: {"bytes": p.stat().st_size, "mtime": datetime.fromtimestamp(p.stat().st_mtime).isoformat(timespec="seconds")}
              for p in sorted([*out_dir.glob("*.parquet"), *out_dir.glob("spikes.csv")])}
    outs = {n: {"bytes": (out_dir / n).stat().st_size, "sha256": hashlib.sha256((out_dir / n).read_bytes()).hexdigest()}
            for n in outputs if (out_dir / n).exists()}
    path = out_dir / name
    path.write_text(json.dumps({"written_at": datetime.now().isoformat(timespec="seconds"), "commit": commit, "inputs": inputs, "outputs": outs} | (extra or {}),
                               ensure_ascii=False, indent=1), encoding="utf-8")
    return path
