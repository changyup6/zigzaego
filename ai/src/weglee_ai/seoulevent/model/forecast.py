"""앞으로의 상권별 예측표: `python -m weglee_ai.seoulevent.model.forecast [시작일 끝일]` → data/out/forecast/forecast.csv

평소 인원(최근 같은 요일 8번 중앙값) × 예측 배수 = 예상 인원. 미래에 알 수 있는 정보만 쓴다:
달력 · 공휴일 · 개강 · 작년 같은 시기 · 예정된 경기(K리그) · 공연(KOPIS) · 문화행사. 날씨는 예보가 없어 비워 둔다.
"""

import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from weglee_ai.seoulevent import config, holidays_kr
from weglee_ai.seoulevent.build import geo
from weglee_ai.seoulevent.build.district_day import BIG_KINDS, BIG_SEATS, FESTIVAL_PREFIX, SHORT_EVENT_DAYS, with_venue_areas
from weglee_ai.seoulevent.build.spikes import WEEKDAYS
from weglee_ai.seoulevent.model import experiment as ex
from weglee_ai.seoulevent.model import train
from weglee_ai.seoulevent.model.features import build_features

DEFAULT_THRESHOLDS = (0.50, 0.02)  # 높은 위험 · (안 씀) 기준값 — experiment_rolling.csv 가 없을 때 (19단계 2026)


# 위글리 적재 계약 (위글리 ai/ weglee_ai.sales_forecast 가 이 열을 읽어 sales_forecast_week 에 넣는다, ADR-0015)
SERVE_PREFIX = "seoulevent-"  # '모델' 열 = seoulevent-<모델 버전>
# 모델 근거(SHAP)에 쓰는 특징 이름 — 사장님께 보일 말. 괄호 · ' · ' 를 쓰지 않는다 (값 괄호 · 구분자와 섞인다)
FEATURE_LABELS = {
    "weekday": "요일", "month": "달", "nth_week": "그 달 몇째 주", "is_holiday": "공휴일", "holiday_before": "공휴일 전날",
    "holiday_after": "공휴일 다음 날", "ly_spike": "작년 같은 시기 급증", "ly_max_ratio": "작년 같은 시기 최대 배수",
    "ly_city_spikes": "작년 같은 시기 서울 급증 수", "ly_ratio": "작년 같은 날 배수", "ly_win_max_ratio": "작년 앞뒤 일주일 최대 배수",
    "ly2_spike": "재작년 같은 시기 급증", "log_base": "평소 인원 규모", "short_event_count": "근처 행사 수",
    "short_festival_count": "근처 축제 수", "max_event_seats": "근처 행사 좌석 수", "big_event_count": "근처 큰 행사",
    "max_event_popularity": "공연 예매 인기", "semester_week": "개강 주차", "venue_effect": "같은 장소 행사 때 반응",
    "holiday_effect": "공휴일 때 반응", "holiday_name_effect": "같은 공휴일 때 반응", "holiday_name_full": "같은 공휴일 때 반응",
    "citywide_effect": "서울 큰 행사 때 반응", "bloom_day": "벚꽃 개화 시기", "bloom_effect": "벚꽃 철 반응",
    "semester_effect": "학기 주차 반응", "suneung_week": "수능 뒤 주말", "opponent_effect": "같은 대진 경기 때 반응",
    "base_event_k": "최근 8주 행사 횟수",
}
EVIDENCE_TOP, EVIDENCE_MIN = 3, 0.02  # 큰 것 3개, 배수에 2% 미만인 기여는 근거로 쓰지 않는다
# 근거에 값도 싣는 특징 — "같은 장소 행사 때 반응(1.41배) +16%" 처럼 설명 문장이 숫자를 지어내지 않고 쓰게
TIMES = ("venue_effect", "holiday_effect", "holiday_name_effect", "holiday_name_full", "citywide_effect", "bloom_effect",
         "semester_effect", "opponent_effect", "ly_max_ratio", "ly_ratio", "ly_win_max_ratio")
UNITS = {"max_event_seats": "석", "short_event_count": "건", "short_festival_count": "건", "big_event_count": "건", "base_event_k": "번"}
ALWAYS = ("weekday", "month", "nth_week", "log_base")  # 값이 0 이어도 뜻이 있는 특징


