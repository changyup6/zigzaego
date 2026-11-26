"""상권 유동인구 예측표 → 가게별 '이번 주 매출 예측' + 설명 문장 (S15P31A308-226 · 267).

입력은 weglee_ai.seoulevent 예측표 CSV 한 장이고, 아래 COLUMNS 만 읽는다.
모델을 바꿔도 같은 열을 내면 여기와 BE · FE 는 그대로다. 어떤 모델이 냈는지는 sales_forecast_week.model 에 남는다.

가게 좌표(store.latitude · longitude)가 있으면 반경 RADIUS_M 안 상권만, 없거나 그 안에 상권이 없으면 store.sigungu("서울 중구")의
구 전체 상권을 더해 본다. 서울 밖 가게는 줄을 만들지 않는다 (BE 는 줄이 없으면 pct null 로 답한다).
shortcut: 지하철 승하차 배수를 매출 배수로 그대로 쓴다, 실제 매출과 맞춘 근거가 생기면 탄력을 곱한다.

설명 문장(analysis)은 GMS(LLM)가 숫자 · 행사 · 모델 근거(SHAP)만 보고 쓴다. 같은 구는 한 번만 부르고, 숫자가 그대로면
지난 문장을 다시 쓴다. GMS 설정이 없거나 실패하면 analysis 는 null 이고 숫자는 그대로 저장한다.

로컬 PC 에서 실행한다 (서버 배치 아님). 실행 방법은 seoulevent/README.md.
"""

import csv
import io
import json
import logging
import os
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

log = logging.getLogger(__name__)

COLUMNS = ["날짜", "상권", "구", "평소 인원", "예상 인원", "행사", "모델", "모델 근거", "위도", "경도", "좌석"]
RADIUS_M = 1000  # 가게 반경 — 상권 중심(위도 · 경도) 까지 거리
EARTH_M = 6_371_000
# 모델 근거 중 행사에서 나온 것 — 행사 항목(이름 · 날짜 · 장소)과 겹치므로 '행사 밖 이유' 에서 뺀다
EVENT_EVIDENCE = ("근처 ", "공연 예매 인기", "같은 장소 행사 때 반응", "같은 대진 경기 때 반응", "서울 큰 행사 때 반응", "최근 8주 행사 횟수")
# 사장님께 이유가 되지 않는 근거(달력 · 상권 크기) — 그 몫은 어느 이유에도 넣지 않는다
NOT_REASON = ("요일", "달", "그 달 몇째 주", "평소 인원 규모")
EVIDENCE_ITEM = re.compile(r"^(?P<name>[^(]+?)(?:\((?P<value>[^)]*)\))? (?P<pct>[+-]\d+)%$")
WEEKDAYS = "월화수목금토일"
MAX_FACTORS = 3
MAX_EVIDENCE = 3
GMS_VARS = ("GMS_KEY", "GMS_BASE_URL", "GMS_MODEL")
PROMPT_DIR = Path(__file__).resolve().parent / "prompts"  # 패키지 안 — 설치된 이미지에서도 읽힌다

# DB 는 ssh → kubectl exec <DB 파드> -- psql 로만 만진다 (ai/scripts/demo_attach.py 와 같은 경로, 비밀번호 · 터널 없음)
UPSERT_SQL = """INSERT INTO sales_forecast_week (store_id, week_start, model, pct, reason, days, factors, analysis)
VALUES ({store_id}, {week_start}, {model}, {pct}, {reason}, {days}::jsonb, {factors}::jsonb, {analysis})
ON CONFLICT (store_id, week_start) DO UPDATE
SET model = EXCLUDED.model, pct = EXCLUDED.pct, reason = EXCLUDED.reason, days = EXCLUDED.days,
    factors = EXCLUDED.factors, analysis = EXCLUDED.analysis, created_at = now();"""
PREVIOUS_SQL = "SELECT store_id, pct, reason, coalesce(analysis, '') FROM sales_forecast_week WHERE week_start = {week_start};"
STORES_SQL = "SELECT id, coalesce(sigungu, ''), coalesce(latitude::text, ''), coalesce(longitude::text, '') FROM store ORDER BY id;"
SAFE_NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")  # 원격 셸 명령에 들어가는 네임스페이스 · 파드 · DB 이름 — 셸 주입 방지


def lit(v) -> str:
    """SQL 값 글자. 문자열 · 날짜는 작은따옴표(안의 ' 는 두 번), None 은 NULL."""
    if v is None:
        return "NULL"
    if isinstance(v, int):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


