"""OCR 두 방식 실행기. 사진 폴더 → 방식별 예측 JSON 폴더. 지라 S15P31A308-109.

.venv/Scripts/python.exe scripts/ocr/run_methods.py --images 사진폴더 --out 출력폴더 --method clova_llm|llm_only [--limit N] [--dry-run]

하위 폴더까지 찾고 출력도 같은 하위 경로에 쓴다. 이미 있는 출력은 건너뛴다(이어 하기). 장마다 결과는 출력폴더/_run.csv.
환경 변수(또는 ai/.env): GMS_KEY · GMS_BASE_URL · GMS_MODEL · CLOVA_OCR_URL · CLOVA_OCR_SECRET.
종료 코드: 키 없음 2, 실패한 장이 있거나 멈춤 1, 모두 성공 0.
"""

import argparse
import os
import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(AI_DIR / "src"))  # PYTHONPATH 없이도 weglee_ai 를 찾게

from weglee_ai.ocr import runner  # noqa: E402
from weglee_ai.ocr.methods import METHODS  # noqa: E402


def _positive_int(s: str) -> int:
    try:
        n = int(s)
    except ValueError:
        n = 0
    if n < 1:
        raise argparse.ArgumentTypeError(f"1 이상의 정수여야 한다: {s}")
    return n


def main(argv=None, *, environ=None, post=None, sleep=None, env_file=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", required=True, help="사진 폴더 (하위 폴더까지)")
    parser.add_argument("--out", required=True, help="예측 JSON 출력 폴더")
    parser.add_argument("--method", required=True, choices=METHODS)
    parser.add_argument("--limit", type=_positive_int, default=None, help="앞에서부터 N장만 (1 이상)")
    parser.add_argument("--dry-run", action="store_true", help="네트워크 대신 가짜 응답으로 흐름만 확인")
    args = parser.parse_args(argv)

    images = Path(args.images)
    if not images.is_dir():
        print(f"사진 폴더가 없다: {images}", file=sys.stderr)
        return 2
    if not runner.find_images(images):
        print(f"경고: 사진 0장 (jpg·jpeg·png, 하위 폴더 포함): {images}", file=sys.stderr)
        return 2

    if args.dry_run:
        env, post, interval = runner.DRY_ENV, runner.dry_run_post, 0
    else:
        env = runner.load_env(os.environ if environ is None else environ, env_file or AI_DIR / ".env")
        missing = runner.missing_env(args.method, env)
        if missing:
            print(f"환경 변수 없음 ({args.method}): {', '.join(missing)} - ai/.env 또는 환경 변수에 넣는다", file=sys.stderr)
            return 2
        interval = runner.CLOVA_INTERVAL

    kw = {} if sleep is None else {"sleep": sleep}
    s = runner.run_folder(args.images, args.out, args.method, env, limit=args.limit, post=post,
                          clova_interval=interval, dry_run=args.dry_run, **kw)
    print(f"{args.method}: 전체 {s['total']} · 새로 {s['done']} · 건너뜀 {s['skipped']} · 실패 {s['failed']}"
          + (" · 연속 실패로 멈춤" if s["stopped"] else ""))
    return 1 if s["failed"] or s["stopped"] else 0


if __name__ == "__main__":
    sys.exit(main())