def area_gu(areas: pd.DataFrame) -> pd.Series:
    """상권 코드 → 구 이름. 행사장 상권(구 정보 없음)은 가장 가까운 일반 상권의 구."""
    known = areas.dropna(subset=["SIGNGU_CD_NM"])
    rest = areas[~areas["TRDAR_CD"].isin(known["TRDAR_CD"])]
    d = np.hypot(rest["x"].to_numpy()[:, None] - known["x"].to_numpy(), rest["y"].to_numpy()[:, None] - known["y"].to_numpy())
    near = known["SIGNGU_CD_NM"].to_numpy()[d.argmin(axis=1)] if len(rest) else []
    return pd.concat([known.set_index("TRDAR_CD")["SIGNGU_CD_NM"], pd.Series(near, index=rest["TRDAR_CD"], dtype=object)])


def _value(col: str, v) -> str:
    if pd.isna(v):
        return ""
    if col in TIMES:
        return f"({v:.2f}배)"
    return f"({v:,.0f}{UNITS[col]})" if col in UNITS and v > 0 else ""


def _applies(col: str, v) -> bool:
    """그날에 해당하는 근거인가 — 값이 비었거나 0 인 특징(공휴일 아님 · 행사 0건 · 기록 없음)은 근거로 내세우지 않는다."""
    return col in ALWAYS or (pd.notna(v) and v != 0)


def evidence(hurdle: dict, futf: pd.DataFrame, use_spike: np.ndarray) -> list[str]:
    """모델 근거: 그 행의 예측 배수를 낸 회귀 모델(평소 · 급증 크기)의 특징 기여(SHAP, XGBoost pred_contribs).
    배수는 log 공간에서 기여의 합이라 기여 c 는 '배수 × e^c' — '+18%' 처럼 쓴다. 규칙이 아니라 모델이 실제로 본 값이다."""
    import xgboost as xgb

    contrib = {}
    for key in ("normal", "spike"):
        model, cols = hurdle[key]
        model.set_params(device="cpu")
        contrib[key] = (model.get_booster().predict(xgb.DMatrix(futf[cols]), pred_contribs=True)[:, :-1], cols)  # 끝 열 = 기준값
    out = []
    for i, spike in enumerate(use_spike):
        c, cols = contrib["spike" if spike else "normal"]
        row = futf.iloc[i]
        top = [j for j in np.argsort(-np.abs(c[i])) if abs(c[i][j]) >= EVIDENCE_MIN and _applies(cols[j], row[cols[j]])][:EVIDENCE_TOP]
        out.append(" · ".join(f"{FEATURE_LABELS.get(cols[j], cols[j])}{_value(cols[j], row[cols[j]])} {np.expm1(c[i][j]):+.0%}"
                              for j in top))
    return out


def precision_note(r: pd.DataFrame, high: float) -> str:
    """과거 시간 순서 검증 정밀도 표기 (15 검토): 전체 합산 + 지금과 같은 기준값을 쓴 해 + 연도별 범위. 보장 확률이 아니다."""
    hits = (r["conf_precision"] * r["conf_alerts"]).round()
    same = r["conf_threshold"].round(3) == round(high, 3)
    busy = ""
    if "conf_busy" in r and r["conf_busy"].notna().any():  # 경보 날 1.3배 이상 붐빈 비율 — 2배 적중률과 따로 (16 검토 3)
        b = (r["conf_busy"] * r["conf_alerts"]).round().sum()
        busy = f" · 1.3배 이상 붐빔 {b / r['conf_alerts'].sum():.0%} ({b:.0f}/{r['conf_alerts'].sum():.0f})"
    return (f"2배 급증 전체 {hits.sum() / r['conf_alerts'].sum():.0%} ({hits.sum():.0f}/{r['conf_alerts'].sum():.0f}) · "
            f"같은 기준값 {hits[same].sum() / r.loc[same, 'conf_alerts'].sum():.0%} ({hits[same].sum():.0f}/{r.loc[same, 'conf_alerts'].sum():.0f}) · "
            f"연도별 {r['conf_precision'].min():.0%} ~ {r['conf_precision'].max():.0%}{busy}")


