"""예측 vs 실제 채점 (S15P31A308-78). 앞쪽은 CI, 마지막은 batch 그룹 + Docker 에서 SQL 과 pandas 결과를 맞춘다."""

from pathlib import Path

import pandas as pd
import pytest

from weglee_ai.accuracy import ACCURACY_SQL, ALL_CHANNELS, join_actual, score


def _sales(rows):
    return pd.DataFrame(rows, columns=["sold_date", "menu_id", "channel", "qty"])


def _fc(rows, model="timesfm"):
    return pd.DataFrame([(d, m, c, model, p) for d, m, c, p in rows],
                        columns=["target_date", "menu_id", "channel", "model", "predicted_qty"])


SALES = _sales([
    ("2026-10-12", 1, "HALL", 10), ("2026-10-12", 2, "HALL", 4),
    ("2026-10-13", 2, "HALL", 5),                                  # 1번 메뉴 미판매 → 실제 0
    ("2026-10-12", 1, "DELIVERY", 6),                              # 10/13 배달은 휴무
])
FC = _fc([
    ("2026-10-12", 1, "HALL", 12), ("2026-10-12", 2, "HALL", 4), ("2026-10-13", 1, "HALL", 3),
    ("2026-10-13", 2, "HALL", 5), ("2026-10-12", 1, "DELIVERY", 3), ("2026-10-13", 1, "DELIVERY", 7),
])


def test_휴무일은_빼고_영업일_미판매는_0():
    j = join_actual(FC, SALES)
    assert len(j) == 5  # 10/13 배달(휴무) 1행 제외
    zero = j[(j["menu_id"] == 1) & (j["channel"] == "HALL") & (j["target_date"] == "2026-10-13")]
    assert zero["actual"].item() == 0.0


def test_채널별과_합산을_pooled로():
    s = score(join_actual(FC, SALES)).set_index("channel")
    # 홀: |10-12|+|4-4|+|0-3|+|5-5| = 5, Σ실제 19
    assert s.loc["HALL", "wape"] == pytest.approx(5 / 19)
    assert s.loc["HALL", "bias"] == pytest.approx((24 - 19) / 19)
    # 배달: |6-3| = 3, Σ실제 6
    assert s.loc["DELIVERY", "wape"] == pytest.approx(3 / 6)
    # 합산: 10/12 메뉴1 = 실제 16 · 예측 15 → 메뉴·날짜로 더한 뒤 채점
    assert s.loc[ALL_CHANNELS, "actual"] == 25
    assert s.loc[ALL_CHANNELS, "wape"] == pytest.approx((1 + 0 + 3 + 0) / 25)


def test_모델별로_따로_채점한다():
    both = pd.concat([FC, _fc([("2026-10-12", 1, "HALL", 10)], model="dow_avg")])
    s = score(join_actual(both, SALES))
    assert set(s["model"]) == {"timesfm", "dow_avg"}
    assert s[(s["model"] == "dow_avg") & (s["channel"] == "HALL")]["wape"].item() == 0.0


def test_채점할_행이_없으면_같은_열의_빈_표():
    out = score(join_actual(_fc([("2026-10-20", 1, "HALL", 3)]), SALES))  # 판매 기록 밖 날짜
    assert out.empty and list(out.columns) == ["model", "channel", "rows", "actual", "wape", "bias"]


# SQL ↔ pandas 비교용 경계 데이터 (리뷰 MEDIUM 1 · 3)
EDGE_SALES = _sales(list(SALES.itertuples(index=False)) + [
    ("2026-10-14", 1, "HALL", 0),                                  # qty 0 행만 있는 날 → 휴무
    ("2026-10-13", 3, "HALL", 2),                                  # 메뉴 3 첫 판매 10/13
    ("2026-10-12", 1, "NONE", 7), ("2026-10-13", 1, "NONE", 1),    # 채널 구분 없는 계열
])
EDGE_FC = _fc([(d, m, c, p) for d, m, c, _, p in FC.itertuples(index=False)] + [
    ("2026-10-14", 1, "HALL", 4),     # qty 0 만 있는 날 — 양쪽 다 빠져야 한다
    ("2026-10-12", 3, "HALL", 1),     # 메뉴 3 첫 판매 이전 — 실제 0
    ("2026-10-12", 4, "HALL", 2),     # 판매가 전혀 없는 메뉴 — 실제 0
    ("2026-10-12", 1, "NONE", 7), ("2026-10-13", 1, "NONE", 2),
])



