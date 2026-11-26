"""섞기 실험 — 저장된 예측값(outputs/timesfm_adoption/*_predictions.csv)만으로 기준선 × TimesFM 섞기를 주 단위로 굴려 잰다.

    cd ai
    uv run python scripts/timesfm_eval/run_blend.py

TimesFM 을 다시 돌리지 않는다(batch 그룹 불필요). recruit · freshretail 둘 다 run_adoption.py 로 먼저 돌려 둬야 한다.
결과: outputs/timesfm_adoption/timesfm_blend.md · blend_units.csv · blend_weeks.csv. 본 리포트(timesfm_adoption.md)는 고치지 않는다.
판정은 탐색적이다 — 같은 시험 기간 안에서 규칙을 정하고 잰 것이라 독립 확인이 아니다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from weglee_ai.timesfm_eval import adoption as ta
from weglee_ai.timesfm_eval import blend as bl

ROOT = Path(__file__).resolve().parents[2]  # ai/
OUT = ROOT / "outputs" / "timesfm_adoption"
REPORT = OUT / "timesfm_blend.md"
DATASETS = {"recruit": "Recruit (일본 음식점 일별 방문객)", "freshretail": "FreshRetailNet-50K (매장×상품 일별 판매)"}
METHOD_ORDER = (ta.METHOD_WINDOW, ta.METHOD_ACCUM)
METHOD_SHORT = {ta.METHOD_WINDOW: "고정 창", ta.METHOD_ACCUM: "쌓이는"}
HIST_ORDER = {ta.METHOD_WINDOW: [ta.history_label(w) for w in ta.WINDOW_WEEKS],
              ta.METHOD_ACCUM: [ta.history_label(w) for w in ta.HISTORY_WEEKS]}
BIN_ORDER = ["전체"] + [b for b, _, _ in ta.ZERO_BINS]


def load(dataset: str) -> tuple[pd.DataFrame, pd.Timestamp, pd.Timestamp]:
    p = pd.read_csv(OUT / f"{dataset}_predictions.csv", parse_dates=["date"])
    meta = json.loads((OUT / f"{dataset}_meta.json").read_text(encoding="utf-8"))
    start, end = pd.Timestamp(meta["test_start"]), pd.Timestamp(meta["test_end"])
    if dataset == "freshretail":
        zb = pd.read_csv(OUT / "freshretail_zero_bins.csv")
        p = p.merge(zb, on="series", how="left")
        if p["zero_bin"].isna().any():
            raise SystemExit("0 비율 구간이 없는 계열이 있다")
    else:
        p["zero_bin"] = "전체"
    return p, start, end


def check_against_summary(dataset: str, p: pd.DataFrame) -> float:
    """w=0·1 의 시험 기간 전체 WAPE 가 본 실험 summary 의 기준선·TimesFM WAPE 와 같은지(최대 차이)."""
    summ = pd.read_csv(OUT / f"{dataset}_summary.csv")
    worst = 0.0
    for (method, mode, hist, zb), g in p.groupby(["method", "mode", "history", "zero_bin"]):
        r = summ[(summ["method"] == method) & (summ["mode"] == mode) & (summ["history"] == hist)
                 & (summ["zero_bin"] == zb)]
        if len(r) != 1 or int(r.iloc[0]["n_rows"]) != len(g):
            raise SystemExit(f"summary 와 짝이 안 맞음: {dataset} {method} {hist} {zb}")
        a = g["actual"].to_numpy(float)
        worst = max(worst, abs(bl.wape(a, bl.blend(g["baseline"], g["timesfm"], 0.0)) - r.iloc[0]["baseline_wape"]),
                    abs(bl.wape(a, bl.blend(g["baseline"], g["timesfm"], 1.0)) - r.iloc[0]["timesfm_wape"]))
    return worst


def run() -> int:
    units, weeks_all, info = [], [], {}
    for d in DATASETS:
        p, start, end = load(d)
        diff = check_against_summary(d, p)
        if diff > 1e-9:
            raise SystemExit(f"[{d}] w=0·1 이 summary 와 다름: {diff}")
        n_weeks = int(bl.week_of(pd.Series([end]), start)[0])
        info[d] = {"start": start.date(), "end": end.date(), "n_weeks": n_weeks, "check_diff": diff}
        print(f"[{d}] 시험 {start.date()} ~ {end.date()}, 주 {n_weeks}개, summary 대조 최대 차이 {diff:.2e}")
        for (method, mode, hist, zb), g in p.groupby(["method", "mode", "history", "zero_bin"], sort=False):
            weeks, s = bl.evaluate_unit(g, start)
            key = {"dataset": d, "method": method, "mode": mode, "history": hist, "zero_bin": zb}
            units.append({**key, **s})
            weeks_all.append(weeks.assign(**key))
    u = pd.DataFrame(units)
    u["_m"] = u["method"].map({m: i for i, m in enumerate(METHOD_ORDER)})
    u["_h"] = [HIST_ORDER[m].index(h) for m, h in zip(u["method"], u["history"], strict=False)]
    u["_b"] = u["zero_bin"].map({b: i for i, b in enumerate(BIN_ORDER)})
    u["_d"] = u["dataset"].map({d: i for i, d in enumerate(DATASETS)})
    u = u.sort_values(["_d", "_b", "_m", "_h"]).drop(columns=["_d", "_m", "_h", "_b"]).reset_index(drop=True)
    u.to_csv(OUT / "blend_units.csv", index=False, encoding="utf-8")
    pd.concat(weeks_all).to_csv(OUT / "blend_weeks.csv", index=False, encoding="utf-8")
    write_report(u, info)
    print(f"단위 {len(u)}개. 리포트: {REPORT}")
    print(u.groupby("dataset", sort=False)["verdict"].value_counts().to_string())
    return 0


def _f(x, pct=False):
    if x is None or (isinstance(x, float) and np.isnan(x)):
        return "-"
    return f"{x * 100:+.1f}%" if pct else f"{x:.3f}"


def alt_verdict(r, keep_bias: bool) -> str:
    """다른 읽기: 개선폭 절댓값이 1% 미만이면 '차이 미미'(keep_bias 면 |Bias| ≤ 10% 일 때만). 그 밖은 지금 규칙."""
    imp = r["improve"]
    bias_ok = abs(r["blend_bias"]) <= bl.BIAS_LIMIT + bl._EPS
    if not np.isnan(imp) and abs(imp) < bl.MIN_IMPROVE - bl._EPS and (bias_ok or not keep_bias):
        return bl.V_TINY
    return r["verdict"]


def _alt_reading_lines(u: pd.DataFrame) -> list[str]:
    out = []
    for keep_bias, label in ((True, "Bias 조건(|Bias| ≤ 10%)은 그대로 둘 때"), (False, "Bias 조건까지 뺄 때")):
        ch = u[[alt_verdict(r, keep_bias) != r["verdict"] for _, r in u.iterrows()]]
        names = "; ".join(f"{DATASETS[r['dataset']].split(' (')[0]}"
                          f"{'' if r['zero_bin'] == '전체' else ' ' + r['zero_bin']} {METHOD_SHORT[r['method']]} {r['history']}"
                          f"({r['verdict']}→{bl.V_TINY})" for _, r in ch.iterrows())
        out.append(f"- 판정 읽기: 지금은 섞은 쪽이 기준선·TimesFM 중 나은 쪽보다 **낮을 때만** 개선폭 1% 미만을 '차이 미미' 로 본다"
                   f"(같거나 나쁘면 '아니다'). 개선폭 **절댓값** 1% 미만을 모두 '차이 미미' 로 읽으면, {label} 바뀌는 단위 "
                   f"{len(ch)}개: {names if names else '없음'}.")
    return out


def write_report(u: pd.DataFrame, info: dict):
    L = ["# 기준선 × TimesFM 섞기 실험 — 주 단위로 굴려 다시 재기", "",
         "> **이 규칙은 앞·뒤 절반으로 한 번 나눈 결과를 본 뒤에 정했다.** 그때 Recruit 는 섞기가 둘보다 2~5% 나았고 "
         "FreshRetailNet 은 TimesFM 단독과 비슷했다. 이 리포트는 그 결과가 여러 번 나눠 다시 재도 유지되는지 보는 것이다. "
         "결과를 보기 전에 고정한 규칙이 아니므로 이 리포트의 판정은 **탐색적**이다(같은 기간 안에서 규칙을 정하고 잼, 독립 확인 아님). "
         "여기서 '섞기가 낫다' 가 나와도 새 발견이 아니다.", "",
         "## 용어", "",
         "- **기준선**: 팀 서비스의 예측식(최근 4주 같은 요일 평균). **TimesFM**: 구글의 미리 학습된 예측 모델(2.5, 학습 없이 사용).",
         "- **섞기**: 두 예측을 비율 w 로 섞은 값 = (1 − w) × 기준선 + w × TimesFM. w = 0 이면 기준선만, 1 이면 TimesFM 만, 0.5 면 반반.",
         "- **WAPE**: 틀린 양의 합 ÷ 실제 양의 합. 낮을수록 좋다. **Bias**: (예측 − 실제)의 합 ÷ 실제 합. + 면 많이, − 면 적게 예측.",
         "- **주 단위로 굴리기**: 시험 기간을 시험 첫날부터 7일씩 끊어 주로 나눈다. j 번째 주를 채점할 때는 그 앞 주들(1 ~ j−1 주)만 보고 "
         "w 를 0, 0.05, …, 1 중 WAPE 가 가장 작은 값으로 고른다(같으면 작은 w). 채점하는 주의 값은 w 고르기에 쓰지 않는다(미래 보기 반칙 방지). "
         "1 주차는 앞 주가 없어 채점하지 않는다.",
         "- **단위**: 본 실험과 같은 묶음 — 데이터셋 × 방식(고정 창·쌓이는) × 이력 길이(FreshRetailNet 은 × 0 인 날 비율 구간). 모두 32개. "
         "w 는 단위 안의 모든 계열에 같은 값을 쓴다.",
         "- **이긴 주 / 채점 주**: 채점한 주 가운데 섞은 쪽 WAPE 가 기준선·TimesFM 둘 다보다 낮았던 주의 수. 결과가 주마다 일관적인지 본다.", "",
         "## 판정 규칙", "", f"- {bl.RULE}", "- 개선폭 = (min(기준선, TimesFM) − 섞은 쪽) ÷ min(기준선, TimesFM). WAPE·Bias 는 채점 주 전체를 합쳐 계산했다.",
         "- 입력은 본 실험이 저장한 데이터셋별 예측값(`ai/outputs/timesfm_adoption/*_predictions.csv`)뿐이다. TimesFM 을 다시 돌리지 않았다. "
         "WAPE·Bias 는 팀 `metrics.py` 그대로다.", ""]
    for d, title in DATASETS.items():
        g = u[u["dataset"] == d]
        if g.empty:
            continue
        i = info[d]
        L += [f"## {title}", "",
              f"- 시험 기간 {i['start']} ~ {i['end']}, 주 {i['n_weeks']}개 → 채점 주 {i['n_weeks'] - 1}개(2 ~ {i['n_weeks']} 주차).",
              f"- 확인: w = 0 / 1 의 시험 기간 전체 WAPE 가 본 실험 표의 기준선 / TimesFM WAPE 와 같다(최대 차이 {i['check_diff']:.1e}).", ""]
        L += ["| 0 비율 | 방식 | 이력 | 기준선 WAPE | TimesFM WAPE | 50:50 WAPE | 섞기 WAPE | 섞기 Bias | 개선폭 "
              "| 이긴 주 / 채점 주 | 주별 고른 w | 판정 (탐색적) |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for _, r in g.iterrows():
            L.append(f"| {r['zero_bin']} | {METHOD_SHORT[r['method']]} | {r['history']} | {_f(r['baseline_wape'])} | "
                     f"{_f(r['timesfm_wape'])} | {_f(r['half_wape'])} | {_f(r['blend_wape'])} | {_f(r['blend_bias'], True)} | "
                     f"{_f(r['improve'], True)} | {r['weeks_won']} / {r['n_weeks']} | {r['weights']} | **{r['verdict']}** |")
        L += ["", "판정 개수: " + ", ".join(f"{k} {v}개" for k, v in g["verdict"].value_counts().items()), ""]
    L += ["## 한계", "", *_alt_reading_lines(u),
          "- 채점 주 수가 적다(Recruit 5개, FreshRetailNet 3개). 특히 2 주차의 w 는 1 주치 자료만 보고 고른다.",
          "- 판정 규칙을 앞·뒤 절반 결과를 본 뒤 정했다(맨 위 참고). 이 결과는 탐색적이고(같은 기간 안에서 규칙을 정하고 잼, 독립 확인 아님), "
          "정해 두고 잰 시험이 아니다. 다른 기간 재측정은 후속이다.",
          "- 채점 주 범위가 본 실험(시험 기간 전체)과 다르다 — 1 주차를 빼고 채점하므로 기준선·TimesFM WAPE 도 본 실험 표와 다르다.",
          "- w 는 단위 안 모든 계열에 하나다. 계열마다 다른 w, 이력 길이·0 비율로 w 를 정하는 모델, 공휴일 같은 외부 정보는 쓰지 않았다.",
          "- 공개 데이터 결과다. 위글리 가게에서 섞기가 낫다는 결론은 이 리포트로 내릴 수 없다.",
          "- 원자료: `ai/outputs/timesfm_adoption/blend_units.csv`(이 표), `blend_weeks.csv`(단위 × 주별 w·WAPE). 저장소에는 없다.", ""]
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(L), encoding="utf-8")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    return run()


if __name__ == "__main__":
    raise SystemExit(main())
