"""TimesFM 채택 검증 — 팀 기준선(최근 4주 같은 요일 평균)과 TimesFM 2.5 를 서비스와 같은 방식으로 비교한다.

서비스와 같은 방식 = rolling 하루 앞 예측: 시험 기간의 날마다 그날 '이전' 기록만 모델에 넣고 그날 하루를 예측한다.
- 기준선: weglee_ai.baseline.forecast_day 를 그대로 부른다(휴무일은 행이 없으니 그냥 건너뛴다).
- TimesFM: 서비스 배치(weglee_ai.batch.predictors.TimesFmPredictor)와 같은 입력을 넣는다. 맥락은 첫 기록일부터
  t 이전 '마지막 기록일' 까지이고(맨 뒤 휴무는 메우지 않는다), 가운데 휴무일은 to_calendar 로 그 요일의 평균
  (없으면 전체 평균)으로 메운다. 예측 길이를 't − 마지막 기록일' 로 늘려 t 날 값을 꺼낸다. 맥락은 마지막 512일까지.
  메우기 평균도 t 이전(고정 창이면 창 안) 기록만으로 낸다(뒤쪽 값을 쓰면 누수다).
  서비스는 오늘부터 7일 구간이 128일을 넘으면 TimesFM 을 건너뛰고 요일 평균을 쓰므로(판매가 오래 끊긴 계열),
  예측 길이가 122일을 넘으면 여기서도 기준선 값을 쓴다.
- 채점: 실제 기록이 있는 날만. 지표는 weglee_ai.metrics 의 WAPE · Bias.
- 이력 길이: 각 계열의 기록을 시험 시작 직전 1·2·4·12주(또는 전체)로 자른다. 시험 기간 안의 기록은 날마다 쌓인다
  (서비스에서 가게 기록이 하루씩 늘어나는 것과 같다).
- 음수 예측은 0 으로 자른다(두 모델 모두).
- 채널 칸이 있으면 (가) 채널별로 따로 예측해 더함, (나) 채널을 합친 계열 하나로 예측 — 둘 다 채점한다.

TimesFM 예측기는 `(contexts: list[np.ndarray], horizons: list[int]) -> np.ndarray(각 context 의 horizons[i] 일 뒤 예측)`
함수면 된다.
시험에서는 가짜 함수를 넣고, 실제 실행에서는 TimesFMPredictor 를 넣는다(uv sync --group batch 필요).
"""
from __future__ import annotations

import math
from collections.abc import Callable
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from weglee_ai import metrics
from weglee_ai.baseline import NO_CHANNEL, fill_daily, forecast_day
from weglee_ai.batch.predictors import FORECAST_CONFIG, TimesFmPredictor, to_calendar
from weglee_ai.batch.run import HORIZON_DAYS

CHECKPOINT = "google/timesfm-2.5-200m-pytorch"

HISTORY_WEEKS: tuple[int | None, ...] = (1, 2, 4, 12, None)  # None = 전체
WINDOW_WEEKS: tuple[int, ...] = (1, 2, 4)  # 고정 창: 시험 기간의 날마다 그날 직전 k주만

METHOD_ACCUM = "쌓이는 방식"
METHOD_WINDOW = "고정 창 방식"

MODE_SINGLE = "단일 계열(채널 칸 없음)"
MODE_SPLIT = "채널별 예측 후 합산"
MODE_MERGED = "채널 합쳐서 예측"

V_YES = "채택 근거 있음"
V_NO = "근거 없음"
V_UNKNOWN = "판단 불가(데이터 부족)"

MIN_MEAN_DAYS = 7
BIAS_LIMIT = 0.10
STRICT_IMPROVE = 0.05
STRICT_WIN_RATE = 0.50
_EPS = 1e-12  # 경계값(정확히 10%, 5%, 50%)이 부동소수 오차로 떨어지지 않게

# 판정 규칙(결과를 보기 전에 고정). 아래 세 문장은 리포트에 글자 그대로 들어간다.
RULE_TEAM = ("팀 기준(설계 8절): TimesFM 전체 WAPE < 기준선 전체 WAPE 이고 |TimesFM Bias| ≤ 10% "
             "→ `채택 근거 있음`, 아니면 `근거 없음`.")
