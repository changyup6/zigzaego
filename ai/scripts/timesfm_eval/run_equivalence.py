"""동등성 검정 — 저장된 TimesFM 채택 검증 예측표만 읽어 TimesFM 대 요일 평균(팀 기준선)을 판정한다.

TimesFM 을 다시 돌리지 않는다(batch 그룹 불필요). 데이터셋별 예측표(`*_predictions.csv`)는 읽기만 한다.
    cd ai
    uv run python scripts/timesfm_eval/run_equivalence.py                 # 기본 --pred-dir = outputs/timesfm_adoption
    uv run python scripts/timesfm_eval/run_equivalence.py --pred-dir D:/다른/폴더 --no-report
결과 표를 화면에 찍고 --pred-dir 폴더의 timesfm_adoption.md 표시 주석 사이(없으면 맨 끝)에 '동등성 검정' 절로 넣는다.
예측표가 같은 12주·전체 줄(시험 전 기록이 12주보다 짧은 데이터)은 '12주(=전체)' 한 줄로 합친다.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

from weglee_ai.timesfm_eval import adoption as ta
from weglee_ai.timesfm_eval import equivalence as eq

ROOT = Path(__file__).resolve().parents[2]  # ai/
PRED_DIR = ROOT / "outputs" / "timesfm_adoption"
REPORT_NAME = "timesfm_adoption.md"
REPORT = PRED_DIR / REPORT_NAME
START, END = eq.REPORT_START, eq.REPORT_END
DATASETS = (("recruit", "Recruit"), ("freshretail", "FreshRetailNet-50K"))
METHOD_ORDER = {ta.METHOD_WINDOW: 0, ta.METHOD_ACCUM: 1}
SAME_12W = "12주(=전체)"
HIST_ORDER = {"1주": 0, "2주": 1, "4주": 2, "12주": 3, SAME_12W: 3, "전체": 4}


def table(pred_dir: Path) -> pd.DataFrame:
    rows = []
    for key, label in DATASETS:
        p = pd.read_csv(pred_dir / f"{key}_predictions.csv", encoding="utf-8")
        p["series"] = p["series"].astype(str)
        groups = [("전체", p)]
        zb = pred_dir / f"{key}_zero_bins.csv"
        if zb.exists():
            bins = pd.read_csv(zb, encoding="utf-8").astype({"series": str}).set_index("series")["zero_bin"]
            p = p.assign(zero_bin=p["series"].map(bins))
            groups += [(lab, p[p["zero_bin"] == lab]) for lab, _, _ in ta.ZERO_BINS]
        for gname, g in groups:
            errs = {}
            for (method, mode, hist), h in g.groupby(["method", "mode", "history"], sort=False):
                errs[(method, mode, hist)] = (eq.daily_errors(h, model="timesfm", base="baseline"), h["series"].nunique())
            for (method, mode, hist), (d, n_series) in errs.items():
                if hist == "전체" and _same_as_12w(errs, method, mode):
                    continue  # 12주 줄에 합쳤다
                if hist == "12주" and _same_as_12w(errs, method, mode):
                    hist = SAME_12W
                r = eq.equivalence_test(d["model_err"].to_numpy(), d["base_err"].to_numpy())
                rows.append({"dataset": label, "group": gname, "method": method, "mode": mode, "history": hist,
                             "n_series": n_series, "n_days": r.n_days,
                             "rel_mean": r.rel_mean_diff, "rel_lo": r.rel_ci_low, "rel_hi": r.rel_ci_high,
                             "mean_diff": r.mean_diff, "ci_low": r.ci_low, "ci_high": r.ci_high,
                             "margin_abs": r.margin_abs, "verdict": r.verdict, "exploratory": r.exploratory,
                             "block": r.block, "n_boot": r.n_boot, "seed": r.seed})
    t = pd.DataFrame(rows)
    t["_m"] = t["method"].map(METHOD_ORDER)
    t["_h"] = t["history"].map(HIST_ORDER)
    t["_d"] = t["dataset"].map({lab: i for i, (_, lab) in enumerate(DATASETS)})
    t["_g"] = t["group"].map({"전체": 0, **{lab: i + 1 for i, (lab, _, _) in enumerate(ta.ZERO_BINS)}})
    return t.sort_values(["_m", "_d", "_g", "mode", "_h"], kind="stable").drop(
        columns=["_m", "_h", "_d", "_g"]).reset_index(drop=True)


def _same_as_12w(errs: dict, method: str, mode: str) -> bool:
    """같은 방식·채널 방식에서 12주와 전체의 하루 오차 배열이 똑같은가(= 같은 예측표). 둘 중 하나라도 없으면 False."""
    a, b = errs.get((method, mode, "12주")), errs.get((method, mode, "전체"))
    if a is None or b is None or a[1] != b[1]:
        return False
    cols = ["date", "model_err", "base_err"]
    return a[0][cols].reset_index(drop=True).equals(b[0][cols].reset_index(drop=True))


def _pct(x: float) -> str:
    return f"{x * 100:+.1f}%"


def _md_rows(t: pd.DataFrame) -> list[str]:
    """표 줄. 모든 줄이 탐색적이면 머리에 한 번만 '(탐색적)', 섞여 있으면 해당 줄 판정 뒤에 붙인다."""
    all_expl = bool(len(t)) and bool(t["exploratory"].all())
    head = "판정 (탐색적)" if all_expl else "판정"
    out = ["| 데이터 | 채널 방식 | 계열 묶음 | 이력 | 계열·날 | 평균 차이 (비율) | 90% 구간 (비율) "
           f"| 평균 차이 (하루 오차, 절대) | 동등 범위 (절대) | {head} |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for r in t.itertuples(index=False):
        grp = "전체" if r.group == "전체" else f"0 비율 {r.group}"
        mark = " (탐색적)" if r.exploratory and not all_expl else ""
        out.append(f"| {r.dataset} | {r.mode} | {grp} | {r.history} | {r.n_series}개·{r.n_days}일 | {_pct(r.rel_mean)} | "
                   f"[{_pct(r.rel_lo)}, {_pct(r.rel_hi)}] | {r.mean_diff:+,.1f} | ±{r.margin_abs:,.1f} | **{r.verdict}**{mark} |")
    return out


def section(t: pd.DataFrame) -> str:
    b, n, s = int(t["block"].iloc[0]), int(t["n_boot"].iloc[0]), int(t["seed"].iloc[0])
    min_days = eq.MIN_BLOCKS_CONFIRM * b
    lines = [START, "## 동등성 검정 — 차이가 우연인지까지 본 판정", "",
             "위 판정은 오차율이 '조금이라도 낮은지' 만 봤다. 이 절은 결과를 보기 전에 정해 둔 동등성 검정으로 "
             "TimesFM 과 요일 평균(팀 기준선)이 **실질적으로 같은지 / 의미 있게 나은지 / 의미 있게 못한지** 를 판정한다. "
             "위 표의 숫자와 판정은 바꾸지 않았다. `scripts/timesfm_eval/run_equivalence.py` 가 저장된 예측표만 읽어 계산했다(TimesFM 재실행 없음).", "",
             f"**이 절의 판정 {len(t)}개는 다중 비교 보정을 하지 않았다.** 칸마다 따로 낸 서술적 결과로 읽고, 여러 줄을 묶어 "
             "'대체로 낫다' 같은 결론을 내지 않는다. 0 비율 구간 줄은 '전체' 줄 계열의 부분집합이라 서로 독립이 아니다. "
             f"예측표가 똑같은 12주·전체 줄은 `{SAME_12W}` 한 줄로 합쳤다.", "",
             "### 어떻게 쟀나", "",
             "- **하루 오차**: 그날 모든 계열(가게·상품)의 |실제 − 예측| 합. WAPE 의 분자와 같다.",
             "- **하루 오차 차이**: TimesFM 하루 오차 − 기준선 하루 오차. 음수면 그날 TimesFM 이 덜 틀렸다.",
             "- **평균 차이 (비율)**: 하루 오차 차이의 평균 ÷ 기준선 평균 하루 오차. "
             "= (TimesFM WAPE − 기준선 WAPE) ÷ 기준선 WAPE, 즉 위 표 개선율의 부호를 뒤집은 값이다.",
             f"- **90% 구간**: {b}일 블록 부트스트랩 — 연속 {b}일 묶음을 무작위로 골라 이어 붙여 시험 기간과 같은 길이의 "
             f"가짜 기간을 만들고, 그 평균 차이를 {n:,}번 계산해 가운데 90% 가 들어가는 범위. 시드 {s}. "
             "7일 묶음으로 뽑는 건 요일 패턴을 깨지 않으려고다. 묶음은 겹쳐도 된다(어느 날에서든 시작).",
             "- **동등 범위**: 기준선 평균 하루 오차의 ±10% (결과를 보기 전에 정해 둔 값). 10% 라는 폭에는 근거가 없다 — "
             "업계 기준이나 사장님 발주 허용 오차에서 온 값이 아니라 관례적으로 고른 값이다.",
             "", "### 판정 규칙 (결과를 보기 전에 고정)", "",
             f"- 90% 구간 전체가 −10% ~ +10% 안이고 0 을 포함 → `{eq.V_SAME}`: TimesFM 은 의미 있게 낫지도 못하지도 않다.",
             f"- 구간 전체가 −10% ~ +10% 안이지만 0 보다 큼 → `{eq.V_TOL_WORSE}`: TimesFM 이 확실히 더 틀리지만 그 차이가 ±10% 안.",
             f"- 구간 전체가 −10% ~ +10% 안이지만 0 보다 작음 → `{eq.V_TOL_BETTER}`: TimesFM 이 확실히 덜 틀리지만 그 차이가 ±10% 안.",
             "- 구간 위끝이 −10% 보다 작음 → `낫다`: TimesFM 이 오차를 10% 넘게 줄인다고 말할 수 있다.",
             "- 구간 아래끝이 +10% 보다 큼 → `못하다`: TimesFM 이 오차를 10% 넘게 늘린다고 말할 수 있다.",
             "- 그 밖(구간이 ±10% 경계에 걸침) → `판단 불가`: 자료가 더 필요하다.",
             "- 90% 구간을 쓰면 양쪽 경계를 각각 5% 수준으로 검정하는 TOST(두 번의 한쪽 검정)와 같은 결론이 된다.",
             "- 허용 범위 내(열세·우세) 이름은 결과를 본 다음 `같다(동등)` 를 0 포함 여부로 나눈 것이다. "
             "판정 경계 자체는 바꾸지 않았다. 채택 규칙(엄격 기준 5% 이상 개선)과 달리 이 검정은 ±10% 안의 열세를 허용하므로 "
             "`같다(동등)` 를 '써도 된다' 로 읽으면 안 된다.",
             f"- {b}일 블록이 {eq.MIN_BLOCKS_CONFIRM}개({min_days}일) 미만이면 판정은 **탐색적**이다(블록이 적어 구간을 믿기 어렵다).",
             "", "### 표 읽는 법", "",
             "- 한 줄 = 데이터 하나 × 채널 방식 × 계열 묶음 × 이력 길이. 계열·날 = 계열 수와 채점한 날 수(시험 기간 길이).",
             "- '평균 차이 (비율)' 가 −5.0% 면 TimesFM 이 기준선보다 하루 오차를 평균 5% 덜 냈다는 뜻.",
             "- '90% 구간' 이 [−8%, −2%] 면 우연을 감안해도 차이가 대략 그 사이라는 뜻. ±10% 안이고 0 을 포함하지 않으므로 "
             f"`{eq.V_TOL_BETTER}`. [−3%, +2%] 처럼 0 을 포함하면 `{eq.V_SAME}`.",
             "- '평균 차이 (절대)' 와 '동등 범위 (절대)' 는 같은 내용을 하루 오차 단위(Recruit 는 방문객 수, FreshRetail 은 판매량)로 적은 것.",
             "- 시험 기간이 Recruit 42일, FreshRetail 28일뿐이라 7일 묶음이 6개·4개 길이밖에 안 된다. 구간이 넓게 나와 `판단 불가` 가 많을 수 있고, "
             "반대로 블록이 적어 구간이 실제보다 좁게 나올 수도 있다.",
             "- 날짜 사이 상관이 7일보다 길게 이어지면 7일 묶음으로는 그 상관이 끊겨 구간이 실제보다 좁게 나올 수 있다.",
             "", "### 고정 창 방식 (주 판정)", ""]
    lines += _md_rows(t[t["method"] == ta.METHOD_WINDOW])
    lines += ["", "### 쌓이는 방식 (보조)", ""]
    lines += _md_rows(t[t["method"] == ta.METHOD_ACCUM])
    lines += ["", "- 이 검정도 공개 데이터 결과다. 위글리 실데이터에서의 채택 결론은 내릴 수 없다.", END]
    return "\n".join(lines)


def write_section(text: str, path: Path = REPORT) -> None:
    body = path.read_text(encoding="utf-8") if path.exists() else ""
    span = eq.find_report_section(body)  # 짝 맞은 마지막 절만 바꾼다(run_adoption 의 절 옮기기와 같은 규칙)
    if span:
        body = body[:span[0]] + text + body[span[1]:]
    else:
        body = body.rstrip("\n") + "\n\n" + text + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--pred-dir", default=str(PRED_DIR), help="timesfm_adoption 예측표 폴더 (읽기만)")
    ap.add_argument("--no-report", action="store_true", help="리포트를 고치지 않고 화면에만")
    a = ap.parse_args(argv)
    t = table(Path(a.pred_dir))
    show = t.assign(rel_mean=t["rel_mean"].map(_pct), rel_lo=t["rel_lo"].map(_pct), rel_hi=t["rel_hi"].map(_pct))
    cols = ["dataset", "mode", "group", "method", "history", "n_series", "n_days", "rel_mean", "rel_lo", "rel_hi",
            "mean_diff", "ci_low", "ci_high", "margin_abs", "verdict", "exploratory"]
    with pd.option_context("display.width", 250, "display.max_columns", 30, "display.max_rows", 200,
                           "display.float_format", "{:,.1f}".format):
        print(show[cols].to_string(index=False))
    print(f"block={int(t['block'].iloc[0])} n_boot={int(t['n_boot'].iloc[0])} seed={int(t['seed'].iloc[0])}")
    if not a.no_report:
        report = Path(a.pred_dir) / REPORT_NAME  # --pred-dir 을 바꾸면 리포트도 그 폴더의 것을 고친다
        write_section(section(t), report)
        print(f"리포트 절 갱신: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
