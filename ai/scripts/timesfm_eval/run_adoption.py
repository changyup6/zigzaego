"""TimesFM 채택 검증 실행 — 팀 기준선 vs TimesFM 2.5, rolling 하루 앞 예측, 이력 길이별 판정.

    cd ai
    uv sync --group batch                  # TimesFM · torch(CPU). 처음 실행 때 체크포인트(약 900MB)를 받는다
    uv run python scripts/timesfm_eval/run_adoption.py --dataset recruit
    uv run python scripts/timesfm_eval/run_adoption.py --dataset freshretail   # parquet 읽기에 pyarrow 필요
    uv run python scripts/timesfm_eval/run_adoption.py --dataset csv --path data/processed/sales.csv

원자료는 저장소에 없다(Kaggle 재배포 금지 · POS 원본 커밋 금지). 기본 경로:
    data/raw/recruit-restaurant-visitor-forecasting.zip   (Kaggle Recruit Restaurant Visitor Forecasting)
    data/raw/freshretailnet/train.parquet                 (FreshRetailNet-50K)
한 데이터셋을 돌리면 outputs/timesfm_adoption/ 에 데이터셋별 예측·계열별·요약 CSV 와 meta.json 을 쓰고,
그때까지 돌린 모든 데이터셋의 결과로 같은 폴더의 timesfm_adoption.md 와 그림(matplotlib 이 있을 때만)을 다시 만든다.
CSV 입력 형식: 가게 하나의 date, series(메뉴), qty[, channel]. 판매가 있는 행만 있어도 팀 baseline.fill_daily 로
영업일에 안 팔린 메뉴는 0, 채널 전체가 쉰 날은 휴무로 채운다. 시험 기간 = 마지막 --test-days 일(기본 28).
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from weglee_ai.timesfm_eval import adoption as ta
from weglee_ai.timesfm_eval import equivalence as eq

ROOT = Path(__file__).resolve().parents[2]  # ai/
OUT = ROOT / "outputs" / "timesfm_adoption"
REPORT = OUT / "timesfm_adoption.md"
FIG = OUT / "figures" / "timesfm_adoption.png"

# Recruit — 지난 매장 수 학습 곡선 실험과 같은 평가 매장 선정
RECRUIT_ZIP = ROOT / "data" / "raw" / "recruit-restaurant-visitor-forecasting.zip"
RECRUIT_START, RECRUIT_END = pd.Timestamp("2017-03-12"), pd.Timestamp("2017-04-22")
RECRUIT_MIN_DAYS, N_RECRUIT, SEED = 300, 100, 42
# FreshRetailNet-50K
FRESH_PARQUET = ROOT / "data" / "raw" / "freshretailnet" / "train.parquet"
N_FRESH, FRESH_TEST_DAYS = 1000, 28

METHOD_ORDER = ("고정 창 방식", "쌓이는 방식")
METHOD_WEEKS = {"고정 창 방식": (1, 2, 4), "쌓이는 방식": (1, 2, 4, 12, None)}
METHOD_SHORT = {"고정 창 방식": "고정 창", "쌓이는 방식": "쌓이는"}
METHOD_TITLE = {"고정 창 방식": "고정 창 방식 — 팀 결정 #24 근거 검증의 주 판정",
                "쌓이는 방식": "쌓이는 방식 — 신규 가게가 시간이 지나며 (보조)"}
METHOD_DESC = {
    "고정 창 방식": "시험 기간의 날마다 두 모델 모두 그날 직전 k주(7k일) 기록만 본다. 시험 내내 진짜 1·2·4주 이력이므로 "
                "'홀 이력이 2주뿐일 때 TimesFM 이 요일 평균보다 나은가' 에 답한다. 휴무 메우기도 창 안 기록만으로 했다.",
    "쌓이는 방식": "시험 시작 **전** 기록만 1·2·4·12주·전체로 자르고, 시험 기간 기록은 날마다 쌓인다. 그래서 '1주' 줄도 "
                "시험 마지막 날에는 1주 + 시험 기간 앞날들의 기록을 본다(Recruit 는 최대 약 7주, FreshRetailNet 은 최대 5주). "
                "'신규 가게가 문을 연 뒤 시간이 지나며 어떻게 되나' 를 보는 표이고, '이력이 계속 1주뿐' 인 상황이 아니다.",
}
DATASET_TITLE = {"recruit": "Recruit (일본 음식점 일별 방문객)",
                 "freshretail": "FreshRetailNet-50K (매장×상품 일별 판매 — 메뉴 단위 대리)",
                 "csv": "위글리 CSV"}


# ================================================================ 데이터
def _read_nested_csv(outer: Path, name: str) -> pd.DataFrame:
    with zipfile.ZipFile(outer) as z:
        inner = zipfile.ZipFile(io.BytesIO(z.read(name + ".zip")))
        with inner.open(name) as f:
            return pd.read_csv(f)


def load_recruit(path: Path = RECRUIT_ZIP):
    v = _read_nested_csv(path, "air_visit_data.csv")
    df = pd.DataFrame({"date": pd.to_datetime(v["visit_date"]), "series": v["air_store_id"].astype(str),
                       "qty": v["visitors"].astype(float)})
    train = df[df["date"] < RECRUIT_START]
    test = df[(df["date"] >= RECRUIT_START) & (df["date"] <= RECRUIT_END)]
    days = train.groupby("series")["date"].nunique()
    eligible = sorted(set(days[days >= RECRUIT_MIN_DAYS].index) & set(test["series"].unique()))
    rng = np.random.default_rng(SEED)
    picked = [str(x) for x in rng.choice(np.array(eligible, dtype=object), size=min(N_RECRUIT, len(eligible)),
                                         replace=False)]
    info = {"n_eligible": len(eligible), "n_picked": len(picked), "n_all_series": int(df["series"].nunique())}
    return df[df["series"].isin(picked)].reset_index(drop=True), RECRUIT_START, RECRUIT_END, info


def load_freshretail(path: Path = FRESH_PARQUET):
    raw = pd.read_parquet(path, columns=["store_id", "product_id", "dt", "sale_amount"])
    keys = raw[["store_id", "product_id"]].drop_duplicates().sort_values(["store_id", "product_id"])
    rng = np.random.default_rng(SEED)
    idx = np.sort(rng.choice(len(keys), size=min(N_FRESH, len(keys)), replace=False))
    pick = keys.iloc[idx]
    sub = raw.merge(pick, on=["store_id", "product_id"])
    df = pd.DataFrame({"date": pd.to_datetime(sub["dt"]),
                       "series": "s" + sub["store_id"].astype(str) + "_p" + sub["product_id"].astype(str),
                       "qty": sub["sale_amount"].astype(float)})
    dates = np.sort(df["date"].unique())
    start, end = pd.Timestamp(dates[-FRESH_TEST_DAYS]), pd.Timestamp(dates[-1])
    info = {"n_all_series": int(len(keys)), "n_picked": int(len(pick)),
            "date_min": str(pd.Timestamp(dates[0]).date()), "date_max": str(end.date())}
    return df, start, end, info


def load_csv(path: Path, test_days: int):
    """위글리 CSV — **가게 하나**의 판매 기록(date, series=메뉴, qty[, channel]). 판매가 있는 행만 있어도 된다.

    팀 baseline.fill_daily(판매_데이터_사전 5절)로 채운다: 채널마다 어느 메뉴도 안 팔린 날은 휴무(행 없음),
    영업일에 안 팔린 메뉴는 0. 여러 가게가 섞인 CSV 는 다른 가게가 판 날까지 영업일이 되므로 가게별로 나눠 돌린다.
    """
    raw = pd.read_csv(path)
    need = {"date", "series", "qty"}
    if not need <= set(raw.columns):
        raise SystemExit(f"CSV 에 칸이 없음: {sorted(need - set(raw.columns))} (필요: date, series, qty[, channel])")
    df = ta.fill_open_days(raw)
    end = df["date"].max().normalize()
    start = end - pd.Timedelta(days=test_days - 1)
    return df, start, end, {"n_all_series": int(df["series"].nunique()), "has_channel": "channel" in df.columns}


# ================================================================ 실행
def run(dataset: str, path: str | None, test_days: int, predictor=None) -> int:
    t_all = time.time()
    OUT.mkdir(parents=True, exist_ok=True)
    if dataset == "recruit":
        df, start, end, info = load_recruit(Path(path) if path else RECRUIT_ZIP)
    elif dataset == "freshretail":
        df, start, end, info = load_freshretail(Path(path) if path else FRESH_PARQUET)
    else:
        if not path:
            raise SystemExit("--dataset csv 에는 --path 가 필요하다")
        df, start, end, info = load_csv(Path(path), test_days)
    print(f"[{dataset}] 계열 {df['series'].nunique()}개, 시험 {start.date()} ~ {end.date()}", flush=True)

    tfm = predictor or ta.TimesFMPredictor()
    rec = df[pd.to_numeric(df["qty"], errors="coerce").notna()]
    open_keys = set(zip(rec["series"].astype(str), pd.to_datetime(rec["date"]).dt.normalize(), strict=False))
    bins = ta.zero_ratio_bins(df, start) if dataset == "freshretail" else None
    preds, summaries, pers = [], [], []
    tfm_sec = 0.0
    n_fallback = 0
    skipped_total = {}
    plan = [(ta.METHOD_WINDOW, w) for w in ta.WINDOW_WEEKS] + [(ta.METHOD_ACCUM, w) for w in ta.HISTORY_WEEKS]
    for method, weeks in plan:
        label = ta.history_label(weeks)
        t0 = time.time()
        modes = ta.evaluate_modes(df, start, end, weeks, tfm, window=(method == ta.METHOD_WINDOW))
        for mode, res in modes.items():
            tfm_sec += res.tfm_seconds
            n_fallback += res.n_tfm_fallback
            skipped_total[f"{method} {label} · {mode}"] = len(res.skipped)
            groups = {"전체": None}
            if bins is not None:
                groups = {lab: lab for lab, _, _ in ta.ZERO_BINS}
            for g_label, g in groups.items():
                if g is None:
                    p, sk = res.pred, res.skipped
                else:
                    names = {n for n, b in bins.items() if b == g}
                    p = res.pred[res.pred["series"].isin(names)]
                    sk = {n: r for n, r in res.skipped.items() if bins.get(n) == g}
                s = ta.summarize(p, sk)
                summaries.append({"dataset": dataset, "method": method, "mode": mode, "zero_bin": g_label,
                                  "history": label, **s})
                per = ta.per_series(p).assign(dataset=dataset, method=method, mode=mode, zero_bin=g_label,
                                              history=label)
                pers.append(per)
            prev_closed = [(n, d - pd.Timedelta(days=1)) not in open_keys
                           for n, d in zip(res.pred["series"], res.pred["date"], strict=False)]
            preds.append(res.pred.assign(dataset=dataset, method=method, mode=mode, history=label,
                                         prev_closed=prev_closed))
        print(f"  {method} 이력 {label}: {time.time() - t0:.1f}초", flush=True)

    summary = pd.DataFrame(summaries)
    pd.concat(preds).to_csv(OUT / f"{dataset}_predictions.csv", index=False, encoding="utf-8")
    pd.concat(pers).to_csv(OUT / f"{dataset}_per_series.csv", index=False, encoding="utf-8")
    summary.to_csv(OUT / f"{dataset}_summary.csv", index=False, encoding="utf-8")
    if bins is not None:
        info["bin_counts"] = {lab: sum(1 for b in bins.values() if b == lab) for lab, _, _ in ta.ZERO_BINS}
        pd.Series(bins, name="zero_bin").rename_axis("series").to_csv(OUT / f"{dataset}_zero_bins.csv",
                                                                      encoding="utf-8")
    meta = {"dataset": dataset, "test_start": str(start.date()), "test_end": str(end.date()),
            "n_input_series": int(df["series"].nunique()), **info,
            "checkpoint": getattr(tfm, "checkpoint", "가짜 예측기"),
            "n_params": int(getattr(tfm, "n_params", 0)),
            "forecast_config": ta.FORECAST_CONFIG, "batch_size": getattr(tfm, "batch_size", None),
            "fill": ta.FILL, "timesfm_fallback_rows": n_fallback,
            "versions": _versions(), "load_seconds": round(getattr(tfm, "load_seconds", 0.0), 1),
            "timesfm_seconds": round(tfm_sec, 1), "total_seconds": round(time.time() - t_all, 1),
            "skipped_total": skipped_total}
    (OUT / f"{dataset}_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    write_report()
    print(f"리포트: {REPORT}  ({meta['total_seconds']}초, TimesFM {meta['timesfm_seconds']}초)")
    return 0


def _versions() -> dict:
    from importlib.metadata import PackageNotFoundError, version

    out = {"python": sys.version.split()[0]}
    for name in ("timesfm", "torch", "huggingface_hub", "safetensors", "numpy", "pandas"):
        try:
            out[name] = version(name)
        except PackageNotFoundError:
            out[name] = "없음"
    return out


# ================================================================ 리포트
def _f(x, pct=False):
    if x is None or (isinstance(x, float) and np.isnan(x)):
        return "-"
    return f"{x * 100:+.1f}%" if pct else f"{x:.3f}"


def _table(s: pd.DataFrame) -> list[str]:
    lines = ["| 이력 길이 | 평가 계열 | 계열당 채점일 | 기준선 WAPE | TimesFM WAPE | 개선율 | 기준선 Bias | TimesFM Bias "
             "| TimesFM 승률 | 팀 기준 | 엄격 기준 |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    for _, r in s.iterrows():
        win = "-" if np.isnan(r["win_rate"]) else f"{r['win_rate'] * 100:.0f}% ({r['n_series_wape']}개 중)"
        lines.append(f"| {r['history']} | {r['n_series']} | {r['mean_days']:.1f} | {_f(r['baseline_wape'])} | "
                     f"{_f(r['timesfm_wape'])} | {_f(r['improve'], True)} | {_f(r['baseline_bias'], True)} | "
                     f"{_f(r['timesfm_bias'], True)} | {win} | {r['verdict_team']} | {r['verdict_strict']} |")
    return lines


def _verdict_lines(s: pd.DataFrame, prefix: str = "") -> list[str]:
    out = []
    for _, r in s.iterrows():
        out.append(f"- {prefix}이력 {r['history']} · 팀 기준: **{r['verdict_team']}**")
        out.append(f"- {prefix}이력 {r['history']} · 엄격 기준: **{r['verdict_strict']}**")
    return out


def _skip_lines(s: pd.DataFrame, prefix: str = "") -> list[str]:
    return [f"- {prefix}이력 {r['history']}: {ta.skip_line(r)}" for _, r in s.iterrows()]


_WARNINGS: list[str] = []


def _load_summary(path: Path) -> pd.DataFrame:
    """summary CSV 를 읽는다. 옛 형식(구분 칸 method 없음)이면 '쌓이는 방식' 으로 보고 경고를 남긴다."""
    s = pd.read_csv(path)
    if "method" not in s.columns:
        s["method"] = ta.METHOD_ACCUM
        msg = (f"- 경고: `{path.name}` 은 옛 형식(고정 창·쌓이는 방식 구분 칸 없음)이라 전부 '쌓이는 방식' 으로 읽었다. "
               "고정 창 결과를 보려면 그 데이터셋을 다시 돌려야 한다.")
        if msg not in _WARNINGS:
            _WARNINGS.append(msg)
    return s


def closed_next_day_table(pred: pd.DataFrame) -> pd.DataFrame:
    """예측표를 '전날 휴무' 와 '전날 영업' 으로 나눠 방식·채널 방식·이력별 행 수와 WAPE 를 낸다.

    pred 에 prev_closed 칸(실행할 때 원본 기록으로 계산)이 있어야 한다.
    """
    rows = []
    for (method, mode, hist, closed), g in pred.groupby(["method", "mode", "history", "prev_closed"], sort=False):
        rows.append({"method": method, "mode": mode, "history": hist, "prev_closed": bool(closed),
                     "n_rows": len(g), "baseline_wape": ta.wape(g["actual"], g["baseline"]),
                     "timesfm_wape": ta.wape(g["actual"], g["timesfm"])})
    return pd.DataFrame(rows, columns=["method", "mode", "history", "prev_closed", "n_rows",
                                       "baseline_wape", "timesfm_wape"])


def _closed_next_day_lines() -> list[str]:
    L = ["- **휴무 다음 날 효과**: 서비스와 같이 TimesFM 입력은 마지막 영업일에서 끝나고, 전날이 휴무면 예측 길이를 "
         "늘려(예: 이틀 쉬었으면 3일 뒤 값) 그날 값을 꺼낸다. 휴무를 메운 값이 입력 끝에 들어가지 않는 대신 더 먼 날을 "
         "예측하게 되므로, 그 영향을 아래에 숫자로 나눠 적는다(데이터셋별 예측표 `*_predictions.csv` 에서 자동 계산, "
         "WAPE 는 '기준선→TimesFM'). 판정 규칙은 바꾸지 않았다.", ""]
    for d in ("recruit", "freshretail", "csv"):
        pp = OUT / f"{d}_predictions.csv"
        if not pp.exists():
            continue
        name = DATASET_TITLE[d].split(" (")[0]
        pred = pd.read_csv(pp, parse_dates=["date"])
        if "prev_closed" not in pred.columns or "method" not in pred.columns:
            L += [f"  - {name}: 예측표가 옛 형식(전날 휴무 칸 없음)이라 계산하지 못했다 — 그 데이터셋을 다시 돌리면 채워진다.", ""]
            continue
        if not pred["prev_closed"].any():
            L += [f"  - {name}: 해당 없음 — 채점한 날 중 전날이 휴무(기록 없음)인 날이 0개다.", ""]
            continue
        t = closed_next_day_table(pred)
        L += [f"  {name}:", "",
              "  | 방식 | 채널 방식 | 이력 | 전날 휴무: 행 수 | 전날 휴무: WAPE | 전날 영업: 행 수 | 전날 영업: WAPE |",
              "  |---|---|---|---|---|---|---|"]
        for (method, mode, hist), g in t.groupby(["method", "mode", "history"], sort=False):
            c = g[g["prev_closed"]]
            o = g[~g["prev_closed"]]

            def cell(x):
                if x.empty:
                    return "0", "-"
                r = x.iloc[0]
                return f"{int(r['n_rows']):,}", f"{_f(r['baseline_wape'])}→{_f(r['timesfm_wape'])}"

            cn, cw = cell(c)
            on, ow = cell(o)
            L.append(f"  | {METHOD_SHORT.get(method, method)} | {mode} | {hist} | {cn} | {cw} | {on} | {ow} |")
        L.append("")
    return L


def _section(dataset: str) -> list[str]:
    sp = OUT / f"{dataset}_summary.csv"
    mp = OUT / f"{dataset}_meta.json"
    if not (sp.exists() and mp.exists()):
        return []
    s = _load_summary(sp)
    m = json.loads(mp.read_text(encoding="utf-8"))
    L = [f"## {DATASET_TITLE[dataset]}", ""]
    L.append(f"- 시험 기간: {m['test_start']} ~ {m['test_end']}")
    if dataset == "recruit":
        L.append(f"- 평가 계열 {m['n_picked']}개 — 시험 시작 전 기록 있는 날이 {RECRUIT_MIN_DAYS}일 이상이고 시험 기간에도 "
                 f"기록이 있는 매장 {m['n_eligible']}곳(전체 {m['n_all_series']}곳 중)을 id 순으로 정렬해 시드 {SEED} 로 "
                 f"뽑았다(지난 매장 수 학습 곡선 실험과 같은 100곳).")
    elif dataset == "freshretail":
        L.append(f"- 평가 계열 {m['n_picked']:,}개 — 매장×상품 계열 {m['n_all_series']:,}개 중 시드 {SEED} 로 뽑았다. "
                 f"자료 기간 {m['date_min']} ~ {m['date_max']}(90일), 마지막 {FRESH_TEST_DAYS}일이 시험 기간. "
                 f"원본이 이미 일 단위 판매량(sale_amount)이라 그대로 썼다.")
        L.append("- 원본에는 휴무일 행이 없다(모든 계열이 90일 다 있음). 판매 0 인 날도 기록이므로 채점에 들어간다.")
        L.append("- 시험 시작 전 기록은 62일뿐이라 **12주(84일) 와 전체는 같은 입력**이다.")
        L.append("- 0 비율 = 시험 시작 전 기록(62일) 중 판매 0 인 날의 비율. 구간마다 따로 채점·판정했다. "
                 "구간 계열 수: " + ", ".join(f"{k} {v}개" for k, v in m.get("bin_counts", {}).items()) + ".")
        L.append("- 건너뛴 계열 전체(0 비율 구간을 정할 수 없는 계열 포함): "
                 + ", ".join(f"{k} {v}개" for k, v in m.get("skipped_total", {}).items()) + ".")
    else:
        L.append(f"- 입력 계열 {m['n_all_series']}개, 채널 칸 {'있음' if m.get('has_channel') else '없음'}.")
    if m.get("fill") == ta.FILL:
        L.append(f"- TimesFM 입력: 서비스 방식(`{ta.FILL}`). 예측 길이가 {ta.SERVICE_SKIP_HORIZON}일을 넘어 기준선 값을 쓴 행 "
                 f"{m.get('timesfm_fallback_rows', 0)}개(모든 방식·이력 합).")
    else:
        L.append("- TimesFM 입력: 이전 실행(선형 보간)의 결과다. 휴무일이 없는 데이터면 서비스 방식과 입력이 같다.")
    L.append(f"- 실행 시간: 전체 {m['total_seconds']}초, 그중 TimesFM 예측 {m['timesfm_seconds']}초"
             f"(체크포인트 불러오기 {m['load_seconds']}초 별도), CPU.")
    L.append("")
    for method in METHOD_ORDER:
        smeth = s[s["method"] == method]
        if smeth.empty:
            continue
        L += [f"### {METHOD_TITLE[method]}", "", METHOD_DESC[method], ""]
        order = [ta.history_label(w) for w in METHOD_WEEKS[method]]
        for mode, sm in smeth.groupby("mode", sort=False):
            if smeth["mode"].nunique() > 1:
                L += [f"#### {mode}", ""]
            for zb, sz in sm.groupby("zero_bin", sort=False):
                sz = sz.set_index("history").loc[order].reset_index()
                prefix = f"{METHOD_SHORT[method]} · "
                if dataset == "freshretail":
                    L += [f"#### {METHOD_SHORT[method]} · 0 인 날 비율 {zb}", ""]
                    prefix += f"0 비율 {zb} · "
                L.append("판정 (이력 길이마다 두 줄):")
                L.append("")
                L += _verdict_lines(sz, prefix)
                L.append("")
                L += _table(sz)
                L.append("")
                L += _skip_lines(sz, prefix)
                bias_fail = sz[(sz["timesfm_wape"] < sz["baseline_wape"]) & (sz["timesfm_bias"].abs() > ta.BIAS_LIMIT)]
                if len(bias_fail):
                    sign = ("적게" if (bias_fail["timesfm_bias"] < 0).all()
                            else "많이" if (bias_fail["timesfm_bias"] > 0).all() else "방향 섞임")
                    L.append(f"- 주의: 이력 {', '.join(bias_fail['history'])} 에서는 TimesFM WAPE 가 기준선보다 낮지만 "
                             f"|Bias| 가 10% 를 넘어({sign} 예측하는 쪽) 팀 기준을 통과하지 못했다.")
                L.append("")
    return L


def _short(v: str) -> str:
    return "근거 있음" if v == ta.V_YES else "근거 없음" if v == ta.V_NO else "판단 불가"


def _summary_block() -> list[str]:
    """맨 위 요약: 고정 창(주 판정) 결과만 한 표로. 상세 판정 줄은 데이터셋 절에 있다."""
    rows = []
    for d in DATASET_TITLE:
        sp = OUT / f"{d}_summary.csv"
        if not sp.exists():
            continue
        s = _load_summary(sp)
        w = s[s["method"] == ta.METHOD_WINDOW]
        for (mode, zb), g in w.groupby(["mode", "zero_bin"], sort=False):
            g = g.set_index("history")
            name = DATASET_TITLE[d].split(" (")[0] + ("" if zb == "전체" else f" · 0 비율 {zb}")
            if w["mode"].nunique() > 1:
                name += f" · {mode}"
            cells = []
            for k in ta.WINDOW_WEEKS:
                r = g.loc[ta.history_label(k)]
                cells.append(f"팀 {_short(r['verdict_team'])} / 엄격 {_short(r['verdict_strict'])} "
                             f"(WAPE {_f(r['baseline_wape'])}→{_f(r['timesfm_wape'])})")
            rows.append(f"| {name} | " + " | ".join(cells) + " |")
    if not rows:
        return []
    head = "| 데이터 | " + " | ".join(f"창 {k}주" for k in ta.WINDOW_WEEKS) + " |"
    return ["## 판정 요약", "",
            "**주 판정은 고정 창 방식이다** — 팀 결정 #24 의 근거('홀 이력이 2주뿐') 를 검증하는 표. "
            "시험 내내 두 모델 모두 그날 직전 1·2·4주 기록만 본다. 아래 표의 WAPE 는 '기준선→TimesFM' 이다. "
            "쌓이는 방식 표(데이터셋 절 아래쪽)는 '신규 가게가 시간이 지나며 기록이 쌓이면 어떻게 되나' 를 보는 보조 표다.",
            "", head, "|---|" + "---|" * len(ta.WINDOW_WEEKS), *rows, ""]


def write_report():
    _WARNINGS.clear()
    metas = {d: json.loads((OUT / f"{d}_meta.json").read_text(encoding="utf-8"))
             for d in DATASET_TITLE if (OUT / f"{d}_meta.json").exists()}
    any_meta = next(iter(metas.values()), {})
    v = any_meta.get("versions", {})
    L = ["# TimesFM 채택 검증 — 이력 길이별로 TimesFM 이 요일 평균을 이기는가", ""]
    L += ["팀은 '홀 이력이 2주뿐이라 요일 평균이 안 돈다' 는 이유로 서비스 예측을 TimesFM 으로 정했다(결정 #24). "
          "이 리포트는 운영 후보인 **TimesFM 2.5** 를 서비스와 같은 방식으로 공개 데이터에서 다시 재서, "
          "이력 길이별로 팀 기준선보다 나은지 숫자로 답한다. 위글리 실데이터가 아니므로 "
          "**'위글리에서 채택' 결론은 이 리포트로 내릴 수 없다.**", ""]
    L += _summary_block()
    L += ["## 용어", "",
          "- **TimesFM 2.5**: 구글이 아주 많은 날짜별 숫자 기록(시계열)으로 미리 학습해 둔 예측 모델. "
          "2.5 는 상업적으로 써도 되는 라이선스(Apache-2.0) 버전이다.",
          "- **zero-shot**: 우리 데이터로 따로 학습시키지 않고, 미리 배운 것만으로 바로 예측하는 것.",
          "- **기준선**: 비교 기준이 되는 단순한 방법. 여기서는 팀 서비스의 식 — 최근 4주 같은 요일 평균"
          "(같은 요일 기록이 2개 미만이면 최근 14일 평균, 휴무일은 건너뜀).",
          "- **rolling 하루 앞 예측**: 시험 기간의 날마다 '어제까지 기록' 만 보고 오늘을 맞혀 보는 채점 방식. "
          "서비스의 새벽 3시 배치와 같고, 미래를 미리 보는 반칙(누수)을 막는다.",
          "- **휴무 메우기(서비스 방식)**: TimesFM 은 빈칸 없는 일별 배열을 받으므로, 서비스 배치(`batch/predictors.py` 의 "
          "`to_calendar`)와 같이 가운데 휴무일을 볼 수 있는 기록 중 같은 요일의 평균(없으면 전체 평균)으로 메운다. "
          "맨 뒤 휴무는 메우지 않고 예측 길이를 늘린다. TimesFM 입력에만 쓴다.",
          "- **WAPE**: 틀린 양의 합 ÷ 실제 양의 합. 0.3 이면 실제의 30% 만큼 틀림. 낮을수록 좋다. "
          "'전체 WAPE' 는 모든 계열·날을 합쳐서 한 번에 계산한 값.",
          "- **Bias**: (예측 − 실제)의 합 ÷ 실제 합. + 면 많이 예측(과잉 발주), − 면 적게 예측(품절 위험).",
          "- **개선율**: (기준선 WAPE − TimesFM WAPE) ÷ 기준선 WAPE. + 면 TimesFM 이 덜 틀림.",
          "- **TimesFM 승률**: 계열(가게 또는 상품)마다 WAPE 를 따로 내서 TimesFM 이 기준선보다 낮았던 계열의 비율.",
          "- **이력 길이**: 모델이 볼 수 있는 과거 기록을 몇 주로 제한했는지. 신규 가게처럼 기록이 짧은 상황을 흉내 낸다. "
          "두 가지로 쟀다 — **고정 창**(날마다 그날 직전 k주만 봄, 주 판정)과 **쌓이는 방식**(시험 시작 전만 자르고 시험 기간 기록은 쌓임, 보조).",
          ""]
    L += ["## 판정 규칙 (결과를 보기 전에 코드로 고정)", ""]
    L += [f"- {r}" for r in ta.RULES]
    L += ["- 엄격 기준은 팀 설계보다 엄격하게 우리가 더한 것이다. 팀 설계의 합격선은 '팀 기준' 줄이다.", ""]
    L += ["## 설계", "",
          "- 기준선: `ai/src/weglee_ai/baseline.py` 의 `forecast_day` 를 그대로 불렀다. "
          "WAPE·Bias 도 `ai/src/weglee_ai/metrics.py` 로 계산했다.",
          f"- TimesFM: 체크포인트 `{ta.CHECKPOINT}`, zero-shot, CPU. 라이브러리 timesfm {v.get('timesfm', '?')}, "
          f"torch {v.get('torch', '?')}, huggingface_hub {v.get('huggingface_hub', '?')}, "
          f"python {v.get('python', '?')}. 매개변수 {any_meta.get('n_params', 0) / 1e6:.1f}M. "
          f"예측 설정 {any_meta.get('forecast_config')}. 점 예측은 라이브러리 기본값(중앙값 분위)을 썼다.",
          "- 채점: 시험 기간의 날마다 그날 이전 기록만 넣고 그날 하루를 예측했다. 실제 기록이 있는 날만 채점(휴무일 제외).",
          "- 고정 창 방식(주 판정): 날마다 두 모델 모두 그날 직전 7k일 [t−7k, t) 의 기록만 받는다(k = 1·2·4). "
          "기준선도 같은 창으로 자른 기록만 팀 식에 넣는다.",
          "- 쌓이는 방식(보조): 시험 시작 전 기록만 1·2·4·12주·전체로 자르고, 시험 기간 안의 기록은 날마다 쌓인다"
          "(서비스에서 기록이 하루씩 늘어나는 것과 같다). 그래서 '1주' 줄의 마지막 날에는 1주 + 시험 기간 앞날들의 기록이 들어간다.",
          "- TimesFM 입력: 서비스 배치(`batch/predictors.py` 의 `TimesFmPredictor`)와 같다. 모델이 볼 수 있는 기록"
          "(고정 창이면 창 안, 쌓이는 방식이면 잘린 뒤 전부)의 첫 기록일부터 예측일 전 **마지막 기록일**까지의 일별 값(마지막 "
          f"{ta.MAX_CONTEXT}일까지)을 넣고, 예측 길이를 '예측일 − 마지막 기록일' 로 늘려 그날 값을 꺼낸다. 가운데 휴무일은 그 기록만으로 "
          "같은 요일 평균(없으면 전체 평균) — 창 밖이나 예측일 이후 값은 끌어오지 않는다. 예측 길이가 "
          f"{ta.SERVICE_SKIP_HORIZON}일을 넘으면 기준선 값을 쓰고 행 수를 적었다(서비스는 오늘부터 {ta.HORIZON_DAYS}일 구간이 "
          f"{ta.MAX_HORIZON}일을 넘으면 TimesFM 을 건너뛰므로). "
          "예측 설정은 서비스 배치의 `FORECAST_CONFIG` 를 그대로 쓴다. 기준선은 팀 식대로 휴무일을 그냥 건너뛴다.",
          "- 계산 시간을 줄이려고 TimesFM 입력을 길이 순으로 정렬해 128개씩 묶어 한 번에 예측했다(묶어도 계열끼리 섞이지 않는다).",
          "- 음수 예측은 0 으로 잘랐다(두 모델 모두). 시험 전 기록이 0줄인(잘린 범위 안에서) 계열은 건너뛰고 개수를 적었다. "
          "실제 합이 0 인 계열은 WAPE 가 정의되지 않아 계열별 표·승률에서 빼고 개수를 적었다(전체 WAPE 에는 포함).",
          "- 채널(홀·배달 등) 칸이 있으면 '채널별 예측 후 합산' 과 '채널 합쳐서 예측' 을 둘 다 채점하는 코드가 있다. "
          "위글리 CSV(가게 하나)는 팀 `baseline.fill_daily`(판매_데이터_사전 5절)로 채운다 — 채널마다 어느 메뉴도 안 팔린 날은 "
          "그 채널 휴무, 영업일에 안 팔린 메뉴는 0. "
          "공개 데이터엔 채널이 없어 이번에는 작은 가짜 데이터 시험으로 동작만 확인했고, 채널 판정은 하지 않는다.",
          ""]
    for d in DATASET_TITLE:
        L += _section(d)
    if _figure():
        L += ["## 그림", "", "![이력 길이별 전체 WAPE](figures/timesfm_adoption.png)", "",
              "위 줄: 고정 창 방식(주 판정), 아래 줄: 쌓이는 방식. 왼쪽: Recruit, 오른쪽: FreshRetailNet(0 비율 구간별). "
              "실선 = TimesFM, 점선 = 기준선. 낮을수록 좋다.", ""]
    L += ["## 해석 주의", "",
          "- 공개 데이터(일본 음식점 방문객, 중국 신선식품 매장 판매) 결과다. 위글리 가게의 메뉴 판매에 그대로 옮길 수 없다.",
          "- 팀 기준은 'TimesFM WAPE 가 기준선보다 조금이라도 낮으면' 통과한다. 개선율이 +0.1% 처럼 아주 작아도 "
          "'채택 근거 있음' 이 될 수 있으니, 표의 개선율과 승률(계열 절반 이상에서 이겼는지)을 같이 봐야 한다. "
          "엄격 기준은 이 점을 보완하려고 더한 것이다.",
          "- 고정 창 1주의 기준선은 사실상 '요일 평균' 이 아니다: 창이 7일이라 같은 요일 기록이 최대 1개뿐이고, "
          "팀 식은 같은 요일이 2개 미만이면 '최근 14일 평균' 으로 넘어가는데 창 안에는 7일만 있으므로 "
          "실제로는 **창 안 7일(영업일) 평균**이 된다. 고정 창 2주도 그 요일에 휴무가 끼면 같은 식으로 최근 14일(=창 전체) 평균으로 넘어간다.",
          "- 표의 숫자는 한 번 실행한 값이다. 신뢰구간(우연으로 흔들릴 수 있는 폭)은 아래 동등성 검정 절에서 따로 본다.",
          "- 지난 실험(매장 수 학습 곡선)의 TimesFM 3.0 수치는 6주를 한 번에 예측한 것이라 이번(하루 앞 예측)과 직접 비교할 수 없다.",
          "- 원자료: `ai/outputs/timesfm_adoption/` 의 데이터셋별 `*_predictions.csv`(날마다 예측), `*_per_series.csv`(계열별 WAPE), "
          "`*_summary.csv`(이 표), `*_meta.json`(버전·실행 시간). 저장소에는 없다.", ""]
    L += ["- 섞기 실험(기준선과 TimesFM 을 비율 w 로 섞으면 나은가)은 같은 폴더의 `timesfm_blend.md` 에 따로 있다"
          "(`scripts/timesfm_eval/run_blend.py`, 저장된 예측값만 사용).", ""]
    L += _closed_next_day_lines()
    L += _WARNINGS + ([""] if _WARNINGS else [])
    L += _kept_equivalence_section()
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(L), encoding="utf-8")


def _kept_equivalence_section() -> list[str]:
    """run_equivalence.py 가 넣은 동등성 절(표시 주석 사이)을 리포트를 다시 쓸 때 잃지 않게 그대로 옮긴다."""
    if not REPORT.exists():
        return []
    body = REPORT.read_text(encoding="utf-8")
    span = eq.find_report_section(body)  # run_equivalence.write_section 과 같은 규칙
    return [body[span[0]:span[1]], ""] if span else []


def _figure() -> bool:
    """그림을 그리고 True. matplotlib 이 없거나 그릴 데이터가 없으면 False(그림 절을 리포트에서 뺀다)."""
    have = [d for d in ("recruit", "freshretail") if (OUT / f"{d}_summary.csv").exists()]
    if not have:
        return False
    try:
        import matplotlib
    except ImportError:
        print("matplotlib 없음 — 그림은 건너뛴다 (팀 의존성에 없다. 필요하면 uv run --with matplotlib)")
        return False

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    # 한글 글꼴 후보: Windows · macOS · Linux 순. 없는 글꼴은 matplotlib 이 건너뛴다
    plt.rcParams["font.family"] = ["Malgun Gothic", "AppleGothic", "NanumGothic", "Noto Sans CJK KR", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False
    fig, axes = plt.subplots(2, len(have), figsize=(6 * len(have), 8), squeeze=False)
    colors = ["#2f6db5", "#d0762c", "#3b9a5a"]
    for row, method in enumerate(METHOD_ORDER):
        xs = [ta.history_label(w) for w in METHOD_WEEKS[method]]
        for ax, d in zip(axes[row], have, strict=False):
            s = _load_summary(OUT / f"{d}_summary.csv")
            s = s[s["method"] == method]
            for i, (zb, sz) in enumerate(s.groupby("zero_bin", sort=False)):
                sz = sz.set_index("history").loc[xs]
                lab = "" if zb == "전체" else f" (0 비율 {zb})"
                ax.plot(xs, sz["timesfm_wape"], "-o", color=colors[i % 3], label=f"TimesFM{lab}")
                ax.plot(xs, sz["baseline_wape"], "--s", color=colors[i % 3], alpha=0.7, label=f"기준선{lab}")
            ax.set_title(f"{DATASET_TITLE[d].split(' (')[0]} — {METHOD_SHORT[method]}")
            ax.set_xlabel("이력 길이" + (" (그날 직전 창)" if method == METHOD_ORDER[0] else " (시험 시작 전)"))
            ax.set_ylabel("전체 WAPE (낮을수록 좋음)")
            ax.grid(alpha=0.3)
            ax.legend(fontsize=8)
    fig.tight_layout()
    FIG.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(FIG, dpi=120)
    plt.close(fig)
    return True


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="TimesFM 채택 검증")
    ap.add_argument("--dataset", required=True, choices=["recruit", "freshretail", "csv"])
    ap.add_argument("--path", default=None)
    ap.add_argument("--test-days", type=int, default=28, help="csv 의 시험 기간 일수(마지막 N일)")
    a = ap.parse_args(argv)
    return run(a.dataset, a.path, a.test_days)


if __name__ == "__main__":
    raise SystemExit(main())