RULE_STRICT = ("엄격 기준: 팀 기준 + (기준선 WAPE − TimesFM WAPE) ÷ 기준선 WAPE ≥ 5% + 계열별 WAPE 로 "
               "TimesFM 이 이긴 계열 ≥ 50% → `채택 근거 있음`, 아니면 `근거 없음`.")
RULE_UNKNOWN = "채점 가능한 날이 계열당 평균 7일 미만이면 두 줄 모두 `판단 불가(데이터 부족)`."
RULES = (RULE_TEAM, RULE_STRICT, RULE_UNKNOWN)

Predictor = Callable[[list, list], np.ndarray]
FILL = "service_to_calendar"  # TimesFM 입력 메우기 방식 — meta.json 에 적는다
MAX_CONTEXT = TimesFmPredictor.MAX_CONTEXT
MAX_HORIZON = TimesFmPredictor.MAX_HORIZON
# 서비스 배치는 예측일(오늘)부터 HORIZON_DAYS(7)일을 한 번에 예측하고, predictors.TimesFmPredictor.predict 는
# 마지막 목표일(오늘+6)까지의 구간이 MAX_HORIZON 을 넘으면 그 계열을 건너뛴다. 그래서 오늘 하루의 예측 길이 h 로는
# h + (HORIZON_DAYS − 1) > MAX_HORIZON, 즉 h > MAX_HORIZON − 6 = 122 일 때 건너뛴다.
SERVICE_SKIP_HORIZON = MAX_HORIZON - (HORIZON_DAYS - 1)


def history_label(weeks: int | None) -> str:
    return "전체" if weeks is None else f"{weeks}주"


# ================================================================ 지표 (팀 metrics.py 그대로)
wape = metrics.wape
bias = metrics.bias


# ================================================================ TimesFM 입력 (서비스와 같게)
def service_context(past: pd.Series, day: pd.Timestamp) -> tuple[np.ndarray, int]:
    """day 를 예측할 때 서비스 배치가 모델에 넣는 (맥락, 예측 길이). past 는 day 이전(창 안) 기록, 비어 있으면 안 된다.

    맥락 = to_calendar(past, 마지막 기록일)의 마지막 MAX_CONTEXT 일, 예측 길이 = day − 마지막 기록일.
    """
    last = past.index.max()
    return to_calendar(past, last.date())[-MAX_CONTEXT:].astype(np.float32), int((pd.Timestamp(day) - last).days)


# ================================================================ 입력 정리
def _prep(df: pd.DataFrame) -> pd.DataFrame:
    """date·series·qty(·channel) 표를 정리: 날짜형, 수량 빈 값 줄 삭제, 같은 날 중복은 합산."""
    d = df.copy()
    d["date"] = pd.to_datetime(d["date"]).dt.normalize()
    d["series"] = d["series"].astype(str)
    d["qty"] = pd.to_numeric(d["qty"], errors="coerce")
    d = d[d["qty"].notna()]
    keys = ["series", "date"] + (["channel"] if "channel" in d.columns else [])
    if "channel" in d.columns:
        d["channel"] = d["channel"].astype(str)
    return d.groupby(keys, as_index=False, sort=True)["qty"].sum()


@dataclass
class EvalResult:
    pred: pd.DataFrame  # series, date, actual, baseline, timesfm
    skipped: dict = field(default_factory=dict)  # 계열 이름 → 건너뛴 이유
    tfm_seconds: float = 0.0
    n_tfm_fallback: int = 0  # 예측 길이가 SERVICE_SKIP_HORIZON(122일)을 넘어 기준선 값을 쓴 행 수(서비스와 같음)


PRED_COLS = ["series", "date", "actual", "baseline", "timesfm"]


