"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { CSSProperties } from "react";
import { useHome, type HomeItem } from "@/hooks/useHome";
import { useOrderDraft } from "@/hooks/useOrderDraft";
import { useOrderFeed } from "@/hooks/useOrderFeed";
import { useStore } from "@/hooks/useStore";
import { fmt, won } from "@/lib/format";
import { comparisonRows } from "@/lib/orderMessage";
import type { OrderFeed } from "@/lib/types";
const EMPTY_FEED: OrderFeed = { groups: [], fine: [], calendar: {}, today: "" };
import { goWithTransition, vt } from "@/lib/viewTransition";
import { SalesForecast, SalesForecastEmpty } from "./SalesForecast";
import { Screen } from "./Screen";
import { Skeleton, SkTitle, SK_BOX, SK_W } from "./Skeleton";
import styles from "./HomeTiles.module.css";

const Go = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M5 12h14M13 6l6 6-6 6" />
  </svg>
);

// 불러오는 동안: 품목명 · 수량 자리
function Rows() {
  return (
    <ul aria-busy="true">
      {SK_W.slice(0, 3).map((w) => (
        <li key={w}>
          <Skeleton w={w} h="1.3em" />
          <Skeleton w="3.2em" h="1.6em" />
        </li>
      ))}
    </ul>
  );
}

function Incoming({ items }: { items: HomeItem[] | undefined }) {
  if (!items) return <Rows />;
  if (!items.length)
    return (
      <ul>
        <li className={styles.none}>없음</li>
      </ul>
    );
  return (
    <ul>
      {items.map((x) => (
        <li key={x.id}>
          <span className={styles.nm}>{x.name}</span>
          <span className={`${styles.q} num`}>
            {x.n}
            <u>{x.u}</u>
          </span>
          <span className={styles.src}>{x.supplier}</span>
        </li>
      ))}
    </ul>
  );
}

// 홈 인사: "안녕하세요 예시식당 사장님!". 가게 이름이 없으면 "안녕하세요 사장님!"
export const greeting = (name?: string) =>
  `안녕하세요 ${name?.trim() ? `${name.trim()} ` : ""}사장님!`;

// 시안 home_v3.html: 인사말 옆 매출 예측 카드 + 큰 버튼 두 개 (DECISIONS #72).
// 발주 카드를 누르면 카드 · 품목명 · 수량이 발주 탭 목록 자리로 이어지고, 추천 품목이 담긴 채 열린다
export function HomeTiles() {
  const store = useStore();
  const router = useRouter();
  const { home, error } = useHome();
  const { feed, error: feedError } = useOrderFeed();
  const draft = useOrderDraft(feed ?? EMPTY_FEED, store?.id);
  const order =
    feed && comparisonRows(feed.groups, store?.priceCompare ?? true, draft.orders.qty, draft.src);
  const rowCount = Math.max(1, order?.rows.length ?? 0, home?.incoming.length ?? 0);
  // 카드 둘 다 받을 때까지 한 번에 스켈레톤 (하나씩 채워지지 않게)
  if (!error && !feedError && (!home || !feed)) return <HomeLoading />;
  return (
    <Screen>
      <div className={styles.hello}>
        <h1>{greeting(store?.displayName || store?.name)}</h1>
        <SalesForecast mini />
      </div>
      {(error || feedError) && <p className={styles.err}>불러오기 실패</p>}
      <div
        className={`${styles.home} ${styles.aligned}`}
        style={
          {
            "--home-rows": rowCount,
            "--home-span": rowCount + 3,
            "--home-summary": rowCount + 2,
            "--home-button": rowCount + 3,
          } as CSSProperties
        }
      >
        <Link
          href="/order"
          className={`${styles.big} ${styles.order}`}
          style={vt("rec-card")}
          onClick={(e) => {
            e.preventDefault();
            goWithTransition(() => router.push("/order"));
          }}
        >
          <h2 style={vt("rec-title")}>오늘 발주 추천 품목</h2>
          {!order ? (
            <Rows />
          ) : !order.rows.length ? (
            <ul>
              <li className={styles.none}>없음</li>
            </ul>
          ) : (
            <ul>
              {order.rows.map(({ line, src, cost, qty }) => (
                <li key={line.id}>
                  <span className={styles.nm} style={vt(`nm-${line.id}`)}>
                    {line.name}
                  </span>
                  <span className={`${styles.q} num`} style={vt(`q-${line.id}`)}>
                    {fmt(qty)}
                    <u>{line.unit}</u>
                  </span>
                  <span className={styles.src}>{src}</span>
                  <span className={`${styles.won} num`}>
                    {cost === null ? "가격 자료 없음" : won(cost)}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {order && order.saved > 0 && (
            <div className={styles.sum}>
              <span>기존 거래처에서만 살 때보다</span>
              <span className="num">−{won(order.saved)}</span>
            </div>
          )}
          <span className={styles.go}>
            발주하기
            {order && order.total > 0 && <span className="num">{won(order.total)}</span>}
            <Go />
          </span>
        </Link>
        <Link className={`${styles.big} ${styles.recv}`} href="/receiving">
          <h2>오늘 입고 예정 품목</h2>
          <Incoming items={home?.incoming} />
          <span className={styles.go}>
            발주 이력 보기
            <Go />
          </span>
        </Link>
      </div>
    </Screen>
  );
}

// 불러오는 동안 (AppGate · 홈 데이터 전): 글자 없이 같은 자리에 막대만
export function HomeLoading() {
  return (
    <Screen>
      <div className={styles.hello}>
        <h1>
          <SkTitle w="5.5em" />
        </h1>
        <SalesForecastEmpty mini />
      </div>
      <div className={styles.home} aria-busy="true">
        {[0, 1].map((k) => (
          <div key={k} className={`${styles.big} ${SK_BOX}`}>
            <Skeleton w="45%" h="var(--fs-big)" r="var(--r-input)" />
            <Rows />
            <span style={{ marginTop: "auto" }}>
              <Skeleton w="100%" h="var(--tile-btn-h)" r="var(--r-pill)" />
            </span>
          </div>
        ))}
      </div>
    </Screen>
  );
}