@dataclass(frozen=True)
class Target:
    """적재할 DB 한 곳 — 서버 ssh(user@host) · 키 · k8s 네임스페이스 · DB 파드."""
    name: str
    ssh: str
    pem: str
    namespace: str
    pod: str = "postgres-1"
    db: str = "zigzaego"

    def __post_init__(self):
        for field in ("namespace", "pod", "db"):
            if not SAFE_NAME.match(getattr(self, field)):
                raise ValueError(f"{self.name}_{field.upper()} 는 영문 소문자 · 숫자 · '-' 만: {getattr(self, field)!r}")

    def psql(self, sql: str) -> str:
        """SQL 을 표준 입력으로 보내고 결과(CSV, 머리줄 없음)를 돌려준다. 실패하면 RuntimeError.
        CSV 라 값 안의 줄바꿈(여러 문단 설명 문장) · 구분자도 따옴표로 감싸져 한 칸으로 읽힌다."""
        import subprocess

        remote = (f"sudo -n kubectl -n {self.namespace} exec -i {self.pod} -c postgres -- "
                  f"psql -U postgres -d {self.db} -v ON_ERROR_STOP=1 --csv -t -f -")
        p = subprocess.run(["ssh", "-i", self.pem, "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", self.ssh, remote],
                           input=sql.encode("utf-8"), capture_output=True, timeout=600)
        if p.returncode != 0:
            raise RuntimeError(f"{self.name} ssh/psql 실패(코드 {p.returncode}): {p.stderr.decode('utf-8', 'replace').strip()[-300:]}")
        return p.stdout.decode("utf-8")


def targets(env: Mapping[str, str]) -> list[Target]:
    """SALES_FORECAST_TARGETS=STAGING,MAIN → 이름마다 <이름>_SSH · _PEM · _NAMESPACE (+ 선택 _PG_POD · _DB)."""
    names = [n.strip() for n in env.get("SALES_FORECAST_TARGETS", "").split(",") if n.strip()]
    if not names:
        raise SystemExit("ai/.env 에 SALES_FORECAST_TARGETS(예: STAGING,MAIN)가 없다")
    missing = [f"{n}_{k}" for n in names for k in ("SSH", "PEM", "NAMESPACE") if not env.get(f"{n}_{k}")]
    if missing:
        raise SystemExit(f"ai/.env 에 없다: {', '.join(missing)}")
    return [Target(n, env[f"{n}_SSH"], env[f"{n}_PEM"], env[f"{n}_NAMESPACE"], env.get(f"{n}_PG_POD") or "postgres-1",
                   env.get(f"{n}_DB") or "zigzaego") for n in names]


@dataclass(frozen=True)
class Week:
    week_start: date
    model: str
    pct: int
    reason: str
    days: list[dict]
    factors: list[dict]
    evidence: list[dict]  # 사람이 가장 많이 늘어난 상권 · 날의 모델 근거 — 설명 문장 재료 (DB 에는 안 넣는다)


def load(path: str | Path) -> pd.DataFrame:
    fc = pd.read_csv(path, encoding="utf-8-sig")
    missing = [c for c in COLUMNS if c not in fc.columns]
    if missing:
        raise ValueError(f"예측표에 열이 없다: {', '.join(missing)}")
    if fc["모델"].nunique() != 1:
        raise ValueError(f"예측표 한 장에 모델이 하나여야 한다: {sorted(fc['모델'].unique())}")
    fc = fc[COLUMNS].copy()
    fc["날짜"] = pd.to_datetime(fc["날짜"]).dt.date
    for c in ("행사", "모델 근거"):
        fc[c] = fc[c].fillna("").astype(str)
    fc["좌석"] = fc["좌석"].fillna(0)
    return fc


def gu_of(sigungu: str | None) -> str | None:
    """store.sigungu("서울 중구") → 예측표의 구("중구"). 서울이 아니면 None."""
    parts = (sigungu or "").split()
    return parts[-1] if len(parts) == 2 and parts[0].startswith("서울") else None


def _short(d: date) -> str:
    return f"{WEEKDAYS[d.weekday()]} {d.month}.{d.day}"