def rolling_eval(df: pd.DataFrame, test_start, test_end, weeks: int | None, tfm_predict: Predictor,
                 targets: dict | None = None, skip_no_history: bool = True, window: bool = False,
                 baseline_fn: Callable = forecast_day) -> EvalResult:
    """계열마다 시험 기간의 날마다 그날 이전 기록만으로 기준선·TimesFM 예측을 낸다.

    df: date, series, qty. 휴무일은 행이 없다.
    weeks: 시험 시작 직전 몇 주만 남길지(None = 전체).
      window=False(쌓이는 방식): 시험 시작 전 기록만 자르고, 시험 기간 기록은 날마다 쌓인다.
      window=True(고정 창 방식): 시험 기간의 날마다 두 모델 모두 그날 직전 7×weeks 일 [t-7k, t) 의 기록만 본다.
        휴무 메우기도 창 안 기록만으로 한다(창 밖 값을 끌어오지 않음). 창 안 기록이 없으면 두 모델 모두 0.
    targets: {계열: 채점할 날짜들} — 없으면 그 계열의 시험 기간 기록일. 지정한 날에 기록이 없으면 실제 = 0.
    건너뛰기: 시험 시작 전 기록이 0줄이거나(잘린 범위 안에서) 채점할 날이 없는 계열.
    """
    import time

    test_start, test_end = pd.Timestamp(test_start), pd.Timestamp(test_end)
    names = sorted(pd.Series(df["series"]).astype(str).unique())
    d = _prep(df[["date", "series", "qty"]])
    if window and not weeks:
        raise ValueError("고정 창 방식에는 weeks(1·2·4 등)가 필요하다")
    hist_start = test_start - pd.Timedelta(days=7 * weeks) if weeks else None
    skipped: dict = {}
    meta, contexts, horizons = [], [], []
    n_fallback = 0
    groups = dict(tuple(d.groupby("series")))
    for name in names:
        g = groups.get(name)
        if g is None:
            skipped[name] = "기록 0줄"
            continue
        s = g.set_index("date")["qty"].sort_index()
        s = s[s.index <= test_end]
        if hist_start is not None:
            s = s[s.index >= hist_start]
        n_hist = int((s.index < test_start).sum())
        days = (pd.DatetimeIndex(targets[name]) if targets is not None and name in targets
                else s.index[s.index >= test_start])
        days = days[(days >= test_start) & (days <= test_end)]
        if n_hist == 0 and skip_no_history:
            skipped[name] = "시험 전 기록 0줄"
            continue
        if len(days) == 0:
            skipped[name] = "채점할 날 0일"
            continue
        for day in days:
            actual = float(s.get(day, 0.0))
            past = s[s.index < day]
            if window:
                past = past[past.index >= day - pd.Timedelta(days=7 * weeks)]
            b = max(baseline_fn(past, day), 0.0)
            tfm = 0.0 if len(past) == 0 else None  # None = 모델에 넣는다
            if tfm is None:
                ctx, h = service_context(past, day)
                # 서비스는 오늘부터 7일 구간이 128일을 넘으면 TimesFM 을 건너뛰고 요일 평균을 쓰므로, 예측 길이가 122일을 넘으면 기준선 값
                if h > SERVICE_SKIP_HORIZON:
                    tfm = b
                    n_fallback += 1
                else:
                    contexts.append(ctx)
                    horizons.append(h)
            meta.append((name, day, actual, b, tfm))
    t0 = time.time()
    out = np.asarray(tfm_predict(contexts, horizons), float).reshape(-1) if contexts else np.zeros(0)
    tfm_sec = time.time() - t0
    if len(out) != len(contexts):
        raise ValueError(f"예측기가 {len(contexts)}개 입력에 {len(out)}개를 돌려줌")
    it = iter(np.clip(out, 0.0, None))
    rows = [(n, day, a, b, next(it) if tfm is None else tfm) for n, day, a, b, tfm in meta]
    pred = pd.DataFrame(rows, columns=PRED_COLS)
    return EvalResult(pred=pred, skipped=skipped, tfm_seconds=tfm_sec, n_tfm_fallback=n_fallback)


