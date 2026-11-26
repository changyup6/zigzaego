import type { MarketPrice, OrderLine, PriceHistory } from "./types";

// 발주 홈의 발주 · 보류 · 수량 (시안 weglee_3.html state.approved · held · qty).
// approved 에 있으면 발주, held 에 있으면 보류, 둘 다 없으면 대기. 둘은 함께 있을 수 없다.
export type Orders = {
  qty: Record<string, number>;
  approved: Record<string, number>;
  held: Record<string, true>;
};

export type LineState = "approved" | "held" | "pending";

const drop = <T>(r: Record<string, T>, id: string): Record<string, T> =>
  Object.fromEntries(Object.entries(r).filter(([k]) => k !== id));

export const initOrders = (lines: OrderLine[]): Orders => ({
  qty: Object.fromEntries(lines.map((l) => [l.id, l.qty])),
  approved: {},
  held: {},
});

export const lineState = (o: Orders, id: string): LineState =>
  id in o.approved ? "approved" : o.held[id] ? "held" : "pending";

// 화면에 보이는 수량: 발주했으면 발주한 양
export const shownQty = (o: Orders, id: string) => o.approved[id] ?? o.qty[id];

export const toggleApprove = (o: Orders, id: string): Orders =>
  id in o.approved
    ? { ...o, approved: drop(o.approved, id) }
    : { ...o, approved: { ...o.approved, [id]: o.qty[id] }, held: drop(o.held, id) };

export const toggleHold = (o: Orders, id: string): Orders =>
  o.held[id]
    ? { ...o, held: drop(o.held, id) }
    : { ...o, held: { ...o.held, [id]: true }, approved: drop(o.approved, id) };

// 발주한 줄은 발주 수량도 함께 바뀐다
export const setQty = (o: Orders, id: string, q: number): Orders => ({
  ...o,
  qty: { ...o.qty, [id]: q },
  approved: id in o.approved ? { ...o.approved, [id]: q } : o.approved,
});

// 모두 발주: 보류한 줄은 건드리지 않는다
export const approveAll = (o: Orders, ids: string[]): Orders =>
  ids.reduce((x, id) => (lineState(x, id) === "pending" ? toggleApprove(x, id) : x), o);

export type OrderSummary = {
  done: boolean;
  label: "발주 대기" | "발주 완료" | "모두 보류";
  count: number;
  cost: number;
  action: { label: string; mode: "all" | "msg" | null };
};

// 요약 카드 · 액션 바 (시안 renderHero · renderActbar)
export function summarize(o: Orders, lines: OrderLine[]): OrderSummary {
  const states = lines.map((l) => lineState(o, l.id));
  const approved = states.filter((s) => s === "approved").length;
  const pending = states.filter((s) => s === "pending").length;
  return {
    done: pending === 0,
    label: pending ? "발주 대기" : approved ? "발주 완료" : "모두 보류",
    count: pending || approved || lines.length,
    cost: lines.reduce((s, l) => s + shownQty(o, l.id) * l.unitPrice, 0),
    action: approved
      ? { label: `${approved}건 메시지 만들기`, mode: "msg" }
      : pending
        ? { label: "모두 발주", mode: "all" }
        : { label: "메시지 만들기", mode: null },
  };
}

// 소진일 (시안 plan): 추정 재고에서 하루씩 예상 소비를 빼고, 입고 예정은 도착일에 더한다.
// 재고가 0 이하가 되는 칸. 7일 안에 없으면 null. 타임라인 · 남은 날이 근거 표와 같은 숫자로 그려진다
export function runoutDay(
  l: Pick<OrderLine, "stock" | "incoming" | "daily" | "timeline">,
): number | null {
  if (!l.daily.some((d) => d > 0)) return null;
  let st = l.stock;
  for (let i = 0; i < 7; i++) {
    if (l.incoming && l.timeline.arriveDay === i) st += l.incoming;
    st -= l.daily[i] ?? 0;
    if (st <= 1e-9) return i;
  }
  return null;
}

export const leftLabel = (runout: number | null) =>
  runout === 0 ? "오늘 소진" : runout === null ? "일주일 이상" : `${runout}일 남음`;

export type PriceCell = MarketPrice & { name: string; lowest: boolean };

export type PriceView = {
  last: number;
  change: "up" | "down" | "flat";
  label: string;
  // 8주 막대 높이(%), 가장 싼 주 20 · 가장 비싼 주 100. marks = 같은 눈금의 식봄 최저가(없는 주 null)
  bars: number[];
  marks: (number | null)[] | null;
  cells: PriceCell[];
};

// 가격 블록 (시안 priceInfo · priceBlock): 이번 주 단가를 앞 7주 평균과 비교, ±5% 미만은 비슷
export function priceView(p: PriceHistory, unitPrice: number): PriceView {
  const h = p.history.filter((v): v is number => v !== null);
  const last = h[h.length - 1];
  const prev = h.slice(0, -1);
  const base = prev.length ? prev.reduce((a, b) => a + b, 0) / prev.length : last;
  const pct = Math.round(((last - base) / base) * 100);
  const s = p.sikbomWeekly?.some((v) => v !== null) ? p.sikbomWeekly : null;
  const all = [...h, ...(s ?? []).flatMap((v) => (v === null ? [] : [v]))];
  const max = Math.max(...all);
  const min = Math.min(...all);
  const y = (v: number) => Math.round(20 + (80 * (v - min)) / (max - min || 1));
  const cells = [
    { name: "기존 거래처", price: unitPrice, stale: false },
    { name: "식봄", ...p.sikbom },
    { name: "쿠팡", ...p.coupang },
  ];
  const low = Math.min(...cells.flatMap((c) => (c.price === null ? [] : [c.price])));
  return {
    last,
    change: Math.abs(pct) < 5 ? "flat" : pct > 0 ? "up" : "down",
    label: Math.abs(pct) < 5 ? "최근 8주와 비슷" : `최근 8주보다 ${pct > 0 ? "+" : ""}${pct}%`,
    bars: h.map(y),
    marks: s && s.map((v) => (v === null ? null : y(v))),
    cells: cells.map((c) => ({ ...c, lowest: c.price === low })),
  };
}
