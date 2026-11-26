"""온보딩 레시피 자동 세팅 채점 — F1 · 1인분 MAPE · 질문 수 (평가 지표 정의 §2.1 ~ 2.3).

python scripts/onboarding/score.py <result.json> <truth.csv> [--catalog seed/ingredient_catalog.csv]

result.json  온보딩 job 의 result_json. 아래 칸을 읽는다
  menus      구성 추출 출력 (menu_extraction.schema.json 의 menus). extraction.menus 도 받는다
  items      표에 없는 재료의 분류 출력 (ingredient_classify.schema.json 의 items). classify.items 도 받는다
  answers    (선택) {재료 이름: 사장님이 고른 1인분}. 있으면 초기값 대신 쓴다
  questions  (선택) 실제로 만든 질문 목록. 없으면 questions.py 규칙으로 다시 만든다
truth.csv    정답 레시피. 아래 "정답 파일 작성 규칙"

정답 파일 작성 규칙 (UTF-8 CSV, 머리줄 필수)
  열        menu,ingredient,qty,unit[,aliases][,menu_aliases][,excluded]
  menu          메뉴 이름. 메뉴판 표기 그대로
  ingredient    재료 이름. 한 메뉴에 같은 재료는 한 줄
  qty,unit      1인분 양과 단위 (g · kg · ml · l · 개 · 마리 · 알 · 장. 큰술 같은 단위는 오차 100% 로 센다)
  aliases       (선택) 같은 재료로 칠 다른 이름, ';' 구분 (예: 소고기 줄에 "불고기;소불고기")
  menu_aliases  (선택) 같은 메뉴로 칠 다른 이름, ';' 구분 (예: 불고기 피자 줄에 "소불고기 피자")
  excluded      (선택) 1 또는 true 면 그 (메뉴, 재료) 를 F1 · MAPE 양쪽에서 뺀다 (예측에 있어도 안 센다)
  - 소스는 재료로 쪼개지 않고 "○○ 소스" 한 줄로 적는다 (구성 추출 프롬프트도 한 줄로 낸다)
  - 소스 원재료(소스 안에 든 마늘 · 버터 등)와 주기형 품목(CYCLE: 포장 용기 · 세제처럼 메뉴와 상관없이
    주기로 사는 것)은 적지 않거나, 적으면 excluded 를 1 로 둔다

정답 파일이 없으면 "정답 파일 없음: <경로>", 결과 파일이 없으면 "결과 파일 없음: <경로>" 를 찍고 종료코드 2.
별칭 하나가 두 이름에 걸리거나 별칭이 다른 메뉴 · 재료의 실제 이름과 같으면 "정답 파일 오류: 채점 별칭 충돌 …" 을 찍고 종료코드 2.
변환표에 없는 단위(큰술 등)는 단위 불일치(오차 100%)로 세고 경고 한 줄을 찍는다.
분류 출력이 허용 값 밖인 재료("분류 결과 무효")는 이름 · 사유를 찍고 MAPE 에서 빼되 F1 에는 남긴다.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

try:
    from weglee_ai.onboarding.catalog import IngredientInfo, load_catalog, resolve
except ImportError:  # PYTHONPATH=src 없이 실행했을 때
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))
    from weglee_ai.onboarding.catalog import IngredientInfo, load_catalog, resolve

from weglee_ai.onboarding.eval import Serving, menu_f1, question_count_ok, serving_mape
from weglee_ai.onboarding.questions import build_questions, question_limit


def _pick(data: dict, key: str, nested: str):
    if key in data:
        return data[key]
    return (data.get(nested) or {}).get(key, [])


def _split(value: str | None) -> list[str]:
    return [a.strip() for a in (value or "").split(";") if a.strip()]


def _read_truth(path: Path):
    pairs, servings, aliases, menu_aliases, excluded = [], {}, {}, {}, []
    with path.open(encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            pair = (r["menu"].strip(), r["ingredient"].strip())
            aliases.setdefault(pair[1], set()).update(_split(r.get("aliases")))
            menu_aliases.setdefault(pair[0], set()).update(_split(r.get("menu_aliases")))
            if (r.get("excluded") or "").strip().lower() in ("1", "true"):
                excluded.append(pair)
                continue
            pairs.append(pair)
            servings[pair] = Serving(float(r["qty"]), r["unit"].strip())
    return pairs, servings, aliases, menu_aliases, excluded


def _pct(v: float | None) -> str:
    return "측정 불가" if v is None else f"{v * 100:.1f}%"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("result")
    parser.add_argument("truth")
    parser.add_argument("--catalog", default=None)
    args = parser.parse_args(argv)

    result_path = Path(args.result)
    if not result_path.is_file():
        print(f"결과 파일 없음: {result_path}")
        return 2
    truth_path = Path(args.truth)
    if not truth_path.is_file():
        print(f"정답 파일 없음: {truth_path}")
        return 2
    true_pairs, true_servings, aliases, menu_aliases, excluded = _read_truth(truth_path)

    data = json.loads(result_path.read_text(encoding="utf-8"))
    menus = _pick(data, "menus", "extraction")
    items = _pick(data, "items", "classify")
    answers = data.get("answers") or {}
    catalog = load_catalog(args.catalog)

    infos, unresolved = {}, []
    for name in dict.fromkeys(c["name"] for m in menus for c in m["components"]):
        try:
            infos.update(resolve([name], items, catalog))
        except KeyError:
            unresolved.append(name)

    pred_pairs, pred_servings = [], {}
    for m in menus:
        for c in m["components"]:
            pair = (m["menu"], c["name"])
            pred_pairs.append(pair)
            info = infos.get(c["name"])
            if info is None or info.invalid_reason is not None:
                continue
            per = float(answers.get(c["name"], info.per_serving))
            prev = pred_servings.get(pair)
            qty = per * c["mult"] + (prev.qty if prev else 0)
            pred_servings[pair] = Serving(qty, info.unit, info.kind)

    if "questions" in data:
        n_questions = len(data["questions"])
    else:
        n_questions = len(build_questions({"menus": menus}, {**infos, **{n: _missing(n) for n in unresolved}}))

    try:
        f1 = menu_f1(pred_pairs, true_pairs, aliases, menu_aliases=menu_aliases, excluded=excluded)
        mape = serving_mape(pred_servings, true_servings, aliases, menu_aliases=menu_aliases, excluded=excluded)
    except ValueError as e:
        print(f"정답 파일 오류: {e}")
        return 2
    invalid = [(n, i.invalid_reason) for n, i in infos.items() if i.invalid_reason is not None]
    n_menus = len(menus)

    print(f"재료 구성 F1   {f1.f1:.3f}  (정밀도 {f1.precision:.3f} · 재현율 {f1.recall:.3f} · TP {f1.tp} FP {f1.fp} FN {f1.fn})  기준 >= 0.85")
    print(f"정답에 없는 메뉴 {f1.dropped_menus}개 (양쪽에서 빼고 셈)")
    print("메뉴별 F1")
    for menu, value in sorted(f1.per_menu.items(), key=lambda kv: kv[1]):
        print(f"  {value:.3f}  {menu}")
    print(
        f"1인분 MAPE    {_pct(mape.mape)}  가중 오차 {_pct(mape.weighted)}"
        f"  (쌍 {mape.n_pairs}개 · 단위 불일치 {mape.n_unit_mismatch}개)  기준 <= 15%"
    )
    if mape.unknown_units:
        print(f"경고: 모르는 단위 {', '.join(mape.unknown_units)} — 단위 불일치(오차 100%)로 셈")
    verdict = "통과" if question_count_ok(n_questions, n_menus) else "초과"
    print(f"질문 수       {n_questions}개 / 상한 {question_limit(n_menus)}개 (메뉴 {n_menus}개)  {verdict}")
    if not answers:
        print("주의: answers 가 없어 MAPE 는 질문 답변 전 초기값 기준이다 (§2.3 은 답변 후)")
    if excluded:
        print(f"정답에서 제외한 줄 {len(excluded)}개 (excluded, F1 · MAPE 양쪽에서 빠짐)")
    for name, reason in invalid:
        print(f"분류 결과 무효 (MAPE 에서 빠짐, F1 에는 남음): {name} — {reason}")
    if unresolved:
        print(f"분류 결과가 없는 재료 {len(unresolved)}개 (MAPE 에서 빠짐): {', '.join(unresolved)}")
    return 0


def _missing(name: str) -> IngredientInfo:
    # 분류가 빠진 재료는 질문 대상이 아니라고 보고 센다 (확신도 1)
    return IngredientInfo(name, "BOM", "G", "AMBIENT", 1.0, 1.0)


if __name__ == "__main__":
    sys.exit(main())