def fill_open_days(df: pd.DataFrame) -> pd.DataFrame:
    """가게 하나의 판매 기록(date, series=메뉴, qty[, channel]) → 팀 baseline.fill_daily 로 영업일 빈칸을 채운 표.

    판매_데이터_사전 5절: 채널마다 그날 어느 메뉴도 안 팔렸으면 그 채널은 휴무(행 없음), 영업일에 안 팔린 메뉴는 0.
    메뉴의 첫 판매일 이전은 채우지 않는다. 채널 칸이 없으면 가게 전체를 한 채널로 본다.
    가게가 여럿 섞인 표에 쓰면 다른 가게가 판 날까지 영업일로 보므로 안 된다(위글리 CSV 는 가게 하나).
    """
    has_channel = "channel" in df.columns
    sales = pd.DataFrame({"sold_date": pd.to_datetime(df["date"]).dt.normalize(),
                          "menu_name": df["series"].astype(str),
                          "qty": pd.to_numeric(df["qty"], errors="coerce")})
    if has_channel:
        sales["channel"] = df["channel"].astype(str).to_numpy()
    f = fill_daily(sales[sales["qty"].notna()])
    out = pd.DataFrame({"date": pd.to_datetime(f["sold_date"]), "series": f["menu_name"].astype(str),
                        "qty": f["qty"].astype(float)})
    if has_channel:
        out["channel"] = f["channel"].astype(str).to_numpy()
    elif len(f) and not (f["channel"] == NO_CHANNEL).all():
        raise ValueError("채널 칸이 없는데 fill_daily 가 채널을 나눴다")
    return out.reset_index(drop=True)


def evaluate_modes(df: pd.DataFrame, test_start, test_end, weeks: int | None,
                   tfm_predict: Predictor, window: bool = False) -> dict[str, EvalResult]:
    """채널 칸이 없으면 {단일 계열: 결과}. 있으면 {채널별 예측 후 합산, 채널 합쳐서 예측} 둘 다.

    채점 단위는 두 방식 모두 '계열(채널 합)' 이고, 채점 날은 채널 합 계열의 시험 기간 기록일이다.
    영업일에 안 팔린 메뉴의 0 행은 부르는 쪽이 미리 채운다(위글리 CSV 는 fill_open_days). 행이 없는 채널은 그날 휴무다.
    채널을 나눠 예측할 때 채점 날에 그 채널 기록이 없으면 실제 = 0. 시험 전 기록이 없는 채널의 예측은 0.
    """
    if "channel" not in df.columns:
        return {MODE_SINGLE: rolling_eval(df, test_start, test_end, weeks, tfm_predict, window=window)}
    d = _prep(df)
    merged_df = d.groupby(["series", "date"], as_index=False)["qty"].sum()
    merged = rolling_eval(merged_df, test_start, test_end, weeks, tfm_predict, window=window)
    targets = {n: g["date"].to_numpy() for n, g in merged.pred.groupby("series")}
    sub = d.assign(orig=d["series"], series=d["series"] + "||" + d["channel"])
    sub_targets = {f"{o}||{c}": targets[o] for o, c in sub[["orig", "channel"]].drop_duplicates().itertuples(index=False)
                   if o in targets}
    sub_res = rolling_eval(sub[["date", "series", "qty"]], test_start, test_end, weeks, tfm_predict,
                           targets=sub_targets, window=window)
    sp = sub_res.pred.assign(series=sub_res.pred["series"].str.split("||", regex=False).str[0])
    sp = sp.groupby(["series", "date"], as_index=False)[["baseline", "timesfm"]].sum()
    split = merged.pred[["series", "date", "actual"]].merge(sp, on=["series", "date"], how="left")
    split[["baseline", "timesfm"]] = split[["baseline", "timesfm"]].fillna(0.0)
    return {MODE_SPLIT: EvalResult(pred=split[PRED_COLS], skipped=dict(merged.skipped),
                                   tfm_seconds=sub_res.tfm_seconds, n_tfm_fallback=sub_res.n_tfm_fallback),
            MODE_MERGED: merged}


# ================================================================ 요약·판정
def per_series(pred: pd.DataFrame) -> pd.DataFrame:
    """계열별 날 수·실제 합·WAPE·Bias. 실제 합이 0 이면 WAPE·Bias 는 빈 값(nan)."""
    rows = []
    for name, g in pred.groupby("series", sort=True):
        rows.append({"series": name, "n_days": len(g), "actual_sum": float(g["actual"].sum()),
                     "baseline_wape": wape(g["actual"], g["baseline"]),
                     "timesfm_wape": wape(g["actual"], g["timesfm"]),
                     "baseline_bias": bias(g["actual"], g["baseline"]),
                     "timesfm_bias": bias(g["actual"], g["timesfm"])})
    cols = ["series", "n_days", "actual_sum", "baseline_wape", "timesfm_wape", "baseline_bias", "timesfm_bias"]
    return pd.DataFrame(rows, columns=cols)


