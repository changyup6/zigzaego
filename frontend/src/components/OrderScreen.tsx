"use client";

import { useEffect, useLayoutEffect, useState } from "react";
import { useOrderFeed } from "@/hooks/useOrderFeed";
import { useOrderDraft } from "@/hooks/useOrderDraft";
import { useOrderMessages } from "@/hooks/useOrderMessages";
import { useStore } from "@/hooks/useStore";
import { fmt, won } from "@/lib/format";
import { lineState, shownQty, type Orders } from "@/lib/orderHome";
import { bySource, resolvedSource, sourceOptions } from "@/lib/orderMessage";
import { takeRecipeToast } from "@/lib/recipeToast";
import { productReference } from "@/lib/productReference";
import type { OrderFeed, OrderLine, SupplierOrderGroup } from "@/lib/types";
import { takeFromHome, transitionReady, vt } from "@/lib/viewTransition";
import { ActionBar } from "./ActionBar";
import { Card } from "./Card";
import { PriceTrend } from "./PriceTrend";
import { OrderMessageSheet } from "./OrderMessageSheet";
import { SalesForecast, SalesForecastEmpty } from "./SalesForecast";
import { Screen } from "./Screen";
import { Skeleton, SkTitle, SK_W } from "./Skeleton";
import { useToast } from "./Toast";
import styles from "./OrderScreen.module.css";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

const Chev = () => (
  <svg className={styles.chev} viewBox="0 0 24 24" aria-hidden="true">
    <path d="M6 9l6 6 6-6" />
  </svg>
);
const Crown = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M3 8l4.5 4L12 5l4.5 7L21 8l-2 11H5z" />
  </svg>
);

// 포장 하나 값: "20,450원/1망". 포장 이름을 모르면 기준 단위 묶음 (예 "/15kg")
const packUnit = (l: OrderLine) =>
  l.msgByPack && l.msgUnit ? `1${l.msgUnit}` : `${fmt(l.pack)}${l.unit}`;
// 주문처 줄은 짧게: "20,000원/봉" (포장 이름이 있으면 1 을 뺀다)
const packShort = (l: OrderLine) =>
  l.msgByPack && l.msgUnit ? l.msgUnit : `${fmt(l.pack)}${l.unit}`;

