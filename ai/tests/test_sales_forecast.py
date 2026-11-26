import json
from datetime import date

import pandas as pd
import pytest

from weglee_ai.sales_forecast import COLUMNS, explain, gu_of, load, near, week_for

MON = date(2026, 11, 9)
SUN = date(2026, 11, 15)
GMS = {"GMS_KEY": "k", "GMS_BASE_URL": "https://gms", "GMS_MODEL": "m"}


def _fc(rows):
    """(날짜, 상권, 구, 평소, 예상, 행사, 모델[, 모델 근거[, 좌석]]) → 계약 열 전부 (x · y 는 0)."""
    full = [tuple(r) + ("", 0)[len(r) - 7:] if len(r) < 9 else tuple(r) for r in rows]
    return pd.DataFrame([r[:8] + (0.0, 0.0, r[8]) for r in full], columns=COLUMNS)


def test_gu_of_seoul_only():
    assert gu_of("서울 송파구") == "송파구"
    assert gu_of("경기 성남시") is None
    assert gu_of(None) is None


def test_week_for_sums_gu_and_names_top_event_with_seats():
    fc = _fc([
        (MON, "잠실", "송파구", 100, 100, "", "m1"),
        (MON, "방이", "송파구", 100, 100, "", "m1"),
        (SUN, "잠실", "송파구", 100, 300, "잠실 마라톤 · 다른 행사", "m1", "", 30000),
        (SUN, "방이", "송파구", 100, 100, "", "m1"),
        (MON, "홍대", "마포구", 100, 500, "공연", "m1"),
    ])
    w = week_for(fc, "송파구", MON)
    assert w.model == "m1"
    assert w.pct == 50  # (200 + 400) / (200 + 200) - 1
    assert [d["value"] for d in w.days] == [100, 200]
    assert w.days[1] == {"date": "2026-11-15", "label": "일", "value": 200}
    assert w.factors == [{"name": "잠실 마라톤", "note": "일 11.15 · 잠실 · 좌석 30,000", "pct": 50}]
    assert w.reason == "일 11.15 잠실 마라톤"


def test_factors_split_extra_once_between_event_and_other_reasons():
    # 늘어난 200명 = 행사 근거 +60 : 작년 근거 +40 → 행사 120명(30%) · 작년 80명(20%), 합이 늘어난 인원(50%)을 넘지 않는다
    fc = _fc([
        (SUN, "잠실", "송파구", 400, 600, "잠실 마라톤", "m1", "근처 행사 수(1건) +60% · 작년 같은 시기 급증 +40%", 0),
    ])
    w = week_for(fc, "송파구", MON)
    assert w.pct == 50
    assert w.factors == [{"name": "잠실 마라톤", "note": "일 11.15 · 잠실", "pct": 30},
                         {"name": "작년 같은 시기 급증", "note": "일 11.15", "pct": 20}]
    assert sum(f["pct"] for f in w.factors) <= w.pct
    assert w.evidence == [{"상권": "잠실", "날짜": "일 11.15", "모델 근거": "근처 행사 수(1건) +60% · 작년 같은 시기 급증 +40%"}]


def test_calendar_evidence_is_not_a_reason():
    fc = _fc([(SUN, "잠실", "송파구", 400, 600, "", "m1", "요일 +50% · 작년 같은 시기 급증 +50%", 0)])
    assert week_for(fc, "송파구", MON).factors == [{"name": "작년 같은 시기 급증", "note": "일 11.15", "pct": 25}]


def test_week_for_without_events_or_rows():
    fc = _fc([(MON, "잠실", "송파구", 100, 90, "", "m1")])
    w = week_for(fc, "송파구", MON)
    assert (w.pct, w.factors, w.reason, w.evidence) == (-10, [], "특이 일정 없음", [])
    assert week_for(fc, "중구", MON) is None


def test_near_keeps_districts_within_radius():
    fc = _fc([(SUN, "잠실", "송파구", 100, 300, "", "m1"), (SUN, "홍대", "마포구", 100, 100, "", "m1")])
    fc.loc[0, ["위도", "경도"]] = [37.5112 + 0.0027, 127.0750]  # 북쪽 약 300m
    fc.loc[1, ["위도", "경도"]] = [37.5563, 126.9236]  # 홍대입구, 약 14km
    assert list(near(fc, 37.5112, 127.0750)["상권"]) == ["잠실"]  # 종합운동장역


