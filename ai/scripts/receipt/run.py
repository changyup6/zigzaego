"""영수증 실행기. 사진 폴더 → 정형화(normalize) → 매칭(match) → 장마다 JSON. 지라 S15P31A308-110.

.venv/Scripts/python.exe scripts/receipt/run.py --images 사진폴더 --catalog 재료표.csv --out 출력폴더 [--limit N] [--dry-run]

재료표는 develop `ai/seed/ingredient_catalog.csv` (이 가지에는 복사하지 않는다 — 경로로 준다).
하위 폴더까지 찾고 출력도 같은 하위 경로에 쓴다. 이미 있는 출력은 건너뛴다(이어 하기). 장마다 결과는 출력폴더/_run.csv.
--limit N 은 이번 실행에서 새로 처리할 장 수다(끝난 장은 세지 않는다). 하루 한도 429 를 받으면 바로 멈추고(종료 코드 1),
정형화를 마친 장은 출력폴더/_normalize/ 에 저장해 다음 실행에서 매칭만 부른다.
환경 변수(또는 ai/.env): GMS_KEY · GMS_BASE_URL · GMS_MODEL (OpenAI 호환 chat/completions).
종료 코드: 키 · 재료표 · 사진 없음 2 (API 를 부르지 않는다), 실패한 장이 있거나 멈춤 1, 모두 성공 0.
"""

import argparse
import os
import sys
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(AI_DIR / "src"))  # PYTHONPATH 없이도 weglee_ai 를 찾게

from weglee_ai.ocr import runner as ocr_runner  # noqa: E402
from weglee_ai.receipt import pipeline  # noqa: E402
from weglee_ai.receipt.catalog import DRY_CATALOG, load_catalog  # noqa: E402


def _positive_int(s: str) -> int:
    try:
        n = int(s)
    except ValueError:
        n = 0
    if n < 1:
        raise argparse.ArgumentTypeError(f"1 이상의 정수여야 한다: {s}")
    return n


def _unquote(v):
    """환경 변수로 직접 넘긴 값도 앞뒤 따옴표를 벗긴다(.env 파일 값은 109 load_env 가 이미 벗긴다)."""
    if isinstance(v, str):
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
            return v[1:-1]
    return v


def main(argv=None, *, environ=None, post=None, sleep=None, env_file=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", required=True, help="사진 폴더 (하위 폴더까지)")
    parser.add_argument("--out", required=True, help="예측 JSON 출력 폴더")
    parser.add_argument("--catalog", default=None, help="범용 재료표 CSV (develop ai/seed/ingredient_catalog.csv)")
    parser.add_argument("--limit", type=_positive_int, default=None,
                        help="이번 실행에서 새로 처리할 장 수 (1 이상). 이미 끝난 장은 세지 않고 건너뛴다")
    parser.add_argument("--dry-run", action="store_true", help="네트워크 대신 가짜 응답으로 흐름만 확인")
    parser.add_argument("--env-file", default=None, help="키 파일(KEY=VALUE, 따옴표는 벗긴다). 기본 ai/.env")
    parser.add_argument("--normalized-dir", default=None, action="append",
                        help="바깥에서 만든 정형화 JSON 폴더(사진과 같은 상대 경로 <이름>.json, 정형화 스키마). 있는 장은 정형화를"
                             " 부르지 않고 매칭만 부른다. 여러 번 줄 수 있다. 출처 이름은 폴더의 _source.txt 첫 줄"
                             "(없으면 claude-vision). 같은 장이 두 폴더에 있으면 2")
    args = parser.parse_args(argv)
    for d in args.normalized_dir or []:
        if not Path(d).is_dir():
            print(f"바깥 정형화 폴더가 없다: {d}", file=sys.stderr)
            return 2

    images = Path(args.images)
    if not images.is_dir():
        print(f"사진 폴더가 없다: {images}", file=sys.stderr)
        return 2
    if not ocr_runner.find_images(images):
        print(f"경고: 사진 0장 (jpg·jpeg·png, 하위 폴더 포함): {images}", file=sys.stderr)
        return 2
    try:
        pipeline.external_sources(images, args.normalized_dir)
    except (OSError, ValueError) as e:
        print(f"바깥 정형화 폴더를 쓸 수 없다: {e}", file=sys.stderr)
        return 2

    if args.catalog and Path(args.catalog).is_file():
        try:
            catalog = load_catalog(args.catalog)
        except ValueError as e:
            print(f"재료표를 읽지 못했다: {e}", file=sys.stderr)
            return 2
    elif args.dry_run:
        print(f"경고: 재료표 없음({args.catalog}) - dry-run 이라 내장 재료표 {len(DRY_CATALOG)}개로 흐름만 본다",
              file=sys.stderr)
        catalog = DRY_CATALOG
    else:
        print(f"재료표 파일이 없다: {args.catalog} - --catalog 로 develop ai/seed/ingredient_catalog.csv 경로를 준다",
              file=sys.stderr)
        return 2

    if args.dry_run:
        env, post = pipeline.DRY_ENV, pipeline.dry_run_post
    else:
        env = ocr_runner.load_env(os.environ if environ is None else environ,
                                  args.env_file or env_file or AI_DIR / ".env")
        env = {k: _unquote(v) for k, v in env.items()}
        if not str(env.get("GMS_KEY") or "").strip() and str(env.get("Gemini_KEY") or "").strip():
            env["GMS_KEY"] = env["Gemini_KEY"]  # Gemini 대체 실측(10/19): GMS_KEY 가 비면 Gemini_KEY
        missing = [k for k in pipeline.GMS_VARS if not str(env.get(k) or "").strip()]
        if missing:
            print(f"환경 변수 없음: {', '.join(missing)} - ai/.env 또는 환경 변수에 넣는다", file=sys.stderr)
            return 2

    kw = {} if sleep is None else {"sleep": sleep}
    s = pipeline.run_folder(images, args.out, env, catalog, limit=args.limit, post=post, dry_run=args.dry_run,
                            normalized_dir=args.normalized_dir, **kw)
    print(f"receipt: 전체 {s['total']} · 새로 {s['done']} · 건너뜀 {s['skipped']} · 실패 {s['failed']}"
          f" · 성공 장 호출 {s['calls']} · 토큰 {s['tokens']}"
          + "".join(f" · 정형화 {k} {v}장" for k, v in s["sources"].items())
          + (" · 하루 한도로 멈춤" if s["quota"] else " · 연속 실패로 멈춤" if s["stopped"] else ""))
    return 1 if s["failed"] or s["stopped"] else 0


if __name__ == "__main__":
    sys.exit(main())