def improve_rate(base_wape: float, tfm_wape: float) -> float:
    """개선율 = (기준선 WAPE − TimesFM WAPE) ÷ 기준선 WAPE. 기준선 WAPE 가 0·빈 값이면 nan. summarize · verdict 가 같이 쓴다."""
    if any(x is None or math.isnan(x) for x in (base_wape, tfm_wape)) or base_wape == 0:
        return float("nan")
    return (base_wape - tfm_wape) / base_wape


def summarize(res: EvalResult | pd.DataFrame, skipped: dict | None = None) -> dict:
    pred = res.pred if isinstance(res, EvalResult) else res
    skipped = res.skipped if isinstance(res, EvalResult) else (skipped or {})
    per = per_series(pred)
    valid = per[per["actual_sum"] != 0]
    n_series = int(len(per))
    bw, tw = wape(pred["actual"], pred["baseline"]), wape(pred["actual"], pred["timesfm"])
    s = {
        "n_series": n_series,
        "n_skipped": len(skipped),
        "n_wape_excluded": int(n_series - len(valid)),
        "n_series_wape": int(len(valid)),
        "n_rows": int(len(pred)),
        "mean_days": float(len(pred) / n_series) if n_series else 0.0,
        "baseline_wape": bw,
        "timesfm_wape": tw,
        "baseline_bias": bias(pred["actual"], pred["baseline"]),
        "timesfm_bias": bias(pred["actual"], pred["timesfm"]),
        "improve": improve_rate(bw, tw),
        "win_rate": float((valid["timesfm_wape"] < valid["baseline_wape"]).mean()) if len(valid) else float("nan"),
    }
    s["verdict_team"], s["verdict_strict"] = verdict(s)
    return s


def verdict(s: dict) -> tuple[str, str]:
    """(팀 기준 판정, 엄격 기준 판정). 규칙 문장은 위 RULES 와 같다."""
    if not s.get("mean_days") or s["mean_days"] < MIN_MEAN_DAYS:
        return V_UNKNOWN, V_UNKNOWN
    bw, tw, tb = s["baseline_wape"], s["timesfm_wape"], s["timesfm_bias"]
    if any(x is None or math.isnan(x) for x in (bw, tw, tb)):
        return V_UNKNOWN, V_UNKNOWN
    team = tw < bw and abs(tb) <= BIAS_LIMIT + _EPS
    improve = improve_rate(bw, tw)
    win = s.get("win_rate", float("nan"))
    strict = team and not math.isnan(improve) and improve >= STRICT_IMPROVE - _EPS and (not math.isnan(win)) and win >= STRICT_WIN_RATE - _EPS
    return (V_YES if team else V_NO), (V_YES if strict else V_NO)


def skip_line(s: dict) -> str:
    return f"건너뛴 계열 {s['n_skipped']}개 / WAPE 제외 계열 {s['n_wape_excluded']}개"


# ================================================================ 0 비율 구간 (FreshRetailNet)
ZERO_BINS = (("0~10%", 0.0, 0.10), ("10~30%", 0.10, 0.30), ("30%+", 0.30, 1.0 + _EPS))


def zero_ratio_bins(df: pd.DataFrame, test_start) -> dict[str, str]:
    """계열 → 0 비율 구간. 0 비율 = 시험 시작 전 기록(전체) 중 수량이 0 인 날의 비율."""
    d = _prep(df[["date", "series", "qty"]])
    pre = d[d["date"] < pd.Timestamp(test_start)]
    ratio = pre.groupby("series")["qty"].apply(lambda q: float((q == 0).mean()))
    out = {}
    for name, r in ratio.items():
        for label, lo, hi in ZERO_BINS:
            if lo <= r < hi:
                out[name] = label
                break
    return out


# ================================================================ 실제 TimesFM
# 예측 설정 FORECAST_CONFIG 는 서비스 배치(batch/predictors.py)의 상수를 그대로 쓴다(위 import).


def round_context(m: int) -> int:
    """묶음의 최대 context = 가장 긴 입력 길이를 32(TimesFM 입력 조각 크기)의 배수로 올림, 최소 32."""
    return max(32, int(math.ceil(m / 32) * 32))