def test_load_rejects_missing_contract_columns(tmp_path):
    p = tmp_path / "f.csv"
    _fc([(MON, "잠실", "송파구", 100, 90, None, "m1")]).drop(columns="모델 근거").to_csv(p, index=False, encoding="utf-8-sig")
    with pytest.raises(ValueError, match="모델 근거"):
        load(p)
    _fc([(MON, "잠실", "송파구", 100, 90, None, "m1")]).assign(extra=1).to_csv(p, index=False, encoding="utf-8-sig")
    assert list(load(p).columns) == COLUMNS


def test_explain_sends_only_numbers_and_evidence():
    fc = _fc([(SUN, "잠실", "송파구", 100, 300, "잠실 마라톤", "m1", "서울 큰 행사 때 반응(1.41배) +90%", 0)])
    sent = {}

    def fake_chat(messages, response_format, **kw):
        sent["system"], sent["user"], sent["format"] = messages[0]["content"], messages[1]["content"], response_format
        return json.dumps({"analysis": " 이번 주는 평소보다 200% 많아요. "}), {}

    text = explain(week_for(fc, "송파구", MON), "송파구", GMS, chat=fake_chat)
    assert text == "이번 주는 평소보다 200% 많아요."
    assert "(1.41배) +90%" in sent["user"] and "잠실 마라톤" in sent["user"]
    assert "날씨는 말하지 않는다" in sent["system"] and "넣어 두었다는 말도 하지 않는다" in sent["system"]
    assert sent["format"]["json_schema"]["schema"]["required"] == ["analysis"]


def test_explain_is_none_without_gms_or_on_failure():
    w = week_for(_fc([(MON, "잠실", "송파구", 100, 90, "", "m1")]), "송파구", MON)
    assert explain(w, "송파구", {}) is None

    def broken(*a, **k):
        raise RuntimeError("LLM 429")

    assert explain(w, "송파구", GMS, chat=broken) is None


class FakeTarget:
    """ssh → psql 대신: 보낸 SQL 을 모으고 정해 둔 결과를 돌려준다."""

    def __init__(self, stores, previous=""):
        self.name, self.sent, self.out = "STAGING", [], {"FROM store": stores, "FROM sales_forecast_week": previous}

    def psql(self, sql):
        self.sent.append(sql)
        return next((v for k, v in self.out.items() if k in sql and sql.startswith("SELECT")), "")


def test_load_target_writes_one_transaction_and_skips_outside_seoul():
    from weglee_ai.sales_forecast import load_target

    fc = _fc([(SUN, "잠실", "송파구", 100, 300, "잠실 마라톤", "m1", "", 0)])
    t = FakeTarget("1,서울 송파구,,\n2,경기 성남시,,\n")
    assert load_target(t, fc, MON, {}, {}, {}) == (1, 1)
    write = t.sent[-1]
    assert write.startswith("BEGIN;") and write.rstrip().endswith("COMMIT;") and write.count("INSERT INTO") == 1
    assert "VALUES (1, '2026-11-09', 'm1', 200," in write and "NULL)" in write  # GMS 설정이 없으면 analysis NULL


def test_load_target_reuses_previous_text_and_quotes_it():
    from weglee_ai.sales_forecast import load_target

    fc = _fc([(SUN, "잠실", "송파구", 100, 300, "잠실 마라톤", "m1", "", 0)])
    w = week_for(fc, "송파구", MON)
    # psql --csv: 여러 문단 설명(줄바꿈) · '|' · 쉼표가 든 값은 따옴표로 감싸져 온다
    t = FakeTarget("1,서울 송파구,,\n", f'1,{w.pct},{w.reason},"사장님\'s 설명 | 그대로, 첫 문단\n\n둘째 문단"\n')
    load_target(t, fc, MON, {}, {}, GMS)  # GMS 가 있어도 숫자가 그대로면 부르지 않는다 (가짜 키라 부르면 실패해 NULL)
    assert "'사장님''s 설명 | 그대로, 첫 문단\n\n둘째 문단')" in t.sent[-1]


def test_targets_reads_env_and_rejects_unsafe_names():
    from weglee_ai.sales_forecast import targets

    env = {"SALES_FORECAST_TARGETS": "STAGING", "STAGING_SSH": "ubuntu@host", "STAGING_PEM": "k.pem", "STAGING_NAMESPACE": "staging"}
    (t,) = targets(env)
    assert (t.ssh, t.namespace, t.pod, t.db) == ("ubuntu@host", "staging", "postgres-1", "zigzaego")
    with pytest.raises(ValueError, match="NAMESPACE"):
        targets(env | {"STAGING_NAMESPACE": "staging; rm -rf /"})
    with pytest.raises(SystemExit, match="MAIN_SSH"):
        targets(env | {"SALES_FORECAST_TARGETS": "STAGING,MAIN"})
