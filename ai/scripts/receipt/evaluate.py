"""영수증 예측 채점: 줄 정확도(109 채점기) · 매칭 정확도(§3.2) · 포장 맞음 · 추가 줄 비율 · 토큰 · 초. 지라 S15P31A308-110.

.venv/Scripts/python.exe scripts/receipt/evaluate.py --gold 정답폴더 --pred 예측폴더 [--pred ...] [--details]

정답 폴더 기준 상대 경로가 같은 *.json 끼리 짝짓는다(예측이 없으면 0줄). --details 는 짝지어진 줄마다 정답 · 예측 재료 · 포장을 찍는다.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))  # PYTHONPATH 없이도 weglee_ai 를 찾게

from weglee_ai.ocr.grading import count_dry_run, load_gold, match_lines, pair_docs  # noqa: E402
from weglee_ai.receipt.scoring import match_ok, pack_ok, summarize  # noqa: E402


def _pct(x) -> str:
    return "-" if x is None else f"{x * 100:.1f}%"


def _num(x, fmt="{:.0f}") -> str:
    return "-" if x is None else fmt.format(x)


def _pack(p) -> str:
    return "-" if not p else f"{p['qty']:g} {p['unit']}"


def _who(it) -> str:
    if it.get("missing"):
        return "(매칭 답 없음)"
    if it.get("ingredient"):
        return it["ingredient"]
    return "(새 주기형)" if it.get("is_new_periodic") else "(재료 아님)"


# 정형화 출처. normalize_source 가 없으면(직접 · 저장본 normalize_cached) gemini. gemini-reuse = 1회차 Gemini 정형화를
# --normalized-dir 로 다시 넘긴 것(10/26 2회차) — 매칭 프롬프트가 달라 따로 센다. 장수 줄은 1장 이상인 출처만 찍는다.
SOURCES = ("gemini", "claude-vision", "gemini-reuse")


def _source(pred_dir, rel, pred) -> str:
    if not (Path(pred_dir) / rel).exists():
        return "예측 없음"
    return pred.get("normalize_source") or "gemini"


def _print_sources(pred_dir, golds, pairs) -> None:
    """정형화 출처 장수 한 줄 + 출처별 줄 · 매칭 · 포장 표(전체 행 + 출처별 행). 매칭 모델은 '모델' 줄 그대로."""
    groups = {}
    for (rel, _, _), (_, g, p) in zip(golds, pairs, strict=False):
        groups.setdefault(_source(pred_dir, rel, p), []).append((g, p))
    name = Path(pred_dir).name
    counts = [f"{k} {len(groups[k])}장" for k in SOURCES if groups.get(k)]
    counts += [f"{k} {len(v)}장" for k, v in groups.items() if k not in SOURCES]
    print(f"정형화 출처 ({name}): {' · '.join(counts)}")
    print()
    print(f"| 정형화 출처 ({name}) | 장수 | 정답 줄 | 줄 정확도 | 매칭 대상 | 매칭 정확도 | 포장 대상 | 포장 맞음 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|")
    rows = [("전체", [(g, p) for _, g, p in pairs])]
    rows += [(k, groups[k]) for k in SOURCES if k in groups]
    rows += [(k, v) for k, v in groups.items() if k not in SOURCES]
    for label, sub in rows:
        s = summarize(sub)
        print(f"| {label} | {s['docs']} | {s['n_gold']} | {_pct(s['line_acc'])} | {s['match_n']} "
              f"| {_pct(s['match_acc'])} | {s['pack_n']} | {_pct(s['pack_acc'])} |")
    print()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gold", required=True, help="정답 JSON 폴더 (하위 폴더까지)")
    parser.add_argument("--pred", required=True, action="append", help="예측 JSON 폴더 (여러 번 줄 수 있다)")
    parser.add_argument("--details", action="store_true", help="짝지어진 줄마다 재료 · 포장 비교를 찍는다")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):  # 콘솔(cp949)이 못 쓰는 글자(≈ 등)는 '?' 로 — 표 출력이 멈추지 않게
        sys.stdout.reconfigure(errors="replace")

    golds, excluded = load_gold(args.gold)
    if not golds:
        parser.error(f"채점할 정답 JSON 이 없다: {args.gold}")

    print("| 예측 | 장수 | 정답 줄 | 줄 정확도 | 매칭 대상 | 매칭 정확도 | 매칭 빠진 줄 | 포장 대상 | 포장 맞음 | 남는 줄 "
          "| 추가 줄 비율 | 호출 | 호출당 토큰 | 장당 초 |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    paired = {}
    for pred_dir in args.pred:
        pairs = pair_docs(golds, pred_dir)
        paired[pred_dir] = pairs
        s = summarize([(g, p) for _, g, p in pairs])
        print(f"| {Path(pred_dir).name} | {s['docs']} | {s['n_gold']} | {_pct(s['line_acc'])} | {s['match_n']} "
              f"| {_pct(s['match_acc'])} | {s['match_missing']} | {s['pack_n']} | {_pct(s['pack_acc'])} | {s['extra']} "
              f"| {_pct(s['extra_rate'])} | {s['calls']} | {_num(s['tokens_per_call'])} "
              f"| {_num(s['seconds_per_doc'], '{:.1f}')} |")
    print()
    print(f"채점 대상 {len(golds)}장")
    if excluded:
        print(f"제외 {sum(excluded.values())}장 ({' · '.join(f'{t} {n}' for t, n in sorted(excluded.items()))})")
    for pred_dir in args.pred:
        # 예측 파일이 없는 장(하루 한도 · 파싱 실패)은 0줄로 짝지어져 매칭 · 포장 분모에서 빠진다
        no_pred = [rel for rel, _, _ in golds if not (Path(pred_dir) / rel).exists()]
        print(f"예측 있는 장 {len(golds) - len(no_pred)}/{len(golds)} ({Path(pred_dir).name})")
        if no_pred:
            print(f"경고: 예측 없는 장 {len(no_pred)}장은 줄 정확도에선 틀림, 매칭 · 포장 분모에선 빠진다 - "
                  f"{', '.join(no_pred[:10])}{' …' if len(no_pred) > 10 else ''}")
        models = sorted({p.get("model") for _, _, p in paired[pred_dir] if p.get("model")})
        if models:
            print(f"모델 ({Path(pred_dir).name}): {', '.join(models)}")
        n = count_dry_run(pred_dir)
        if n:
            print(f"경고: dry-run 가짜 결과 {n}장 포함 - 실제 성적 아님 ({Path(pred_dir).name})")
    # 정형화 출처 표는 모델 · 경고 줄 뒤에 — run.sh 의 head -12 가 이어 하기 상태 줄을 놓치지 않게
    for pred_dir in args.pred:
        _print_sources(pred_dir, golds, paired[pred_dir])

    if args.details:
        for pred_dir in args.pred:
            print()
            print(f"### 줄별 ({Path(pred_dir).name})")
            print("| 사진 | 정답 품목 | 정답 재료 | 예측 재료 | 매칭 | 정답 포장 | 예측 포장 | 포장 | 근거 |")
            print("|---|---|---|---|---|---|---|---|---|")
            for (rel, _, _), (_, gold, pred) in zip(golds, paired[pred_dir], strict=False):
                gi, pi = gold.get("items") or [], pred.get("items") or []
                for i, j in match_lines(gi, pi):
                    if j is None:
                        print(f"| {rel} | {gi[i].get('name')} | {_who(gi[i])} | (줄 놓침) | - | "
                              f"{_pack(gi[i].get('pack'))} | - | - | |")
                        continue
                    g, p = gi[i], pi[j]
                    pk = "-" if not g.get("pack") else ("O" if pack_ok(g["pack"], p.get("pack")) else "X")
                    print(f"| {rel} | {g.get('name')} | {_who(g)} | {_who(p)} | {'O' if match_ok(g, p) else 'X'} "
                          f"| {_pack(g.get('pack'))} | {_pack(p.get('pack'))} | {pk} | {p.get('reason', '')} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