def near(fc: pd.DataFrame, lat: float, lon: float) -> pd.DataFrame:
    """가게 반경 RADIUS_M 안 상권 행 (하버사인 거리)."""
    p1, p2 = np.radians(lat), np.radians(fc["위도"])
    a = np.sin((p2 - p1) / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(np.radians(fc["경도"] - lon) / 2) ** 2
    return fc[2 * EARTH_M * np.arcsin(np.sqrt(a)) <= RADIUS_M]


def week_for(fc: pd.DataFrame, gu: str, week_start: date) -> Week | None:
    """그 구의 이번 주."""
    return week_of(fc[fc["구"] == gu], week_start)


def _days_note(dates) -> str:
    ds = sorted(set(dates))
    return _short(ds[0]) if len(ds) == 1 else f"{WEEKDAYS[ds[0].weekday()]} ~ {WEEKDAYS[ds[-1].weekday()]}"


def _factors(g: pd.DataFrame, base: float) -> list[dict]:
    """이유별 기여(%) = 늘어난 인원을 그 상권 · 날의 모델 근거(+ 기여) 비율로 한 번만 나눠 이유별로 모은 것 / 이번 주 평소 인원 합.
    행사에서 나온 근거 몫은 그날 행사 이름으로, 나머지(작년 같은 시기 · 공휴일 · 벚꽃 철 …)는 근거 이름으로. 근거 없이 행사만
    있는 날은 전부 행사로. 그래서 이유들의 합은 늘어난 인원을 넘지 않는다."""
    acc: dict[str, dict] = {}
    for _, r in g[g["extra"] > 0].iterrows():
        event = r["행사"].split(" · ")[0] if r["행사"] else ""
        items = [(m["name"].strip(), int(m["pct"])) for m in (EVIDENCE_ITEM.match(t.strip()) for t in r["모델 근거"].split(" · "))
                 if m and int(m["pct"]) > 0]
        total = sum(p for _, p in items) or 1
        parts = [(event if name.startswith(EVENT_EVIDENCE) else name, p) for name, p in items if name not in NOT_REASON]
        parts = [(name, p) for name, p in parts if name] or ([(event, total)] if event else [])
        for name, p in parts:
            a = acc.setdefault(name, {"extra": 0.0, "dates": [], "top": (0.0, None, ""), "seats": 0.0, "event": name == event})
            share = r["extra"] * p / total
            a["extra"] += share
            a["dates"].append(r["날짜"])
            a["top"] = max(a["top"], (share, r["날짜"], r["상권"]), key=lambda t: t[0])
            if a["event"]:
                a["seats"] = max(a["seats"], r["좌석"])
    out = []
    for name, a in acc.items():
        if a["event"]:
            note = f"{_short(a['top'][1])} · {a['top'][2]}" + (f" · 좌석 {a['seats']:,.0f}" if a["seats"] > 0 else "")
        else:
            note = _days_note(a["dates"])
        out.append({"name": name, "note": note, "pct": round(100 * a["extra"] / base)})
    return sorted([f for f in out if f["pct"] >= 1], key=lambda f: -f["pct"])[:MAX_FACTORS]


def week_of(g: pd.DataFrame, week_start: date) -> Week | None:
    """고른 상권들의 이번 주(월 ~ 일). pct = 이번 주 예상 / 평소 - 1, days = 날짜별 예상 / 평소 × 100."""
    g = g[g["날짜"].between(week_start, week_start + timedelta(days=6))]
    daily = g.groupby("날짜")[["평소 인원", "예상 인원"]].sum()
    daily = daily[daily["평소 인원"] > 0]
    if daily.empty:
        return None
    base = daily["평소 인원"].sum()
    days = [{"date": d.isoformat(), "label": WEEKDAYS[d.weekday()], "value": round(100 * r["예상 인원"] / r["평소 인원"])}
            for d, r in daily.iterrows()]
    g = g.assign(extra=g["예상 인원"] - g["평소 인원"])

    factors = _factors(g, base)
    reason = f"{factors[0]['note'].split(' · ')[0]} {factors[0]['name']}" if factors else "특이 일정 없음"
    # 모델 근거 = 사람이 가장 많이 늘거나 준 상권 · 날의 SHAP 근거
    moved = g[g["모델 근거"] != ""].assign(size=lambda x: x["extra"].abs()).sort_values("size", ascending=False)
    evidence = [{"상권": r["상권"], "날짜": _short(r["날짜"]), "모델 근거": r["모델 근거"]}
                for _, r in moved.head(MAX_EVIDENCE).iterrows()]
    return Week(week_start, str(g["모델"].iloc[0]), round(100 * (daily["예상 인원"].sum() / base - 1)), reason, days, factors,
                evidence)


def explain(week: Week, gu: str, env: Mapping[str, str], chat: Callable | None = None) -> str | None:
    """GMS 설명 문장. 숫자 · 행사 · 모델 근거만 넘긴다. 설정이 없거나 실패하면 None (숫자 저장은 막지 않는다)."""
    if not all(env.get(k) for k in GMS_VARS):
        return None
    from weglee_ai.receipt.llm import chat as gms_chat
    from weglee_ai.receipt.prompt import load_prompt

    prompt = load_prompt("analysis", PROMPT_DIR)
    payload = {"구": gu, "기간": f"{week.week_start} 주", "평소 대비(%)": week.pct, "가장 큰 이유": week.reason,
               "요일별(평소=100)": {d["label"]: d["value"] for d in week.days}, "행사 기여": week.factors,
               "모델 근거": week.evidence, "모델": week.model}
    messages = [{"role": "system", "content": prompt.system},
                {"role": "user", "content": prompt.user(forecast=json.dumps(payload, ensure_ascii=False, indent=1))}]
    try:
        # temperature 는 보내지 않는다 — 팀 모델(gpt-6-luna)은 기본값만 받는다 (0 이면 400)
        text, _ = (chat or gms_chat)(messages, prompt.response_format(), base_url=env["GMS_BASE_URL"], key=env["GMS_KEY"],
                                     model=env["GMS_MODEL"], temperature=None)
        return json.loads(text)["analysis"].strip() or None
    except Exception:
        log.exception("설명 문장 실패 — %s, analysis 는 비운다", gu)
        return None


def _rows(out: str, n: int) -> list[list[str]]:
    """psql --csv -t 출력 → n 칸인 줄만 (명령 태그 BEGIN · INSERT 같은 줄은 칸 수가 달라 빠진다)."""
    return [r for r in csv.reader(io.StringIO(out)) if len(r) == n]


def load_target(target, fc: pd.DataFrame, week_start: date, weeks: dict, texts: dict, env: Mapping[str, str]) -> tuple[int, int]:
    """한 DB 에 이번 주 예측을 넣는다 — 가게 읽기 → 계산 → 한 트랜잭션으로 덮어쓰기. (저장, 건너뜀) 수.
    weeks · texts 는 DB 끼리 나눠 쓴다: 같은 상권 묶음은 한 번만 계산 · 설명(LLM 1번)."""
    previous = {int(r[0]): (int(r[1]), r[2], r[3]) for r in _rows(target.psql(PREVIOUS_SQL.format(week_start=lit(week_start))), 4)}
    stmts, skipped = [], 0
    for sid, sigungu, lat, lon in _rows(target.psql(STORES_SQL), 4):
        gu = gu_of(sigungu or None)
        key = None
        if lat and lon:
            rows = near(fc, float(lat), float(lon))
            if len(rows):
                key = ("near",) + tuple(sorted(rows["상권"].unique()))
                if key not in weeks:
                    weeks[key] = week_of(rows, week_start)
        if key is None and gu:
            key = gu
            if key not in weeks:
                weeks[key] = week_for(fc, gu, week_start)
        week = weeks.get(key) if key else None
        if week is None:
            skipped += 1
            continue
        old = previous.get(int(sid))
        if old and old[0] == week.pct and old[1] == week.reason and old[2]:
            text = old[2]  # 숫자가 그대로면 지난 문장 (LLM 을 다시 부르지 않는다)
        else:
            if key not in texts:
                area = f"가게 주변 {RADIUS_M // 1000}km" if key[0] == "near" else gu
                texts[key] = explain(week, area, env)
            text = texts[key]
        stmts.append(UPSERT_SQL.format(store_id=int(sid), week_start=lit(week.week_start), model=lit(week.model), pct=week.pct,
                                       reason=lit(week.reason), days=lit(json.dumps(week.days, ensure_ascii=False)),
                                       factors=lit(json.dumps(week.factors, ensure_ascii=False)), analysis=lit(text)))
    if stmts:
        target.psql("BEGIN;\n" + "\n".join(stmts) + "\nCOMMIT;\n")
    return len(stmts), skipped


def main(argv: list[str]) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if len(argv) != 1:
        raise SystemExit("사용법: python -m weglee_ai.sales_forecast <예측표.csv>")
    fc = load(argv[0])
    first = min(fc["날짜"])
    week_start = first - timedelta(days=first.weekday())
    weeks: dict = {}
    texts: dict = {}
    failed = []
    for target in targets(os.environ):
        try:  # 한 곳이 실패해도 다른 곳은 넣는다
            saved, skipped = load_target(target, fc, week_start, weeks, texts, os.environ)
            log.info("%s: 이번 주 매출 예측 %s 주, 모델 %s — 저장 %d곳 · 건너뜀 %d곳", target.name, week_start, fc["모델"].iloc[0], saved, skipped)
        except Exception:
            log.exception("%s: 적재 실패", target.name)
            failed.append(target.name)
    log.info("설명 문장 호출 %d번", len(texts))
    if failed:
        raise SystemExit(f"적재 실패: {', '.join(failed)}")
