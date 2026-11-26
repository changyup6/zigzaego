"""데모 출력 + 범용 재료표 → 질문 목록을 찍는다.

python scripts/onboarding/demo_questions.py

prompts/onboarding/examples/ 의 demo_extraction.json (구성 추출) 과 demo_classify.json (표에 없는 재료 분류) 을 읽고,
표가 먼저 · 없으면 분류 출력 순서로 재료 정보를 정한 뒤 questions.py 규칙으로 질문을 만든다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

AI = Path(__file__).resolve().parents[2]
try:
    from weglee_ai.onboarding.catalog import load_catalog, resolve
except ImportError:  # PYTHONPATH=src 없이 실행했을 때
    sys.path.insert(0, str(AI / "src"))
    from weglee_ai.onboarding.catalog import load_catalog, resolve

from weglee_ai.onboarding.questions import build_questions, question_limit  # noqa: E402

EXAMPLES = AI / "prompts" / "onboarding" / "examples"


def main() -> int:
    extraction = json.loads((EXAMPLES / "demo_extraction.json").read_text(encoding="utf-8"))
    classified = json.loads((EXAMPLES / "demo_classify.json").read_text(encoding="utf-8"))["items"]
    catalog = load_catalog()
    names = list(dict.fromkeys(c["name"] for m in extraction["menus"] for c in m["components"]))
    infos = resolve(names, classified, catalog)

    n_menus = len(extraction["menus"])
    in_catalog = sum(catalog.lookup(n) is not None for n in names)
    print(f"메뉴 {n_menus}개 · 재료 {len(names)}개 (재료표 {in_catalog} · LLM 분류 {len(names) - in_catalog})")
    for m in extraction["menus"]:
        parts = ", ".join(f"{c['name']}×{c['mult']:g}" if c["mult"] != 1 else c["name"] for c in m["components"])
        print(f"  {m['menu']} ({len(m['components'])}): {parts}")

    questions = build_questions(extraction, infos)
    print(f"질문 {len(questions)}개 (상한 {question_limit(n_menus)}개)")
    for i, q in enumerate(questions, 1):
        opts = " / ".join(str(o) for o in q.options)
        print(f"  {i}. {q.text} [{q.unit}] 보기 {opts} · 확신 {q.confidence:g} · 메뉴 {len(q.menus)}개")
    return 0


if __name__ == "__main__":
    sys.exit(main())