def thresholds(out, sfx: str = "") -> tuple[float, str, bool]:
    """높은 위험 기준값 = 시간 순서 검증(--rolling)의 마지막 해 운영 행(OPS_LABEL, 없으면 반반 평균 행 — 이전 해 out-of-fold 로 고른 값)
    + 과거 검증 정밀도 표기 + 보정 점수 기준인지 (보정 안 한 기준값을 보정 점수에 쓰면 눈금이 어긋난다)."""
    path = out / f"experiment_rolling{sfx}.csv"  # 3일 앞은 experiment_rolling_h3.csv (--rolling --horizon 3)
    if path.exists():
        r = pd.read_csv(path, encoding="utf-8-sig")
        for label in (ex.OPS_LABEL, ex.BLEND_LABEL):
            rl = r[(r["variant"] == label) & r["conf_threshold"].notna()]
            if len(rl):
                high = float(rl.sort_values("year").iloc[-1]["conf_threshold"])
                return high, precision_note(rl, high), label == ex.OPS_LABEL
    return DEFAULT_THRESHOLDS[0], "전체 50% (176/352) · 같은 기준값 42% (95/227) · 연도별 41% ~ 77%", False


def alert_level(p: float, cand: bool, high: float, venue: bool = False, vhigh: float | None = None) -> str:
    """후보 상권만 높은 위험 (사용자 결정 11/05 — 참고 구간 정밀도 0.03 ~ 0.15 라 뺌). 행사장 상권은 따로 행사장 참고 (52단계 결정)."""
    if venue:
        return ex.ALERT_VENUE if vhigh is not None and p >= vhigh else ""
    return ex.ALERT_HIGH if cand and p >= high else ""


def venue_threshold(out, sfx: str = "") -> tuple[float | None, str]:
    """행사장 참고 기준값 = 시간순 검증 운영 줄 마지막 해의 venue_threshold (이전 해 행사장 신호에서 정밀도 0.5) + 과거 정밀도 표기.
    없으면 (None, '') — 등급 안 냄. 운영 모델 점수로는 2024 ~ 26 약 0.43 (2026 0.30) — 기대(0.5)보다 낮아 예측표에 함께 적는다."""
    path = out / f"experiment_rolling{sfx}.csv"
    r = pd.read_csv(path, encoding="utf-8-sig") if path.exists() else pd.DataFrame()
    if "venue_threshold" not in r:
        return None, ""
    r = r[(r["variant"] == ex.OPS_LABEL) & r["venue_threshold"].notna()].sort_values("year")
    if r.empty:
        return None, ""
    hits, alerts = (r["venue_precision"] * r["venue_alerts"]).round().sum(), r["venue_alerts"].sum()
    note = f"행사장 2배 급증 전체 {hits / max(alerts, 1):.0%} ({hits:.0f}/{alerts:.0f}) · 연도별 {r['venue_precision'].min():.0%} ~ {r['venue_precision'].max():.0%}"
    return float(r.iloc[-1]["venue_threshold"]), note


def forecast_dir(out: Path) -> Path:
    """예측 결과 폴더 data/out/forecast/ (예측표 · 날짜 요약 · 합친 예측표 · 근거 설명 · archive/ · scores.csv) — out 바로 아래가 너무 많아 모은다."""
    d = Path(out) / "forecast"
    (d / "archive").mkdir(parents=True, exist_ok=True)
    return d


def day_summary(res: pd.DataFrame) -> pd.DataFrame:
    """날짜 단위 경보 (52단계 결정): 그날 높은 위험 이 하나라도 있으면 '붐비는 날'. 과거 날짜 단위 성적은 7일 앞 정밀도 0.70 · 재현율 0.74, 3일 앞 0.74 · 0.77."""
    g = res.groupby(["날짜", "요일"], sort=True)
    out = pd.DataFrame({"높은 위험 상권 수": g["경보"].apply(lambda a: int((a == ex.ALERT_HIGH).sum())),
                        "행사장 참고 수": g["경보"].apply(lambda a: int((a == ex.ALERT_VENUE).sum())),
                        "최고 급증 점수": g["급증 점수"].max(),
                        "높은 위험 상권": g.apply(lambda d: " · ".join(d.loc[d["경보"] == ex.ALERT_HIGH, "상권"].astype(str).head(5)), include_groups=False)}).reset_index()
    out["날짜 경보"] = np.where(out["높은 위험 상권 수"] > 0, "붐비는 날", "")
    return out


def status(ratio: float) -> str:
    if ratio >= 2.0:
        return "급증"
    if ratio >= 1.3:
        return "붐빔"
    if ratio <= 0.7:
        return "한산"
    return "평소"


def _reason(row: pd.Series, events: list) -> str:
    parts = [] if train.explain(row) == "평소와 같음" else [train.explain(row)]
    if row["semester_week"] > 0:
        parts.append(f"개강 {int(row['semester_week'])}주차")
    if events:
        parts.append("예정: " + " · ".join(map(str, events[:2])) + (f" 외 {len(events) - 2}건" if len(events) > 2 else ""))
    return ", ".join(parts) or "특이 일정 없음"