class TimesFMPredictor:
    """TimesFM 2.5(200M, PyTorch, CPU) zero-shot 예측. 처음 부를 때 체크포인트를 불러온다.

    입력마다 예측 길이 h 가 다르다(맨 뒤 휴무가 있으면 h > 1). 묶음은 그 안의 가장 긴 h 로 한 번 예측하고
    입력마다 h 일 뒤 값을 꺼낸다 — 서비스 배치가 계열마다 마지막 판매일 기준으로 꺼내는 것과 같다.

    CPU 시간을 줄이려고 context 를 길이 순으로 정렬해 batch_size 개씩 묶고,
    묶음마다 최대 context 를 그 묶음의 가장 긴 길이(32 의 배수로 올림)로 맞춰 쓸데없는 채움 계산을 줄인다.
    점 예측은 라이브러리의 기본 점 예측(중앙값 분위)이다.
    timesfm · torch 는 pyproject 의 batch 그룹에만 있다 (uv sync --group batch).
    """

    def __init__(self, checkpoint: str = CHECKPOINT, batch_size: int = 128, model=None):
        """model: 시험용으로 compile(cfg)·forecast(horizon, inputs) 를 가진 가짜 모델을 넣을 수 있다."""
        self.checkpoint = checkpoint
        self.batch_size = batch_size
        self.model = model
        self._ctx = None
        self.load_seconds = 0.0
        self.n_calls = 0
        self._cache: dict[tuple[bytes, int], float] = {}  # (맥락, 예측 길이)가 같으면(예: 12주와 전체) 다시 계산하지 않는다

    def _load(self):
        import time

        import torch
        from timesfm.timesfm_2p5.timesfm_2p5_torch import TimesFM_2p5_200M_torch

        t0 = time.time()
        torch.manual_seed(0)
        self.model = TimesFM_2p5_200M_torch.from_pretrained(self.checkpoint, torch_compile=False)
        self.model.model.eval()
        self.n_params = sum(p.numel() for p in self.model.model.parameters())
        self.load_seconds = time.time() - t0

    def _compile(self, max_context: int):
        from timesfm import ForecastConfig

        if self._ctx != max_context:
            self.model.compile(ForecastConfig(max_context=max_context, per_core_batch_size=self.batch_size,
                                              **FORECAST_CONFIG))
            self._ctx = max_context

    def __call__(self, contexts: list, horizons: list | None = None) -> np.ndarray:
        """contexts[i] 의 horizons[i] 일 뒤 예측(horizons 가 없으면 모두 1 = 다음 날)."""
        if self.model is None:
            self._load()
        contexts = [np.asarray(c, np.float32) for c in contexts]
        horizons = [1] * len(contexts) if horizons is None else [int(h) for h in horizons]
        if len(horizons) != len(contexts) or any(h < 1 for h in horizons):
            raise ValueError("horizons 는 contexts 와 길이가 같고 모두 1 이상이어야 한다")
        keys = [(c.tobytes(), h) for c, h in zip(contexts, horizons, strict=False)]
        todo = [i for i, k in enumerate(keys) if k not in self._cache]
        uniq = list(dict.fromkeys(keys[i] for i in todo))
        first = {k: i for i in reversed(todo) for k in [keys[i]]}
        new = self._predict([contexts[first[k]] for k in uniq], [k[1] for k in uniq])
        self._cache.update(zip(uniq, new, strict=False))
        return np.array([self._cache[k] for k in keys], float)

    def _predict(self, contexts: list, horizons: list) -> np.ndarray:
        import torch

        n = len(contexts)
        out = np.zeros(n)
        if n == 0:
            return out
        order = np.argsort([len(c) for c in contexts], kind="stable")
        with torch.inference_mode():
            for i in range(0, n, self.batch_size):
                idx = order[i:i + self.batch_size]
                chunk = [np.asarray(contexts[j], np.float32) for j in idx]
                hs = np.array([horizons[j] for j in idx])
                self._compile(round_context(max(len(c) for c in chunk)))
                point, _ = self.model.forecast(horizon=int(hs.max()), inputs=list(chunk))
                out[idx] = np.asarray(point, float)[np.arange(len(idx)), hs - 1]
                self.n_calls += 1
        return out
