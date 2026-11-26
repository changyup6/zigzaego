"""OCR 방식별 줄 정확도를 정답 폴더와 비교해 마크다운 표로 찍는다. 지라 S15P31A308-109.

.venv/Scripts/python.exe scripts/ocr/evaluate.py --gold 정답폴더 --pred 방식A폴더 [--pred 방식B폴더 ...]

하위 폴더까지 찾고, 정답 폴더 기준 상대 경로가 같은 *.json 끼리 짝짓는다. 예측 파일이 없는 정답은 예측 0줄로 센다.
정답은 종류(type)별로 품목 줄로 바꾼다(gold_items). chat·tax_invoice_list·ledger 는 빼고 표 아래에 장수를 찍는다.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))  # PYTHONPATH 없이도 weglee_ai 를 찾게

from weglee_ai.ocr.grading import count_dry_run, load_gold, pair_docs, summarize  # noqa: E402


def _pct(x) -> str:
    return "-" if x is None else f"{x * 100:.1f}%"


def _row(label: str, s: dict) -> str:
    return (f"| {label} | {s['docs']} | {s['n_gold']} | {_pct(s['line_acc'])} "
            f"| {_pct(s['printed_acc'])} ({s['printed_lines']}) | {_pct(s['handwritten_acc'])} ({s['handwritten_lines']}) "
            f"| {_pct(s['name_acc'])} | {_pct(s['qty_acc'])} | {_pct(s['price_acc'])} | {s['extra']} | {_pct(s['extra_rate'])} |")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", required=True, help="정답 JSON 폴더 (하위 폴더까지)")
    parser.add_argument("--pred", required=True, action="append", help="방식별 예측 JSON 폴더 (여러 번 줄 수 있다)")
    args = parser.parse_args()

    golds, excluded = load_gold(args.gold)
    if not golds:
        parser.error(f"채점할 정답 JSON 이 없다: {args.gold}")
    types = sorted({t for _, t, _ in golds})

    print("| 방식 | 장수 | 정답 줄 | 줄 정확도 | 인쇄 | 손글씨 | 품목명 | 수량 | 단가 | 남는 줄 | 추가 줄 비율 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for pred_dir in args.pred:
        name = Path(pred_dir).name
        pairs = pair_docs(golds, pred_dir)
        print(_row(name, summarize([(g, p) for _, g, p in pairs])))
        for t in types:
            print(_row(f"{name} · {t}", summarize([(g, p) for tt, g, p in pairs if tt == t])))
    print()
    print(f"채점 대상 {len(golds)}장")
    if excluded:
        detail = " · ".join(f"{t} {n}" for t, n in sorted(excluded.items()))
        print(f"제외 {sum(excluded.values())}장 ({detail})")
    for pred_dir in args.pred:
        n = count_dry_run(pred_dir)
        if n:
            print(f"경고: dry-run 가짜 결과 {n}장 포함 - 실제 성적 아님 ({Path(pred_dir).name})")


if __name__ == "__main__":
    main()