def future_rows(day: pd.DataFrame, events: pd.DataFrame, holidays: pd.DataFrame, start: date, end: date) -> pd.DataFrame:
    hist = day.assign(wd=pd.to_datetime(day["date"]).dt.day_of_week)
    # v3 정답과 같은 평소 인원: 큰 행사 · 도시 행사 날을 뺀 같은 요일 최근 8번 (build.district_day.clean_baseline)
    city = set(pd.to_datetime(ex._ref("citywide_days.csv", ["date", "name"])["date"]).dt.date)
    hist = hist[(hist["big_event_count"].fillna(0) == 0) & ~hist["date"].isin(city)]
    base = hist.sort_values("date").groupby(["trdar_cd", "wd"])["riders"].apply(lambda s: s.tail(8).median()).rename("riders_base")
    dates = pd.date_range(start, end)
    rows = pd.DataFrame([(t, d.date(), d.day_of_week) for t in day["trdar_cd"].unique() for d in dates], columns=["trdar_cd", "date", "wd"])
    rows = rows.join(base, on=["trdar_cd", "wd"]).drop(columns="wd")
    hol = set(holidays.loc[holidays["is_holiday"].astype(bool), "date"])
    rows["is_holiday"] = rows["date"].isin(hol)

    local = events[(events["source"] != "holiday") & events["trdar_cd"].notna()]
    local = local[(local["end_date"] >= start) & (local["start_date"] <= end)]
    ev = pd.DataFrame(
        [(r.trdar_cd, d.date(), r.name, short, short and str(r.kind).startswith(FESTIVAL_PREFIX), seats, seats >= BIG_SEATS and r.kind in BIG_KINDS,
          float(getattr(r, "popularity", 0.0)) if short and pd.notna(getattr(r, "popularity", np.nan)) else 0.0)
         for r in local.itertuples(index=False)
         for short in [(r.end_date - r.start_date).days + 1 <= SHORT_EVENT_DAYS]
         for seats in [float(r.seats) if short and pd.notna(r.seats) else 0.0]
         for d in pd.date_range(max(r.start_date, start), min(r.end_date, end))],
        columns=["trdar_cd", "date", "name", "short", "festival", "seats", "big", "popularity"])
    agg = ev.groupby(["trdar_cd", "date"]).agg(events=("name", list), short_event_count=("short", "sum"), short_festival_count=("festival", "sum"),
                                               max_event_seats=("seats", "max"), big_event_count=("big", "sum"),
                                               max_event_popularity=("popularity", "max"))
    rows = rows.join(agg, on=["trdar_cd", "date"])
    rows["events"] = rows["events"].map(lambda v: v if isinstance(v, list) else [])
    for c in ("short_event_count", "short_festival_count", "max_event_seats", "big_event_count", "max_event_popularity"):
        rows[c] = rows[c].fillna(0)
    rows["event_count"] = rows["events"].map(len)
    return rows.assign(riders=np.nan, ratio=np.nan, avg_ta=np.nan, sum_rn=np.nan)


MODEL_VERSION = "v3"  # 47단계: v2 + 정답 재정의(행사 뺀 기준선). v2 = 14일 지연 조건 · 기준선 창 행사 수 · 보정 단계 · 높은 위험 목표 0.4


