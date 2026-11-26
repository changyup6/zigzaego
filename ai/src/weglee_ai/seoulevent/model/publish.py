"""합친 예측표 + 근거 설명: `python -m weglee_ai.seoulevent.model.publish` (매일 daily · 매주 weekly 가 예측표를 만든 뒤 실행)

data/out/forecast/ 의 7일 앞(forecast.csv)과 3일 앞(forecast_h3.csv)을 합친다 — 3일 앞이 있는 날은 3일 앞(날씨 예보 반영,
평균정밀도 0.692 > 0.656), 나머지 날은 7일 앞. 오늘부터의 날짜만. → forecast_merged.csv · forecast_days_merged.csv · archive/forecast_merged_YYYYMMDD.csv
근거 설명 reason.json (최신) · reason_YYYYMMDD.json: 날짜 · 경보 수 · 주요 상권 숫자는 코드가 예측표에서 채우고(지어낼 여지 없음),
GMS(.env 의 GMS_BASE_URL · GMS_KEY · GMS_MODEL, OpenAI Responses · JSON 출력)는 일주일 통합 '주간 설명' 한 문단만 쓴다.
GMS 가 안 되거나 JSON 이 깨지면 규칙으로 만든 문장을 넣는다 (파일이 비지 않게).
"""

import json
import os
import sys
import traceback
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config
from weglee_ai.seoulevent._http import send
from weglee_ai.seoulevent.model.experiment import ALERT_HIGH, ALERT_VENUE
from weglee_ai.seoulevent.model.forecast import day_summary, forecast_dir

TOP_PER_DAY = 5
CAVEAT = "과거 검증에 기반한 확률적 예측이며 실제 유동인구를 보장하지 않는다."


def merge(f7: pd.DataFrame, f3: pd.DataFrame, today: date) -> pd.DataFrame:
    """3일 앞 표에 있는 날짜는 3일 앞 줄, 그 밖의 날짜는 7일 앞 줄 (오늘 이후만). '예측 시점' 열을 붙인다."""
    d3 = set(f3["날짜"])
    m = pd.concat([f3.assign(**{"예측 시점": "3일 앞"}), f7[~f7["날짜"].isin(d3)].assign(**{"예측 시점": "7일 앞"})], ignore_index=True)
    m = m[pd.to_datetime(m["날짜"]).dt.date >= today]
    return m.sort_values(["날짜", "급증 점수"], ascending=[True, False]).reset_index(drop=True)


def day_items(m: pd.DataFrame, weather: dict | None = None) -> list[dict]:
    """날짜마다 사실 값 (경보 수 · 경보 상권 + 급증 점수 상위 상권). weather: {날짜: (평균기온, 강수 mm)} — 3일 앞 날짜만 (모델이 쓴 예보)."""
    items = []
    for d, g in m.groupby("날짜", sort=True):
        alert = g["경보"].isin([ALERT_HIGH, ALERT_VENUE])
        w = (weather or {}).get(d) if g["예측 시점"].iloc[0] == "3일 앞" else None
        top = pd.concat([g[alert], g.head(TOP_PER_DAY)]).drop_duplicates("상권")
        items.append({"날짜": d, "요일": g["요일"].iloc[0], "예측 시점": g["예측 시점"].iloc[0],
                      "날씨 예보": {"평균기온": round(float(w[0]), 1), "강수mm": round(float(w[1]), 1)} if w else None,
                      "높은 위험": int((g["경보"] == ALERT_HIGH).sum()), "행사장 참고": int((g["경보"] == ALERT_VENUE).sum()),
                      "주요 상권": [{"상권": r["상권"], "평소 인원": int(r["평소 인원"]), "예상 인원": int(r["예상 인원"]),
                                  "예측 배수": float(r["예측 배수"]), "상태": r["상태"], "급증 점수": float(r["급증 점수"]),
                                  "경보": r["경보"] if isinstance(r["경보"], str) and r["경보"] else "없음", "근거": str(r["근거"])}
                                 for _, r in top.iterrows()]})
    return items


def rule_text(items: list[dict]) -> str:
    """GMS 가 없을 때 주간 설명 — 경보가 있는 날과 기간 중 배수가 가장 높은 상권."""
    alert_days = [f"{it['날짜']}({it['요일']})" for it in items if it["높은 위험"] or it["행사장 참고"]]
    d, t = max(((it, it["주요 상권"][0]) for it in items), key=lambda x: x[1]["예측 배수"])
    return (f"이번 기간 경보가 있는 날은 {', '.join(alert_days) if alert_days else '없다'}. "
            f"가장 붐빌 곳은 {d['날짜']}({d['요일']}) {t['상권']} (예상 {t['예상 인원']:,}명, 평소의 {t['예측 배수']:.2f}배), 근거: {t['근거']}.")