// 품목 드롭다운 (시안 order_v5.html panel): 발주량 · 8주 단가 · 주문처 표(기존 거래처 먼저, 나머지 최저가순)
function Panel({
  line,
  group,
  orders,
  chosen,
  compare,
  onQty,
  onPut,
}: {
  line: OrderLine;
  group: SupplierOrderGroup;
  compare: boolean;
  orders: Orders;
  chosen: string | null;
  onQty: (q: number) => void;
  onPut: (key: string) => void;
}) {
  const q = shownQty(orders, line.id);
  const os = sourceOptions(line, group, compare, q).filter((o) => o.price !== null);
  const rec = resolvedSource(line, group, compare).key;
  const rows = os.length ? [os[0], ...os.slice(1).sort((a, b) => a.price! - b.price!)] : [];
  return (
    <div className={styles.panel}>
      <div className={styles.head}>
        <div className={styles.qty}>
          <div>
            <div className={styles.lb}>발주량</div>
            <div className={`${styles.qv} num`}>
              {fmt(q)}
              <small>{line.unit}</small>
            </div>
            <div className={styles.pk}>
              {line.packLabel && (
                <span>
                  {line.packLabel}
                  <br />
                </span>
              )}
              {packUnit(line)} = {fmt(line.pack)}
              {line.unit} · {fmt(q / line.pack)}묶음
            </div>
          </div>
          <div className={styles.stp}>
            <button
              type="button"
              aria-label="빼기"
              disabled={q <= line.pack}
              onClick={() => onQty(Math.max(line.pack, q - line.pack))}
            >
              −
            </button>
            <button
              type="button"
              aria-label="더하기"
              disabled={q + line.pack > line.maxQty}
              onClick={() => onQty(q + line.pack)}
            >
              +
            </button>
          </div>
        </div>
        {line.price ? (
          <PriceTrend price={line.price} name={group.name} unit={line.unit} compare={compare} />
        ) : (
          <div className={styles.chart}>단가 이력 없음</div>
        )}
      </div>
      <div className={styles.offs}>
        {rows.map((o) => {
          const picked = chosen === o.key;
          const url = productReference(o.url);
          return (
            <div key={o.key} className={cx(styles.off, picked && styles.picked)}>
              {url && (
                <a
                  className={styles.productLink}
                  href={url}
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`${line.name} · ${o.name} 상품 보기 (새 탭)`}
                />
              )}
              <span className={styles.who}>
                {o.key === rec && (
                  <span className={styles.crown} role="img" aria-label="추천 주문처">
                    <Crown />
                  </span>
                )}
                <span className={styles.sn}>
                  {o.name}
                  {o.key === group.id && <span className={styles.mine}>기존 거래처</span>}
                  {o.lowest && <span className={styles.lowt}>최저가</span>}
                </span>
              </span>
              <span className={styles.pr}>
                <span className={`${styles.u} num`}>
                  {o.packPrice != null
                    ? `${won(o.packPrice)} · ${o.spec || o.productName || `${fmt(o.packQty ?? 0)}${line.unit}`}`
                    : `${won(o.price! * line.pack)}/${packShort(line)}`}
                  <small>
                    {won(o.price!)}/{line.unit}
                  </small>
                </span>
                <span className={cx(styles.t, "num", o.lowest && styles.low)}>
                  {won(o.price! * q)}
                </span>
              </span>
              <span className={styles.eta}>
                {o.stale
                  ? `${o.on ?? "어제"} 가격`
                  : o.available === false
                    ? "배송 미확인"
                    : o.key === group.id
                      ? `${group.deliveryDate} 도착`
                      : ""}
              </span>
              <button
                type="button"
                className={styles.put}
                disabled={o.stale || o.available === false}
                onClick={() => onPut(o.key)}
              >
                {picked ? "취소" : "담기"}
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// 발주 탭 (시안 order_v5.html, DECISIONS #72): 왼쪽 오늘 발주 추천 품목 → 누르면 드롭다운,
// 오른쪽 이번 주 매출 예측 + 거래처별 발주 목록. 담은 줄만 발주하기 → 주문 메시지 (DECISIONS #63)
function Board({ feed }: { feed: OrderFeed }) {
  const [placed, setPlaced] = useState<string[]>([]);
  const groups = feed.groups
    .map((g) => ({ ...g, lines: g.lines.filter((l) => !placed.includes(l.id)) }))
    .filter((g) => g.lines.length > 0);
  const lines = groups.flatMap((g) => g.lines);
  const store = useStore();
  const act = useOrderDraft(feed, store?.id);
  // 가격 비교를 끄면 기존 거래처만 (#48)
  const compare = store?.priceCompare ?? true;
  const { orders } = act;
  // 담은 줄의 주문처
  const { src, setSrc } = act;
  const [open, setOpen] = useState<string | null>(null);
  const { toast, show } = useToast();
  useEffect(() => {
    const text = takeRecipeToast();
    if (text) show(text);
  }, [show]);
  // 홈 발주 카드에서 넘어왔으면 추천 품목을 추천 주문처로 담고, 그린 뒤 화면 전환을 끝낸다.
  // 탭을 오가도 화면이 살아 있어(Next 가 이전 화면을 숨겨 둠) 마운트가 아니라 보일 때마다 확인한다
  const fillFromHome = () => {
    act.fillAll();
  };
  useLayoutEffect(() => {
    // 홈에서 넘어온 신호(lib/viewTransition)는 화면 밖 값이라 보이는 순간 한 번 반영한다
    if (takeFromHome()) fillFromHome();
    transitionReady();
    // 보일 때마다 한 번. groups · act 는 그 순간 값이면 된다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const sheet = useOrderMessages(
    groups,
    orders,
    store?.displayName || store?.name || "",
    (ids) => setPlaced((p) => [...p, ...ids]),
    compare,
  );
  const inCart = (id: string) => lineState(orders, id) === "approved";
  const chosen = Object.fromEntries(
    lines
      .filter((l) => inCart(l.id))
      .map((l) => [
        l.id,
        resolvedSource(
          l,
          groups.find((g) => g.lines.includes(l))!,
          compare,
          src[l.id],
        ).key,
      ]),
  );
  const cart = bySource(
    groups.map((g) => ({ ...g, lines: g.lines.filter((l) => inCart(l.id)) })),
    chosen,
  );
  const count = cart.reduce((n, g) => n + g.lines.length, 0);
  const total = cart.reduce(
    (s, g) => s + g.lines.reduce((t, l) => t + l.unitPrice * orders.approved[l.id], 0),
    0,
  );
  const put = (id: string, key: string) => {
    if (inCart(id) && chosen[id] === key) return act.approve(id);
    if (!inCart(id)) act.approve(id);
    setSrc((s) => ({ ...s, [id]: key }));
  };
  const order = () => sheet.show(chosen);

  return (
    <Screen title="발주">
      <div className={styles.ord}>
        <div className={styles.list} style={vt("rec-card")}>
          <div className={styles.sec}>
            <h2 style={vt("rec-title")}>오늘 발주 추천 품목</h2>
            <span className="num">{lines.length}</span>
          </div>
          {lines.length === 0 && <p className={styles.empty}>오늘 주문 없음</p>}
          {groups.flatMap((g) =>
            g.lines.map((l) => {
              const on = open === l.id;
              return (
                <div
                  key={l.id}
                  className={cx(styles.item, on && styles.open, inCart(l.id) && styles.in)}
                >
                  <button
                    type="button"
                    className={styles.ih}
                    aria-expanded={on}
                    onClick={() => setOpen(on ? null : l.id)}
                  >
                    <span className={styles.nm} style={vt(`nm-${l.id}`)}>
                      {l.name}
                    </span>
                    <span className={styles.got}>
                      {inCart(l.id) && (
                        <span className="num" style={vt(`q-${l.id}`)}>
                          {fmt(orders.approved[l.id])}
                          {l.unit}
                        </span>
                      )}
                      <i>
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M5 12l5 5L20 7" />
                        </svg>
                      </i>
                    </span>
                    <Chev />
                  </button>
                  <div className={styles.fold}>
                    <div>
                      {on && (
                        <Panel
                          line={l}
                          group={g}
                          orders={orders}
                          chosen={inCart(l.id) ? chosen[l.id] : null}
                          compare={compare}
                          onQty={(q) => act.changeQty(l.id, q)}
                          onPut={(key) => put(l.id, key)}
                        />
                      )}
                    </div>
                  </div>
                </div>
              );
            }),
          )}
        </div>
        <aside className={styles.side}>
          <SalesForecast />
          <Card className={styles.wg}>
            <h3>
              발주 목록<span className="num">{count}</span>
            </h3>
            {count === 0 ? (
              <p className={styles.none}>담은 품목 없음</p>
            ) : (
              cart.map((g) => (
                <div key={g.id} className={styles.cg}>
                  <div className={styles.cgh}>
                    <b>{g.name}</b>
                    <span>{g.channel}</span>
                  </div>
                  {g.lines.map((l) => (
                    <div key={l.id} className={styles.cl}>
                      <span>{l.name}</span>
                      <span className="num">
                        {fmt(orders.approved[l.id])}
                        {l.unit}
                      </span>
                      <button
                        type="button"
                        aria-label={`${l.name} 빼기`}
                        onClick={() => act.approve(l.id)}
                      >
                        ×
                      </button>
                    </div>
                  ))}
                </div>
              ))
            )}
            <div className={styles.sum}>
              <span>합계</span>
              <span className="num">{won(total)}</span>
            </div>
            <button type="button" className={styles.go} disabled={count === 0} onClick={order}>
              발주하기
            </button>
          </Card>
        </aside>
      </div>
      {lines.length > 0 && (
        <div className={styles.mbar}>
          <ActionBar
            lead={
              <>
                발주 목록 {count} <b>{won(total)}</b>
              </>
            }
            disabled={count === 0}
            onClick={order}
          >
            발주하기
          </ActionBar>
        </div>
      )}
      <OrderMessageSheet sheet={sheet} />
      {toast}
    </Screen>
  );
}

export function OrderScreen() {
  const { feed, error } = useOrderFeed();
  if (error)
    return (
      <Screen title="발주">
        <Card>
          <p>불러오기 실패</p>
        </Card>
      </Screen>
    );
  return feed ? <Board feed={feed} /> : <OrderLoading />;
}

// 불러오는 동안: 추천 품목 목록 · 매출 카드 · 발주 목록 자리. 글자 없이 막대만
export function OrderLoading() {
  return (
    <Screen title={<SkTitle />}>
      <div className={styles.ord} aria-busy="true">
        <div className={styles.list}>
          <div className={styles.sec}>
            <Skeleton w="8em" h="1.1em" />
          </div>
          {SK_W.map((w) => (
            <div key={w} className={styles.item}>
              <div className={styles.ih}>
                <Skeleton w={w} h="1.3em" />
              </div>
            </div>
          ))}
        </div>
        <aside className={styles.side}>
          <SalesForecastEmpty />
          <Card className={styles.wg}>
            <div style={{ display: "grid", gap: "var(--sp-row)" }}>
              <Skeleton w="5em" h="1.2em" />
              <Skeleton w="100%" h="3em" r="var(--r-row)" />
              <Skeleton w="100%" h="58px" r="var(--r-pill)" />
            </div>
          </Card>
        </aside>
      </div>
    </Screen>
  );
}