def save_model(folder: Path, bundle: dict) -> Path:
    """학습한 모델 묶음(허들 모델 · 보정기 · 높은 위험 기준값 · 특징 · 후보 상권)을 model.joblib 로, 사람이 볼 설명은 meta.json 으로."""
    import json

    import joblib
    folder.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, folder / "model.joblib")
    meta = {k: v for k, v in bundle.items() if k not in ("hurdle", "calibrator", "cand")} | {
        "candidates": len(bundle["cand"]), "calibrated": bundle["calibrator"] is not None}
    (folder / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    return folder / "model.joblib"


def load_model(folder: Path) -> dict:
    import joblib
    bundle = joblib.load(folder / "model.joblib")
    if bundle["features"] != ex.features_for(bundle.get("horizon", ex.HORIZON)):  # 코드의 운영 특징이 바뀌면 옛 모델로 예측하지 않는다
        raise ValueError(f"저장 모델({bundle['version']})의 특징이 지금 운영 특징과 다르다 — 다시 학습해 저장하라")
    return bundle


def _commit() -> str | None:
    import subprocess
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def main(argv: list[str]) -> None:
    """`[시작일 끝일] [--model 폴더] [--save 이름] [--horizon 3]` — --model 이면 저장 모델로 예측(학습 안 함), 아니면 학습해 data/models/<이름>(기본 latest)에 저장.
    --horizon 3 (51단계): 3일 앞 + 3일 전 예보 날씨 — 기준값 experiment_rolling_h3.csv, 저장 latest_h3, 출력 forecast_h3.csv."""
    argv, opt = list(argv), {}
    for k in ("--model", "--save", "--horizon"):
        if k in argv:
            i = argv.index(k)
            opt[k] = argv[i + 1]
            del argv[i:i + 2]
    # 기본 = 앞으로 7일 (모델이 7일 앞 조건으로 학습 · 검증됨, 14 검토 1). 더 먼 날도 만들 수는 있지만 검증 범위 밖
    h = int(opt.get("--horizon", ex.HORIZON))
    sfx = ex.horizon_suffix(h)
    start, end = (date.fromisoformat(argv[0]), date.fromisoformat(argv[1])) if len(argv) == 2 else (date.today(), date.today() + timedelta(days=h - 1))
    cfg = config.load()
    out = cfg.data_dir / "out"
    day = pd.read_parquet(out / "district_day.parquet")
    events = pd.read_parquet(out / "events.parquet")
    spikes = pd.read_csv(out / "spikes.csv", encoding="utf-8-sig", dtype={"trdar_cd": str}, parse_dates=["date"])
    spikes["date"] = spikes["date"].dt.date
    holidays = holidays_kr.load(cfg.data_dir / "raw", range(2019, end.year + 1))
    areas = with_venue_areas(pd.read_parquet(cfg.data_dir / "raw" / "district_areas" / "areas.parquet"))
    names = areas.set_index("TRDAR_CD")["TRDAR_CD_NM"]

    fut = future_rows(day, events, holidays, start, end)
    both = pd.concat([day, fut], ignore_index=True)
    if h != ex.HORIZON:  # 학습 · 검증 · 예측 모두 h 일 전 예보 날씨 (collect_weather_fc h)
        both = ex.with_forecast_weather(both, pd.read_parquet(cfg.data_dir / "raw" / f"weather_fc{h}.parquet"))
    base = ex.add_extended_features(build_features(both, spikes, holidays), spikes)
    # 학습 · 검증과 같은 7일 앞 조건 (과거 반응 · 평소 규모를 7일 전 기준으로). 승하차 자료가 끝난 뒤와 예측 시작 사이에 빈 날이 있으면
    # 7일 전 평소 규모가 없어 → 지금 아는 평소 규모(최근 8주 같은 요일 중앙값)로 채운다
    feats = ex.features_for(h)
    allf = ex.at_horizon(base, events, ex.ops_lag(h))  # v2: 학습 · 검증과 같은 14일 지연 조건 (승하차 최근 시트가 3일 전까지)
    allf["log_base"] = allf["log_base"].fillna(base["log_base"])
    hist, futf = ex.without_covid(allf[allf["y"].notna()]), allf[allf["date"] >= start].copy()
    if "--model" in opt:  # 저장 모델로 예측 (학습 안 함 — 특징만 지금 데이터로 만든다)
        bundle = load_model(Path(opt["--model"]))
        hurdle, cal, cand = bundle["hurdle"], bundle["calibrator"], set(bundle["cand"])
        high, hit_range, vhigh, vnote = bundle["threshold"], bundle["hit_range"], bundle.get("venue_threshold"), bundle.get("venue_note", "")
        ex.log(f"저장 모델 사용 — {bundle['version']} · 학습 {bundle['created_at']} · 자료 {bundle['train_until']} 까지")
        version = bundle["version"]
    else:
        cand = ex.candidate_districts(hist)
        # 미래엔 날씨 예보 이력이 없어 날씨 뺀 운영 모델 · 분류기는 전체 + 후보 상권 반반 평균 (16단계)
        hurdle = ex.fit_hurdle(hist, feats, cand, blend=True)
        high, hit_range, calibrated = thresholds(out, sfx)
        vhigh, vnote = venue_threshold(out, sfx)
        cal = None
        if ex.OPS_STACK and calibrated:  # 보정 단계 (33단계 운영) — 기준값도 보정 점수로 고른 OPS_LABEL 줄일 때만
            cal = ex.fit_calibrator(hist, feats)
        elif ex.OPS_STACK:
            ex.log(f"보정 안 함 — experiment_rolling.csv 에 '{ex.OPS_LABEL}' 줄이 없어 기준값이 원래 점수 기준")
        saved = save_model(cfg.data_dir / "models" / opt.get("--save", "latest" + sfx), {
            "version": MODEL_VERSION, "created_at": datetime.now().isoformat(timespec="seconds"), "commit": _commit(),
            "horizon": h, "features": feats, "ops_lag": ex.ops_lag(h), "conf_target": ex.OPS_CONF_TARGET, "threshold": high, "venue_threshold": vhigh, "venue_note": vnote, "hit_range": hit_range,
            "train_until": str(max(hist["date"])), "train_rows": len(hist), "hurdle": hurdle, "calibrator": cal, "cand": sorted(cand)})
        ex.log(f"모델 저장 → {saved}")
        version = MODEL_VERSION
    p, normal, spike = ex.predict_hurdle(hurdle, futf)
    if cal is not None:
        p = ex._stack_apply(cal, futf, p)
    futf["예측 배수"] = ex.hurdle(p, normal, spike, high)
    in_cand = futf["trdar_cd"].isin(cand).to_numpy()
    is_venue = futf["trdar_cd"].astype(str).str.startswith(ex.VENUE_PREFIX).to_numpy()

    res = pd.DataFrame({
        "날짜": futf["date"], "요일": [WEEKDAYS[d.weekday()] for d in futf["date"]], "상권": futf["trdar_cd"].map(names),
        "평소 인원": futf["riders_base"].round(-1), "예상 인원": (futf["riders_base"] * futf["예측 배수"]).round(-1),
        "예측 배수": futf["예측 배수"].round(2), "상태": futf["예측 배수"].map(status), "급증 점수": np.round(p, 3),
        "경보": [alert_level(x, c, high, v, vhigh) for x, c, v in zip(p, in_cand, is_venue)],
        "과거 검증 정밀도": [hit_range if c and x >= high else vnote if v and vhigh is not None and x >= vhigh else ""
                       for x, c, v in zip(p, in_cand, is_venue)],  # 보장 확률 아님 (14 · 15 검토)
        "근거": [_reason(r, e) for (_, r), e in zip(futf.iterrows(), futf["events"])],
    })
    res = res.dropna(subset=["평소 인원"]).sort_values(["날짜", "급증 점수"], ascending=[True, False])
    # 자체 확인 (13 검토): 경보 단계가 기준값 정의와 맞는지
    assert (res.loc[res["경보"] == ex.ALERT_HIGH, "급증 점수"] >= round(high, 3) - 1e-9).all()
    res["상권 코드"] = futf.loc[res.index, "trdar_cd"].astype(str)  # 채점(model.score)용 — 상권 이름은 겹칠 수 있다
    f = futf.loc[res.index]  # 위글리 적재 계약 열
    latlon = {c: geo.to_4326(x, y) for c, x, y in areas[["TRDAR_CD", "x", "y"]].itertuples(index=False)}
    res["구"] = f["trdar_cd"].map(area_gu(areas))
    res["행사"] = [" · ".join(map(str, e)) for e in f["events"]]
    res["모델"] = SERVE_PREFIX + version
    res["모델 근거"] = pd.Series(evidence(hurdle, futf, p >= high), index=futf.index).loc[res.index]
    res["위도"], res["경도"] = [round(latlon[c][0], 6) for c in f["trdar_cd"]], [round(latlon[c][1], 6) for c in f["trdar_cd"]]
    res["좌석"] = f["max_event_seats"].fillna(0)
    fdir = forecast_dir(out)
    res.to_csv(fdir / f"forecast{sfx}.csv", index=False, encoding="utf-8-sig")
    # 예측표는 매번 덮어써서 지난 예측이 사라진다 → 만든 날짜로 보관해 실제 승하차가 나오면 채점한다 (model.score)
    res.to_csv(fdir / "archive" / f"forecast{sfx}_{start:%Y%m%d}.csv", index=False, encoding="utf-8-sig")
    days = day_summary(res)
    days.to_csv(fdir / f"forecast_days{sfx}.csv", index=False, encoding="utf-8-sig")
    pd.set_option("display.width", 250)


if __name__ == "__main__":
    main(sys.argv[1:])
