import { marketCandidates, weekBuckets } from "./marketComparison.ts";
import { leftLabel } from "./orderHome.ts";
import { CHANNEL_LABEL, shownUnit, unitFactor } from "./suppliers.ts";
import type {
  CalendarEvent,
  FineLine,
  HistoryApi,
  IngredientOffersApi,
  IngredientRow,
  InventoryEstimateApi,
  MarketPrice,
  MarketPriceHistoryApi,
  OrderFeed,
  OrderLine,
  PriceHistoryApi,
  RecommendationApi,
  SupplierApi,
  SupplierOrderGroup,
} from "./types";

const WD = ["일", "월", "화", "수", "목", "금", "토"];
const DAY = 864e5;

// "YYYY-MM-DD" 를 그 날짜의 자정(UTC)으로. 날짜 차이만 쓰므로 시간대는 상관없다
export const dayOf = (d: string) => Date.parse(`${d}T00:00:00Z`);
export const addDays = (d: string, n: number) =>
  new Date(dayOf(d) + n * DAY).toISOString().slice(0, 10);
const diffDays = (a: string, b: string) => Math.round((dayOf(b) - dayOf(a)) / DAY);

// 메시지 {배송일} 표기 M/d(요일) (백엔드 OrderMessageRenderer)
export const mdw = (d: string) =>
  `${Number(d.slice(5, 7))}/${Number(d.slice(8, 10))}(${WD[new Date(dayOf(d)).getUTCDay()]})`;

export const arriveLabel = (today: string, lead: number) =>
  lead <= 0
    ? "오늘"
    : lead === 1
      ? "내일"
      : `${WD[new Date(dayOf(addDays(today, lead))).getUTCDay()]}요일`;

// 비교 화면은 식봄만 (DECISIONS #68). 쿠팡은 수집하지 않아 비어 있다
const SIKBOM = "식봄";
const md = (d: string) => `${Number(d.slice(5, 7))}/${Number(d.slice(8, 10))}`;

// 식봄 1위 단가 → 화면 단위당 원 (f = 화면 단위 1 의 기준 단위 양)
export function sikbomPrice(
  o: IngredientOffersApi | undefined,
  f: number,
  today: string,
): MarketPrice {
  const s = o?.sites.find((x) => x.site === SIKBOM);
  const k = o?.toIngredientUnitFactor;
  if (!s || k == null) return { price: null, stale: false };
  const stale = s.collectedOn < today;
  return {
    price: Math.round(Number(s.bestUnitPrice) * Number(k) * f),
    stale,
    on: stale ? md(s.collectedOn) : undefined,
    url: s.offers[0]?.url,
  };
}

// 식봄 일별 최저가 → 거래처 8주와 같은 7일 칸(마지막 칸 = 오늘 − 6일 ~ 오늘)의 최저값. 점이 없으면 undefined
export function sikbomWeeks(
  h: MarketPriceHistoryApi | undefined,
  f: number,
  today: string,
): (number | null)[] | undefined {
  const s = h?.sites.find((x) => x.site === SIKBOM);
  const k = h?.toIngredientUnitFactor;
  if (!s?.points.length || k == null) return undefined;
  const w: (number | null)[] = Array(8).fill(null);
  for (const p of s.points) {
    const i = 7 - Math.floor(diffDays(p.date, today) / 7);
    if (i < 0 || i > 7) continue;
    const v = Math.round(Number(p.unitPrice) * Number(k) * f);
    w[i] = w[i] === null ? v : Math.min(w[i]!, v);
  }
  return w;
}