PROMPT = """너는 서울 상권 유동인구(지하철 승하차) 예측 결과를 외식 가게 사장님께 설명하는 분석가다.
아래 JSON 은 앞으로 일주일의 날짜별 예측 사실이다. 날짜별로 나누지 말고 일주일 전체를 하나로 묶어 한국어 평문 한 문단(4 ~ 8문장)으로 설명해라:
경보(높은 위험 · 행사장 참고)가 있는 날과 장소를 먼저, 이어서 가장 붐빌 날 · 상권과 그 근거(예정 행사 · 작년 같은 시기 · 요일),
한산할 날, 날씨 예보가 있는 날의 날씨 영향. 숫자는 JSON 에 있는 값만 쓰고 지어내지 마라. 날씨 예보가 null 인 날은 날씨를 언급하지 마라.
마크다운(#, *, -, 표 · 줄바꿈 목록)과 이모티콘은 쓰지 마라.
답은 JSON 객체 하나로만: {{"주간 설명": "문단"}}

예측 사실:
{facts}"""


def ask_gms(items: list[dict], client: httpx.Client | None = None) -> dict:
    """GMS(OpenAI Responses · JSON 출력)로 {'주간 설명': 문단}. 설정이 없거나 형식이 틀리면 예외."""
    url, key, model = os.getenv("GMS_BASE_URL"), os.getenv("GMS_KEY"), os.getenv("GMS_MODEL")
    if not (url and key and model):
        raise RuntimeError("GMS 설정(.env 의 GMS_BASE_URL · GMS_KEY · GMS_MODEL)이 없다")
    client = client or httpx.Client(timeout=180)
    js = send(client, "POST", url, secret=key, headers={"Authorization": f"Bearer {key}"},
              json={"model": model, "input": PROMPT.format(facts=json.dumps(items, ensure_ascii=False)),
                    "text": {"format": {"type": "json_object"}}}).json()
    raw = "".join(c.get("text", "") for item in js.get("output", []) if item.get("type") == "message"
                  for c in item.get("content", []) if c.get("type") == "output_text")
    out = json.loads(raw)
    if not isinstance(out.get("주간 설명"), str) or not out["주간 설명"].strip():
        raise RuntimeError("GMS 응답에 '주간 설명' 이 없다")
    return out


def build_reason(items: list[dict], gms: dict | None, how: str, today: date) -> dict:
    """사실 값(날짜별) + 일주일 통합 설명 → reason.json 내용. GMS 설명이 없으면 규칙 문장."""
    return {"작성일": str(today), "기간": [items[0]["날짜"], items[-1]["날짜"]] if items else [],
            "예측표": "forecast_merged.csv (가까운 3일은 3일 앞 예측, 나머지는 7일 앞 예측)", "설명 출처": how,
            "주간 설명": str((gms or {}).get("주간 설명") or (rule_text(items) if items else "")),
            "주의": CAVEAT,  # 면책 문구는 고정 — GMS 에 맡기면 다른 내용을 쓴다
            "날짜별": items}


def main(argv: list[str] | None = None) -> int:
    cfg = config.load()  # .env 를 읽는다 (GMS 설정 포함)
    fdir = forecast_dir(cfg.data_dir / "out")
    today = date.today()
    f7, f3 = (pd.read_csv(fdir / n, encoding="utf-8-sig") if (fdir / n).exists() else None for n in ("forecast.csv", "forecast_h3.csv"))
    if f7 is None and f3 is None:
        print(f"예측표가 없다: {fdir} — forecast.csv 또는 forecast_h3.csv 를 먼저 만든다", file=sys.stderr)
        return 1
    f7 = f7 if f7 is not None else f3.iloc[0:0]
    f3 = f3 if f3 is not None else f7.iloc[0:0]
    m = merge(f7, f3, today)
    m.to_csv(fdir / "forecast_merged.csv", index=False, encoding="utf-8-sig")
    m.to_csv(fdir / "archive" / f"forecast_merged_{today:%Y%m%d}.csv", index=False, encoding="utf-8-sig")
    day_summary(m).to_csv(fdir / "forecast_days_merged.csv", index=False, encoding="utf-8-sig")
    wf = cfg.data_dir / "raw" / "weather_fc3.parquet"  # collect_weather_fc 3 — 3일 앞 모델이 쓴 예보
    w = pd.read_parquet(wf).dropna() if wf.exists() else pd.DataFrame(columns=["date", "avg_ta", "sum_rn"])
    items = day_items(m, {str(pd.Timestamp(d).date()): (t, r) for d, t, r in zip(w["date"], w["avg_ta"], w["sum_rn"])})
    try:
        gms, how = ask_gms(items), f"GMS ({os.getenv('GMS_MODEL')})"
    except Exception:
        traceback.print_exc()
        gms, how = None, "규칙 요약 (GMS 실패)"
    reason = json.dumps(build_reason(items, gms, how, today), ensure_ascii=False, indent=1)
    for name in ("reason.json", f"reason_{today:%Y%m%d}.json"):
        (fdir / name).write_text(reason + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
