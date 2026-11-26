"""야간 예측 배치 ↔ 실제 Postgres (V1 스키마). batch 의존성 그룹과 Docker 가 있을 때만 돈다.

uv sync --group batch && uv run pytest tests/test_batch_db.py
"""

from datetime import date, timedelta
from pathlib import Path

import pytest

psycopg = pytest.importorskip("psycopg")
postgres = pytest.importorskip("testcontainers.postgres")

from weglee_ai.batch.db import DbConfig, PostgresRepository  # noqa: E402
from weglee_ai.batch.predictors import DowAvgPredictor  # noqa: E402
from weglee_ai.batch.run import HORIZON_DAYS, run_batch  # noqa: E402


class BrokenTimesFm:
    name = "timesfm"

    def predict(self, series, targets):
        raise RuntimeError("모델 로드 실패 흉내")

V1 = Path(__file__).resolve().parents[2] / "backend/src/main/resources/db/migration/V1__init_schema.sql"
TODAY = date(2026, 10, 16)


@pytest.fixture(scope="module")
def conn():
    try:
        container = postgres.PostgresContainer("postgres:18", driver=None)
        container.start()
    except Exception as e:  # Docker 가 없으면 건너뛴다 (CI)
        pytest.skip(f"Docker 없음: {e}")
    try:
        config = DbConfig.from_env({
            "DB_URL": f"jdbc:postgresql://{container.get_container_host_ip()}:{container.get_exposed_port(5432)}/{container.dbname}",
            "DB_USERNAME": container.username,
            "DB_PASSWORD": container.password,
        })
        with psycopg.connect(container.get_connection_url(), autocommit=True) as admin:
            admin.execute(V1.read_text(encoding="utf-8"))
        yield config
    finally:
        container.stop()


def _seed(c):
    store = c.execute("INSERT INTO store (name, display_name) VALUES ('가게', '가게') RETURNING id").fetchone()[0]
    other = c.execute("INSERT INTO store (name, display_name) VALUES ('남의가게', '남의가게') RETURNING id").fetchone()[0]
    menu = c.execute("INSERT INTO menu (store_id, name, source) VALUES (%s, 'A', 'POS') RETURNING id", (store,)).fetchone()[0]
    stopped = c.execute(
        "INSERT INTO menu (store_id, name, source, active) VALUES (%s, 'B', 'POS', false) RETURNING id", (store,)
    ).fetchone()[0]
    for k in range(1, 29):
        day = TODAY - timedelta(days=k)
        c.execute("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (%s, %s, %s, 10, 'HALL')",
                  (store, day, menu))
        c.execute("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (%s, %s, %s, 4, 'DELIVERY')",
                  (store, day, menu))
        c.execute("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (%s, %s, %s, 3, 'HALL')",
                  (store, day, stopped))
    c.execute("INSERT INTO store_closed_day (store_id, closed_date) VALUES (%s, %s)", (store, TODAY + timedelta(days=1)))
    return store, other, menu


def _forecasts(c, store):
    return c.execute(
        "SELECT menu_id, channel, target_date, model, predicted_qty FROM forecast WHERE store_id = %s ORDER BY 2, 3",
        (store,),
    ).fetchall()


def test_요일_평균을_forecast에_저장하고_다시_돌리면_덮어쓴다(conn):
    # 배치 실행 경로와 같은 PostgresRepository.connect 로 연결하고, 확인은 별도 연결로 한다 — 커밋됐는지까지 본다
    config = conn
    check = psycopg.connect(host=config.host, port=config.port, dbname=config.dbname, user=config.user,
                            password=config.password, autocommit=True)
    repo = PostgresRepository.connect(config)
    try:
        _run_and_check(check, repo)
    finally:
        repo.close()
        check.close()


def _run_and_check(check, repo):
    store, other, menu = _seed(check)
    # 어제 배치가 만든 오늘자 timesfm 값 — 오늘 TimesFM 이 실패하면 지워져야 한다
    check.execute("INSERT INTO forecast (store_id, menu_id, target_date, predicted_qty, model, channel) "
                  "VALUES (%s, %s, %s, 99, 'timesfm', 'HALL')", (store, menu, TODAY))

    summary = run_batch(repo, [BrokenTimesFm(), DowAvgPredictor()], lambda s: TODAY)
    rows = _forecasts(check, store)

    assert summary.stores_failed == []
    assert {r[0] for r in rows} == {menu}  # 판매 중지 메뉴(B)는 예측하지 않는다
    assert {r[1] for r in rows} == {"HALL", "DELIVERY"}
    assert TODAY + timedelta(days=1) not in {r[2] for r in rows}  # 휴무일
    assert len(rows) == 2 * (HORIZON_DAYS - 1)
    assert {(r[1], float(r[4])) for r in rows} == {("HALL", 10.0), ("DELIVERY", 4.0)}
    assert {r[3] for r in rows} == {"dow_avg"}  # 어제 timesfm 행은 지워졌다
    assert summary.model_failures == [(store, "timesfm")]
    assert _forecasts(check, other) == []

    run_batch(repo, [DowAvgPredictor()], lambda s: TODAY)
    assert len(_forecasts(check, store)) == len(rows)  # 다시 돌려도 같은 행 수 (지우고 새로 넣기)
