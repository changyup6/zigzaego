"""상권 예측 원본(foodspring-crawler)을 weglee_ai.seoulevent 로 옮긴다 — 모델을 갱신할 때 한 번 실행.

    uv run python scripts/sync_seoulevent.py <crawler 폴더>

하는 일: crawler 의 src/seoulevent · ref/ · tests/test_*.py 를 src/weglee_ai/seoulevent · tests/seoulevent 로 복사하고
import 경로(seoulevent → weglee_ai.seoulevent) · ref 경로(config.REF_DIR)를 바꾼다. 위글리 전용 파일(KEEP)은 그대로 두고,
crawler 전용 예측 API(api.py)는 옮기지 않는다. 패키지 코드의 진행 로그 print 는 지운다(오류 안내 · check.py · experiment.py 는 둠).
다 바꾼 뒤 Path("ref") 가 남으면 멈춘다. 옮긴 뒤 테스트로 확인한다:
    uv run --group event pytest tests/seoulevent tests/test_sales_forecast.py
"""

import ast
import re
import shutil
import sys
from pathlib import Path

AI = Path(__file__).resolve().parents[1]
PKG, TESTS = AI / "src/weglee_ai/seoulevent", AI / "tests/seoulevent"
KEEP = {PKG: ("daily.py", "README.md"), TESTS: ("conftest.py", "test_evidence.py")}  # 위글리에만 있는 파일
SKIP = ("api.py", "test_api.py")  # crawler 전용 예측 API — 위글리는 BE 가 읽는다
PRINT_OK = ("check.py", "experiment.py")  # 진단 · 실험 결과는 CLI 출력도 산출물이다
IMPORT = re.compile(r"(?<![\w/.])seoulevent(?=\.|\s+import)")
REF = (('Path(__file__).resolve().parents[3] / "ref"', "config.REF_DIR"), ('Path("ref")', "config.REF_DIR"))
CONFIG_IMPORT = "from weglee_ai.seoulevent import config"
REF_DIR_LINE = ('\n\n# 참조표(역 좌표 · 도시 행사일 · 개화일 등)는 패키지 안에 둔다 — 작업 폴더와 상관없이 읽힌다\n'
                'REF_DIR = Path(__file__).resolve().parent / "ref"')


def rewrite(text: str, name: str) -> str:
    """파일 하나의 글자 바꾸기: import 경로, ref 경로, ref 를 쓰게 된 모듈의 config import, config.py 의 REF_DIR."""
    text = IMPORT.sub("weglee_ai.seoulevent", text)
    for old, new in REF:
        text = text.replace(old, new)
    uses_config = "config.REF_DIR" in text
    has_config = re.search(r"^from weglee_ai\.seoulevent import .*\bconfig\b", text, re.M)
    if uses_config and not has_config and "import pandas as pd\n" in text:
        text = text.replace("import pandas as pd\n", f"import pandas as pd\n\n{CONFIG_IMPORT}\n", 1)
    if name == "config.py" and "REF_DIR =" not in text:
        i = text.index("\n\n", text.index("from dotenv"))
        text = text[:i] + REF_DIR_LINE + text[i:]
    return text


def strip_prints(text: str) -> str:
    """진행 로그 print 문장을 지운다. 오류 안내(print(..., file=sys.stderr))는 둔다. 블록이 비면 pass 를 남긴다."""
    tree = ast.parse(text)
    drop = {id(s) for s in ast.walk(tree) if isinstance(s, ast.Expr) and isinstance(s.value, ast.Call)
            and getattr(s.value.func, "id", None) == "print" and not any(k.arg == "file" for k in s.value.keywords)}
    edits = []  # (시작 줄, 시작 칸, 끝 줄, 끝 칸, 바꿀 글) — 줄은 0부터
    for node in ast.walk(tree):
        for field in ("body", "orelse", "finalbody"):
            block = getattr(node, field, None)
            if not isinstance(block, list):
                continue
            hits = [s for s in block if id(s) in drop]
            for i, s in enumerate(hits):
                keep_pass = i == 0 and len(hits) == len(block)  # 블록 전체가 print 면 첫 줄은 pass
                edits.append((s.lineno - 1, s.col_offset, s.end_lineno - 1, s.end_col_offset, "pass" if keep_pass else ""))
    lines = [ln.encode("utf-8") for ln in text.splitlines(keepends=True)]  # ast 의 칸 위치는 UTF-8 바이트
    for a, ac, b, bc, new in sorted(edits, reverse=True):
        head, tail = lines[a][:ac], lines[b][bc:]
        alone = not head.strip() and (not tail.strip() or tail.lstrip().startswith(b"#"))
        if alone and not new:
            del lines[a:b + 1]  # 줄 전체가 print 문장
        else:
            if not new:  # 같은 줄의 다른 문장은 남기고 print 와 세미콜론 하나만 지운다
                if re.search(rb";\s*$", head):
                    head = re.sub(rb";\s*$", b"", head)
                else:
                    tail = re.sub(rb"^\s*;\s*", b"", tail)
            lines[a:b + 1] = [head + new.encode() + tail]
    return b"".join(lines).decode("utf-8")


def sync(crawler: Path) -> None:
    kept = {d / n: (d / n).read_text(encoding="utf-8") for d, names in KEEP.items() for n in names if (d / n).exists()}
    for d in (PKG, TESTS):
        shutil.rmtree(d, ignore_errors=True)
    ignore = shutil.ignore_patterns("__pycache__", "*.pyc")
    shutil.copytree(crawler / "src/seoulevent", PKG, ignore=ignore)
    shutil.copytree(crawler / "ref", PKG / "ref", ignore=ignore)
    TESTS.mkdir(parents=True)
    for t in (crawler / "tests").glob("test_*.py"):
        shutil.copy(t, TESTS / t.name)
    for f in [p for d in (PKG, TESTS) for p in d.rglob("*.py")]:
        if f.name in SKIP:
            f.unlink()
            continue
        text = rewrite(f.read_text(encoding="utf-8"), f.name)
        if f.is_relative_to(PKG) and f.name not in PRINT_OK:
            text = strip_prints(text)
        f.write_text(text, encoding="utf-8")
    for path, text in kept.items():
        path.write_text(text, encoding="utf-8")
    left = [str(f) for f in PKG.rglob("*.py") if 'Path("ref")' in f.read_text(encoding="utf-8")]
    if left:
        raise SystemExit(f"ref 경로가 남았다 — 손으로 config.REF_DIR 로: {left}")
    print(f"옮김: {sum(1 for _ in PKG.rglob('*.py'))}개 모듈 · {sum(1 for _ in TESTS.glob('*.py'))}개 테스트 파일 (원본 {crawler})")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("사용법: python scripts/sync_seoulevent.py <crawler 폴더>")
    sync(Path(sys.argv[1]))