def _require_docker():
    docker = pytest.importorskip("docker")
    requests = pytest.importorskip("requests")
    client = None
    try:
        client = docker.from_env()
        client.ping()
    except (docker.errors.DockerException, requests.exceptions.RequestException) as e:
        pytest.skip(f"Docker 연결 불가: {e}")
    finally:
        if client is not None:
            client.close()


def test_SQL과_pandas가_같은_값():
    psycopg = pytest.importorskip("psycopg")
    postgres = pytest.importorskip("testcontainers.postgres")
    _require_docker()
    container = postgres.PostgresContainer("postgres:18", driver=None)
    container.start()  # 연결 가능한 Docker 의 이미지 · 시작 오류는 실패해야 한다.
    v1 = Path(__file__).resolve().parents[2] / "backend/src/main/resources/db/migration/V1__init_schema.sql"
    try:
        with psycopg.connect(container.get_connection_url(), autocommit=True) as c:
            c.execute(v1.read_text(encoding="utf-8"))
            store = c.execute("INSERT INTO store (name, display_name) VALUES ('가', '가') RETURNING id").fetchone()[0]
            ids = {m: c.execute("INSERT INTO menu (store_id, name, source) VALUES (%s, %s, 'POS') RETURNING id",
                                (store, f"M{m}")).fetchone()[0] for m in (1, 2, 3, 4)}
            for d, m, ch, q in EDGE_SALES.itertuples(index=False):
                c.execute("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (%s,%s,%s,%s,%s)",
                          (store, d, ids[m], q, ch))
            for d, m, ch, model, p in EDGE_FC.itertuples(index=False):
                c.execute("INSERT INTO forecast (store_id, menu_id, target_date, predicted_qty, model, channel) "
                          "VALUES (%s,%s,%s,%s,%s,%s)", (store, ids[m], d, p, model, ch))
            from weglee_ai.accuracy import AccuracyRepository

            # 다른 가게와 기간 밖 행은 채점 입력에 섞이지 않아야 한다.
            other = c.execute("INSERT INTO store (name, display_name) VALUES ('나', '나') RETURNING id").fetchone()[0]
            other_menu = c.execute("INSERT INTO menu (store_id, name, source) VALUES (%s, '다른 메뉴', 'POS') RETURNING id", (other,)).fetchone()[0]
            for sid, mid, day in [(other, other_menu, "2026-10-12"), (store, ids[1], "2026-10-11"), (store, ids[1], "2026-10-15")]:
                c.execute("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (%s,%s,%s,99,'HALL')", (sid, day, mid))
                c.execute("INSERT INTO forecast (store_id, menu_id, target_date, predicted_qty, model, channel) "
                          "VALUES (%s,%s,%s,99,'timesfm','HALL')", (sid, mid, day))
            forecasts, sales = AccuracyRepository(c).inputs(store, "2026-10-12", "2026-10-14")
            assert len(forecasts) == len(EDGE_FC) and len(sales) == len(EDGE_SALES)
            assert forecasts["predicted_qty"].dtype == float and sales["qty"].dtype == float
            rows = c.execute(ACCURACY_SQL, {"store_id": store, "start": "2026-10-12", "end": "2026-10-14"}).fetchall()
    finally:
        container.stop()
    def as_float(v):
        return float("nan") if v is None else float(v)  # Σ실제 0 → SQL NULL
    sql = {(r[0], r[1]): (r[2], as_float(r[4]), as_float(r[5])) for r in rows}
    pd_ = score(join_actual(EDGE_FC, EDGE_SALES))
    by_channel = pd_[pd_["channel"] != ALL_CHANNELS]
    assert set(sql) == {(r.model, r.channel) for r in by_channel.itertuples()}  # 양쪽 키가 같다
    for r in by_channel.itertuples():
        assert sql[(r.model, r.channel)] == (r.rows, pytest.approx(r.wape, nan_ok=True),
                                             pytest.approx(r.bias, nan_ok=True))


def test_Docker가_있는데_컨테이너_시작에_실패하면_skip하지_않는다(monkeypatch):
    pytest.importorskip("psycopg")
    postgres = pytest.importorskip("testcontainers.postgres")
    _require_docker()

    class BrokenContainer:
        def __init__(self, *args, **kwargs):
            pass

        def start(self):
            raise RuntimeError("잘못된 컨테이너 설정")

    monkeypatch.setattr(postgres, "PostgresContainer", BrokenContainer)
    try:
        with pytest.raises(RuntimeError, match="잘못된 컨테이너 설정"):
            test_SQL과_pandas가_같은_값()
    except pytest.skip.Exception:
        pytest.fail("연결 가능한 Docker에서 컨테이너 시작 오류를 skip하면 안 된다")