// 발주 탭 데이터 (DECISIONS #61). 수량은 화면 단위(kg · L · 개), 단가는 화면 단위당 원
export function toOrderFeed(input: {
  today: string;
  storeId?: string;
  recs: RecommendationApi[];
  suppliers: SupplierApi[];
  rows: IngredientRow[];
  estimates: InventoryEstimateApi[];
  history: HistoryApi[];
  prices: Record<string, PriceHistoryApi | undefined>;
  offers?: Record<string, IngredientOffersApi | undefined>;
  market?: Record<string, MarketPriceHistoryApi | undefined>;
}): OrderFeed {
  const {
    today,
    recs,
    suppliers,
    rows,
    estimates,
    history,
    prices,
    offers = {},
    market = {},
  } = input;
  const sup = (id: string) => suppliers.find((s) => s.id === id);
  const pending = recs.filter((r) => r.status === "PENDING");

  const lines: (OrderLine & { supplierId: string })[] = pending.flatMap((r) => {
    const row = rows.find((x) => x.id === r.ingredientId);
    if (!row) return [];
    const f = unitFactor(row.baseUnit);
    const s = sup(row.supplierId);
    const lead = s?.leadTimeDays ?? 1;
    const weeks = prices[r.ingredientId]?.weekly.map((v) =>
      v == null || !Number.isFinite(Number(v)) ? null : Number(v),
    );
    const markets = marketCandidates(offers[r.ingredientId], market[r.ingredientId], f, today);
    const count = Math.max(weeks?.length ?? 0, markets.length ? 8 : 0);
    const historyValues = [
      ...Array(Math.max(0, count - (weeks?.length ?? 0))).fill(null),
      ...(weeks ?? []),
    ];
    return [
      {
        id: r.id,
        supplierId: row.supplierId,
        name: r.ingredientName,
        unit: shownUnit(row.baseUnit),
        qty: Number(r.finalQty ?? r.recommendedQty) / f,
        stock: Number(r.estStock) / f,
        incoming: Number(r.onOrder) / f,
        safety: Number(r.safetyStock) / f,
        reorderPoint: Number(r.reorderPoint) / f,
        daily: row.daily,
        arriveLabel: arriveLabel(today, lead),
        pack: row.packQty > 0 ? row.packQty : 1,
        packLabel: row.packLabel,
        unitPrice: row.unitPrice,
        supplierPriceKnown: row.supplierPriceKnown,
        msgUnit: row.packQty > 0 ? row.packUnit || "팩" : row.baseUnit,
        msgByPack: row.packQty > 0,
        baseFactor: f,
        maxQty: row.maxQty ?? Number.MAX_SAFE_INTEGER,
        timeline: { arriveDay: Math.min(lead, 6), orderDay: 0 },
        price:
          weeks || markets.length
            ? {
                history: historyValues.map((w) => (w == null ? null : w * f)),
                buckets: weekBuckets(today, count),
                markets,
                sikbom: sikbomPrice(offers[r.ingredientId], f, today),
                coupang: { price: null, stale: false },
                sikbomWeekly: sikbomWeeks(market[r.ingredientId], f, today),
              }
            : null,
        needsReview: r.needsReview,
        reasons: r.reviewReasons as OrderLine["reasons"],
      },
    ];
  });

  const groups: SupplierOrderGroup[] = suppliers
    .map((s) => ({
      id: s.id,
      name: s.name,
      channel: s.channel ? CHANNEL_LABEL[s.channel] : "",
      cutoff: s.cutoffTime?.slice(0, 5) ?? "",
      minOrder: s.minOrderAmount ?? 0,
      template: s.messageTemplate ?? "",
      deliveryDate: arriveLabel(today, s.leadTimeDays ?? 1),
      arrivalDate: mdw(addDays(today, s.leadTimeDays ?? 1)),
      topUp: [],
      lines: lines.filter((l) => l.supplierId === s.id),
    }))
    .filter((g) => g.lines.length > 0)
    .sort((a, b) => a.cutoff.localeCompare(b.cutoff));

  const ordering = new Set(pending.map((r) => r.ingredientId));
  const fine: FineLine[] = estimates
    .filter((e) => !ordering.has(e.ingredientId))
    .map((e) => {
      const row = rows.find((x) => x.id === e.ingredientId);
      const f = unitFactor(row?.baseUnit ?? "g");
      const left = e.runOutDate ? Math.max(0, diffDays(today, e.runOutDate)) : null;
      return {
        id: e.ingredientId,
        name: e.ingredientName,
        stock: Number(e.estimatedQty ?? 0) / f,
        unit: shownUnit(row?.baseUnit ?? "g"),
        supplier: (row && sup(row.supplierId)?.name) ?? "",
        status: e.needsCount ? "실사 필요" : leftLabel(left !== null && left < 7 ? left : null),
        soon: e.needsCount || (left !== null && left <= 3),
      };
    });

  // 달력: 오늘 = 오늘 주문, 앞으로 = 소진일 − 배송 기간에 주문할 재료, 지난날 = 발주 기록
  const calendar: Record<string, CalendarEvent[]> = {};
  const put = (d: string, e: CalendarEvent) => (calendar[d] ??= []).push(e);
  for (const l of lines)
    put(today, { name: l.name, supplierId: l.supplierId, kind: "today", lineId: l.id });
  for (const e of estimates) {
    if (ordering.has(e.ingredientId) || !e.runOutDate) continue;
    const row = rows.find((x) => x.id === e.ingredientId);
    const lead = (row && sup(row.supplierId)?.leadTimeDays) ?? 1;
    const d = addDays(e.runOutDate, -lead);
    if (diffDays(today, d) > 0)
      put(d, { name: e.ingredientName, supplierId: row?.supplierId ?? "", kind: "plan" });
  }
  for (const h of history) {
    if (h.status === "DRAFT" || diffDays(today, h.date) >= 0) continue;
    put(h.date, {
      name: h.name,
      supplierId: suppliers.find((s) => s.name === h.supplier)?.id ?? "",
      kind: h.status === "CANCELLED" ? "rejected" : "done",
      status:
        h.status === "RECEIVED" ? "입고 완료" : h.status === "CANCELLED" ? "취소" : "입고 예정",
    });
  }
  return { groups, fine, calendar, today, storeId: input.storeId };
}
