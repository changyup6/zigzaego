"""야간 예측 배치의 DB 쪽. Spring 과 DB 테이블로만 주고받는다 (DECISIONS #25).

접속 정보는 백엔드와 같은 환경변수를 쓴다: DB_URL(JDBC 형식) · DB_USERNAME · DB_PASSWORD.
인프라는 BE 의 env 를 그대로 배치 CronJob 에 넣으면 된다.
"""

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from urllib.parse import parse_qsl

import pandas as pd

from weglee_ai.batch.run import ForecastRow, Store

_JDBC = re.compile(r"^(?:jdbc:)?postgresql://(?P<host>[^:/?\[\]]+)(?::(?P<port>\d+))?/(?P<dbname>[^?]+)(?:\?(?P<query>.*))?$")
# JDBC 쿼리 파라미터 중 배치가 그대로 넘기는 것. 나머지는 조용히 버리지 않고 거부한다 (Spring 과 접속 방식이 갈리지 않게)
_PASSTHROUGH = {"sslmode"}
# TimesFM 은 최근 512일, 요일 평균은 4주만 쓴다. 그보다 오래된 판매는 읽지 않는다
HISTORY_DAYS = 600


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: int
    dbname: str
    user: str
    password: str
    sslmode: str | None = None

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> "DbConfig":
        url = env.get("DB_URL", "jdbc:postgresql://localhost:5432/zigzaego")
        m = _JDBC.match(url)
        if not m:
            # 쿼리 부분(비밀값이 있을 수 있다)은 메시지에 넣지 않는다
            raise ValueError(f"DB_URL 형식이 jdbc:postgresql://호스트:포트/DB 가 아니다: {url.split('?')[0]}")
        params = dict(parse_qsl(m["query"] or ""))
        unknown = sorted(set(params) - _PASSTHROUGH)
        if unknown:
            raise ValueError(f"DB_URL 의 지원하지 않는 파라미터: {', '.join(unknown)} (지원: {', '.join(sorted(_PASSTHROUGH))})")
        user = env.get("DB_USERNAME", "")
        if not user:
            raise ValueError("DB_USERNAME 이 비어 있다")
        return cls(m["host"], int(m["port"] or 5432), m["dbname"], user, env.get("DB_PASSWORD", ""),
                   params.get("sslmode"))


class PostgresRepository:
    """run.Repository 구현. 가게마다 저장을 한 트랜잭션으로 묶는다.

    연결은 autocommit 으로 연다. 그래야 save_forecasts 의 transaction() 이 진짜 트랜잭션이 된다 —
    autocommit 이 꺼져 있으면 앞선 SELECT 가 연 바깥 트랜잭션 안의 세이브포인트가 되고, 연결을 닫을 때 전부 롤백된다.
    """

    def __init__(self, conn) -> None:
        self.conn = conn

    @classmethod
    def connect(cls, config: DbConfig) -> "PostgresRepository":
        import psycopg  # batch 의존성 그룹에만 있다

        extra = {"sslmode": config.sslmode} if config.sslmode else {}
        conn = psycopg.connect(host=config.host, port=config.port, dbname=config.dbname, user=config.user,
                               password=config.password, application_name="ai-batch-forecast", autocommit=True, **extra)
        return cls(conn)

    def close(self) -> None:
        self.conn.close()

    def stores(self) -> list[Store]:
        with self.conn.cursor() as cur:
            cur.execute("SELECT id, timezone FROM store ORDER BY id")
            return [Store(row[0], row[1]) for row in cur.fetchall()]

    def sales(self, store_id: int, before: date) -> pd.DataFrame:
        with self.conn.cursor() as cur:
            cur.execute(
                """
                SELECT s.sold_date, s.menu_id, s.channel, s.qty
                FROM pos_sale s JOIN menu m ON m.id = s.menu_id AND m.store_id = s.store_id
                WHERE s.store_id = %s AND s.sold_date < %s AND s.sold_date >= %s AND m.active
                """,
                (store_id, before, before - timedelta(days=HISTORY_DAYS)),
            )
            rows = cur.fetchall()
        frame = pd.DataFrame(rows, columns=["sold_date", "menu_id", "channel", "qty"])
        frame["qty"] = frame["qty"].astype(float)
        return frame

    def closed_days(self, store_id: int, start: date, end: date) -> set[date]:
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT closed_date FROM store_closed_day WHERE store_id = %s AND closed_date BETWEEN %s AND %s",
                (store_id, start, end),
            )
            return {row[0] for row in cur.fetchall()}

    def replace_forecasts(
        self, store_id: int, models: Sequence[str], start: date, end: date, rows: Sequence[ForecastRow]
    ) -> None:
        with self.conn.transaction(), self.conn.cursor() as cur:
            cur.execute(
                "DELETE FROM forecast WHERE store_id = %s AND model = ANY(%s) AND target_date BETWEEN %s AND %s",
                (store_id, list(models), start, end),
            )
            cur.executemany(
                """
                INSERT INTO forecast (store_id, menu_id, target_date, predicted_qty, model, channel)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (store_id, menu_id, target_date, model, channel)
                DO UPDATE SET predicted_qty = EXCLUDED.predicted_qty, created_at = now()
                """,
                [(store_id, r.menu_id, r.target_date, r.predicted_qty, r.model, r.channel) for r in rows],
            )
