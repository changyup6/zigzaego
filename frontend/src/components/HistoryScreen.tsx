"use client";

import { useState } from "react";
import { useIncoming, useMonthHistory } from "@/hooks/useHistory";
import { won } from "@/lib/format";
import { todayYmd } from "@/lib/today";
import { monthRange } from "@/lib/history";
import { Card } from "./Card";
import { MonthPicker } from "./MonthPicker";
import { ReceiptBlock } from "./ReceiptBlock";
import { Screen } from "./Screen";
import { Skeleton, SkTitle, SK_BOX, SK_PAD, SK_W } from "./Skeleton";
import styles from "./HistoryScreen.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");
const WD = ["일", "월", "화", "수", "목", "금", "토"];
const dayHead = (d: string) =>
  `${Number(d.slice(5, 7))}월 ${Number(d.slice(8, 10))}일 (${WD[new Date(`${d}T00:00:00Z`).getUTCDay()]})`;
const ymLabel = (ym: string) => `${ym.slice(0, 4)}년 ${Number(ym.slice(5, 7))}월`;

const Chev = ({ d }: { d: string }) => (
  <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
    <path d={d} />
  </svg>
);

// 불러오는 동안: 지난 발주 줄 자리 (품목 · 거래처 / 금액)
function TxRows() {
  return SK_W.slice(0, 4).map((w) => (
    <div key={w} className={styles.tx} aria-hidden="true">
      <span style={{ flex: 1 }}>
        <Skeleton w={w} h="1.1em" />
        <small>
          <Skeleton w="30%" h="0.9em" />
        </small>
      </span>
      <span className={styles.r}>
        <Skeleton w="5em" h="1.1em" />
      </span>
    </div>
  ));
}

// 지난 발주: ‹ 연월 › + 연월 선택판, 그 달 합계, 날짜별 줄 (은행 거래 내역 모양). 달마다 서버에서 불러온다
function Past() {
  const [ym, setYm] = useState(() => todayYmd().slice(0, 7));
  const [picking, setPicking] = useState(false);
  const { data, earliest, error } = useMonthHistory(ym);
  const months = monthRange(earliest, todayYmd());
  const i = months.indexOf(ym);
  const shown = data?.rows ?? [];
  const days = [...new Set(shown.map((r) => r.date))];
  const years = [...new Set(months.map((m) => Number(m.slice(0, 4))))];
  const go = (m: string) => {
    setYm(m);
    setPicking(false);
  };
  return (
    <Card className={styles.past}>
      <div className={styles.head}>
        <div className={styles.nav}>
          <button
            type="button"
            aria-label="지난달"
            disabled={i <= 0}
            onClick={() => go(months[i - 1])}
          >
            <Chev d="M15 5l-7 7 7 7" />
          </button>
          <button
            type="button"
            className={styles.ym}
            aria-expanded={picking}
            onClick={() => setPicking(!picking)}
          >
            {ymLabel(ym)}
            <Chev d="m6 9 6 6 6-6" />
          </button>
          <button
            type="button"
            aria-label="다음 달"
            disabled={i >= months.length - 1}
            onClick={() => go(months[i + 1])}
          >
            <Chev d="M9 6l6 6-6 6" />
          </button>
        </div>
        <span className={styles.sum}>
          합계 <b className="num">{data ? won(data.total) : "–"}</b>
        </span>
      </div>
      {picking && (
        <MonthPicker
          value={ym}
          years={[years[0], years[years.length - 1]]}
          enabled={(m) => months.includes(m)}
          onPick={go}
        />
      )}
      {error && <p className={styles.empty}>불러오기 실패</p>}
      {!data && !error && <TxRows />}
      {data && !shown.length && <p className={styles.empty}>발주 없음</p>}
      {days.map((d) => (
        <section key={d}>
          <h3 className={styles.date}>{dayHead(d)}</h3>
          {shown
            .filter((r) => r.date === d)
            .map((r) => {
              const off = r.status === "취소";
              return (
                <div key={r.id} className={cx(styles.tx, off && styles.off)}>
                  <span>
                    <b>{r.name}</b>
                    <small>
                      {r.supplier}
                      {!off && ` · ${r.n} ${r.u}`}
                    </small>
                  </span>
                  <span className={styles.r}>
                    {off ? (
                      <b>취소</b>
                    ) : (
                      <>
                        <b className="num">{r.amount === null ? "–" : won(r.amount)}</b>
                        <small className={cx(r.status === "입고 예정" && styles.redT)}>
                          {r.status}
                        </small>
                      </>
                    )}
                  </span>
                </div>
              );
            })}
        </section>
      ))}
    </Card>
  );
}

// 발주 이력 탭 (시안 app_all.html viewReceiving, DECISIONS #62 · #66): 오늘 입고 예정 + 지난 발주 + 영수증
export function HistoryScreen() {
  const { data, error } = useIncoming();
  if (!data && !error) return <HistoryLoading />;
  return (
    <Screen title="발주 이력">
      {error && (
        <Card>
          <p>불러오기 실패</p>
        </Card>
      )}
      {data && (
        <>
          <section className={styles.inc}>
            <div className={styles.incH}>
              <h2>오늘 입고 예정</h2>
              <span className="num">{data.length}</span>
            </div>
            {data.length ? (
              data.map((x) => (
                <div key={x.id} className={styles.incRow}>
                  <span>
                    <b>{x.name}</b>
                    <small>
                      <span>{x.supplier}</span>
                      {x.pack && <span>{x.pack}</span>}
                    </small>
                  </span>
                  <span className={`${styles.q} num`}>
                    {x.n}
                    <u>{x.u}</u>
                  </span>
                </div>
              ))
            ) : (
              <p className={styles.empty}>없음</p>
            )}
          </section>
          <Past />
        </>
      )}
      <ReceiptBlock />
    </Screen>
  );
}

// 불러오는 동안 (AppGate · 입고 예정 전): 오늘 입고 예정 판 + 지난 발주 카드. 글자 없이 막대만
export function HistoryLoading() {
  return (
    <Screen title={<SkTitle w="4.2em" />}>
      <section className={`${SK_BOX} ${SK_PAD}`} aria-busy="true">
        <Skeleton w="7em" h="1.3em" />
        {SK_W.slice(0, 2).map((w) => (
          <Skeleton key={w} w={w} h="1.1em" />
        ))}
      </section>
      <Card className={styles.past}>
        <div className={styles.head}>
          <Skeleton w="9em" h="2em" r="var(--r-pill)" />
          <Skeleton w="6em" h="1.2em" />
        </div>
        <TxRows />
      </Card>
    </Screen>
  );
}
